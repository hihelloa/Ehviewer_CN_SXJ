package com.hippo.ehviewer.translation;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.alibaba.fastjson.JSONArray;
import com.hippo.ehviewer.EhApplication;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 免费 Google 翻译服务客户端
 * 免配置 API Key，开箱即用，自动识别源语言并翻译为简体中文。
 *
 * <p>为了彻底避免"占位符合并被翻译接口改写导致译文串段"，这里改为**逐块翻译**，
 * 并对 429/5xx 做退避重试。整本翻译是后台任务，可靠性优先于极限速度。
 */
public class GoogleTranslator {
    private static final String TAG = "GoogleTranslator";
    private static final String TRANSLATE_URL = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=zh-CN&dt=t&q=";
    private static final int MAX_QUERY_LENGTH = 1500;
    private static final int MAX_RETRY = 3;
    /** 批量请求的 UTF-8 字节上限：中文 URL 编码后约 9 字节/字，超了会 414。 */
    private static final int MAX_BATCH_BYTES = 3500;
    /** 块之间的最小间隔，降低免费接口的突发速率，减少 429。 */
    private static final long BLOCK_INTERVAL_MS = 120L;
    private static final ExecutorService sExecutor = Executors.newFixedThreadPool(3);
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    private static volatile OkHttpClient sClient;

    public interface TranslationCallback {
        void onSuccess(@NonNull List<MangaTextBlock> translatedBlocks);
        void onFailure(@NonNull String errorMessage);
    }

