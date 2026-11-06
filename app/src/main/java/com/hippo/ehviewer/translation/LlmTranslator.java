package com.hippo.ehviewer.translation;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.Settings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 用户自填的大模型翻译接口（OpenAI 兼容的 /chat/completions）。
 *
 * <p>只要服务商兼容 OpenAI 协议就能用：DeepSeek、OpenAI、通义千问、Kimi、
 * 智谱、硅基流动、本地 Ollama 等等。模型翻译的质量和语气比免费翻译接口好很多，
 * 而且走用户自己的 key，不会遇到免费接口那种限流。
 */
public final class LlmTranslator {

    private static final String TAG = "LlmTranslator";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int CONCURRENCY = 2;
    private static final int MAX_BLOCKS_PER_REQUEST = 30;
    private static final int MAX_CHARS_PER_REQUEST = 2500;
    private static final int MAX_RETRY = 2;
    private static final int CIRCUIT_BREAK_AFTER = 3;

    private static final String BATCH_PROMPT =
            "你是专业的日文漫画翻译。用户会按编号给你若干条日文台词，请逐条翻译成自然、口语化的简体中文。\n" +
            "严格按下面的规则输出：\n" +
            "1. 每条译文单独一行，格式为「编号. 译文」，编号和顺序必须与输入完全一致。\n" +
            "2. 不要合并、拆分、遗漏任何一条；原文不是日文时原样输出。\n" +
            "3. 不要输出解释、注释、罗马音、引号或任何额外内容。\n" +
            "4. 拟声词翻译成对应的中文拟声词，语气词保留原意。";

    private static final String SINGLE_PROMPT =
            "把下面这句日文漫画台词翻译成自然、口语化的简体中文。" +
            "只输出译文本身，不要解释、不要引号、不要罗马音；原文不是日文就原样返回。";

    /** 行首编号：支持 "1. "、"1、"、"1)"、"1：" 等模型常见格式。 */
    private static final Pattern NUMBERED =
            Pattern.compile("^\\s*(\\d{1,3})\\s*[.、)．:：]\\s*(.+)$");

    private static final ExecutorService sExecutor = Executors.newFixedThreadPool(CONCURRENCY);
    private static volatile OkHttpClient sClient;

    private LlmTranslator() {
    }

    /** 用户是否已经选了大模型引擎并且把三项配置填全。 */
    public static boolean isConfigured() {
        return Settings.getTranslationEngine() == Settings.TRANSLATION_ENGINE_LLM
                && !Settings.getTranslationApiBaseUrl().trim().isEmpty()
                && !Settings.getTranslationApiKey().trim().isEmpty()
                && !Settings.getTranslationApiModel().trim().isEmpty();
    }

    /**
     * 翻译一组块，返回成功翻译的块数。失败时会自动退化成逐块请求。
     * 必须在后台线程调用。
     */
    public static int translateBlocksSync(@NonNull List<MangaTextBlock> blocks) {
        List<List<MangaTextBlock>> batches = splitBatches(blocks);
        if (batches.isEmpty()) {
            return 0;
        }

        List<Future<Integer>> futures = new ArrayList<>(batches.size());
        for (List<MangaTextBlock> batch : batches) {
            futures.add(sExecutor.submit(() -> {
                int translated = translateBatch(batch);
                if (translated < 0) {
                    translated = translateOneByOne(batch);
                }
                return translated;
            }));
        }

        int success = 0;
        for (Future<Integer> future : futures) {
            try {
                success += future.get(300, TimeUnit.SECONDS);
            } catch (Throwable t) {
                Log.w(TAG, "batch failed: " + t.getMessage());
            }
        }
        return success;
    }

    /** 只补译缺失的块，不动已有译文。 */
    public static int fillMissingTranslations(@NonNull List<MangaTextBlock> blocks) {
        List<MangaTextBlock> missing = new ArrayList<>();
        for (MangaTextBlock block : blocks) {
            if (block.originalText == null || block.originalText.trim().isEmpty()) {
                continue;
            }
            if (block.translatedText == null || block.translatedText.trim().isEmpty()) {
                missing.add(block);
            }
        }
        return missing.isEmpty() ? 0 : translateBlocksSync(missing);
    }

