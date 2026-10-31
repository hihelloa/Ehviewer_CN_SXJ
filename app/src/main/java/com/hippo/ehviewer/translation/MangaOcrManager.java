package com.hippo.ehviewer.translation;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 漫画 OCR 识别管理器
 * 基于 Google ML Kit 离线日文与拉丁文识别模型
 */
public class MangaOcrManager {
    private static final String TAG = "MangaOcrManager";
    private static final long DEFAULT_SYNC_TIMEOUT_MS = 45_000L;
    /** 切片高度：ML Kit 输入过大时会内部缩小，小字直接糊掉，切片能显著提升召回。 */
    private static final int TILE_HEIGHT = 1400;
    /** 切片重叠带，避免一行字正好被切在边界上导致两边都识别不全。 */
    private static final int TILE_OVERLAP = 220;
    private static final long TILE_TIMEOUT_MS = 30_000L;
    private static volatile MangaOcrManager sInstance;

    private final TextRecognizer mRecognizer;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    public interface OcrCallback {
        void onSuccess(@NonNull List<MangaTextBlock> blocks);
        void onFailure(@NonNull String error);
    }

    /**
     * 同步识别结果。
     *
     * <p>{@code recycleSafe} 很重要：超时返回时 ML Kit 的任务可能还在这张 bitmap 上跑，
     * 这时调用方如果 recycle，轻则抛 "trying to use a recycled bitmap"，重则 native 崩溃。
     * 超时的情况下宁可让 bitmap 交给 GC，也不能主动回收。
     */
    public static final class PageOcrResult {
        @NonNull
        public final List<MangaTextBlock> blocks;
        public final boolean recycleSafe;

        PageOcrResult(@NonNull List<MangaTextBlock> blocks, boolean recycleSafe) {
            this.blocks = blocks;
            this.recycleSafe = recycleSafe;
        }
    }

    private MangaOcrManager() {
        // 使用针对日文（平假名、片假名、汉字）优化的识别器，且兼容拉丁字母（英文）
        JapaneseTextRecognizerOptions options = new JapaneseTextRecognizerOptions.Builder().build();
        mRecognizer = TextRecognition.getClient(options);
    }

    public static synchronized MangaOcrManager getInstance() {
        if (sInstance == null) {
            sInstance = new MangaOcrManager();
        }
        return sInstance;
    }

