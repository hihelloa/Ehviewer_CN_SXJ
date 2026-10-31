package com.hippo.ehviewer.translation;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.alibaba.fastjson.JSONArray;
import com.hippo.ehviewer.EhApplication;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 免费 Google 翻译服务客户端
 * 免配置 API Key，开箱即用，支持多语言自动检测并翻译为简体中文
 */
public class GoogleTranslator {
    private static final String TAG = "GoogleTranslator";
    private static final String TRANSLATE_URL = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=zh-CN&dt=t&q=";
    private static final ExecutorService sExecutor = Executors.newFixedThreadPool(3);
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    private static OkHttpClient sClient;

    public interface TranslationCallback {
        void onSuccess(@NonNull List<MangaTextBlock> translatedBlocks);
        void onFailure(@NonNull String errorMessage);
    }

    private static synchronized OkHttpClient getClient() {
        if (sClient == null) {
            OkHttpClient baseClient = EhApplication.getOkHttpClient(EhApplication.getInstance());
            sClient = baseClient.newBuilder()
                    .connectTimeout(12, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build();
        }
        return sClient;
    }

    /**
     * 单条文本翻译 (同步方法，必须在后台线程调用)
     */
    public static String translateSync(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        try {
            String encoded = URLEncoder.encode(text.trim(), "UTF-8");
            String url = TRANSLATE_URL + encoded;

            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
                    .build();

            Response response = getClient().newCall(request).execute();
            if (!response.isSuccessful() || response.body() == null) {
                return text;
            }

            String bodyString = response.body().string();
            JSONArray rootArray = JSONArray.parseArray(bodyString);
            if (rootArray != null && !rootArray.isEmpty()) {
                JSONArray sentencesArray = rootArray.getJSONArray(0);
                if (sentencesArray != null) {
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
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "translateSync failed: " + e.getMessage());
        }
        return text;
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
                // 采用特殊分隔符批量打包翻译，减少请求频率
                final String DELIMITER = "\n---PARAGRAPH_SEP---\n";
                StringBuilder combined = new StringBuilder();
                for (int i = 0; i < blocks.size(); i++) {
                    if (i > 0) {
                        combined.append(DELIMITER);
                    }
                    // 将单个块内部多行合并，换行替换为空格
                    String single = blocks.get(i).originalText.replace("\n", " ").trim();
                    combined.append(single);
                }

                String translatedAll = translateSync(combined.toString());
                String[] parts = translatedAll.split("---PARAGRAPH_SEP---|\\n---PARAGRAPH_SEP---\\n");

                if (parts.length == blocks.size()) {
                    for (int i = 0; i < blocks.size(); i++) {
                        blocks.get(i).setTranslatedText(parts[i].trim());
                    }
                } else {
                    // 若分段不匹配，逐段兜底翻译
                    for (MangaTextBlock block : blocks) {
                        String trans = translateSync(block.originalText);
                        block.setTranslatedText(trans);
                    }
                }

                sMainHandler.post(() -> callback.onSuccess(blocks));
            } catch (Throwable t) {
                Log.e(TAG, "translateBlocks error: " + t.getMessage(), t);
                sMainHandler.post(() -> callback.onFailure("翻译请求失败: " + t.getMessage()));
            }
        });
    }
}