    /**
     * 测试连接：发一条最小请求，成功返回译文，失败返回 null 并把原因写进 error。
     * 必须在后台线程调用。
     */
    @Nullable
    public static String testConnection(@NonNull StringBuilder error) {
        String result = request(BATCH_PROMPT, "1. こんにちは\n2. ありがとう");
        if (result == null || result.trim().isEmpty()) {
            error.append("请求失败，检查地址、Key、模型名和网络");
            return null;
        }
        return result.trim();
    }

    private static List<List<MangaTextBlock>> splitBatches(@NonNull List<MangaTextBlock> blocks) {
        List<List<MangaTextBlock>> result = new ArrayList<>();
        List<MangaTextBlock> current = new ArrayList<>();
        int chars = 0;
        for (MangaTextBlock block : blocks) {
            String original = block.originalText == null ? "" : block.originalText.trim();
            if (original.isEmpty()) {
                continue;
            }
            if (!current.isEmpty()
                    && (current.size() >= MAX_BLOCKS_PER_REQUEST
                    || chars + original.length() > MAX_CHARS_PER_REQUEST)) {
                result.add(current);
                current = new ArrayList<>();
                chars = 0;
            }
            current.add(block);
            chars += original.length();
        }
        if (!current.isEmpty()) {
            result.add(current);
        }
        return result;
    }

    /** @return 翻译成功的块数，-1 表示整批失败需要逐块重试 */
    private static int translateBatch(@NonNull List<MangaTextBlock> batch) {
        StringBuilder sb = new StringBuilder();
        int seq = 1;
        for (MangaTextBlock block : batch) {
            String original = block.originalText == null ? "" : block.originalText.trim();
            sb.append(seq).append(". ").append(original).append('\n');
            seq++;
        }
        String content = request(BATCH_PROMPT, sb.toString());
        if (content == null) {
            return -1;
        }

        Map<Integer, String> parsed = parseNumbered(content);
        if (parsed.size() < batch.size() / 2) {
            // 模型没按编号输出，直接按行对可能导致整页串位，退回逐块
            Log.w(TAG, "unexpected batch format, fallback to one by one");
            return -1;
        }

        int success = 0;
        for (int i = 0; i < batch.size(); i++) {
            String translated = parsed.get(i + 1);
            if (translated != null && !translated.isEmpty()) {
                batch.get(i).setTranslatedText(translated);
                success++;
            }
        }
        return success;
    }

