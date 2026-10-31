package com.hippo.ehviewer.translation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.unifile.UniFile;

import java.io.File;

/**
 * 画廊详情页使用的页面来源：直接驱动 SpiderQueen 下载整本所需页面。
 * 这样用户可以在"开始阅读"之前就把整本翻译跑完。
 */
public class SpiderQueenPageSource implements BatchMangaTranslator.PageSource {

    private static final String TAG = "SpiderQueenPageSource";
    private static final int POLL_INTERVAL_MS = 500;
    private static final int MAX_POLL_COUNT = 40; // 单次尝试最多等 20 秒

    private final SpiderQueen mQueen;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private SpiderQueenPageSource(@NonNull SpiderQueen queen) {
        mQueen = queen;
    }

    @UiThread
    public static SpiderQueenPageSource create(@NonNull Context context, @NonNull GalleryInfo galleryInfo) {
        SpiderQueen queen = SpiderQueen.obtainSpiderQueen(context, galleryInfo, SpiderQueen.MODE_READ);
        return new SpiderQueenPageSource(queen);
    }

    @Override
    public int getPageCount() {
        int size = mQueen.size();
        return size > 0 ? size : 0;
    }

    @Override
    public boolean fetchPage(int index, @NonNull File outFile,
                             @NonNull BatchMangaTranslator.CancelChecker checker) {
        try {
            if (outFile.exists() && !outFile.delete()) {
                Log.w(TAG, "Failed to delete stale temp file");
            }
            // 已下载到本地的页直接取用
            if (mQueen.save(index, UniFile.fromFile(outFile))) {
                return outFile.exists() && outFile.length() > 0;
            }
            mQueen.request(index);

            for (int i = 0; i < MAX_POLL_COUNT; i++) {
                if (checker.isCancelled()) {
                    return false;
                }
                sleep(POLL_INTERVAL_MS);
                if (mQueen.save(index, UniFile.fromFile(outFile))) {
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
        mMainHandler.post(() -> {
            try {
                SpiderQueen.releaseSpiderQueen(mQueen, SpiderQueen.MODE_READ);
            } catch (Throwable t) {
                Log.w(TAG, "release queen failed: " + t.getMessage());
            }
        });
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
