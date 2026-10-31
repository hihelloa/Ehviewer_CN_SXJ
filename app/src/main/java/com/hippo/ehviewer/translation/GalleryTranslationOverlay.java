package com.hippo.ehviewer.translation;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 漫画阅读器原位气泡覆盖层
 * 将 OCR + 翻译后的文本以半透明气泡形式原位覆盖在漫画对白区域
 */
public class GalleryTranslationOverlay extends View {
    private final List<MangaTextBlock> mTextBlocks = new ArrayList<>();
    private final RectF mDisplayedPageRect = new RectF();
    private int mCurrentPageIndex = -1;
    private boolean mIsVisible = true;

    /**
     * 排版结果缓存。onDraw 与触摸判定会对每个气泡反复调用 getBubbleRect，
     * 不缓存的话每帧都要重新构建 StaticLayout，整页几十个气泡会明显掉帧。
     */
    private final Map<MangaTextBlock, StaticLayout> mLayoutCache = new IdentityHashMap<>();
    private final Map<MangaTextBlock, Integer> mLayoutWidthCache = new IdentityHashMap<>();

    private final Paint mBubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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
        mBorderPaint.setColor(Color.argb(70, 0, 0, 0));
        mBorderPaint.setStyle(Paint.Style.STROKE);
        mBorderPaint.setStrokeWidth(1.5f);

        mShadowPaint.setColor(Color.argb(55, 0, 0, 0));

        // 文本画笔
        mTextPaint.setColor(Color.argb(245, 15, 15, 15));
        mTextPaint.setTextSize(sp2px(13));

        mGestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                float x = e.getX();
                float y = e.getY();
                for (MangaTextBlock block : mTextBlocks) {
                    RectF rect = getBubbleRect(block);
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
                    RectF rect = getBubbleRect(block);
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
        if (!mTextBlocks.equals(blocks)) {
            mLayoutCache.clear();
            mLayoutWidthCache.clear();
        }
        mTextBlocks.clear();
        mTextBlocks.addAll(blocks);
        mDisplayedPageRect.set(displayedRect);
        mIsVisible = true;
        invalidate();
    }

    public void updateDisplayedRect(@NonNull RectF displayedRect) {
        if (mDisplayedPageRect.equals(displayedRect)) {
            return;
        }
        mDisplayedPageRect.set(displayedRect);
        invalidate();
    }

    public void clear() {
        mCurrentPageIndex = -1;
        mTextBlocks.clear();
        mLayoutCache.clear();
        mLayoutWidthCache.clear();
        invalidate();
    }

    public int getCurrentPageIndex() {
        return mCurrentPageIndex;
    }

    public boolean hasData() {
        return !mTextBlocks.isEmpty();
    }

    public int getBlockCount() {
        return mTextBlocks.size();
    }

    public boolean isOverlayVisible() {
        return mIsVisible;
    }

    public void setOverlayVisible(boolean visible) {
        mIsVisible = visible;
        invalidate();
    }

    /**
     * 把原图坐标映射成屏幕坐标。注意 scaleX / scaleY 分开算，
     * 避免图片被拉伸时气泡整体偏移。
     */
    private RectF getMappedRect(MangaTextBlock block) {
        if (block.imageWidth <= 0 || block.imageHeight <= 0 || mDisplayedPageRect.isEmpty()) {
            return new RectF();
        }
        float scaleX = mDisplayedPageRect.width() / block.imageWidth;
        float scaleY = mDisplayedPageRect.height() / block.imageHeight;

        float left = mDisplayedPageRect.left + block.boundingBox.left * scaleX;
        float top = mDisplayedPageRect.top + block.boundingBox.top * scaleY;
        float right = mDisplayedPageRect.left + block.boundingBox.right * scaleX;
        float bottom = mDisplayedPageRect.top + block.boundingBox.bottom * scaleY;

        return new RectF(left, top, right, bottom);
    }

    private String textOf(MangaTextBlock block) {
        return block.hasTranslation() ? block.translatedText : block.originalText;
    }