    @NonNull
    private static Map<Integer, String> parseNumbered(@NonNull String content) {
        Map<Integer, String> result = new HashMap<>();
        for (String line : content.split("\n")) {
            String text = line.trim();
            if (text.isEmpty() || text.startsWith("```")) {
                // 有些模型会把译文包在 markdown 代码块里
                continue;
            }
            Matcher matcher = NUMBERED.matcher(text);
            if (matcher.matches()) {
                try {
                    int index = Integer.parseInt(matcher.group(1));
                    String translated = matcher.group(2).trim();
                    // 去掉模型偶尔自己加的引号
                    if (translated.length() >= 2
                            && (translated.startsWith("「") && translated.endsWith("」")
                            || translated.startsWith("\"") && translated.endsWith("\""))) {
                        translated = translated.substring(1, translated.length() - 1).trim();
                    }
                    if (!translated.isEmpty()) {
                        result.put(index, translated);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return result;
    }

    private static int translateOneByOne(@NonNull List<MangaTextBlock> blocks) {
        AtomicInteger success = new AtomicInteger();
        AtomicInteger consecutiveFailures = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>(blocks.size());
        for (MangaTextBlock block : blocks) {
            String original = block.originalText == null ? "" : block.originalText.trim();
            if (original.isEmpty()) {
                continue;
            }
            futures.add(sExecutor.submit(() -> {
                if (consecutiveFailures.get() >= CIRCUIT_BREAK_AFTER) {
                    return;
                }
                String translated = request(SINGLE_PROMPT, original);
                if (translated != null && !translated.trim().isEmpty()) {
                    block.setTranslatedText(cleanSingle(translated));
                    success.incrementAndGet();
                    consecutiveFailures.set(0);
                } else {
                    block.setTranslatedText("");
                    consecutiveFailures.incrementAndGet();
                }
            }));
            sleep(80);
        }
        for (Future<?> future : futures) {
            try {
                future.get(180, TimeUnit.SECONDS);
            } catch (Throwable ignored) {
            }
        }
        return success.get();
    }

    @NonNull
    private static String cleanSingle(@NonNull String text) {
        String result = text.trim();
        if (result.length() >= 2
                && (result.startsWith("「") && result.endsWith("」")
                || result.startsWith("\"") && result.endsWith("\""))) {
            result = result.substring(1, result.length() - 1).trim();
        }
        return result;
    }

    @Nullable
    private static String request(@NonNull String systemPrompt, @NonNull String userContent) {
        String baseUrl = Settings.getTranslationApiBaseUrl().trim();
        String apiKey = Settings.getTranslationApiKey().trim();
        String model = Settings.getTranslationApiModel().trim();
        if (baseUrl.isEmpty() || apiKey.isEmpty() || model.isEmpty()) {
            return null;
        }
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String url = baseUrl.endsWith("/chat/completions")
                ? baseUrl
                : baseUrl + "/chat/completions";

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", 0.2);
        body.put("stream", false);
        JSONArray messages = new JSONArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userContent));
        body.put("messages", messages);

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(JSON, body.toJSONString()))
                .build();

        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            try {
                Response response = getClient().newCall(request).execute();
                try {
                    String text = response.body() == null ? "" : response.body().string();
                    if (response.isSuccessful()) {
                        String content = extractContent(text);
                        if (content != null && !content.trim().isEmpty()) {
                            return content;
                        }
                        Log.w(TAG, "empty completion: " + brief(text));
                        return null;
                    }
                    if (response.code() == 429 || response.code() >= 500) {
                        // 限流或服务端忙，退避后重试
                        sleep(1000L * (attempt + 1));
                        continue;
                    }
                    Log.w(TAG, "http " + response.code() + ": " + brief(text));
                    return null;
                } finally {
                    response.close();
                }
            } catch (Throwable t) {
                Log.e(TAG, "request failed: " + t.getMessage());
                if (attempt + 1 < MAX_RETRY) {
                    sleep(600L * (attempt + 1));
                }
            }
        }
        return null;
    }

    @Nullable
    private static String extractContent(@NonNull String text) {
        try {
            JSONObject root = JSONObject.parseObject(text);
            JSONArray choices = root == null ? null : root.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }
            JSONObject choice = choices.getJSONObject(0);
            JSONObject message = choice == null ? null : choice.getJSONObject("message");
            if (message == null) {
                return null;
            }
            return message.getString("content");
        } catch (Throwable t) {
            Log.e(TAG, "parse response failed: " + t.getMessage());
            return null;
        }
    }

    @NonNull
    private static JSONObject message(@NonNull String role, @NonNull String content) {
        JSONObject object = new JSONObject();
        object.put("role", role);
        object.put("content", content);
        return object;
    }

    private static synchronized OkHttpClient getClient() {
        if (sClient == null) {
            // 大模型生成比普通接口慢得多，单独放宽读超时，别用翻译接口那套 20 秒
            sClient = EhApplication.getOkHttpClient(EhApplication.getInstance()).newBuilder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(90, TimeUnit.SECONDS)
                    .build();
        }
        return sClient;
    }

    @NonNull
    private static String brief(@NonNull String text) {
        // 错误响应里偶尔会回显 key（"Incorrect API key provided: sk-..."），先遮掉
        String oneLine = text.replace('\n', ' ').trim()
                .replaceAll("sk-[A-Za-z0-9_-]{6,}", "sk-******");
        return oneLine.length() > 200 ? oneLine.substring(0, 200) : oneLine;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
