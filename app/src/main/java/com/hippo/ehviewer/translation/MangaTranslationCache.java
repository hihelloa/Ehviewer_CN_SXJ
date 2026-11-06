package com.hippo.ehviewer.translation;

import android.content.Context;
import android.graphics.Rect;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 整本漫画翻译结果的本地持久化缓存。
 *
 * <p>每个画廊一个 JSON 文件，存放在应用私有目录 {@code files/translation_cache/{key}.json}：
 * <pre>
 * {
 *   "version": 1,
 *   "key": "1234567",
 *   "pages": {
 *     "0": [{"o":"原文","t":"译文","b":[l,t,r,b],"w":1200,"h":1800}],
 *     "1": [...]
 *   }
 * }
 * </pre>
 * 使用 files 目录而不是 cache 目录，避免系统清理缓存导致整本翻译成果丢失。
 */
public class MangaTranslationCache {

    private static final String TAG = "MangaTranslationCache";
    private static final String DIR_NAME = "translation_cache";
    /**
     * 缓存结构/识别质量版本。
     *
     * <p>v1 用整页 1800px 缩略图做 OCR，小字大量漏识别，缓存结果本身就不完整；
     * v2 改为 2600px + 长页切片识别。版本不一致的旧缓存要在整本翻译时重建，
     * 否则"翻译不全"会被永久固化。
     */
    private static final int VERSION = 2;

    private static volatile MangaTranslationCache sInstance;

    private final Context mContext;
    private final Map<String, GalleryCache> mCaches = new ConcurrentHashMap<>();

    private MangaTranslationCache(Context context) {
        mContext = context.getApplicationContext();
    }

    public static MangaTranslationCache get(@NonNull Context context) {
        MangaTranslationCache instance = sInstance;
        if (instance == null) {
            synchronized (MangaTranslationCache.class) {
                instance = sInstance;
                if (instance == null) {
                    instance = new MangaTranslationCache(context);
                    sInstance = instance;
                }
            }
        }
        return instance;
    }

    /**
     * 生成画廊的缓存 key。网络画廊用 gid，本地画廊退化为路径哈希。
     */
    @NonNull
    public static String keyForGid(long gid) {
        return String.valueOf(gid);
    }

    @NonNull
    public static String keyForLocal(@NonNull String identity) {
        return "local_" + Integer.toHexString(identity.hashCode());
    }

    @NonNull
    public List<MangaTextBlock> getPage(@NonNull String key, int pageIndex) {
        GalleryCache cache = obtain(key);
        synchronized (cache) {
            List<MangaTextBlock> blocks = cache.pages.get(pageIndex);
            if (blocks == null || blocks.isEmpty()) {
                return Collections.emptyList();
            }
            // 必须深拷贝：覆盖层会修改 visible 等 UI 状态，
            // 若直接返回缓存里的对象，用户点掉的气泡会被永久写回缓存。
            List<MangaTextBlock> copy = new ArrayList<>(blocks.size());
            for (MangaTextBlock block : blocks) {
                MangaTextBlock item = new MangaTextBlock(block.originalText,
                        new Rect(block.boundingBox), block.imageWidth, block.imageHeight);
                // 旧版本会把模型原样返回的日文当成译文写进缓存，
                // 读出来先过滤掉，否则会被当成中文烧到图上
                item.setTranslatedText(TranslationVerify.isUsable(block.originalText, block.translatedText)
                        ? block.translatedText : "");
                copy.add(item);
            }
            return copy;
        }
    }

    public boolean hasPage(@NonNull String key, int pageIndex) {
        GalleryCache cache = obtain(key);
        synchronized (cache) {
            List<MangaTextBlock> blocks = cache.pages.get(pageIndex);
            return blocks != null && !blocks.isEmpty();
        }
    }