    /**
     * 计算实际绘制区域：先按文字量估算需要的高度，再向上/向下扩展到不超出版面。
     */
    private RectF getBubbleRect(MangaTextBlock block) {
        RectF mapped = getMappedRect(block);
        if (mapped.isEmpty()) {
            return mapped;
        }
        float density = getResources().getDisplayMetrics().density;
        float padH = 4f * density;
        float padV = 3f * density;

        float maxWidth = Math.max(mapped.width(), 40f * density);
        int textWidth = Math.max((int) (maxWidth - padH * 2), 20);
        StaticLayout layout = layoutFor(block, textWidth);

        float needHeight = layout.getHeight() + padV * 2;
        float needWidth = Math.max(mapped.width(), Math.min(layout.getWidth() + padH * 2, mDisplayedPageRect.width()));

        float centerX = (mapped.left + mapped.right) / 2f;
        float centerY = (mapped.top + mapped.bottom) / 2f;

        float left = centerX - needWidth / 2f;
        float top = centerY - needHeight / 2f;
        float right = centerX + needWidth / 2f;
        float bottom = centerY + needHeight / 2f;

        // 限制在漫画画面范围内，避免气泡跑到黑边上
        if (left < mDisplayedPageRect.left) {
            right += mDisplayedPageRect.left - left;
            left = mDisplayedPageRect.left;
        }
        if (right > mDisplayedPageRect.right) {
            left -= right - mDisplayedPageRect.right;
            right = mDisplayedPageRect.right;
        }
        if (top < mDisplayedPageRect.top) {
            bottom += mDisplayedPageRect.top - top;
            top = mDisplayedPageRect.top;
        }
        if (bottom > mDisplayedPageRect.bottom) {
            top -= bottom - mDisplayedPageRect.bottom;
            bottom = mDisplayedPageRect.bottom;
        }
        return new RectF(left, top, right, bottom);
    }

    /**
     * 取（并按需缓存）排版结果。宽度变化时才重建。
     */
    private StaticLayout layoutFor(MangaTextBlock block, int width) {
        StaticLayout cached = mLayoutCache.get(block);
        Integer cachedWidth = mLayoutWidthCache.get(block);
        if (cached != null && cachedWidth != null && cachedWidth == width) {
            return cached;
        }
        StaticLayout layout = buildLayout(textOf(block), width);
        mLayoutCache.put(block, layout);
        mLayoutWidthCache.put(block, width);
        return layout;
    }

    private StaticLayout buildLayout(String text, int width) {
        float size = sp2px(15);
        float minSize = sp2px(8);
        while (size > minSize) {
            mTextPaint.setTextSize(size);
            StaticLayout layout = new StaticLayout(text, mTextPaint, width,
                    Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false);
            // 单页气泡允许比原文字框高一些，但不超过 3 倍
            if (layout.getLineCount() <= 6) {
                return layout;
            }
            size -= 1f;
        }
        mTextPaint.setTextSize(minSize);
        return new StaticLayout(text, mTextPaint, width, Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!mIsVisible || mTextBlocks.isEmpty() || mDisplayedPageRect.isEmpty()) {
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        float radius = 6f * density;

        for (MangaTextBlock block : mTextBlocks) {
            if (!block.visible) {
                continue;
            }

            RectF rect = getBubbleRect(block);
            if (rect.isEmpty() || rect.width() < 8 || rect.height() < 8) {
                continue;
            }

            // 阴影
            RectF shadow = new RectF(rect);
            shadow.offset(0, 2f * density);
            canvas.drawRoundRect(shadow, radius, radius, mShadowPaint);

            // 气泡
            canvas.drawRoundRect(rect, radius, radius, mBubblePaint);
            canvas.drawRoundRect(rect, radius, radius, mBorderPaint);

            // 复用 getBubbleRect 刚算好的布局，避免同一帧内重复构建 StaticLayout
            StaticLayout layout = mLayoutCache.get(block);
            if (layout == null) {
                int textWidth = Math.max((int) (rect.width() - 8f * density), 20);
                layout = layoutFor(block, textWidth);
            }

            canvas.save();
            float textTop = rect.top + (rect.height() - layout.getHeight()) / 2f;
            canvas.translate(rect.left + 4f * density, Math.max(rect.top + 2f, textTop));
            layout.draw(canvas);
            canvas.restore();
        }
    }

    private void copyToClipboard(MangaTextBlock block) {
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            String text = textOf(block);
            ClipData clipData = ClipData.newPlainText("Manga Translation", text);
            cm.setPrimaryClip(clipData);
            Toast.makeText(getContext(), "已复制：" + text, Toast.LENGTH_SHORT).show();
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
            if (getBubbleRect(block).contains(x, y)) {
                mGestureDetector.onTouchEvent(event);
                return true;
            }
        }
        return false;
    }
}
