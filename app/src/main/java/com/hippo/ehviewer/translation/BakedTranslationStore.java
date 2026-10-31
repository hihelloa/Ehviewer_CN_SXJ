package com.hippo.ehviewer.translation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.lib.image.Image;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.List;

/**
 * 译文烧录：把中文直接画进漫画图片，生成一张"汉化版"图片。
 * 阅读器加载图片时优先使用它，文字因此成为画面的一部分，
 * 拖动、缩放、滚动都完全跟手，不会像悬浮气泡那样残留错位。
 */
public final class BakedTranslationStore {

    private static final String TAG = "BakedTranslationStore";
    private static final String DIR = "baked_translation";
    private static final int JPEG_QUALITY = 92;

    /**
     * 记录"这张 Image 就是烧录图"。解码线程会优先返回汉化图，
     * 上层回调如果再查一次就会重复解码，用它做去重。
     * 弱引用：GL 层回收纹理后自动消失，不会持有图片内存。
     */
    private static final java.util.Map<Image, Boolean> BAKED_IMAGES =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Image, Boolean>());

    private BakedTranslationStore() {
    }

    /** 这张图是否由 {@link #decode} 产出（即已经是汉化图）。 */
    public static boolean isBakedImage(@Nullable Image image) {
        if (image == null) {
            return false;
        }
        return BAKED_IMAGES.containsKey(image);
    }

    @NonNull
    private static File root(@NonNull Context context) {
        // 放在 files 目录而不是 cache：译文 JSON 存在 files 里，
        // 烧录图却放 cache 会被系统在存储紧张时直接清掉，
        // 结果就是"翻译记录还在，但画面上一句译文都没有"。
        File dir = new File(context.getFilesDir(), DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Failed to create " + dir);
        }
        return dir;
    }

    /** 旧版本把烧录图写在 cacheDir，读取时仍要兼容，避免升级后译文全部消失。 */
    @NonNull
    private static File legacyRoot(@NonNull Context context) {
        return new File(context.getCacheDir(), DIR);
    }

    @NonNull
    public static File file(@NonNull Context context, @NonNull String key, int index) {
        File dir = new File(root(context), key);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Failed to create " + dir);
        }
        return new File(dir, index + ".jpg");
    }

    /**
     * 读取时先找新位置，再退回旧位置。
     */
    @NonNull
    private static File find(@NonNull Context context, @NonNull String key, int index) {
        File current = file(context, key, index);
        if (current.exists() && current.length() > 0) {
            return current;
        }
        File legacy = new File(new File(legacyRoot(context), key), index + ".jpg");
        if (legacy.exists() && legacy.length() > 0) {
            // 顺手迁到新位置，否则旧图迟早会被系统当缓存清掉
            if (legacy.renameTo(current) && current.exists()) {
                return current;
            }
            if (current.exists() && current.length() > 0) {
                return current;
            }
            return legacy;
        }
        return current;
    }

    public static boolean has(@NonNull Context context, @NonNull String key, int index) {
        File f = find(context, key, index);
        return f.exists() && f.length() > 0;
    }

    /**
     * 把译文烧进图片并保存。source 必须与 blocks 的坐标系一致
     * （即 OCR 时使用的那张位图）。
     */
    public static boolean bake(@NonNull Context context, @NonNull String key, int index,
                               @NonNull Bitmap source, @NonNull List<MangaTextBlock> blocks) {
        if (blocks.isEmpty()) {
            return false;
        }
        // 一句译文都没有时不要生成图片：否则整页会被画满空白气泡
        int drawable = 0;
        for (MangaTextBlock block : blocks) {
            if (block.translatedText != null && !block.translatedText.trim().isEmpty()) {
                drawable++;
            }
        }
        if (drawable == 0) {
            Log.w(TAG, "No translated text on page " + index + ", skip baking");
            return false;
        }
        Bitmap out = copyForDraw(source);
        if (out == null) {
            return false;
        }
        try {
            Canvas canvas = new Canvas(out);
            // 先画大块再画小块：ML Kit 偶尔会返回范围重叠的块，
            // 按面积降序可以避免大背景把小气泡连同文字一起盖掉。
            List<MangaTextBlock> ordered = new java.util.ArrayList<>(blocks);
            java.util.Collections.sort(ordered,
                    (a, b) -> Long.compare(areaOf(b.boundingBox), areaOf(a.boundingBox)));
            for (MangaTextBlock block : ordered) {
                String text = block.translatedText == null ? "" : block.translatedText.trim();
                if (text.isEmpty()) {
                    continue;
                }
                drawBlock(canvas, block.boundingBox, text, out.getWidth(), out.getHeight());
            }
            File dest = file(context, key, index);
            File tmp = new File(dest.getAbsolutePath() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
                fos.flush();
            }
            // 先直接 rename：Linux 上 rename 是原子替换，中途被杀进程也不会丢文件。
            // 只有 rename 失败（少数机型不允许覆盖）才退回"删除再重命名"。
            if (!tmp.renameTo(dest)) {
                if (dest.exists() && !dest.delete()) {
                    Log.w(TAG, "Failed to delete old baked image");
                }
                return tmp.renameTo(dest);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "bake failed: " + t.getMessage());
            return false;
        } finally {
            out.recycle();
        }
    }

    /**
     * 复制一份可绘制的位图。2600px 的页面 ARGB_8888 大约 37MB，
     * 低内存机型容易 OOM，失败时降级到 RGB_565（漫画多为黑白，画质损失可接受）。
     */
    @Nullable
    private static Bitmap copyForDraw(@NonNull Bitmap source) {
        try {
            return source.copy(Bitmap.Config.ARGB_8888, true);
        } catch (Throwable t) {
            Log.w(TAG, "ARGB_8888 copy failed (" + t.getMessage() + "), fallback to RGB_565");
        }
        try {
            return source.copy(Bitmap.Config.RGB_565, true);
        } catch (Throwable t) {
            Log.w(TAG, "copy bitmap failed: " + t.getMessage());
            return null;
        }
    }

    private static void drawBlock(@NonNull Canvas canvas, @NonNull Rect box, @NonNull String text,
                                  int imgWidth, int imgHeight) {
        if (box.width() <= 2 || box.height() <= 2 || imgWidth <= 4 || imgHeight <= 4) {
            return;
        }
        // 日文对白多是竖排窄条，中文是横排，直接塞进原框会挤成十几行。
        // 这类框优先向两侧扩宽；横向排版则优先向下扩高。
        boolean verticalSource = box.height() > box.width() * 1.3f;

        float padX = Math.max(2f, box.width() * 0.05f);
        float padY = Math.max(2f, box.height() * 0.05f);

        int maxW = verticalSource
                ? (int) Math.min(imgWidth - 4, box.width() * 1.9f)
                : (int) Math.min(imgWidth - 4, box.width() * 1.2f);
        maxW = Math.max(maxW, Math.min(box.width(), imgWidth - 4));
        // 高度上限给到原框的 3 倍：宁可多盖一点背景，也不要把译文裁掉
        int maxH = (int) Math.min(imgHeight - 4, box.height() * 3.0f);
        maxH = Math.max(maxH, Math.min(box.height(), imgHeight - 4));

        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(Color.BLACK);
        tp.setTypeface(Typeface.DEFAULT_BOLD);
        tp.setTextAlign(Paint.Align.LEFT);

        int textSize = fitTextSize(tp, text, maxW, maxH);
        tp.setTextSize(textSize);
        StaticLayout layout = buildLayout(text, tp, maxW);

        // 背景按"原框"和"实际排版尺寸"取大者，保证文字不会被裁。
        // 注意 StaticLayout.getWidth() 返回的是构造时给的宽度 maxW，不是文字真正占的宽度；
        // 直接拿它会画出一条和 maxW 一样宽的白条，把旁边的画面整块盖住。
        float textW = 0f;
        for (int i = 0; i < layout.getLineCount(); i++) {
            textW = Math.max(textW, layout.getLineWidth(i));
        }
        float bgW = Math.max(box.width() + padX * 2f, textW + padX * 2f);
        float bgH = Math.max(box.height() + padY * 2f, layout.getHeight() + padY * 2f);
        bgW = Math.min(bgW, imgWidth - 2f);
        bgH = Math.min(bgH, imgHeight - 2f);

        float left = box.exactCenterX() - bgW / 2f;
        float top = box.exactCenterY() - bgH / 2f;
        left = Math.max(1f, Math.min(left, imgWidth - bgW - 1f));
        top = Math.max(1f, Math.min(top, imgHeight - bgH - 1f));
        RectF bg = new RectF(left, top, left + bgW, top + bgH);

        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(0xFAFFFFFF);
        float radius = Math.min(bg.width(), bg.height()) * 0.18f;
        canvas.drawRoundRect(bg, radius, radius, bgPaint);

        bgPaint.setStyle(Paint.Style.STROKE);
        bgPaint.setStrokeWidth(Math.max(1f, box.width() * 0.006f));
        bgPaint.setColor(0x66000000);
        canvas.drawRoundRect(bg, radius, radius, bgPaint);

        // 文字在扩展后的背景里居中，不再用 clipRect 把溢出部分切掉
        float tx = bg.left + (bg.width() - layout.getWidth()) / 2f;
        float ty = bg.top + (bg.height() - layout.getHeight()) / 2f;

        canvas.save();
        canvas.clipRect(0, 0, imgWidth, imgHeight);
        canvas.translate(tx, ty);
        layout.draw(canvas);
        canvas.restore();
    }

    private static long areaOf(@NonNull Rect r) {
        return (long) Math.max(0, r.width()) * Math.max(0, r.height());
    }

    /**
     * 二分找最大可放下的字号，让中文尽量填满原文区域。
     */
    private static int fitTextSize(@NonNull TextPaint tp, @NonNull String text, int width, int maxHeight) {
        int low = 6;
        int high = Math.max(12, Math.min(maxHeight, width));
        int best = low;
        for (int i = 0; i < 9 && low <= high; i++) {
            int mid = (low + high) / 2;
            tp.setTextSize(mid);
            StaticLayout l = buildLayout(text, tp, width);
            if (l.getHeight() <= maxHeight) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return best;
    }

    @SuppressWarnings("deprecation")
    private static StaticLayout buildLayout(@NonNull String text, @NonNull TextPaint tp, int width) {
        return new StaticLayout(text, tp, Math.max(width, 8),
                Layout.Alignment.ALIGN_CENTER, 1.0f, 0f, false);
    }

    /**
     * 如果该页已烧录，返回解码后的图片；否则返回 null。
     * 必须在后台线程调用。
     */
    @Nullable
    public static Image decode(@NonNull Context context, @NonNull String key, int index) {
        File f = find(context, key, index);
        if (!f.exists() || f.length() <= 0) {
            return null;
        }
        try (FileInputStream is = new FileInputStream(f)) {
            Image image = Image.decode(is, false);
            if (image != null) {
                BAKED_IMAGES.put(image, Boolean.TRUE);
            }
            return image;
        } catch (Throwable t) {
            Log.w(TAG, "decode baked image failed: " + t.getMessage());
            return null;
        }
    }

    public static void clear(@NonNull Context context, @NonNull String key) {
        deleteRecursively(new File(root(context), key));
        // 旧版本留下的同 key 目录也要一起清，否则会和新图混在一起
        deleteRecursively(new File(legacyRoot(context), key));
    }

    private static void deleteRecursively(@Nullable File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete()) {
            Log.w(TAG, "Failed to delete " + file);
        }
    }
}