    /**
     * 这一页缓存里是否存在"译文其实还是日文"的脏数据。
     *
     * <p>只由旧版本写入：当时没有校验，模型原样返回的日文被当成译文存了下来，
     * 还被烧进了图片。检测到就必须重做这一页，光清缓存不够。
     */
    public boolean hasInvalidTranslation(@NonNull String key, int pageIndex) {
        GalleryCache cache = obtain(key);
        synchronized (cache) {
            List<MangaTextBlock> blocks = cache.pages.get(pageIndex);
            if (blocks == null || blocks.isEmpty()) {
                return false;
            }
            for (MangaTextBlock block : blocks) {
                if (block.originalText == null || block.originalText.trim().isEmpty()) {
                    continue;
                }
                String translated = block.translatedText;
                // 空译文不算脏数据：那可能是模型确实译不出的拟声词，重做也没用
                if (translated == null || translated.trim().isEmpty()) {
                    continue;
                }
                if (!TranslationVerify.isUsable(block.originalText, translated)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 已经翻译完成的页码数量（注意：空白页也会写入空列表，这里按"存在记录"计数）。
     */
    public int getCachedPageCount(@NonNull String key) {
        GalleryCache cache = obtain(key);
        synchronized (cache) {
            return cache.pages.size();
        }
    }

    public int getCachedBlockCount(@NonNull String key) {
        GalleryCache cache = obtain(key);
        synchronized (cache) {
            int count = 0;
            for (List<MangaTextBlock> blocks : cache.pages.values()) {
                count += blocks.size();
            }
            return count;
        }
    }

    public boolean isPageProcessed(@NonNull String key, int pageIndex) {
        GalleryCache cache = obtain(key);
        synchronized (cache) {
            return cache.pages.containsKey(pageIndex);
        }
    }

    /** 磁盘上这份缓存的版本号，0 表示没有缓存文件。 */
    public int getVersion(@NonNull String key) {
        GalleryCache cache = obtain(key);
        synchronized (cache) {
            return cache.version;
        }
    }

    public boolean isLegacy(@NonNull String key) {
        int version = getVersion(key);
        return version > 0 && version < VERSION;
    }

    public void putPage(@NonNull String key, int pageIndex, @NonNull List<MangaTextBlock> blocks) {
        GalleryCache cache = obtain(key);
        synchronized (cache) {
            cache.pages.put(pageIndex, new ArrayList<>(blocks));
            writeToDisk(key, cache);
        }
    }

    public void clear(@NonNull String key) {
        GalleryCache cache = obtain(key);
        synchronized (cache) {
            cache.pages.clear();
            cache.version = 0;
            File file = getCacheFile(key);
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Failed to delete " + file);
            }
        }
    }

    @NonNull
    public File getCacheFile(@NonNull String key) {
        File dir = new File(mContext.getFilesDir(), DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Failed to create " + dir);
        }
        return new File(dir, sanitize(key) + ".json");
    }

    @NonNull
    private GalleryCache obtain(@NonNull String key) {
        GalleryCache cache = mCaches.get(key);
        if (cache == null) {
            synchronized (mCaches) {
                cache = mCaches.get(key);
                if (cache == null) {
                    cache = new GalleryCache();
                    readFromDisk(key, cache);
                    mCaches.put(key, cache);
                }
            }
        }
        return cache;
    }

    private void readFromDisk(@NonNull String key, @NonNull GalleryCache cache) {
        Map<Integer, List<MangaTextBlock>> pages = new LinkedHashMap<>();
        File file = getCacheFile(key);
        if (!file.exists() || file.length() <= 0) {
            cache.pages = pages;
            cache.version = 0;
            return;
        }
        String content = readFile(file);
        if (content == null || content.isEmpty()) {
            cache.pages = pages;
            return;
        }
        try {
            JSONObject root = new JSONObject(content);
            cache.version = root.optInt("version", 0);
            JSONObject pagesJson = root.optJSONObject("pages");
            if (pagesJson == null) {
                cache.pages = pages;
                return;
            }
            for (java.util.Iterator<String> it = pagesJson.keys(); it.hasNext(); ) {
                String pageKey = it.next();
                int pageIndex;
                try {
                    pageIndex = Integer.parseInt(pageKey);
                } catch (NumberFormatException e) {
                    continue;
                }
                JSONArray array = pagesJson.optJSONArray(pageKey);
                pages.put(pageIndex, parseBlocks(array));
            }
        } catch (Throwable t) {
            Log.e(TAG, "Corrupted cache for " + key + ", ignored: " + t.getMessage());
            pages.clear();
        }
        cache.pages = pages;
    }

    @NonNull
    private List<MangaTextBlock> parseBlocks(@Nullable JSONArray array) {
        List<MangaTextBlock> blocks = new ArrayList<>();
        if (array == null) {
            return blocks;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String original = item.optString("o", "");
            if (original.trim().isEmpty()) {
                continue;
            }
            String translated = item.optString("t", "");
            JSONArray box = item.optJSONArray("b");
            int left = 0, top = 0, right = 0, bottom = 0;
            if (box != null && box.length() >= 4) {
                left = box.optInt(0);
                top = box.optInt(1);
                right = box.optInt(2);
                bottom = box.optInt(3);
            }
            int width = item.optInt("w", 0);
            int height = item.optInt("h", 0);
            MangaTextBlock block = new MangaTextBlock(original, new Rect(left, top, right, bottom), width, height);
            block.setTranslatedText(translated);
            blocks.add(block);
        }
        return blocks;
    }

    private void writeToDisk(@NonNull String key, @NonNull GalleryCache cache) {
        JSONObject root = new JSONObject();
        JSONObject pagesJson = new JSONObject();
        // 注意：不能无脑写 VERSION。旧版缓存（v1）如果被单页翻译写一次盘就"升级"成 v2，
        // 整本翻译的 isLegacy 判定会失效，其余页的漏字就会被永久固化。
        // 只有整本重建（clear 后 version=0）或新画廊才写当前版本号。
        int writeVersion = cache.version > 0 ? cache.version : VERSION;
        try {
            root.put("version", writeVersion);
            root.put("key", key);
            for (Map.Entry<Integer, List<MangaTextBlock>> entry : cache.pages.entrySet()) {
                JSONArray array = new JSONArray();
                for (MangaTextBlock block : entry.getValue()) {
                    if (block.originalText.trim().isEmpty()) {
                        continue;
                    }
                    JSONObject item = new JSONObject();
                    item.put("o", block.originalText);
                    item.put("t", block.translatedText);
                    JSONArray box = new JSONArray();
                    box.put(block.boundingBox.left);
                    box.put(block.boundingBox.top);
                    box.put(block.boundingBox.right);
                    box.put(block.boundingBox.bottom);
                    item.put("b", box);
                    item.put("w", block.imageWidth);
                    item.put("h", block.imageHeight);
                    array.put(item);
                }
                pagesJson.put(String.valueOf(entry.getKey()), array);
            }
            root.put("pages", pagesJson);
        } catch (Throwable t) {
            Log.e(TAG, "Build json failed: " + t.getMessage());
            return;
        }

        File file = getCacheFile(key);
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        FileOutputStream os = null;
        try {
            os = new FileOutputStream(tmp);
            os.write(root.toString().getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
            os = null;
            // 先直接 rename（Linux 上是原子替换）。原来的"先删旧文件再 rename"
            // 在两步之间被杀进程会连旧缓存一起丢，整本翻译成果归零。
            boolean renamed = tmp.renameTo(file);
            if (!renamed) {
                if (file.exists() && !file.delete()) {
                    Log.w(TAG, "Failed to delete old cache file");
                }
                renamed = tmp.renameTo(file);
            }
            if (renamed) {
                cache.version = writeVersion;
            } else {
                Log.w(TAG, "Failed to rename cache file");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Write cache failed: " + t.getMessage());
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    @Nullable
    private String readFile(@NonNull File file) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            byte[] data = new byte[(int) raf.length()];
            raf.readFully(data);
            return new String(data, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Log.e(TAG, "Read cache failed: " + t.getMessage());
            return null;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private String sanitize(String key) {
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    private static class GalleryCache {
        Map<Integer, List<MangaTextBlock>> pages = new LinkedHashMap<>();
        /** 磁盘文件的版本号，0 表示无文件或刚被清空。 */
        int version;
    }
}
