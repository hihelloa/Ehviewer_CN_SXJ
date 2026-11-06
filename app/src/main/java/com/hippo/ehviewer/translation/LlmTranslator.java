package com.hippo.ehviewer.translation;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.Settings;

import java.util.ArrayList;
import java.util.Collections;
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

import okhttp3.ConnectionSpec;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.TlsVersion;

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
            "你是一名日文漫画的专职翻译。用户会按编号给你若干条日文台词，你要逐条译成简体中文。\n" +
            "\n" +
            "硬性要求：\n" +
            "1. 输出语言只能是简体中文。原文里的日文假名必须译成中文，禁止保留假名、禁止输出日文原文、禁止罗马音、禁止英文。\n" +
            "2. 每条译文单独一行，格式为「编号. 译文」，编号和顺序必须与输入完全一致。\n" +
            "3. 不要合并、拆分、遗漏任何一条；输入里本来就是中文的条目照抄即可。\n" +
            "4. 不要输出解释、注释、引号、markdown 代码块或任何额外内容。\n" +
            "5. 拟声词译成对应的中文拟声词，语气词按中文口语习惯表达。\n" +
            "6. 人名、地名用通行的中文译名或保留汉字，不要写成片假名。\n" +
            "\n" +
            "示例：\n" +
            "输入：\n" +
            "1. おはよう\n" +
            "2. 何してるの？\n" +
            "输出：\n" +
            "1. 早上好\n" +
            "2. 你在干什么？";

    private static final String SINGLE_PROMPT =
            "把下面这句日文漫画台词翻译成简体中文。\n" +
            "只输出中文译文本身：不要日文原文、不要假名、不要罗马音、不要英文、不要解释、不要引号。\n" +
            "保持口语化，符合漫画对白的语气。";

    /** 行首编号：支持 "1. "、"1、"、"1)"、"1：" 等模型常见格式。 */
    private static final Pattern NUMBERED =
            Pattern.compile("^\\s*(\\d{1,3})\\s*[.、)．:：]\\s*(.+)$");

    private static final ExecutorService sExecutor = Executors.newFixedThreadPool(CONCURRENCY);
    private static volatile OkHttpClient sClient;
    private static volatile Boolean sClientCompat;
    /** 最近一次请求失败的可读原因，供「测试连接」直接展示。 */
    private static volatile String sLastError;

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
            // 空译文，或旧版本存下来的"译文其实还是日文"，都要重译
            if (!TranslationVerify.isUsable(block.originalText, block.translatedText)) {
                block.setTranslatedText("");
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
            String detail = sLastError;
            error.append(detail == null
                    ? "请求失败，检查地址、Key、模型名和网络"
                    : detail);
            return null;
        }
        if (TranslationVerify.looksJapanese(result)) {
            // 模型连通但没在翻译，这种情况最难发现：返回的日文会被当成译文画到图上
            error.append("模型返回的仍是日文，没有执行翻译。请换一个对话模型（如 deepseek-chat、gpt-4o-mini、qwen-plus）");
            return null;
        }
        return result.trim();
    }

    /**
     * 从上游的 /models 接口拉取可用模型列表。
     *
     * <p>不同服务商的 base URL 写法不一样：OpenAI 习惯写到 /v1，DeepSeek 写到域名根，
     * 所以先按 {base}/models 试，404 再补一次 {base}/v1/models。
     *
     * <p>必须在后台线程调用。
     *
     * @return 去重并排序后的模型名；失败返回 null，原因写进 error
     */
    @Nullable
    public static List<String> fetchModels(@NonNull StringBuilder error) {
        String baseUrl = Settings.getTranslationApiBaseUrl().trim();
        String apiKey = Settings.getTranslationApiKey().trim();
        if (baseUrl.isEmpty() || apiKey.isEmpty()) {
            error.append("请先填写 API 地址和 Key");
            return null;
        }
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (baseUrl.endsWith("/chat/completions")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - "/chat/completions".length());
        }

        List<String> candidates = new ArrayList<>(2);
        candidates.add(baseUrl + "/models");
        if (!baseUrl.matches(".*/v\\d+[a-z0-9]*$")) {
            // 没写版本号时再补一个 /v1/models，兼容 OpenAI 风格的根地址
            candidates.add(baseUrl + "/v1/models");
        }

        String lastError = null;
        // 拉列表不该等满 90 秒：服务端没实现 /models 时往往直接挂住
        OkHttpClient client = getClient().newBuilder()
                .readTimeout(20, TimeUnit.SECONDS)
                .build();
        for (String url : candidates) {
            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json")
                    .get()
                    .build();
            try {
                Response response = client.newCall(request).execute();
                try {
                    String text = response.body() == null ? "" : response.body().string();
                    if (response.isSuccessful()) {
                        List<String> models = parseModels(text);
                        if (!models.isEmpty()) {
                            return models;
                        }
                        lastError = "接口返回的内容里没有模型名，可手动填写";
                    } else if (response.code() == 404) {
                        // 路径不对，换下一个候选地址；都 404 说明服务商没实现这个接口
                        lastError = "该服务商没有 /models 接口（HTTP 404），可手动填写模型名";
                    } else if (response.code() == 401 || response.code() == 403) {
                        error.append("Key 无效或没有权限（HTTP ").append(response.code()).append("）");
                        return null;
                    } else {
                        error.append("HTTP ").append(response.code()).append("：").append(brief(text));
                        return null;
                    }
                } finally {
                    response.close();
                }
            } catch (Throwable t) {
                Log.e(TAG, "fetch models failed: " + t.getMessage());
                lastError = describeError(t);
            }
        }
        error.append(lastError == null ? "无法获取模型列表" : lastError);
        return null;
    }

    /** 解析 /models 响应，兼容 data[].id、models[].name、data.models[] 和纯字符串数组四种格式。 */
    @NonNull
    private static List<String> parseModels(@NonNull String text) {
        List<String> result = new ArrayList<>();
        try {
            String trimmed = text.trim();
            JSONArray array = null;
            if (trimmed.startsWith("[")) {
                // 少数服务商直接返回 ["model-a","model-b"]，没有外层对象
                array = JSONArray.parseArray(trimmed);
            } else {
                JSONObject root = JSONObject.parseObject(trimmed);
                if (root != null) {
                    array = root.getJSONArray("data");
                    if (array == null) {
                        array = root.getJSONArray("models");
                    }
                    if (array == null) {
                        // 少数网关包了两层：{"data":{"models":[...]}}
                        Object data = root.get("data");
                        if (data instanceof JSONObject) {
                            array = ((JSONObject) data).getJSONArray("models");
                        }
                    }
                }
            }
            if (array == null) {
                return result;
            }
            for (int i = 0; i < array.size(); i++) {
                Object item = array.get(i);
                String id = null;
                if (item instanceof String) {
                    id = (String) item;
                } else if (item instanceof JSONObject) {
                    JSONObject object = (JSONObject) item;
                    id = object.getString("id");
                    if (id == null || id.trim().isEmpty()) {
                        id = object.getString("name");
                    }
                }
                if (id != null && !id.trim().isEmpty()) {
                    result.add(id.trim());
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "parse models failed: " + t.getMessage());
        }
        // 去重后把 chat/instruct 这类适合对话的模型排前面，其余的按字母序
        List<String> unique = new ArrayList<>();
        for (String name : result) {
            if (!unique.contains(name)) {
                unique.add(name);
            }
        }
        unique.sort((a, b) -> {
            boolean ca = isChatModel(a);
            boolean cb = isChatModel(b);
            if (ca != cb) {
                return ca ? -1 : 1;
            }
            return a.compareToIgnoreCase(b);
        });
        return unique;
    }

    private static boolean isChatModel(@NonNull String name) {
        String lower = name.toLowerCase();
        // 排除向量、语音、图像生成这类明显不能用于对话的模型
        if (lower.contains("embedding") || lower.contains("whisper")
                || lower.contains("tts") || lower.contains("dall-e")
                || lower.contains("moderation")) {
            return false;
        }
        return lower.contains("chat") || lower.contains("instruct")
                || lower.contains("deepseek") || lower.contains("qwen")
                || lower.contains("gpt") || lower.contains("claude")
                || lower.contains("glm") || lower.contains("kimi");
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
        int rejected = 0;
        for (int i = 0; i < batch.size(); i++) {
            MangaTextBlock block = batch.get(i);
            String translated = parsed.get(i + 1);
            if (translated == null || translated.isEmpty()) {
                continue;
            }
            // 模型有时会把日文原样返回，这种"译文"画到图上就是一句日文
            if (!TranslationVerify.isUsable(block.originalText, translated)) {
                rejected++;
                Log.w(TAG, "reject non-Chinese: " + brief(block.originalText)
                        + " -> " + brief(translated));
                continue;
            }
            block.setTranslatedText(translated);
            success++;
        }
        if (rejected > 0) {
            Log.w(TAG, "rejected " + rejected + " result(s) that are not Chinese");
        }
        if (success == 0) {
            // 整批都没译出中文，退回逐块再试一次（逐块提示词约束更直接）
            return -1;
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
                String translated = cleanSingle(request(SINGLE_PROMPT, original));
                if (!translated.isEmpty() && TranslationVerify.isUsable(original, translated)) {
                    block.setTranslatedText(translated);
                    success.incrementAndGet();
                    consecutiveFailures.set(0);
                } else {
                    if (!translated.isEmpty()) {
                        Log.w(TAG, "reject non-Chinese: " + brief(original)
                                + " -> " + brief(translated));
                    }
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
    private static String cleanSingle(@Nullable String text) {
        if (text == null) {
            return "";
        }
        String result = text.trim();
        // 有些模型会把译文包在 markdown 代码块里
        result = result.replace("```", "").trim();
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
                            sLastError = null;
                            return content;
                        }
                        sLastError = "接口返回了空内容";
                        Log.w(TAG, "empty completion: " + brief(text));
                        return null;
                    }
                    if (response.code() == 429 || response.code() >= 500) {
                        // 限流或服务端忙，退避后重试
                        sLastError = "HTTP " + response.code() + "（服务端限流或异常）";
                        sleep(1000L * (attempt + 1));
                        continue;
                    }
                    sLastError = "HTTP " + response.code() + "，请检查地址、Key 和模型名";
                    Log.w(TAG, "http " + response.code() + ": " + brief(text));
                    return null;
                } finally {
                    response.close();
                }
            } catch (Throwable t) {
                sLastError = describeError(t);
                Log.e(TAG, "request failed: " + t.getMessage());
                if (attempt + 1 < MAX_RETRY) {
                    sleep(600L * (attempt + 1));
                }
            }
        }
        return null;
    }

    /** 把底层异常翻译成可操作的提示，别把 BoringSSL 的堆栈直接甩给用户。 */
    @NonNull
    private static String describeError(@NonNull Throwable t) {
        String msg = t.getMessage() == null ? t.toString() : t.getMessage();
        String lower = msg.toLowerCase();
        if (lower.contains("handshake_failure") || lower.contains("handshake failed")
                || lower.contains("certificate") || lower.contains("ssl")) {
            return "TLS 握手失败：网络或 VPN 拦截了 HTTPS 连接。"
                    + "先断开 VPN/代理再试；仍然失败就打开下面的「仅用 TLS 1.2 连接」。";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "连接超时：当前网络到该地址不通，换 Wi-Fi 或换个时段重试。";
        }
        if (lower.contains("unable to resolve host") || lower.contains("unknownhost")
                || lower.contains("nodename nor servname")) {
            return "域名解析失败：检查 API 地址有没有拼错。";
        }
        return "请求失败：" + msg;
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
        boolean compat = Settings.getTranslationTlsCompat();
        if (sClient == null || sClientCompat == null || sClientCompat != compat) {
            // 大模型生成比普通接口慢得多，单独放宽读超时，别用翻译接口那套 20 秒
            OkHttpClient.Builder builder = EhApplication.getOkHttpClient(EhApplication.getInstance())
                    .newBuilder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(90, TimeUnit.SECONDS);
            if (compat) {
                // 部分中转站或中间设备不接受 TLS 1.3 的 ClientHello，
                // 表现就是 BoringSSL 的 HANDSHAKE_FAILURE_ON_CLIENT_HELLO。
                // 退回 TLS 1.2，并把 HTTP 协议锁到 1.1，绕开这类兼容问题。
                ConnectionSpec spec = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                        .tlsVersions(TlsVersion.TLS_1_2)
                        .build();
                builder.connectionSpecs(Collections.singletonList(spec));
                builder.protocols(Collections.singletonList(Protocol.HTTP_1_1));
            }
            sClient = builder.build();
            sClientCompat = compat;
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
