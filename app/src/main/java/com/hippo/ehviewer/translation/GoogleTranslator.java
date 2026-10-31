package com.hippo.ehviewer.translation;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.alibaba.fastjson.JSONArray;
import com.hippo.ehviewer.EhApplication;

import java.net.URLEncoder;
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
    private static final int MAX_RETRY = 2;
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
     * 同步翻译一组文字块，逐块请求，相同原文只翻译一次。
     * 必须在后台线程调用。翻译失败的文字块保留原文。
     *
     * @return 成功翻译的块数
     */
    public static int translateBlocksSync(@NonNull List<MangaTextBlock> blocks) {
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
                block.setTranslatedText(original);
            }
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
