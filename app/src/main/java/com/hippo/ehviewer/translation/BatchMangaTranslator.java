package com.hippo.ehviewer.translation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.hippo.ehviewer.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 整本漫画后台批量翻译引擎。
 *
 * <p>单线程顺序处理整本画廊：跳过已有缓存的页面 -> 取图 -> OCR -> 翻译 -> 立即落盘，
 * 因此中途被杀进程也不会丢掉已完成的页。
 */
public class BatchMangaTranslator {

    private static final String TAG = "BatchMangaTranslator";
    private static final int MAX_DECODE_SIZE = 1800;
    /** 单页取图最多重试次数，约 90 × (20s + 退避) ≈ 40 分钟，避免后台任务永久卡在某一页。 */
    private static final int MAX_FETCH_RETRY = 90;

    private static volatile BatchMangaTranslator sInstance;

    /** 用于在长时间等待下载时及时响应取消。 */
    public interface CancelChecker {
        boolean isCancelled();
    }

    /** 页面图片来源：由阅读器或详情页分别实现。 */
    public interface PageSource {
        /** 总页数，未知时返回 0。 */
        int getPageCount();

        /**
         * 阻塞地取回某一页的原始图片并写入 outFile。单次调用只尝试一轮，
         * 失败由上层持续重试。
         *
         * @return 成功返回 true
         */
        boolean fetchPage(int index, @NonNull File outFile, @NonNull CancelChecker checker);

        /** 批量结束或取消时释放资源。 */
        void release();
    }

    public interface Listener {
        default void onBatchProgress(@NonNull String key, int finished, int total, int failed, int currentIndex) {}

        default void onPageTranslated(@NonNull String key, int pageIndex, @NonNull List<MangaTextBlock> blocks) {}

        /** 某页取图/下载失败，即将重试。attempt 从 1 开始。 */
        default void onPageRetry(@NonNull String key, int pageIndex, int attempt) {}

        default void onBatchFinished(@NonNull String key, int finished, int total, int failed, boolean cancelled) {}
    }

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Task> mTasks = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Listener> mListeners = new CopyOnWriteArrayList<>();

    private BatchMangaTranslator() {
    }

    public static BatchMangaTranslator getInstance() {
        BatchMangaTranslator instance = sInstance;
        if (instance == null) {
            synchronized (BatchMangaTranslator.class) {
                instance = sInstance;
                if (instance == null) {
                    instance = new BatchMangaTranslator();
                    sInstance = instance;
                }
            }
        }
        return instance;
    }

