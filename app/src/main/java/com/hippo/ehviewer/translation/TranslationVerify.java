package com.hippo.ehviewer.translation;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 译文质量校验。
 *
 * <p>翻译接口（尤其是大模型）偶尔会不按提示词输出：把日文原文原样返回、
 * 返回罗马音、或者干脆回一段英文。这些内容一旦被当成译文写进缓存，
 * 就会直接被烧录到漫画图上，看起来像"翻译出来不是中文"。
 *
 * <p>这里只做确定性的字符集判断，不依赖任何模型能力：
 * 日文假名（平假名 / 片假名）在正常的中文译文里几乎不会出现，
 * 一旦出现且占比不低，就可以判定这条"译文"其实还是日文。
 */
public final class TranslationVerify {

    /** 假名在译文中占比达到这个百分比就判定为仍是日文。 */
    private static final int KANA_RATIO_LIMIT = 18;
    /** 少于这个数量的假名不做比例判断，避免"の"这类单字误伤。 */
    private static final int KANA_MIN_COUNT = 2;

    private TranslationVerify() {
    }

    /**
     * 这条译文能不能用：非空、不是原文照抄、不是日文。
     *
     * @param original  原文
     * @param translated 模型/接口返回的译文
     */
    public static boolean isUsable(@Nullable String original, @Nullable String translated) {
        if (translated == null) {
            return false;
        }
        String text = translated.trim();
        if (text.isEmpty()) {
            return false;
        }
        // 原文本来就是中文时，模型原样返回是正常结果，不算失败
        if (!isMostlyJapanese(original) && isSameText(original, text)) {
            return true;
        }
        return !looksJapanese(text);
    }

    /**
     * 判断一段文本是否"还是日文"。
     *
     * <p>判据：假名数量达到 2 个，且假名占非空白字符的比例超过 18%，
     * 或者假名数量已经超过汉字数量。中文译文里出现的日文专名通常是
     * "の"、"ちゃん"这种零星残留，达不到这个比例。
     */
    public static boolean looksJapanese(@Nullable String text) {
        if (text == null) {
            return false;
        }
        int kana = 0;
        int han = 0;
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            total++;
            if (isKana(c)) {
                kana++;
            } else if (isHan(c)) {
                han++;
            }
        }
        if (kana < KANA_MIN_COUNT || total == 0) {
            return false;
        }
        if (kana > han) {
            return true;
        }
        return kana * 100 / total >= KANA_RATIO_LIMIT;
    }

    /** 原文是否以日文为主（用于区分"原文就是中文"的情况）。 */
    public static boolean isMostlyJapanese(@Nullable String text) {
        if (text == null) {
            return false;
        }
        int kana = 0;
        int han = 0;
        int latin = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (isKana(c)) {
                kana++;
            } else if (isHan(c)) {
                han++;
            } else if (c < 128 && Character.isLetter(c)) {
                latin++;
            }
        }
        if (kana == 0) {
            return false;
        }
        // 日文里有假名，且假名和汉字占主导；纯英文混一点假名不算
        return kana + han > latin;
    }

    /** 去掉空白和常见标点后比较，判断模型是否只是把原文抄了一遍。 */
    public static boolean isSameText(@Nullable String a, @Nullable String b) {
        String left = normalize(a);
        String right = normalize(b);
        return !left.isEmpty() && left.equals(right);
    }

    @NonNull
    private static String normalize(@Nullable String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            // 全角标点和半角标点都忽略，"。「」！？"这类不影响语义
            if (c == '。' || c == '、' || c == '「' || c == '」' || c == '！' || c == '？'
                    || c == '.' || c == ',' || c == '!' || c == '?' || c == '"' || c == '\''
                    || c == '…' || c == '~' || c == '〜') {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isKana(char c) {
        // 平假名 3040-309F、片假名 30A0-30FF（含长音符 30FC）
        return (c >= 0x3040 && c <= 0x309F) || (c >= 0x30A0 && c <= 0x30FF);
    }

    private static boolean isHan(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }
}
