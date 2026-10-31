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
        Bitmap out;
        try {
            out = source.copy(Bitmap.Config.ARGB_8888, true);
        } catch (Throwable t) {
            Log.w(TAG, "copy bitmap failed: " + t.getMessage());
            return false;
        }
        if (out == null) {
            return false;
        }
        try {
            Canvas canvas = new Canvas(out);
            for (MangaTextBlock block : blocks) {
                String text = block.translatedText == null ? "" : block.translatedText.trim();
                if (text.isEmpty()) {
                    continue;
                }
                drawBlock(canvas, block.boundingBox, text);
            }
            File dest = file(context, key, index);
            File tmp = new File(dest.getAbsolutePath() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
                fos.flush();
            }
            if (dest.exists() && !dest.delete()) {
                Log.w(TAG, "Failed to delete old baked image");
            }
            return tmp.renameTo(dest);
        } catch (Throwable t) {
            Log.w(TAG, "bake failed: " + t.getMessage());
            return false;
        } finally {
            out.recycle();
        }
    }

    private static void drawBlock(@NonNull Canvas canvas, @NonNull Rect box, @NonNull String text) {
        if (box.width() <= 2 || box.height() <= 2) {
            return;
        }
        // 略微外扩，盖住原文边缘
        RectF bg = new RectF(box);
        float padX = Math.max(2f, box.width() * 0.04f);
        float padY = Math.max(2f, box.height() * 0.04f);
        bg.inset(-padX, -padY);

        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(0xF7FFFFFF);
        float radius = Math.min(bg.width(), bg.height()) * 0.18f;
        canvas.drawRoundRect(bg, radius, radius, bgPaint);

        bgPaint.setStyle(Paint.Style.STROKE);
        bgPaint.setStrokeWidth(Math.max(1f, box.width() * 0.006f));
        bgPaint.setColor(0x66000000);
        canvas.drawRoundRect(bg, radius, radius, bgPaint);

        int width = Math.max((int) (box.width() - 2f), 8);
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(Color.BLACK);
        tp.setTypeface(Typeface.DEFAULT_BOLD);
        tp.setTextAlign(Paint.Align.LEFT);

        int textSize = fitTextSize(tp, text, width, box.height());
        tp.setTextSize(textSize);
        StaticLayout layout = buildLayout(text, tp, width);
        float tx = box.left;
        // 垂直居中
        float ty = box.top + Math.max(0f, (box.height() - layout.getHeight()) / 2f);

        canvas.save();
        canvas.clipRect(bg);
        canvas.translate(tx, ty);
        layout.draw(canvas);
        canvas.restore();
    }

    /**
     * 二分找最大可放下的字号，让中文尽量填满原文区域。
     */
    private static int fitTextSize(@NonNull TextPaint tp, @NonNull String text, int width, int maxHeight) {
        int low = 8;
        int high = Math.max(12, Math.min(maxHeight, width));
        int best = low;
        for (int i = 0; i < 8 && low <= high; i++) {
            int mid = (low + high) / 2;
            tp.setTextSize(mid);
            StaticLayout l = buildLayout(text, tp, width);
            if (l.getHeight() <= maxHeight && l.getLineCount() * mid <= maxHeight * 1.6f) {
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