    public void addListener(@NonNull Listener listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public void removeListener(@NonNull Listener listener) {
        mListeners.remove(listener);
    }

    public boolean isRunning(@NonNull String key) {
        return mTasks.containsKey(key);
    }

    public boolean isRunningAny() {
        return !mTasks.isEmpty();
    }

    public int getFinished(@NonNull String key) {
        Task task = mTasks.get(key);
        return task == null ? 0 : task.finished;
    }

    public int getTotal(@NonNull String key) {
        Task task = mTasks.get(key);
        return task == null ? 0 : task.total;
    }

    public void cancel(@NonNull String key) {
        Task task = mTasks.get(key);
        if (task != null) {
            task.cancelled.set(true);
        }
    }

    public void cancelAll() {
        for (Task task : mTasks.values()) {
            task.cancelled.set(true);
        }
    }

    /**
     * 启动整本翻译。若该画廊已经在翻译中则直接返回 false。
     */
    public boolean start(@NonNull Context context, @NonNull String key, @NonNull PageSource source) {
        if (mTasks.containsKey(key)) {
            return false;
        }
        Task task = new Task();
        task.key = key;
        task.source = source;
        task.appContext = context.getApplicationContext();
        if (mTasks.putIfAbsent(key, task) != null) {
            return false;
        }
        mExecutor.execute(() -> runTask(task));
        return true;
    }

    private void runTask(@NonNull Task task) {
        String key = task.key;
        int total = 0;
        try {
            total = task.source.getPageCount();
            if (total <= 0) {
                // 页数可能还没拉取到，给一次等待机会（最多 30 秒）
                for (int i = 0; i < 60 && total <= 0; i++) {
                    sleep(500);
                    total = task.source.getPageCount();
                }
            }
            if (total <= 0) {
                // 页数拿不到，直接结束；最终回调统一在 finally 中发出，避免重复通知
                Log.w(TAG, "No page count for " + key + ", abort");
                return;
            }
            task.total = total;
            MangaTranslationCache cache = MangaTranslationCache.get(task.appContext);

            for (int index = 0; index < total; index++) {
                if (task.cancelled.get()) {
                    break;
                }
                task.currentIndex = index;

                // 已有译文缓存：正常情况下跳过；
                // 但若这页还没烧录成"汉化图"，需要再取一次图补烧录。
                List<MangaTextBlock> cachedBlocks = null;
                if (cache.isPageProcessed(key, index)) {
                    if (Settings.getBakedTranslation()
                            && !BakedTranslationStore.has(task.appContext, key, index)) {
                        cachedBlocks = cache.getPage(key, index);
                    }
                    if (cachedBlocks == null || cachedBlocks.isEmpty()) {
                        task.finished++;
                        postProgress(task);
                        continue;
                    }
                }

                File tempFile = new File(task.appContext.getCacheDir(),
                        "manga_tl_" + System.nanoTime() + "_" + index + ".img");
                try {
                    // 只翻译已经下载到本地（或可下载到本地）的图片；
                    // 下载失败就一直重试，直到成功或用户取消。
                    boolean fetched = false;
                    int attempt = 0;
                    while (!task.cancelled.get()) {
                        if (task.source.fetchPage(index, tempFile, () -> task.cancelled.get())) {
                            fetched = true;
                            break;
                        }
                        if (task.cancelled.get()) {
                            break;
                        }
                        attempt++;
                        postRetry(key, index, attempt);
                        if (attempt >= MAX_FETCH_RETRY) {
                            // 重试到上限仍失败：放弃本页继续下一页，避免整本任务永久卡死
                            Log.w(TAG, "Give up page " + index + " after " + attempt + " attempts");
                            break;
                        }
                        // 渐进退避，最长 8 秒一次，避免把站点打挂
                        sleep(Math.min(1500L * attempt, 8000L));
                    }
                    if (!fetched) {
                        if (task.cancelled.get()) {
                            // 被用户取消，不算失败，保留未处理状态
                            break;
                        }
                        // 本页彻底失败，跳过继续下一页
                        task.failed++;
                        task.finished++;
                        postProgress(task);
                        continue;
                    }
                    Bitmap bitmap = decodeSampled(tempFile.getAbsolutePath(), MAX_DECODE_SIZE);
                    if (bitmap == null) {
                        task.failed++;
                        task.finished++;
                        postProgress(task);
                        continue;
                    }
                    try {
                        List<MangaTextBlock> blocks = cachedBlocks != null
                                ? cachedBlocks
                                : MangaOcrManager.getInstance().recognizeSync(bitmap);
                        int translated = cachedBlocks != null
                                ? cachedBlocks.size()
                                : GoogleTranslator.translateBlocksSync(blocks);
                        if (!blocks.isEmpty() && translated == 0) {
                            // 整页翻译全部失败（限流/断网），不要写缓存，否则该页会永久停留在原文
                            Log.w(TAG, "Page " + index + " translation all failed, will retry later");
                            task.failed++;
                            task.finished++;
                            postProgress(task);
                            continue;
                        }
                        cache.putPage(key, index, blocks);
                        // 译文直接烧进图片，阅读时就是"汉化图"，拖动缩放完全跟手
                        if (Settings.getBakedTranslation()) {
                            BakedTranslationStore.bake(task.appContext, key, index, bitmap, blocks);
                        }
                        task.finished++;
                        postPage(key, index, blocks);
                        postProgress(task);
                    } finally {
                        bitmap.recycle();
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "Translate page " + index + " failed: " + t.getMessage(), t);
                    task.failed++;
                    task.finished++;
                    postProgress(task);
                } finally {
                    if (tempFile.exists() && !tempFile.delete()) {
                        Log.w(TAG, "Failed to delete temp file " + tempFile);
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Batch translate crashed: " + t.getMessage(), t);
        } finally {
            boolean cancelled = task.cancelled.get();
            int finished = task.finished;
            int finalTotal = task.total > 0 ? task.total : total;
            try {
                task.source.release();
            } catch (Throwable ignored) {
            }
            mTasks.remove(key);
            postFinish(key, finished, finalTotal, task.failed, cancelled);
        }
    }

    private void postProgress(@NonNull Task task) {
        int finished = task.finished;
        int total = task.total;
        int failed = task.failed;
        int current = task.currentIndex;
        String key = task.key;
        mHandler.post(() -> {
            for (Listener listener : mListeners) {
                listener.onBatchProgress(key, finished, total, failed, current);
            }
        });
    }

    private void postRetry(@NonNull String key, int index, int attempt) {
        mHandler.post(() -> {
            for (Listener listener : mListeners) {
                listener.onPageRetry(key, index, attempt);
            }
        });
    }

    private void postPage(@NonNull String key, int index, @NonNull List<MangaTextBlock> blocks) {
        mHandler.post(() -> {
            for (Listener listener : mListeners) {
                listener.onPageTranslated(key, index, blocks);
            }
        });
    }

    private void postFinish(@NonNull String key, int finished, int total, int failed, boolean cancelled) {
        mHandler.post(() -> {
            for (Listener listener : mListeners) {
                listener.onBatchFinished(key, finished, total, failed, cancelled);
            }
        });
    }

    @Nullable
    public static Bitmap decodeSampled(@NonNull String path, int maxSide) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);
            int width = options.outWidth;
            int height = options.outHeight;
            if (width <= 0 || height <= 0) {
                return null;
            }
            int sample = 1;
            int longest = Math.max(width, height);
            while (longest / sample > maxSide) {
                sample *= 2;
            }
            BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
            decodeOptions.inSampleSize = sample;
            decodeOptions.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(path, decodeOptions);
        } catch (Throwable t) {
            Log.e(TAG, "decodeSampled failed: " + t.getMessage());
            return null;
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static class Task {
        String key;
        PageSource source;
        Context appContext;
        volatile int total;
        volatile int finished;
        volatile int failed;
        volatile int currentIndex;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
    }
}