    private static synchronized OkHttpClient getClient() {
        if (sClient == null) {
            OkHttpClient baseClient = EhApplication.getOkHttpClient(EhApplication.getInstance());
            sClient = baseClient.newBuilder()
                    .connectTimeout(12, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build();
        }
        return sClient;
    }

    /**
     * 单条文本翻译。失败返回 null（调用方可决定是否回退到原文）。
     */
    @Nullable
    public static String translateText(@Nullable String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        String query = text.trim();
        if (query.length() > MAX_QUERY_LENGTH) {
            query = query.substring(0, MAX_QUERY_LENGTH);
        }

        for (int attempt = 0; attempt <= MAX_RETRY; attempt++) {
            try {
                String encoded = URLEncoder.encode(query, "UTF-8");
                Request request = new Request.Builder()
                        .url(TRANSLATE_URL + encoded)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
                        .build();

                Response response = getClient().newCall(request).execute();
                try {
                    if (response.isSuccessful() && response.body() != null) {
                        String bodyString = response.body().string();
                        String result = parseTranslateResponse(bodyString);
                        if (result != null && !result.isEmpty()) {
                            return result;
                        }
                    } else if (response.code() == 429 || response.code() >= 500) {
                        // 被限流或服务端异常，退避后重试
                        sleep((long) (600 * Math.pow(2, attempt)));
                        continue;
                    } else {
                        Log.w(TAG, "translate http " + response.code());
                        return null;
                    }
                } finally {
                    response.close();
                }
            } catch (Throwable e) {
                Log.e(TAG, "translateText failed (attempt " + attempt + "): " + e.getMessage());
                if (attempt < MAX_RETRY) {
                    sleep(500L * (attempt + 1));
                }
            }
        }
        return null;
    }

    @Nullable
    private static String parseTranslateResponse(String bodyString) {
        try {
            JSONArray rootArray = JSONArray.parseArray(bodyString);
            if (rootArray == null || rootArray.isEmpty()) {
                return null;
            }
            JSONArray sentencesArray = rootArray.getJSONArray(0);
            if (sentencesArray == null) {
                return null;
            }
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < sentencesArray.size(); i++) {
                JSONArray item = sentencesArray.getJSONArray(i);
                if (item != null && !item.isEmpty()) {
                    String part = item.getString(0);
                    if (part != null) {
                        result.append(part);
                    }
                }
            }
            return result.toString();
        } catch (Throwable t) {
            Log.e(TAG, "parse response failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * 单条文本翻译（兼容旧接口，失败时返回原文）。
     */
    @NonNull
    public static String translateSync(String text) {
        String result = translateText(text);
        return result == null ? text : result;
    }

    /**
     * 同步翻译一组文字块。
     *
     * <p>优先把整页文字用换行拼接后一次性请求（一页通常只需 1 次网络往返），
     * 只有行数对不上或请求失败时才回退到逐块翻译，兼顾速度与准确。
     * 必须在后台线程调用。翻译失败的文字块保留原文。
     *
     * @return 成功翻译的块数
     */
    public static int translateBlocksSync(@NonNull List<MangaTextBlock> blocks) {
        if (blocks.isEmpty()) {
            return 0;
        }
        int batch = translateBatch(blocks);
        if (batch < 0) {
            batch = translateBlocksOneByOne(blocks);
        }
        // 免费接口偶发限流会让个别块留空，补两轮，尽量把整页译全
        for (int round = 0; round < 2; round++) {
            List<MangaTextBlock> missing = collectMissing(blocks);
            if (missing.isEmpty()) {
                break;
            }
            Log.w(TAG, "retry missing blocks: " + missing.size() + " (round " + (round + 1) + ")");
            sleep(600L * (round + 1));
            translateBlocksOneByOne(missing);
        }
        return countTranslated(blocks);
    }

    @NonNull
    private static List<MangaTextBlock> collectMissing(@NonNull List<MangaTextBlock> blocks) {
        List<MangaTextBlock> missing = new ArrayList<>();
        for (MangaTextBlock block : blocks) {
            if (block.originalText == null || block.originalText.trim().isEmpty()) {
                continue;
            }
            if (block.translatedText == null || block.translatedText.trim().isEmpty()) {
                missing.add(block);
            }
        }
        return missing;
    }

    /**
     * 只补译缺失的块，不动已经译好的内容。
     * 用于"缓存里已有译文、但当时有几句没译出来"的页面，避免整页重翻浪费请求。
     *
     * @return 本次补译成功的块数
     */
    public static int fillMissingTranslations(@NonNull List<MangaTextBlock> blocks) {
        List<MangaTextBlock> missing = collectMissing(blocks);
        if (missing.isEmpty()) {
            return 0;
        }
        Log.w(TAG, "fill missing blocks: " + missing.size());
        return translateBlocksOneByOne(missing);
    }

    public static int countTranslated(@NonNull List<MangaTextBlock> blocks) {
        int count = 0;
        for (MangaTextBlock block : blocks) {
            if (block.hasTranslation()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 整页批量翻译。成功返回翻译块数，需要回退时返回 -1。
     */
    private static int translateBatch(@NonNull List<MangaTextBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        List<MangaTextBlock> valid = new ArrayList<>();
        for (MangaTextBlock block : blocks) {
            String original = block.originalText == null ? "" : block.originalText.trim();
            if (original.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(original);
            valid.add(block);
        }
        // 只有一块没必要批量；超长也回退，避免被截断后错位
        if (valid.size() <= 1) {
            return -1;
        }
        String joined = sb.toString();
        if (joined.length() > MAX_QUERY_LENGTH
                || joined.getBytes(StandardCharsets.UTF_8).length > MAX_BATCH_BYTES) {
            return -1;
        }
        String translated = translateText(joined);
        if (translated == null || translated.isEmpty()) {
            return -1;
        }
        String[] lines = translated.split("\n", -1);
        if (lines.length != valid.size()) {
            // 行数被翻译接口改动，无法可靠对齐，回退逐块
            Log.w(TAG, "batch line count mismatch: " + lines.length + " vs " + valid.size());
            return -1;
        }
        int success = 0;
        List<MangaTextBlock> failed = new ArrayList<>();
        for (int i = 0; i < valid.size(); i++) {
            String text = lines[i].trim();
            if (!text.isEmpty()) {
                valid.get(i).setTranslatedText(text);
                success++;
            } else {
                failed.add(valid.get(i));
            }
        }
        // 少数行没译出来，逐块补齐，避免整页里零星几句永远停在原文
        if (!failed.isEmpty() && failed.size() <= valid.size() / 2) {
            success += translateBlocksOneByOne(failed);
        }
        return success > 0 ? success : -1;
    }

    private static int translateBlocksOneByOne(@NonNull List<MangaTextBlock> blocks) {
        Map<String, String> memo = new HashMap<>();
        int success = 0;
        for (MangaTextBlock block : blocks) {
            String original = block.originalText == null ? "" : block.originalText.trim();
            if (original.isEmpty()) {
                continue;
            }
            String cached = memo.get(original);
            if (cached != null) {
                block.setTranslatedText(cached);
                success++;
                continue;
            }
            String translated = translateText(original);
            if (translated != null && !translated.isEmpty()) {
                block.setTranslatedText(translated);
                memo.put(original, translated);
                success++;
            } else {
                // 不要把日文写回译文：烧录时会把原文重画一遍，糊在气泡上
                block.setTranslatedText("");
            }
            // 稍微放慢节奏，避免整页几十个请求瞬间打满免费接口
            sleep(BLOCK_INTERVAL_MS);
        }
        return success;
    }

    /**
     * 批量翻译漫画文字块 (异步，回调在主线程)
     */
    public static void translateBlocks(@NonNull List<MangaTextBlock> blocks, @NonNull TranslationCallback callback) {
        if (blocks.isEmpty()) {
            sMainHandler.post(() -> callback.onSuccess(blocks));
            return;
        }

        sExecutor.execute(() -> {
            try {
                translateBlocksSync(blocks);
                sMainHandler.post(() -> callback.onSuccess(blocks));
            } catch (Throwable t) {
                Log.e(TAG, "translateBlocks error: " + t.getMessage(), t);
                sMainHandler.post(() -> callback.onFailure("翻译请求失败: " + t.getMessage()));
            }
        });
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
