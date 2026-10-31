package com.hippo.ehviewer.translation;

import android.graphics.Rect;
import androidx.annotation.NonNull;

/**
 * 代表识别到的一个漫画对白/文字气泡块
 */
public class MangaTextBlock {
    @NonNull
    public final String originalText;
    @NonNull
    public String translatedText;
    @NonNull
    public final Rect boundingBox;
    public final int imageWidth;
    public final int imageHeight;
    public boolean visible = true;

    public MangaTextBlock(@NonNull String originalText, @NonNull Rect boundingBox, int imageWidth, int imageHeight) {
        this.originalText = originalText;
        this.translatedText = "";
        this.boundingBox = boundingBox;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
    }

    public void setTranslatedText(@NonNull String translatedText) {
        this.translatedText = translatedText;
    }

    public boolean hasTranslation() {
        return !translatedText.trim().isEmpty();
    }
}
