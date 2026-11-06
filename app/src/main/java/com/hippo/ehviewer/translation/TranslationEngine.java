package com.hippo.ehviewer.translation;

import androidx.annotation.NonNull;

import java.util.List;

/**
 * 翻译引擎统一入口。
 *
 * <p>用户在设置里选了「大模型 API」并且地址/Key/模型都填了，就走 {@link LlmTranslator}；
 * 否则继续用内置的免费 Google 接口。调用方不需要关心用的是哪一个。
 */
public final class TranslationEngine {

    private TranslationEngine() {
    }

    /** 当前是否真的走大模型（选了但没填全时不算，避免整本翻译全失败）。 */
    public static boolean isLlmActive() {
        return LlmTranslator.isConfigured();
    }

    public static int translateBlocksSync(@NonNull List<MangaTextBlock> blocks) {
        if (isLlmActive()) {
            return LlmTranslator.translateBlocksSync(blocks);
        }
        return GoogleTranslator.translateBlocksSync(blocks);
    }

    public static int fillMissingTranslations(@NonNull List<MangaTextBlock> blocks) {
        if (isLlmActive()) {
            return LlmTranslator.fillMissingTranslations(blocks);
        }
        return GoogleTranslator.fillMissingTranslations(blocks);
    }

    public static int countTranslated(@NonNull List<MangaTextBlock> blocks) {
        return GoogleTranslator.countTranslated(blocks);
    }
}
