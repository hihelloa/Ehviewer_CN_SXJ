package com.hippo.ehviewer.translation;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 漫画阅读器原位气泡覆盖层
 * 将 OCR + 翻译后的文本以半透明气泡形式原位覆盖在漫画对白区域
 */
public class GalleryTranslationOverlay extends View {
    private final List<MangaTextBlock> mTextBlocks = new ArrayList<>();
    private final RectF mDisplayedPageRect = new RectF();
    private int mCurrentPageIndex = -1;
    private boolean mIsVisible = true;

    private final Paint mBubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final GestureDetector mGestureDetector;

    public GalleryTranslationOverlay(Context context) {
        this(context, null);
    }

    public GalleryTranslationOverlay(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public GalleryTranslationOverlay(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        // 气泡半透明白色背景
        mBubblePaint.setColor(Color.argb(242, 255, 255, 255));
        mBubblePaint.setStyle(Paint.Style.FILL);

        // 气泡描边
        mBorderPaint.setColor(Color.argb(80, 0, 0, 0));
        mBorderPaint.setStyle(Paint.Style.STROKE);
        mBorderPaint.setStrokeWidth(2f);

        // 文本画笔
        mTextPaint.setColor(Color.argb(240, 15, 15, 15));
        mTextPaint.setTextSize(sp2px(12));

        mGestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                float x = e.getX();
                float y = e.getY();
                for (MangaTextBlock block : mTextBlocks) {
                    RectF rect = getScreenRect(block);
                    if (rect.contains(x, y)) {
                        block.visible = !block.visible;
                        invalidate();
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                float x = e.getX();
                float y = e.getY();
                for (MangaTextBlock block : mTextBlocks) {
                    RectF rect = getScreenRect(block);
                    if (rect.contains(x, y)) {
                        copyToClipboard(block);
                        return;
                    }
                }
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                // 双击隐藏或恢复整页覆盖层
                mIsVisible = !mIsVisible;
                invalidate();
                return true;
            }
        });
    }

    public void setPageData(int pageIndex, @NonNull List<MangaTextBlock> blocks, @NonNull RectF displayedRect) {
        mCurrentPageIndex = pageIndex;
        mTextBlocks.clear();
        mTextBlocks.addAll(blocks);
        mDisplayedPageRect.set(displayedRect);
        mIsVisible = true;
        invalidate();
    }

    public void updateDisplayedRect(@NonNull RectF displayedRect) {
        mDisplayedPageRect.set(displayedRect);
        invalidate();
    }

    public void clear() {
        mCurrentPageIndex = -1;
        mTextBlocks.clear();
        invalidate();
    }

    public int getCurrentPageIndex() {
        return mCurrentPageIndex;
    }

    public boolean hasData() {
        return !mTextBlocks.isEmpty();
    }

    private RectF getScreenRect(MangaTextBlock block) {
        if (block.imageWidth <= 0 || block.imageHeight <= 0 || mDisplayedPageRect.isEmpty()) {
            return new RectF();
        }
        float scaleX = mDisplayedPageRect.width() / block.imageWidth;
        float scaleY = mDisplayedPageRect.height() / block.imageHeight;

        // 适当留出 4px 内边距覆盖完整原字
        float left = mDisplayedPageRect.left + block.boundingBox.left * scaleX - 3;
        float top = mDisplayedPageRect.top + block.boundingBox.top * scaleY - 3;
        float right = mDisplayedPageRect.left + block.boundingBox.right * scaleX + 3;
        float bottom = mDisplayedPageRect.top + block.boundingBox.bottom * scaleY + 3;

        return new RectF(left, top, right, bottom);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!mIsVisible || mTextBlocks.isEmpty() || mDisplayedPageRect.isEmpty()) {
            return;
        }

        for (MangaTextBlock block : mTextBlocks) {
            if (!block.visible) {
                continue;
            }

            RectF rect = getScreenRect(block);
            if (rect.isEmpty() || rect.width() < 10 || rect.height() < 10) {
                continue;
            }

            // 绘制圆角气泡
            float radius = Math.min(16f, Math.min(rect.width(), rect.height()) / 4);
            canvas.drawRoundRect(rect, radius, radius, mBubblePaint);
            canvas.drawRoundRect(rect, radius, radius, mBorderPaint);

            String text = block.hasTranslation() ? block.translatedText : block.originalText;
            int availableWidth = Math.max((int) (rect.width() - 8), 10);

            // 自适应字号
            float textSize = calculateOptimalTextSize(text, availableWidth, rect.height() - 6);
            mTextPaint.setTextSize(textSize);

            StaticLayout layout = new StaticLayout(
                    text,
                    mTextPaint,
                    availableWidth,
                    Layout.Alignment.ALIGN_CENTER,
                    1.0f,
                    0.0f,
                    false
            );

            canvas.save();
            float textTop = rect.top + (rect.height() - layout.getHeight()) / 2f;
            canvas.translate(rect.left + 4, Math.max(rect.top + 2, textTop));
            layout.draw(canvas);
            canvas.restore();
        }
    }

    private float calculateOptimalTextSize(String text, int width, float maxHeight) {
        float size = sp2px(13);
        float minSize = sp2px(8);

        while (size > minSize) {
            mTextPaint.setTextSize(size);
            StaticLayout layout = new StaticLayout(
                    text,
                    mTextPaint,
                    width,
                    Layout.Alignment.ALIGN_CENTER,
                    1.0f,
                    0.0f,
                    false
            );
            if (layout.getHeight() <= maxHeight) {
                return size;
            }
            size -= 1.5f;
        }
        return minSize;
    }

    private void copyToClipboard(MangaTextBlock block) {
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            String text = block.hasTranslation() ? block.translatedText : block.originalText;
            ClipData clipData = ClipData.newPlainText("Manga Translation", text);
            cm.setPrimaryClip(clipData);
            Toast.makeText(getContext(), "已复制文本: " + text, Toast.LENGTH_SHORT).show();
        }
    }

    private float sp2px(float sp) {
        return sp * getResources().getDisplayMetrics().scaledDensity;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!mIsVisible || mTextBlocks.isEmpty()) {
            return false;
        }
        // 如果点击在任何一个气泡内，消费该事件
        float x = event.getX();
        float y = event.getY();
        for (MangaTextBlock block : mTextBlocks) {
            if (getScreenRect(block).contains(x, y)) {
                mGestureDetector.onTouchEvent(event);
                return true;
            }
        }
        return false;
    }
}