    /**
     * 仅做 OCR 识别，不翻译。回调在主线程。
     */
    public void recognize(@NonNull Bitmap bitmap, @NonNull OcrCallback callback) {
        try {
            InputImage inputImage = InputImage.fromBitmap(bitmap, 0);
            int imgWidth = bitmap.getWidth();
            int imgHeight = bitmap.getHeight();

            mRecognizer.process(inputImage)
                    .addOnSuccessListener(visionText -> {
                        List<MangaTextBlock> blocks = new ArrayList<>();
                        for (Text.TextBlock textBlock : visionText.getTextBlocks()) {
                            String text = textBlock.getText();
                            Rect rect = textBlock.getBoundingBox();

                            // 过滤无效干扰：文本为空、纯标点、或者区域过小
                            if (text == null || text.trim().isEmpty() || rect == null) {
                                continue;
                            }
                            if (isNoiseText(text) || rect.width() < 6 || rect.height() < 6) {
                                continue;
                            }

                            blocks.add(new MangaTextBlock(text.trim(), rect, imgWidth, imgHeight));
                        }
                        callback.onSuccess(blocks);
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "OCR recognition error: " + e.getMessage(), e);
                        callback.onFailure("OCR识别失败: " + e.getMessage());
                    });
        } catch (Throwable t) {
            Log.e(TAG, "recognize error: " + t.getMessage(), t);
            callback.onFailure("图像处理错误: " + t.getMessage());
        }
    }

    /**
     * OCR + 翻译（兼容旧调用）。回调在主线程。
     */
    public void processPageBitmap(@NonNull Bitmap bitmap, @NonNull OcrCallback callback) {
        recognize(bitmap, new OcrCallback() {
            @Override
            public void onSuccess(@NonNull List<MangaTextBlock> blocks) {
                if (blocks.isEmpty()) {
                    callback.onSuccess(blocks);
                    return;
                }
                GoogleTranslator.translateBlocks(blocks, new GoogleTranslator.TranslationCallback() {
                    @Override
                    public void onSuccess(@NonNull List<MangaTextBlock> translatedBlocks) {
                        callback.onSuccess(translatedBlocks);
                    }

                    @Override
                    public void onFailure(@NonNull String errorMessage) {
                        callback.onSuccess(blocks);
                    }
                });
            }

            @Override
            public void onFailure(@NonNull String error) {
                callback.onFailure(error);
            }
        });
    }

    /**
     * 同步 OCR，供整本批量翻译的后台线程使用。
     * 注意：不要在主线程调用，否则会阻塞主线程回调。
     */
    @NonNull
    public List<MangaTextBlock> recognizeSync(@NonNull Bitmap bitmap) {
        return recognizeSyncResult(bitmap, DEFAULT_SYNC_TIMEOUT_MS).blocks;
    }

    @NonNull
    public List<MangaTextBlock> recognizeSync(@NonNull Bitmap bitmap, long timeoutMs) {
        return recognizeSyncResult(bitmap, timeoutMs).blocks;
    }

    /**
     * 同步识别，并告诉调用方这张 bitmap 现在能不能安全回收。
     * 必须在后台线程调用。
     */
    @NonNull
    public PageOcrResult recognizeSyncResult(@NonNull Bitmap bitmap, long timeoutMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<List<MangaTextBlock>> holder =
                new java.util.concurrent.atomic.AtomicReference<>(Collections.emptyList());

        recognize(bitmap, new OcrCallback() {
            @Override
            public void onSuccess(@NonNull List<MangaTextBlock> blocks) {
                holder.set(blocks);
                latch.countDown();
            }

            @Override
            public void onFailure(@NonNull String error) {
                Log.w(TAG, "recognizeSync failed: " + error);
                holder.set(Collections.emptyList());
                latch.countDown();
            }
        });

        boolean completed = false;
        try {
            completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!completed) {
                // 注意：此时不能回收 bitmap，ML Kit 内部任务可能仍持有它
                Log.w(TAG, "recognizeSync timeout after " + timeoutMs + "ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new PageOcrResult(holder.get(), completed);
    }

    /**
     * 整页识别：长页按纵向切片分别识别再合并，短页直接识别。
     *
     * <p>ML Kit 对输入图有尺寸上限，超大图会被内部降采样，漫画小字因此识别不出来，
     * 表现就是"整页只译了其中几句"。切片后每片都保持原始像素密度，召回明显提高。
     * 必须在后台线程调用。
     */
    @NonNull
    public PageOcrResult recognizePageSync(@NonNull Bitmap bitmap) {
        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) {
            return new PageOcrResult(Collections.emptyList(), true);
        }
        if (height <= TILE_HEIGHT + TILE_OVERLAP) {
            return recognizeSyncResult(bitmap, DEFAULT_SYNC_TIMEOUT_MS);
        }

        List<MangaTextBlock> merged = new ArrayList<>();
        boolean recycleSafe = true;
        int step = TILE_HEIGHT - TILE_OVERLAP;
        for (int top = 0; top < height; top += step) {
            int bottom = Math.min(top + TILE_HEIGHT, height);
            if (bottom - top < 120) {
                break;
            }
            Bitmap tile = null;
            boolean tileSafe = true;
            List<MangaTextBlock> blocks;
            try {
                tile = Bitmap.createBitmap(bitmap, 0, top, width, bottom - top);
                PageOcrResult result = recognizeSyncResult(tile, TILE_TIMEOUT_MS);
                tileSafe = result.recycleSafe;
                blocks = result.blocks;
            } catch (Throwable t) {
                Log.w(TAG, "slice at " + top + " failed: " + t.getMessage());
                break;
            } finally {
                if (tile != null) {
                    if (tileSafe) {
                        tile.recycle();
                    } else {
                        // 这片超时了，ML Kit 可能还在读它：不回收，交给 GC，
                        // 否则会在 native 层踩到已释放的内存。
                        recycleSafe = false;
                    }
                }
            }
            if (!tileSafe) {
                Log.w(TAG, "slice at " + top + " timed out, stop slicing");
                break;
            }
            for (MangaTextBlock block : blocks) {
                Rect box = new Rect(block.boundingBox);
                box.offset(0, top);
                if (isDuplicate(merged, box, block.originalText)) {
                    continue;
                }
                merged.add(new MangaTextBlock(block.originalText, box, width, height));
            }
            if (bottom >= height) {
                break;
            }
        }
        return new PageOcrResult(merged, recycleSafe);
    }

    /** 重叠带里的同一段文字会被相邻两片各识别一次，按交叠比例去重。 */
    private boolean isDuplicate(@NonNull List<MangaTextBlock> merged, @NonNull Rect box, @NonNull String text) {
        for (MangaTextBlock existing : merged) {
            Rect other = existing.boundingBox;
            if (!Rect.intersects(other, box)) {
                continue;
            }
            long inter = intersectionArea(other, box);
            if (inter <= 0) {
                continue;
            }
            long minArea = Math.min(area(other), area(box));
            if (minArea <= 0) {
                continue;
            }
            float ratio = (float) inter / minArea;
            if (ratio > 0.6f) {
                return true;
            }
            // 同一段文字被切片边界截断，两半会有部分重叠
            if (existing.originalText.equals(text) && ratio > 0.2f) {
                return true;
            }
        }
        return false;
    }

    private static long area(@NonNull Rect r) {
        return (long) Math.max(0, r.width()) * Math.max(0, r.height());
    }

    private static long intersectionArea(@NonNull Rect a, @NonNull Rect b) {
        int left = Math.max(a.left, b.left);
        int top = Math.max(a.top, b.top);
        int right = Math.min(a.right, b.right);
        int bottom = Math.min(a.bottom, b.bottom);
        if (right <= left || bottom <= top) {
            return 0;
        }
        return (long) (right - left) * (bottom - top);
    }

    /**
     * 判断是否为纯噪点（如纯数字、纯符号）
     */
    private boolean isNoiseText(String text) {
        String clean = text.replaceAll("[\\s\\p{Punct}〜…・「」『』（）［］【】、。!?！？0-9]+", "");
        return clean.isEmpty();
    }
}
