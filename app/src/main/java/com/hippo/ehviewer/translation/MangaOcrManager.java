package com.hippo.ehviewer.translation;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * 漫画 OCR 识别管理器
 * 基于 Google ML Kit 离线日文与拉丁文识别模型
 */
public class MangaOcrManager {
    private static final String TAG = "MangaOcrManager";
    private static MangaOcrManager sInstance;
    private final TextRecognizer mRecognizer;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    public interface OcrCallback {
        void onSuccess(@NonNull List<MangaTextBlock> blocks);
        void onFailure(@NonNull String error);
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
     * 对漫画位图进行 OCR 识别并调用翻译
     */
    public void processPageBitmap(@NonNull Bitmap bitmap, @NonNull OcrCallback callback) {
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
                            if (isNoiseText(text) || rect.width() < 10 || rect.height() < 10) {
                                continue;
                            }

                            blocks.add(new MangaTextBlock(text, rect, imgWidth, imgHeight));
                        }

                        if (blocks.isEmpty()) {
                            callback.onSuccess(blocks);
                            return;
                        }

                        // 识别成功后，无缝调用 Google 翻译
                        GoogleTranslator.translateBlocks(blocks, new GoogleTranslator.TranslationCallback() {
                            @Override
                            public void onSuccess(@NonNull List<MangaTextBlock> translatedBlocks) {
                                callback.onSuccess(translatedBlocks);
                            }

                            @Override
                            public void onFailure(@NonNull String errorMessage) {
                                // 翻译即使网络失败，也把原始识别文字返回展示
                                callback.onSuccess(blocks);
                            }
                        });
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "OCR recognition error: " + e.getMessage(), e);
                        callback.onFailure("OCR识别失败: " + e.getMessage());
                    });
        } catch (Throwable t) {
            Log.e(TAG, "processPageBitmap error: " + t.getMessage(), t);
            callback.onFailure("图像处理错误: " + t.getMessage());
        }
    }

    /**
     * 判断是否为纯噪点（如纯数字、纯符号）
     */
    private boolean isNoiseText(String text) {
        String clean = text.replaceAll("[\\s\\p{Punct}〜…・「」『』（）［］【】、。!?！？0-9]+", "");
        return clean.isEmpty();
    }
}
