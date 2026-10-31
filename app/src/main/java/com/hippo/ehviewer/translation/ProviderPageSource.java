package com.hippo.ehviewer.translation;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.hippo.ehviewer.gallery.GalleryProvider2;
import com.hippo.unifile.UniFile;

import java.io.File;

/**
 * 阅读器内使用的页面来源：直接复用当前 GalleryProvider。
 *
 * <p>只在页面已经落到本地（下载完成）时才返回成功；未下载的页面先 request，
 * 单次调用内最多等待 20 秒，失败后由上层 {@link BatchMangaTranslator} 持续重试。
 */
public class ProviderPageSource implements BatchMangaTranslator.PageSource {

    private static final String TAG = "ProviderPageSource";
    private static final int POLL_INTERVAL_MS = 500;
    private static final int MAX_POLL_COUNT = 40; // 单次尝试最多等 20 秒

    private final GalleryProvider2 mProvider;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    public ProviderPageSource(@NonNull GalleryProvider2 provider) {
        mProvider = provider;
    }

    @Override
    public int getPageCount() {
        int size = mProvider.size();
        return size > 0 ? size : 0;
    }

    @Override
    public boolean fetchPage(int index, @NonNull File outFile, @NonNull BatchMangaTranslator.CancelChecker checker) {
        try {
            if (outFile.exists() && !outFile.delete()) {
                Log.w(TAG, "Failed to delete stale temp file");
            }
            // 已经下载过的页面直接落盘，这是"只翻译已下载漫画"的主路径
            if (mProvider.save(index, UniFile.fromFile(outFile))) {
                return outFile.exists() && outFile.length() > 0;
            }

            // 让 Provider 去请求（下载），请求动作放主线程更安全
            mMainHandler.post(() -> {
                try {
                    mProvider.request(index);
                } catch (Throwable t) {
                    Log.w(TAG, "request page failed: " + t.getMessage());
                }
            });

            for (int i = 0; i < MAX_POLL_COUNT; i++) {
                if (checker.isCancelled()) {
                    return false;
                }
                sleep(POLL_INTERVAL_MS);
                if (mProvider.save(index, UniFile.fromFile(outFile))) {
                    return outFile.exists() && outFile.length() > 0;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "fetchPage " + index + " failed: " + t.getMessage());
        }
        return false;
    }

    @Override
    public void release() {
        // Provider 由 Activity 管理生命周期，这里不做处理
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
