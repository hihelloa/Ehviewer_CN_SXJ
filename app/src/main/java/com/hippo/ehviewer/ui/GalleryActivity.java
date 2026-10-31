/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.ui;

import static com.hippo.ehviewer.ui.scene.download.DownloadsScene.LOCAL_GALLERY_INFO_CHANGE;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.widget.Toast;
import com.hippo.ehviewer.translation.BakedTranslationStore;
import com.hippo.ehviewer.translation.BatchMangaTranslator;
import com.hippo.ehviewer.translation.GalleryTranslationOverlay;
import com.hippo.ehviewer.translation.GoogleTranslator;
import com.hippo.ehviewer.translation.MangaOcrManager;
import com.hippo.ehviewer.translation.MangaTextBlock;
import com.hippo.ehviewer.translation.MangaTranslationCache;
import com.hippo.ehviewer.translation.ProviderPageSource;
import com.hippo.ehviewer.translation.SpiderQueenPageSource;
import com.hippo.ehviewer.translation.TranslationProgressDialog;
import com.hippo.util.IoThreadPoolExecutor;
import java.io.File;
import java.util.List;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.hippo.android.resource.AttrResources;
import com.hippo.ehviewer.AppConfig;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.event.GalleryActivityEvent;
import com.hippo.ehviewer.gallery.ArchiveGalleryProvider;
import com.hippo.ehviewer.gallery.DirGalleryProvider;
import com.hippo.ehviewer.gallery.EhGalleryProvider;
import com.hippo.ehviewer.gallery.GalleryProvider2;
import com.hippo.ehviewer.widget.GalleryGuideView;
import com.hippo.ehviewer.widget.GalleryHeader;
import com.hippo.ehviewer.widget.ReversibleSeekBar;
import com.hippo.lib.glgallery.GalleryProvider;
import com.hippo.lib.glgallery.GalleryView;
import com.hippo.lib.glgallery.SimpleAdapter;
import com.hippo.lib.glview.view.GLRootView;
import com.hippo.unifile.UniFile;
import com.hippo.ehviewer.util.ReadingRefreshRate;
import com.hippo.util.ExceptionUtils;
import com.hippo.util.SystemUiHelper;
import com.hippo.widget.ColorView;
import com.hippo.lib.yorozuya.AnimationUtils;
import com.hippo.lib.yorozuya.ConcurrentPool;
import com.hippo.lib.yorozuya.IOUtils;
import com.hippo.lib.yorozuya.MathUtils;
import com.hippo.lib.yorozuya.ResourcesUtils;
import com.hippo.lib.yorozuya.SimpleAnimatorListener;
import com.hippo.lib.yorozuya.SimpleHandler;
import com.hippo.lib.yorozuya.ViewUtils;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;

public class GalleryActivity extends EhActivity implements SeekBar.OnSeekBarChangeListener, GalleryView.Listener {

    public static final String ACTION_DIR = "dir";
    public static final String ACTION_EH = "eh";

    public static final String KEY_ACTION = "action";
    public static final String KEY_FILENAME = "filename";
    public static final String KEY_URI = "uri";
    public static final String KEY_GALLERY_INFO = "gallery_info";
    public static final String DATA_IN_EVENT = "data_in_event";
    public static final String KEY_PAGE = "page";
    public static final String KEY_CURRENT_INDEX = "current_index";

    private static final long SLIDER_ANIMATION_DURING = 150;
    private static final long HIDE_SLIDER_DELAY = 3000;

    private static final int WRITE_REQUEST_CODE = 43;

    private String mAction;
    private String mFilename;
    private Uri mUri;
    private GalleryInfo mGalleryInfo;
    private int mPage;
    private String mCacheFileName;

    @Nullable
    private GLRootView mGLRootView;
    @Nullable
    private GalleryView mGalleryView;
    @Nullable
    private GalleryProvider2 mGalleryProvider;
    @Nullable
    private GalleryAdapter mGalleryAdapter;

    @Nullable
    private SystemUiHelper mSystemUiHelper;
    private boolean mShowSystemUi;

    @Nullable
    private ColorView mMaskView;
    @Nullable
    private View mClock;
    @Nullable
    private TextView mProgress;
    @Nullable
    private View mBattery;
    @Nullable
    private View mSeekBarPanel;
    @Nullable
    private ImageView mAutoTransferPanel;
    @Nullable
    private View mBtnTranslate;
    @Nullable
    private GalleryTranslationOverlay mTranslationOverlay;
    @Nullable
    private TextView mLeftText;
    @Nullable
    private TextView mRightText;
    @Nullable
    private ReversibleSeekBar mSeekBar;

    private ObjectAnimator mSeekBarPanelAnimator;
    private ObjectAnimator mAutoTransferAnimator;
    private ObjectAnimator mBtnTranslateAnimator;

    private int mLayoutMode;
    private int mSize;
    private int mCurrentIndex;

    @Nullable
    private String mTranslationKey;
    private final Handler mOverlayHandler = new Handler(Looper.getMainLooper());
    private boolean mOverlaySyncScheduled;
    private boolean mOverlaySyncPaused;

    /** 翻页/批量翻译完成后，自动贴出已缓存的译文气泡 */
    private final Runnable mOverlaySyncRunnable = new Runnable() {
        @Override
        public void run() {
            mOverlaySyncScheduled = false;
            if (mOverlaySyncPaused || mTranslationOverlay == null || !mTranslationOverlay.hasData()) {
                return;
            }
            RectF rect = queryPageRect(mTranslationOverlay.getCurrentPageIndex());
            if (rect != null && !rect.isEmpty()) {
                mTranslationOverlay.updateDisplayedRect(rect);
            }
            mOverlaySyncScheduled = true;
            mOverlayHandler.postDelayed(this, 700);
        }
    };

    private final BatchMangaTranslator.Listener mBatchListener = new BatchMangaTranslator.Listener() {
        @Override
        public void onPageTranslated(@NonNull String key, int pageIndex, @NonNull List<MangaTextBlock> blocks) {
            if (mTranslationKey == null || !mTranslationKey.equals(key)) {
                return;
            }
            if (pageIndex == mCurrentIndex) {
                if (Settings.getBakedTranslation()) {
                    refreshPageImage(pageIndex);
                } else if (Settings.getAutoShowTranslation()) {
                    showCachedTranslation(pageIndex);
                }
            }
        }

        @Override
        public void onBatchFinished(@NonNull String key, int finished, int total, int failed, boolean cancelled) {
            if (mTranslationKey == null || !mTranslationKey.equals(key)) {
                return;
            }
            // 失败页给个明确提示：以前跑完什么都不说，
            // 用户只会觉得"翻译没生效"，分不清是没跑、跑挂了还是这页没字。
            if (!cancelled && failed > 0) {
                Toast.makeText(GalleryActivity.this,
                        getString(R.string.translate_finished, Math.max(0, finished - failed), failed),
                        Toast.LENGTH_LONG).show();
            }
            if (mCurrentIndex >= 0) {
                if (Settings.getBakedTranslation()) {
                    refreshPageImage(mCurrentIndex);
                } else {
                    showCachedTranslation(mCurrentIndex);
                }
            }
        }
    };

    private boolean canFinish = false;
    private boolean autoTransferring = false;

    private final ConcurrentPool<NotifyTask> mNotifyTaskPool = new ConcurrentPool<>(3);

    private ScheduledExecutorService transferService = Executors.newSingleThreadScheduledExecutor();
    private final Handler transHandle = new Handler(Looper.getMainLooper());

    private final ValueAnimator.AnimatorUpdateListener mUpdateSliderListener = new ValueAnimator.AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            if (null != mSeekBarPanel) {
                mSeekBarPanel.requestLayout();
            }
            if (null != mAutoTransferPanel) {
                mAutoTransferPanel.requestLayout();
            }
            if (null != mBtnTranslate) {
                mBtnTranslate.requestLayout();
            }
        }
    };

    private final SimpleAnimatorListener mShowSliderListener = new SimpleAnimatorListener() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mSeekBarPanelAnimator = null;
            mAutoTransferAnimator = null;
            mBtnTranslateAnimator = null;
        }
    };

    private final SimpleAnimatorListener mHideSliderListener = new SimpleAnimatorListener() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mSeekBarPanelAnimator = null;
            if (mSeekBarPanel != null) {
                mSeekBarPanel.setVisibility(View.INVISIBLE);
            }
            mAutoTransferAnimator = null;
            if (mAutoTransferPanel != null) {
                mAutoTransferPanel.setVisibility(View.INVISIBLE);
            }
        }
    };

    private final Runnable mHideSliderRunnable = new Runnable() {
        @Override
        public void run() {
            if (mSeekBarPanel != null) {
                hideSlider(mSeekBarPanel, mSeekBarPanelAnimator);
                hideSlider(mAutoTransferPanel, mAutoTransferAnimator);
            }
        }
    };

    @Override
    protected int getThemeResId(int theme) {
        switch (theme) {
            case Settings.THEME_LIGHT:
            default:
                return R.style.AppTheme_Gallery;
            case Settings.THEME_DARK:
                return R.style.AppTheme_Gallery_Dark;
            case Settings.THEME_BLACK:
                return R.style.AppTheme_Gallery_Black;
        }
    }

    private void buildProvider() {
        if (mGalleryProvider != null) {
            return;
        }

        if (ACTION_DIR.equals(mAction)) {
            if (mFilename != null) {
                mGalleryProvider = new DirGalleryProvider(UniFile.fromFile(new File(mFilename)));
            }
        } else if (ACTION_EH.equals(mAction)) {
            if (mGalleryInfo != null) {
                mGalleryProvider = new EhGalleryProvider(this, mGalleryInfo);
            }
        } else if (Intent.ACTION_VIEW.equals(mAction)) {
            if (mUri != null) {
                // Only support zip now
                mGalleryProvider = new ArchiveGalleryProvider(this, mUri);
            }
        }
    }

    /**
     * eventbus 通知，用于修复跳转奔溃的问题
     *
     * @param event 通知数据对象
     */
    @Subscribe(threadMode = ThreadMode.MAIN, sticky = true)
    public void onGalleryActivityEvent(GalleryActivityEvent event) {
        if (mGalleryProvider != null) {
            return;
        }
        mGalleryInfo = event.galleryInfo;
        mPage = event.pagePosition;
        buildProvider();
        onCreateView(null);
    }

    private void onInit() {
        Intent intent = getIntent();
        if (intent == null) {
            canFinish = true;
            return;
        }

        mAction = intent.getAction();
        mFilename = intent.getStringExtra(KEY_FILENAME);
        mUri = intent.getData();
        mGalleryInfo = intent.getParcelableExtra(KEY_GALLERY_INFO);
        boolean onEvent = intent.getBooleanExtra(DATA_IN_EVENT, false);
        if (!onEvent) {
            canFinish = true;
        }
        mPage = intent.getIntExtra(KEY_PAGE, -1);
        buildProvider();
    }

    private void onRestore(@NonNull Bundle savedInstanceState) {
        mAction = savedInstanceState.getString(KEY_ACTION);
        mFilename = savedInstanceState.getString(KEY_FILENAME);
        mUri = savedInstanceState.getParcelable(KEY_URI);
        mGalleryInfo = savedInstanceState.getParcelable(KEY_GALLERY_INFO);
        mPage = savedInstanceState.getInt(KEY_PAGE, -1);
        mCurrentIndex = savedInstanceState.getInt(KEY_CURRENT_INDEX);
        buildProvider();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(KEY_ACTION, mAction);
        outState.putString(KEY_FILENAME, mFilename);
        outState.putParcelable(KEY_URI, mUri);
        if (mGalleryInfo != null) {
            outState.putParcelable(KEY_GALLERY_INFO, mGalleryInfo);
        }
        outState.putInt(KEY_PAGE, mPage);
        outState.putInt(KEY_CURRENT_INDEX, mCurrentIndex);
    }

    @Override
    @SuppressWarnings({"WrongConstant"})
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        if (Settings.getReadingFullscreen()) {
            Window w = getWindow();
            w.setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION, WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            w.setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS, WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        }
        super.onCreate(savedInstanceState);
        StrictMode.VmPolicy.Builder builder = new StrictMode.VmPolicy.Builder();
        StrictMode.setVmPolicy(builder.build());
        builder.detectFileUriExposure();

        if (savedInstanceState == null) {
            onInit();
        } else {
            onRestore(savedInstanceState);
        }
        onCreateView(savedInstanceState);
        //注册事件
        EventBus.getDefault().register(this);
    }

    private void onCreateView(@Nullable Bundle savedInstanceState) {
        if (mGalleryProvider == null) {
            if (!canFinish) {
                return;
            }
            finish();
            return;
        }
        mGalleryProvider.start();

        // Get start page
        int startPage;
        if (savedInstanceState == null) {
            startPage = mPage >= 0 ? mPage : mGalleryProvider.getStartPage();
        } else {
            startPage = mCurrentIndex;
        }

        if (!isEglAvailable()) {
            mGalleryProvider.stop();
            showGlFallbackView();
            return;
        }

        setContentView(R.layout.activity_gallery);
        mGLRootView = (GLRootView) ViewUtils.$$(this, R.id.gl_root_view);
        mGalleryAdapter = new GalleryAdapter(mGLRootView, mGalleryProvider);
        Resources resources = getResources();
        mGalleryView = new GalleryView.Builder(this, mGalleryAdapter).setListener(this).setLayoutMode(Settings.getReadingDirection()).setScaleMode(Settings.getPageScaling()).setStartPosition(Settings.getStartPosition()).setStartPage(startPage).setBackgroundColor(AttrResources.getAttrColor(this, android.R.attr.colorBackground)).setEdgeColor(AttrResources.getAttrColor(this, R.attr.colorEdgeEffect) & 0xffffff | 0x33000000).setPagerInterval(Settings.getShowPageInterval() ? resources.getDimensionPixelOffset(R.dimen.gallery_pager_interval) : 0).setScrollInterval(Settings.getShowPageInterval() ? resources.getDimensionPixelOffset(R.dimen.gallery_scroll_interval) : 0).setPageMinHeight(resources.getDimensionPixelOffset(R.dimen.gallery_page_min_height)).setPageInfoInterval(resources.getDimensionPixelOffset(R.dimen.gallery_page_info_interval)).setProgressColor(ResourcesUtils.getAttrColor(this, androidx.appcompat.R.attr.colorPrimary)).setProgressSize(resources.getDimensionPixelOffset(R.dimen.gallery_progress_size)).setPageTextColor(AttrResources.getAttrColor(this, android.R.attr.textColorSecondary)).setPageTextSize(resources.getDimensionPixelOffset(R.dimen.gallery_page_text_size)).setPageTextTypeface(Typeface.DEFAULT).setErrorTextColor(resources.getColor(R.color.red_500, null)).setErrorTextSize(resources.getDimensionPixelOffset(R.dimen.gallery_error_text_size)).setDefaultErrorString(resources.getString(R.string.error_unknown)).setEmptyString(resources.getString(R.string.error_empty)).build();
        mGLRootView.setContentPane(mGalleryView);
        mGLRootView.setOnGenericMotionListener(this::onGenericMotion);
        mGalleryProvider.setListener(mGalleryAdapter);
        mGalleryProvider.setGLRoot(mGLRootView);

        // System UI helper
        if (Settings.getReadingFullscreen()) {
            Window w = getWindow();
            w.setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION, WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            w.setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS, WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            mSystemUiHelper = new SystemUiHelper(this, SystemUiHelper.LEVEL_IMMERSIVE, SystemUiHelper.FLAG_LAYOUT_IN_SCREEN_OLDER_DEVICES | SystemUiHelper.FLAG_IMMERSIVE_STICKY);
            mSystemUiHelper.hide();
            mShowSystemUi = false;
        }

        mMaskView = (ColorView) ViewUtils.$$(this, R.id.mask);
        mClock = ViewUtils.$$(this, R.id.clock);
        mProgress = (TextView) ViewUtils.$$(this, R.id.progress);
        mBattery = ViewUtils.$$(this, R.id.battery);
        mClock.setVisibility(Settings.getShowClock() ? View.VISIBLE : View.GONE);
        mProgress.setVisibility(Settings.getShowProgress() ? View.VISIBLE : View.GONE);
        mBattery.setVisibility(Settings.getShowBattery() ? View.VISIBLE : View.GONE);

        mSeekBarPanel = ViewUtils.$$(this, R.id.seek_bar_panel);
        mAutoTransferPanel = (ImageView) ViewUtils.$$(this, R.id.auto_transfer);
        mLeftText = (TextView) ViewUtils.$$(mSeekBarPanel, R.id.left);
        mRightText = (TextView) ViewUtils.$$(mSeekBarPanel, R.id.right);
        mSeekBar = (ReversibleSeekBar) ViewUtils.$$(mSeekBarPanel, R.id.seek_bar);
        mSeekBar.setOnSeekBarChangeListener(this);
        mAutoTransferPanel.setOnClickListener(this::autoRead);

        mTranslationOverlay = findViewById(R.id.translation_overlay);
        mBtnTranslate = findViewById(R.id.btn_translate);
        if (mBtnTranslate != null) {
            // 常驻显示，不再跟随控制栏隐藏，避免"找不到翻译按钮"
            mBtnTranslate.setVisibility(View.VISIBLE);
            mBtnTranslate.setOnClickListener(v -> translateCurrentPage());
            mBtnTranslate.setOnLongClickListener(v -> {
                showTranslationQuickMenu();
                return true;
            });
        }

        mTranslationKey = buildTranslationKey();
        BatchMangaTranslator.getInstance().addListener(mBatchListener);

        mSize = mGalleryProvider.size();
        mCurrentIndex = startPage;
        // GL 首帧渲染完成后再贴气泡
        mOverlayHandler.postDelayed(() -> showCachedTranslation(mCurrentIndex), 800);
        if (mGalleryView != null) {
            mLayoutMode = mGalleryView.getLayoutMode();
        }
        updateSlider();

        // Update keep screen on
        if (Settings.getKeepScreenOn()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        applyReadingRefreshRate();

        // Orientation
        int orientation;
        switch (Settings.getScreenRotation()) {
            default:
            case 0:
                orientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
                break;
            case 1:
                orientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
                break;
            case 2:
                orientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
                break;
            case 3:
                orientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR;
                break;
        }
        setRequestedOrientation(orientation);

        // Screen lightness
        setScreenLightness(Settings.getCustomScreenLightness(), Settings.getScreenLightness());

        // Cutout
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

            GalleryHeader galleryHeader = findViewById(R.id.gallery_header);
            galleryHeader.setOnApplyWindowInsetsListener((v, insets) -> {
                galleryHeader.setDisplayCutout(insets.getDisplayCutout());
                return insets;
            });
        }

        if (Settings.getGuideGallery()) {
            FrameLayout mainLayout = (FrameLayout) ViewUtils.$$(this, R.id.main);
            mainLayout.addView(new GalleryGuideView(this));
        }
    }

    private boolean isEglAvailable() {
        EGL10 egl = (EGL10) EGLContext.getEGL();
        EGLDisplay display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
        if (display == null || display == EGL10.EGL_NO_DISPLAY) {
            return false;
        }

        int[] version = new int[2];
        if (!egl.eglInitialize(display, version)) {
            return false;
        }

        try {
            int[] numConfig = new int[1];
            return egl.eglChooseConfig(display, new int[]{EGL10.EGL_NONE}, null, 0, numConfig)
                    && numConfig[0] > 0;
        } catch (Throwable e) {
            return false;
        } finally {
            egl.eglTerminate(display);
        }
    }

    private void showGlFallbackView() {
        setContentView(R.layout.activity_gallery_fallback);
        View close = ViewUtils.$$(this, R.id.gl_fallback_close);
        close.setOnClickListener(v -> finish());
        Log.w("GalleryActivity", "EGL init failed, switch to non-GL fallback page");
        Toast.makeText(this, R.string.gallery_gl_fallback_toast, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        if (!transferService.isShutdown()) {
            transferService.shutdown();
            transferService = null;
        }
        mGLRootView = null;
        mGalleryView = null;
        if (mGalleryAdapter != null) {
            mGalleryAdapter.clearUploader();
            mGalleryAdapter = null;
        }
        if (mGalleryProvider != null) {
            mGalleryProvider.setListener(null);
            mGalleryProvider.stop();
            mGalleryProvider = null;
        }

        BatchMangaTranslator.getInstance().removeListener(mBatchListener);
        mOverlayHandler.removeCallbacksAndMessages(null);
        mOverlaySyncScheduled = false;

        mMaskView = null;
        mClock = null;
        mProgress = null;
        mBattery = null;
        mSeekBarPanel = null;
        mAutoTransferPanel = null;
        mBtnTranslate = null;
        mTranslationOverlay = null;
        mLeftText = null;
        mRightText = null;
        mSeekBar = null;

        if (transferService != null && !transferService.isShutdown()) {
            transferService.shutdown();
            transferService = null;
        }

        super.onDestroy();
        SimpleHandler.getInstance().removeCallbacks(mHideSliderRunnable);
        //销毁事件
        EventBus.getDefault().unregister(this);
    }

    @Override
    public void onBackPressed() {
        Intent intent = new Intent();
        intent.putExtra("info", mGalleryInfo);
        setResult(LOCAL_GALLERY_INFO_CHANGE, intent);
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();

        // GL 线程暂停时不要再抢渲染锁，避免卡死
        mOverlaySyncPaused = true;
        mOverlayHandler.removeCallbacks(mOverlaySyncRunnable);
        mOverlaySyncScheduled = false;

        if (mGLRootView != null) {
            mGLRootView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        applyReadingRefreshRate();
        if (mGLRootView != null) {
            mGLRootView.onResume();
        }
        mOverlaySyncPaused = false;
        if (mTranslationOverlay != null && mTranslationOverlay.hasData()) {
            startOverlaySync();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        SimpleHandler.getInstance().postDelayed(() -> {
            if (hasFocus && mSystemUiHelper != null) {
                if (mShowSystemUi) {
                    mSystemUiHelper.show();
                } else {
                    mSystemUiHelper.hide();
                }
            }
        }, 300);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mGalleryView == null) {
            return super.onKeyDown(keyCode, event);
        }
        boolean unReverse = !Settings.getReverseVolumePage();
        // Check volume
        if (Settings.getVolumePage()) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                if (mLayoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT && unReverse) {
                    mGalleryView.pageRight();
                } else {
                    mGalleryView.pageLeft();
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (mLayoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT && unReverse) {
                    mGalleryView.pageLeft();
                } else {
                    mGalleryView.pageRight();
                }
                return true;
            }
        }

        // Check keyboard and Dpad
        switch (keyCode) {
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_DPAD_UP:
                if (mLayoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT) {
                    mGalleryView.pageRight();
                } else {
                    mGalleryView.pageLeft();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                mGalleryView.pageLeft();
                return true;
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (mLayoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT) {
                    mGalleryView.pageLeft();
                } else {
                    mGalleryView.pageRight();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                mGalleryView.pageRight();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MENU:
                onTapMenuArea();
                return true;
        }

        return super.onKeyDown(keyCode, event);
    }


    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // Check volume
        if (Settings.getVolumePage()) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                return true;
            }
        }

        // Check keyboard and Dpad
        if (keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_MENU) {
            return true;
        }

        return super.onKeyUp(keyCode, event);
    }

//    private GalleryPageView findPageByIndex(int index) {
//        if (mGalleryView != null) {
//            return mGalleryView.findPageByIndex(index);
//        } else {
//            return null;
//        }
//    }

    private void autoRead(View view) {
        autoTransferring = !autoTransferring;
        if (mAutoTransferPanel == null) {
            return;
        }

        if (!autoTransferring) {
            mAutoTransferPanel.setImageResource(R.drawable.ic_start_play_24);
            transferService.shutdown();
        } else {
            mAutoTransferPanel.setImageResource(R.drawable.ic_pause_circle);
            if (transferService.isShutdown()) {
                transferService = Executors.newSingleThreadScheduledExecutor();
            }
            long initialDelay = Settings.getStartTransferTime();
            long waitTime = initialDelay * 2L;
            try {
                transferService.scheduleWithFixedDelay(() -> transHandle.post(() -> {
                    if (mGalleryView == null) {
                        return;
                    }
                    if (mLayoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT) {
                        mGalleryView.pageLeft();
                    } else {
                        mGalleryView.pageRight();
                    }
                }), initialDelay, waitTime, TimeUnit.SECONDS);
            } catch (IllegalArgumentException ignore) {

            }
        }
    }

    public boolean onGenericMotion(View view, MotionEvent motionEvent) {
        if (mGalleryView == null) {
            return false;
        }

        if (motionEvent.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            if (motionEvent.getAction() == MotionEvent.ACTION_SCROLL) {
                float scrollY = motionEvent.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (scrollY == 0) return false;  // wrong input
                if (mLayoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT) {
                    if (scrollY > 0) {
                        mGalleryView.pageLeft();
                    } else {
                        mGalleryView.pageRight();
                    }
                } else {
                    if (scrollY < 0) {
                        mGalleryView.pageLeft();
                    } else {
                        mGalleryView.pageRight();
                    }
                }
                return true;
            }
        }
        return false;
    }

    @SuppressLint("SetTextI18n")
    private void updateProgress() {
        if (mProgress == null) {
            return;
        }
        if (mSize <= 0 || mCurrentIndex < 0) {
            mProgress.setText(null);
        } else {
            mProgress.setText((mCurrentIndex + 1) + "/" + mSize);
        }
    }

    @SuppressLint("SetTextI18n")
    private void updateSlider() {
        if (mSeekBar == null || mRightText == null || mLeftText == null || mSize <= 0 || mCurrentIndex < 0) {
            return;
        }

        TextView start;
        TextView end;
        if (mLayoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT) {
            start = mRightText;
            end = mLeftText;
            mSeekBar.setReverse(true);
        } else {
            start = mLeftText;
            end = mRightText;
            mSeekBar.setReverse(false);
        }
        start.setText(Integer.toString(mCurrentIndex + 1));
        end.setText(Integer.toString(mSize));
        mSeekBar.setMax(mSize - 1);
        mSeekBar.setProgress(mCurrentIndex);
    }

    @Override
    @SuppressLint("SetTextI18n")
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        TextView start;
        if (mLayoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT) {
            start = mRightText;
        } else {
            start = mLeftText;
        }
        if (fromUser && null != start) {
            start.setText(Integer.toString(progress + 1));
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        SimpleHandler.getInstance().removeCallbacks(mHideSliderRunnable);
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        SimpleHandler.getInstance().postDelayed(mHideSliderRunnable, HIDE_SLIDER_DELAY);
        int progress = seekBar.getProgress();
        if (progress != mCurrentIndex && null != mGalleryView) {
            mGalleryView.setCurrentPage(progress);
        }
    }

    @Override
    public void onUpdateCurrentIndex(int index) {
        if (null != mGalleryProvider) {
            mGalleryProvider.putStartPage(index);
        }

        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setData(NotifyTask.KEY_CURRENT_INDEX, index);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onTapSliderArea() {
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setData(NotifyTask.KEY_TAP_SLIDER_AREA, 0);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onTapMenuArea() {
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setData(NotifyTask.KEY_TAP_MENU_AREA, 0);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onTapErrorText(int index) {
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setData(NotifyTask.KEY_TAP_ERROR_TEXT, index);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onLongPressPage(int index) {
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setData(NotifyTask.KEY_LONG_PRESS_PAGE, index);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onAutoTransferDone() {
        if (autoTransferring) {
            autoRead(mAutoTransferPanel);
        }
    }

//    @Override
//    public boolean onGenericMotionEvent(MotionEvent event) {
//        //The input source is a pointing device associated with a display.
//        //输入源为可显示的指针设备，如：mouse pointing device(鼠标指针),stylus pointing device(尖笔设备)
//        if (0 != (event.getSource() & InputDevice.SOURCE_CLASS_POINTER)) {
//            switch (event.getAction()) {
//                // process the scroll wheel movement...处理滚轮事件
//                case MotionEvent.ACTION_SCROLL:
//                    //获得垂直坐标上的滚动方向,也就是滚轮向下滚
//                    if (event.getAxisValue(MotionEvent.AXIS_VSCROLL) < 0.0f) {
//                        Log.i("fortest::onGenericMotionEvent", "down");
//                    }
//                    //获得垂直坐标上的滚动方向,也就是滚轮向上滚
//                    else {
//                        Log.i("fortest::onGenericMotionEvent", "up");
//                    }
//                    return true;
//            }
//        }
//        return super.onGenericMotionEvent(event);
//    }


    private void showSlider(View sliderPanel, ObjectAnimator animator) {
        if (null == sliderPanel) {
            return;
        }
        if (null != animator) {
            animator.cancel();
        }
        if (sliderPanel == mAutoTransferPanel || sliderPanel == mBtnTranslate) {
            sliderPanel.setTranslationX(sliderPanel.getWidth());
            animator = ObjectAnimator.ofFloat(sliderPanel, "translationX", 0.0f);
        } else {
            sliderPanel.setTranslationY(sliderPanel.getHeight());
            animator = ObjectAnimator.ofFloat(sliderPanel, "translationY", 0.0f);
        }

        sliderPanel.setVisibility(View.VISIBLE);

        animator.setDuration(SLIDER_ANIMATION_DURING);
        animator.setInterpolator(AnimationUtils.FAST_SLOW_INTERPOLATOR);
        animator.addUpdateListener(mUpdateSliderListener);
        animator.addListener(mShowSliderListener);
        animator.start();

        if (null != mSystemUiHelper) {
            mSystemUiHelper.show();
            mShowSystemUi = true;
        }
    }

    private void hideSlider(View sliderPanel, ObjectAnimator animator) {
        if (null == sliderPanel) {
            return;
        }
        if (null != animator) {
            animator.cancel();
        }
        if (sliderPanel == mAutoTransferPanel || sliderPanel == mBtnTranslate) {
            animator = ObjectAnimator.ofFloat(sliderPanel, "translationX", sliderPanel.getWidth());
        } else {
            animator = ObjectAnimator.ofFloat(sliderPanel, "translationY", sliderPanel.getHeight());
        }

        animator.setDuration(SLIDER_ANIMATION_DURING);
        animator.setInterpolator(AnimationUtils.SLOW_FAST_INTERPOLATOR);
        animator.addUpdateListener(mUpdateSliderListener);
        animator.addListener(mHideSliderListener);
        animator.start();

        if (null != mSystemUiHelper) {
            mSystemUiHelper.hide();
            mShowSystemUi = false;
        }
    }

    @NonNull
    private String buildTranslationKey() {
        if (mGalleryInfo != null && mGalleryInfo.gid > 0) {
            return MangaTranslationCache.keyForGid(mGalleryInfo.gid);
        }
        if (mFilename != null && !mFilename.isEmpty()) {
            return MangaTranslationCache.keyForLocal(mFilename);
        }
        if (mUri != null) {
            return MangaTranslationCache.keyForLocal(mUri.toString());
        }
        return MangaTranslationCache.keyForLocal("unknown");
    }

    /**
     * 在渲染锁保护下读取页面在屏幕上的实际绘制区域。
     * 直接在主线程裸调 findPageImageRect() 会读到未完成的布局，导致气泡整体错位。
     */
    @Nullable
    private RectF queryPageRect(int index) {
        GalleryView galleryView = mGalleryView;
        GLRootView rootView = mGLRootView;
        if (galleryView == null || rootView == null || index < 0) {
            return null;
        }
        rootView.lockRenderThread();
        try {
            return galleryView.findPageImageRect(index);
        } catch (Throwable t) {
            Log.w("GalleryActivity", "findPageImageRect failed: " + t.getMessage());
            return null;
        } finally {
            rootView.unlockRenderThread();
        }
    }

    /**
     * 丢弃该页已解码的图片并重新请求，让新烧录的"汉化图"立即生效。
     */
    private void refreshPageImage(int index) {
        if (mGalleryProvider == null || index < 0) {
            return;
        }
        if (mTranslationOverlay != null) {
            mTranslationOverlay.clear();
            mTranslationOverlay.setVisibility(View.GONE);
        }
        // 丢掉内存中的旧图并重新请求，provider 会优先返回刚烧录好的"汉化图"
        mGalleryProvider.removeCache(index);
        mGalleryProvider.request(index);
    }

    private void startOverlaySync() {
        if (!mOverlaySyncScheduled) {
            mOverlaySyncScheduled = true;
            mOverlayHandler.postDelayed(mOverlaySyncRunnable, 400);
        }
    }

    /**
     * 从本地缓存取出指定页译文并自动贴到画面上。翻页即调用。
     */
    private void showCachedTranslation(final int index) {
        showCachedTranslation(index, false, 0);
    }

    /**
     * @param force true 表示用户主动触发，忽略"自动显示"开关
     */
    private void showCachedTranslation(final int index, final boolean force) {
        showCachedTranslation(index, force, 0);
    }

    private void showCachedTranslation(final int index, final boolean force, final int retry) {
        if (mTranslationOverlay == null || mTranslationKey == null || index < 0) {
            return;
        }
        if (Settings.getBakedTranslation()) {
            // 译文已烧进图片，再叠气泡会重复遮挡。
            // 但如果这一页没有烧录图（缓存被系统清理、烧录失败、或旧版本遗留），
            // 就必须回退到气泡。否则 JSON 里明明有译文，画面却什么都没有，
            // 看起来就是"翻译全没了"。
            if (BakedTranslationStore.has(this, mTranslationKey, index)) {
                mTranslationOverlay.clear();
                mTranslationOverlay.setVisibility(View.GONE);
                return;
            }
        }
        if (!force && !Settings.getAutoShowTranslation()) {
            mTranslationOverlay.clear();
            mTranslationOverlay.setVisibility(View.GONE);
            return;
        }
        final List<MangaTextBlock> blocks = MangaTranslationCache.get(this).getPage(mTranslationKey, index);
        if (blocks.isEmpty()) {
            mTranslationOverlay.clear();
            mTranslationOverlay.setVisibility(View.GONE);
            return;
        }
        RectF rect = queryPageRect(index);
        if (rect == null || rect.isEmpty()) {
            // 页面可能还在下载或排版，短间隔重试最多 10 次（约 4 秒），
            // 避免慢速加载的页面永远贴不上气泡。
            if (retry >= 10) {
                return;
            }
            mOverlayHandler.postDelayed(() -> {
                if (mCurrentIndex != index || mTranslationOverlay == null) {
                    return;
                }
                showCachedTranslation(index, force, retry + 1);
            }, retry < 4 ? 200 : 500);
            return;
        }
        mTranslationOverlay.setVisibility(View.VISIBLE);
        mTranslationOverlay.setPageData(index, blocks, rect);
        startOverlaySync();
    }

    private void translateCurrentPage() {
        if (mGalleryProvider == null || mTranslationKey == null) {
            Toast.makeText(this, "阅读器未初始化", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mCurrentIndex < 0 || mCurrentIndex >= mSize) {
            Toast.makeText(this, "当前页码无效", Toast.LENGTH_SHORT).show();
            return;
        }

        final int index = mCurrentIndex;
        List<MangaTextBlock> cached = MangaTranslationCache.get(this).getPage(mTranslationKey, index);
        if (!cached.isEmpty()) {
            // 已翻译过：再次点击切换气泡显隐
            if (mTranslationOverlay != null && mTranslationOverlay.getCurrentPageIndex() == index
                    && mTranslationOverlay.hasData()) {
                boolean visible = mTranslationOverlay.isOverlayVisible();
                mTranslationOverlay.setOverlayVisible(!visible);
                Toast.makeText(this, visible ? "已隐藏翻译气泡" : "已恢复翻译气泡", Toast.LENGTH_SHORT).show();
            } else {
                showCachedTranslation(index, true);
                Toast.makeText(this, "已显示本页缓存译文", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        Toast.makeText(this, "正在翻译第 " + (index + 1) + " 页…", Toast.LENGTH_SHORT).show();
        translatePageAsync(index, true);
    }

    /**
     * 单页翻译：后台取图 -> OCR -> 翻译 -> 写缓存 -> 贴字。
     */
    private void translatePageAsync(final int index, final boolean toastResult) {
        final GalleryProvider2 provider = mGalleryProvider;
        final String key = mTranslationKey;
        if (provider == null || key == null) {
            return;
        }
        final Context appContext = getApplicationContext();
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            File tempFile = new File(getCacheDir(), "manga_tl_single_" + System.nanoTime() + "_" + index + ".img");
            try {
                ProviderPageSource source = new ProviderPageSource(provider);
                // 单页最多等 90 秒，期间反复请求下载，避免偶发失败直接放弃
                final long deadline = System.currentTimeMillis() + 90_000L;
                boolean fetched = false;
                while (System.currentTimeMillis() < deadline) {
                    if (source.fetchPage(index, tempFile, () -> System.currentTimeMillis() > deadline)) {
                        fetched = true;
                        break;
                    }
                    Thread.sleep(1000);
                }
                if (!fetched) {
                    runOnUiThread(() -> Toast.makeText(GalleryActivity.this,
                            R.string.translate_not_ready, Toast.LENGTH_SHORT).show());
                    return;
                }
                Bitmap bitmap = BatchMangaTranslator.decodeSampled(tempFile.getAbsolutePath(), 1800);
                if (bitmap == null) {
                    runOnUiThread(() -> Toast.makeText(GalleryActivity.this,
                            "图片解码失败", Toast.LENGTH_SHORT).show());
                    return;
                }
                boolean baked = false;
                int countTemp = 0;
                try {
                    List<MangaTextBlock> blocks = MangaOcrManager.getInstance().recognizeSync(bitmap);
                    countTemp = blocks.size();
                    if (blocks.isEmpty()) {
                        MangaTranslationCache.get(appContext).putPage(key, index, blocks);
                        runOnUiThread(() -> Toast.makeText(GalleryActivity.this,
                                R.string.translate_nothing, Toast.LENGTH_SHORT).show());
                        return;
                    }
                    GoogleTranslator.translateBlocksSync(blocks);
                    MangaTranslationCache.get(appContext).putPage(key, index, blocks);
                    // 直接把中文烧进图片，翻页/拖动/缩放都跟手
                    if (Settings.getBakedTranslation()) {
                        baked = BakedTranslationStore.bake(appContext, key, index, bitmap, blocks);
                    }
                } finally {
                    bitmap.recycle();
                }
                final boolean bakedOk = baked;
                final int count = countTemp;
                runOnUiThread(() -> {
                    if (index == mCurrentIndex) {
                        if (bakedOk) {
                            // 让这一页按"汉化图"重新解码
                            refreshPageImage(index);
                        } else {
                            showCachedTranslation(index, true);
                        }
                    }
                    if (toastResult) {
                        Toast.makeText(GalleryActivity.this,
                                getString(R.string.translate_page_done, index + 1, count),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Throwable t) {
                Log.e("GalleryActivity", "translatePageAsync error: " + t.getMessage(), t);
                runOnUiThread(() -> Toast.makeText(GalleryActivity.this,
                        "翻译失败：" + t.getMessage(), Toast.LENGTH_SHORT).show());
            } finally {
                if (tempFile.exists() && !tempFile.delete()) {
                    Log.w("GalleryActivity", "Failed to delete " + tempFile);
                }
            }
        });
    }

    /**
     * 启动整本翻译（阅读器入口）。
     */
    private void startBatchTranslation() {
        if (mGalleryProvider == null || mTranslationKey == null) {
            Toast.makeText(this, "阅读器未初始化", Toast.LENGTH_SHORT).show();
            return;
        }
        BatchMangaTranslator translator = BatchMangaTranslator.getInstance();
        if (!translator.isRunning(mTranslationKey)) {
            BatchMangaTranslator.PageSource source;
            if (mGalleryInfo != null && mGalleryInfo.gid > 0) {
                // 网络画廊用进程级 SpiderQueen，退出阅读器后仍能继续翻译
                source = SpiderQueenPageSource.create(getApplicationContext(), mGalleryInfo);
            } else {
                source = new ProviderPageSource(mGalleryProvider);
            }
            if (translator.start(getApplicationContext(), mTranslationKey, source)) {
                Toast.makeText(this, R.string.translate_started, Toast.LENGTH_SHORT).show();
            } else {
                // 已有任务在跑，释放刚申请的 SpiderQueen 引用，避免泄漏
                source.release();
            }
        }
        TranslationProgressDialog.show(this, mTranslationKey);
    }

    /**
     * 悬浮按钮长按：把整本翻译、单页翻译、气泡开关、清缓存集中在这里。
     */
    private void showTranslationQuickMenu() {
        if (mTranslationKey == null) {
            return;
        }
        final boolean autoShow = Settings.getAutoShowTranslation();
        final CharSequence[] items = new CharSequence[]{
                getString(R.string.translate_whole_manga),
                getString(R.string.translate_current_page),
                autoShow ? "关闭自动显示气泡" : "开启自动显示气泡",
                getString(R.string.translate_clear_cache)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.translate_progress_title)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            startBatchTranslation();
                            break;
                        case 1:
                            translateCurrentPage();
                            break;
                        case 2:
                            Settings.putAutoShowTranslation(!autoShow);
                            if (autoShow) {
                                if (mTranslationOverlay != null) {
                                    mTranslationOverlay.clear();
                                    mTranslationOverlay.setVisibility(View.GONE);
                                }
                            } else {
                                showCachedTranslation(mCurrentIndex);
                            }
                            Toast.makeText(this, autoShow ? "已关闭自动显示" : "已开启自动显示",
                                    Toast.LENGTH_SHORT).show();
                            break;
                        case 3:
                            MangaTranslationCache.get(this).clear(mTranslationKey);
                            if (mTranslationOverlay != null) {
                                mTranslationOverlay.clear();
                                mTranslationOverlay.setVisibility(View.GONE);
                            }
                            Toast.makeText(this, R.string.translate_cleared, Toast.LENGTH_SHORT).show();
                            break;
                    }
                })
                .show();
    }

    /**
     * @param lightness 0 - 200
     */
    private void setScreenLightness(boolean enable, int lightness) {
        if (null == mMaskView) {
            return;
        }

        Window w = getWindow();
        WindowManager.LayoutParams lp = w.getAttributes();
        if (enable) {
            lightness = MathUtils.clamp(lightness, 0, 200);
            if (lightness > 100) {
                mMaskView.setColor(0);
                // Avoid BRIGHTNESS_OVERRIDE_OFF,
                // screen may be off when lp.screenBrightness is 0.0f
                lp.screenBrightness = Math.max((lightness - 100) / 100.0f, 0.01f);
            } else {
                mMaskView.setColor(MathUtils.lerp(0xde, 0x00, lightness / 100.0f) << 24);
                lp.screenBrightness = 0.01f;
            }
        } else {
            mMaskView.setColor(0);
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        }
        w.setAttributes(lp);
    }

    private void applyReadingRefreshRate() {
        if (!ReadingRefreshRate.isSupported()) {
            return;
        }
        Window window = getWindow();
        WindowManager.LayoutParams lp = window.getAttributes();
        int setting = Settings.getReadingRefreshRateHz();
        if (setting == ReadingRefreshRate.SYSTEM_DEFAULT) {
            lp.preferredRefreshRate = 0f;
            lp.preferredDisplayModeId = 0;
        } else {
            Display.Mode mode = ReadingRefreshRate.findBestMode(getDisplay(), setting);
            if (mode != null) {
                lp.preferredDisplayModeId = mode.getModeId();
                lp.preferredRefreshRate = 0f;
            } else {
                lp.preferredRefreshRate = 0f;
                lp.preferredDisplayModeId = 0;
            }
        }
        window.setAttributes(lp);
    }

    private void shareImage(int page) {
        if (null == mGalleryProvider) {
            return;
        }

        File dir = AppConfig.getExternalTempDir();
        if (null == dir) {
            Toast.makeText(this, R.string.error_cant_create_temp_file, Toast.LENGTH_SHORT).show();
            return;
        }
        UniFile file;
        if (null == (file = mGalleryProvider.save(page, UniFile.fromFile(dir), mGalleryProvider.getImageFilename(page)))) {
            Toast.makeText(this, R.string.error_cant_save_image, Toast.LENGTH_SHORT).show();
            return;
        }
        String filename = file.getName();
        if (filename == null) {
            Toast.makeText(this, R.string.error_cant_save_image, Toast.LENGTH_SHORT).show();
            return;
        }


        String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(filename));
        if (TextUtils.isEmpty(mimeType)) {
            mimeType = "image/jpeg";
        }

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.putExtra(Intent.EXTRA_STREAM, file.getUri());
        intent.setType(mimeType);

        try {
            startActivity(Intent.createChooser(intent, getString(R.string.share_image)));
        } catch (Throwable e) {
            ExceptionUtils.throwIfFatal(e);
            Toast.makeText(this, R.string.error_cant_find_activity, Toast.LENGTH_SHORT).show();
        }
    }

    private void saveImage(int page) {
        if (null == mGalleryProvider) {
            return;
        }

        File dir = AppConfig.getExternalImageDir();
        if (null == dir) {
            Toast.makeText(this, R.string.error_cant_save_image, Toast.LENGTH_SHORT).show();
            return;
        }
        UniFile file;
        if (null == (file = mGalleryProvider.save(page, UniFile.fromFile(dir), mGalleryProvider.getImageFilename(page)))) {
            Toast.makeText(this, R.string.error_cant_save_image, Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, getString(R.string.image_saved, file.getUri()), Toast.LENGTH_SHORT).show();

        // Sync media store
        sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, file.getUri()));
    }

    private void saveImageTo(int page) {
        if (null == mGalleryProvider) {
            return;
        }
        File dir = getCacheDir();
        UniFile file;
        if (null == (file = mGalleryProvider.save(page, UniFile.fromFile(dir), mGalleryProvider.getImageFilename(page)))) {
            Toast.makeText(this, R.string.error_cant_save_image, Toast.LENGTH_SHORT).show();
            return;
        }
        String filename = file.getName();
        if (filename == null) {
            Toast.makeText(this, R.string.error_cant_save_image, Toast.LENGTH_SHORT).show();
            return;
        }
        mCacheFileName = filename;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_TITLE, filename);
        try {
            startActivityForResult(intent, WRITE_REQUEST_CODE);
//            registerForActivityResult(intent, WRITE_REQUEST_CODE);
//            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::saveImageDats)
//                    .launch(intent);
        } catch (Throwable e) {
            ExceptionUtils.throwIfFatal(e);
            Toast.makeText(this, R.string.error_cant_find_activity, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent resultData) {
        super.onActivityResult(requestCode, resultCode, resultData);
        if (requestCode == WRITE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (resultData != null) {
                Uri uri = resultData.getData();
                String filepath = getCacheDir() + "/" + mCacheFileName;
                File cacheFile = new File(filepath);

                InputStream is = null;
                OutputStream os = null;
                ContentResolver resolver = getContentResolver();

                try {
                    is = new FileInputStream(cacheFile);
                    os = resolver.openOutputStream(uri);
                    IOUtils.copy(is, os);
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    IOUtils.closeQuietly(is);
                    IOUtils.closeQuietly(os);
                }

                cacheFile.delete();

                Toast.makeText(this, getString(R.string.image_saved, uri.getPath()), Toast.LENGTH_SHORT).show();
                // Sync media store
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri));
            }
        }
    }

    private void saveImageDats(ActivityResult result) {
        if (result == null) {
            return;
        }
        if (result.getResultCode() != Activity.RESULT_OK) {
            return;
        }
        Intent resultData = result.getData();
        if (resultData != null) {
            Uri uri = resultData.getData();
            String filepath = getCacheDir() + "/" + mCacheFileName;
            File cacheFile = new File(filepath);

            InputStream is = null;
            OutputStream os = null;
            ContentResolver resolver = getContentResolver();

            try {
                is = new FileInputStream(cacheFile);
                os = resolver.openOutputStream(uri);
                IOUtils.copy(is, os);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                IOUtils.closeQuietly(is);
                IOUtils.closeQuietly(os);
            }

            boolean deleted = cacheFile.delete();
            if (!deleted) {
                cacheFile.deleteOnExit();
            }

            Toast.makeText(this, getString(R.string.image_saved, uri.getPath()), Toast.LENGTH_SHORT).show();
            // Sync media store
            sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri));
        }
    }


    private void showPageDialog(final int page) {
        Resources resources = GalleryActivity.this.getResources();
        AlertDialog.Builder builder = new AlertDialog.Builder(GalleryActivity.this);
        builder.setTitle(resources.getString(R.string.page_menu_title, page + 1));

        final CharSequence[] items;
        items = new CharSequence[]{getString(R.string.page_menu_refresh), getString(R.string.page_menu_share), getString(R.string.page_menu_save), getString(R.string.page_menu_save_to)};
        pageDialogListener(builder, items, page);
        builder.show();
    }

    private void pageDialogListener(AlertDialog.Builder builder, CharSequence[] items, int page) {
        builder.setItems(items, (dialog, which) -> {
            if (mGalleryProvider == null) {
                return;
            }

            switch (which) {
                case 0: // Refresh
                    mGalleryProvider.removeCache(page);
                    mGalleryProvider.forceRequest(page);
                    break;
                case 1: // Share
                    shareImage(page);
                    break;
                case 2: // Save
                    saveImage(page);
                    break;
                case 3: // Save to
                    saveImageTo(page);
                    break;
            }
        });
    }

    private class GalleryMenuHelper implements DialogInterface.OnClickListener {

        private final View mView;
        private final Spinner mScreenRotation;
        private final Spinner mReadingDirection;
        private final Spinner mScaleMode;
        private final Spinner mStartPosition;
        private final SeekBar mStartTransferTime;
        private final SwitchCompat mKeepScreenOn;
        private final SwitchCompat mShowClock;
        private final SwitchCompat mShowProgress;
        private final SwitchCompat mShowBattery;
        private final SwitchCompat mShowPageInterval;
        private final SwitchCompat mVolumePage;
        private final SwitchCompat mReverseVolumePage;
        private final SwitchCompat mReadingFullscreen;
        private final SwitchCompat mCustomScreenLightness;
        private final SeekBar mScreenLightness;
        private final SwitchCompat mAutoShowTranslation;

        @Nullable
        private AlertDialog mDialog;

        @SuppressLint("InflateParams")
        public GalleryMenuHelper(Context context) {
            mView = LayoutInflater.from(context).inflate(R.layout.dialog_gallery_menu, null);
            mScreenRotation = mView.findViewById(R.id.screen_rotation);
            mReadingDirection = mView.findViewById(R.id.reading_direction);
            mScaleMode = mView.findViewById(R.id.page_scaling);
            mStartPosition = mView.findViewById(R.id.start_position);
            mStartTransferTime = mView.findViewById(R.id.start_transfer_time);
            mKeepScreenOn = mView.findViewById(R.id.keep_screen_on);
            mShowClock = mView.findViewById(R.id.show_clock);
            mShowProgress = mView.findViewById(R.id.show_progress);
            mShowBattery = mView.findViewById(R.id.show_battery);
            mShowPageInterval = mView.findViewById(R.id.show_page_interval);
            mVolumePage = mView.findViewById(R.id.volume_page);
            mReverseVolumePage = mView.findViewById(R.id.reverse_volume_page);
            mReadingFullscreen = mView.findViewById(R.id.reading_fullscreen);
            mCustomScreenLightness = mView.findViewById(R.id.custom_screen_lightness);
            mScreenLightness = mView.findViewById(R.id.screen_lightness);
            mAutoShowTranslation = mView.findViewById(R.id.menu_auto_show_translation);

            View translateWhole = mView.findViewById(R.id.menu_translate_whole);
            View translatePage = mView.findViewById(R.id.menu_translate_page);
            translateWhole.setOnClickListener(v -> {
                dismissMenu();
                startBatchTranslation();
            });
            translatePage.setOnClickListener(v -> {
                dismissMenu();
                translateCurrentPage();
            });
            mAutoShowTranslation.setChecked(Settings.getAutoShowTranslation());
            mAutoShowTranslation.setOnCheckedChangeListener((buttonView, isChecked) -> {
                Settings.putAutoShowTranslation(isChecked);
                if (isChecked) {
                    showCachedTranslation(mCurrentIndex);
                } else if (mTranslationOverlay != null) {
                    mTranslationOverlay.clear();
                    mTranslationOverlay.setVisibility(View.GONE);
                }
            });

            mScreenRotation.setSelection(Settings.getScreenRotation());
            mReadingDirection.setSelection(Settings.getReadingDirection());
            mScaleMode.setSelection(Settings.getPageScaling());
            mStartPosition.setSelection(Settings.getStartPosition());
            mStartTransferTime.setProgress(Settings.getStartTransferTime());
            mKeepScreenOn.setChecked(Settings.getKeepScreenOn());
            mShowClock.setChecked(Settings.getShowClock());
            mShowProgress.setChecked(Settings.getShowProgress());
            mShowBattery.setChecked(Settings.getShowBattery());
            mShowPageInterval.setChecked(Settings.getShowPageInterval());
            mVolumePage.setChecked(Settings.getVolumePage());
            mReverseVolumePage.setChecked(Settings.getReverseVolumePage());
            mReadingFullscreen.setChecked(Settings.getReadingFullscreen());
            mCustomScreenLightness.setChecked(Settings.getCustomScreenLightness());
            mScreenLightness.setProgress(Settings.getScreenLightness());
            mScreenLightness.setEnabled(Settings.getCustomScreenLightness());

            mVolumePage.setOnCheckedChangeListener(this::onVolumePageChange);

            if (Settings.getVolumePage()) {
                mReverseVolumePage.setVisibility(View.VISIBLE);

            } else {
                mReverseVolumePage.setVisibility(View.GONE);
            }

            mCustomScreenLightness.setOnCheckedChangeListener((buttonView, isChecked) -> mScreenLightness.setEnabled(isChecked));
        }

        private void onVolumePageChange(CompoundButton compoundButton, boolean b) {
            if (compoundButton.isChecked()) {
                mReverseVolumePage.setVisibility(View.VISIBLE);
            } else {
                mReverseVolumePage.setVisibility(View.GONE);
            }
        }

        public View getView() {
            return mView;
        }

        public void attachDialog(@Nullable AlertDialog dialog) {
            mDialog = dialog;
        }

        private void dismissMenu() {
            if (mDialog != null) {
                mDialog.dismiss();
            }
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (mGalleryView == null) {
                return;
            }

            int screenRotation = mScreenRotation.getSelectedItemPosition();
            int layoutMode = GalleryView.sanitizeLayoutMode(mReadingDirection.getSelectedItemPosition());
            int scaleMode = GalleryView.sanitizeScaleMode(mScaleMode.getSelectedItemPosition());
            int startPosition = GalleryView.sanitizeStartPosition(mStartPosition.getSelectedItemPosition());
            boolean keepScreenOn = mKeepScreenOn.isChecked();
            boolean showClock = mShowClock.isChecked();
            boolean showProgress = mShowProgress.isChecked();
            boolean showBattery = mShowBattery.isChecked();
            boolean showPageInterval = mShowPageInterval.isChecked();
            boolean volumePage = mVolumePage.isChecked();
            boolean reverseVolumePage = mReverseVolumePage.isChecked();
            boolean readingFullscreen = mReadingFullscreen.isChecked();
            boolean customScreenLightness = mCustomScreenLightness.isChecked();

            int screenLightness = mScreenLightness.getProgress();
            int transferTime = mStartTransferTime.getProgress();

            boolean oldReadingFullscreen = Settings.getReadingFullscreen();

            Settings.putScreenRotation(screenRotation);
            Settings.putReadingDirection(layoutMode);
            Settings.putPageScaling(scaleMode);
            Settings.putStartPosition(startPosition);
            Settings.putStartTransferTime(transferTime);
            Settings.putKeepScreenOn(keepScreenOn);
            Settings.putShowClock(showClock);
            Settings.putShowProgress(showProgress);
            Settings.putShowBattery(showBattery);
            Settings.putShowPageInterval(showPageInterval);
            Settings.putVolumePage(volumePage);
            Settings.putReadingFullscreen(readingFullscreen);
            Settings.putCustomScreenLightness(customScreenLightness);
            Settings.putScreenLightness(screenLightness);
            Settings.putReverseVolumePage(reverseVolumePage);
            if (!volumePage) {
                mReverseVolumePage.setVisibility(View.GONE);
            } else {
                mReverseVolumePage.setVisibility(View.VISIBLE);
            }

            int orientation;
            switch (screenRotation) {
                default:
                case 0:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
                    break;
                case 1:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
                    break;
                case 2:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
                    break;
                case 3:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR;
                    break;
            }
            setRequestedOrientation(orientation);
            mGalleryView.setLayoutMode(layoutMode);
            mGalleryView.setScaleMode(scaleMode);
            mGalleryView.setStartPosition(startPosition);
            if (keepScreenOn) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            if (mClock != null) {
                mClock.setVisibility(showClock ? View.VISIBLE : View.GONE);
            }
            if (mProgress != null) {
                mProgress.setVisibility(showProgress ? View.VISIBLE : View.GONE);
            }
            if (mBattery != null) {
                mBattery.setVisibility(showBattery ? View.VISIBLE : View.GONE);
            }
            mGalleryView.setPagerInterval(showPageInterval ? getResources().getDimensionPixelOffset(R.dimen.gallery_pager_interval) : 0);
            mGalleryView.setScrollInterval(showPageInterval ? getResources().getDimensionPixelOffset(R.dimen.gallery_scroll_interval) : 0);
            setScreenLightness(customScreenLightness, screenLightness);

            // Update slider
            mLayoutMode = layoutMode;
            updateSlider();

            if (oldReadingFullscreen != readingFullscreen) {
                recreate();
            }
        }
    }

    private class NotifyTask implements Runnable {

        public static final int KEY_LAYOUT_MODE = 0;
        public static final int KEY_SIZE = 1;
        public static final int KEY_CURRENT_INDEX = 2;
        public static final int KEY_TAP_SLIDER_AREA = 3;
        public static final int KEY_TAP_MENU_AREA = 4;
        public static final int KEY_TAP_ERROR_TEXT = 5;
        public static final int KEY_LONG_PRESS_PAGE = 6;

        private int mKey;
        private int mValue;

        public void setData(int key, int value) {
            mKey = key;
            mValue = value;
        }

        private void onTapMenuArea() {
            AlertDialog.Builder builder = new AlertDialog.Builder(GalleryActivity.this);
            GalleryMenuHelper helper = new GalleryMenuHelper(builder.getContext());
            builder.setTitle(R.string.gallery_menu_title).setView(helper.getView()).setPositiveButton(android.R.string.ok, helper);
            AlertDialog dialog = builder.create();
            helper.attachDialog(dialog);
            dialog.show();
        }

        private void onTapSliderArea() {
            if (mSeekBarPanel == null || mSize <= 0 || mCurrentIndex < 0 || mAutoTransferPanel == null) {
                return;
            }

            SimpleHandler.getInstance().removeCallbacks(mHideSliderRunnable);

            if (mSeekBarPanel.getVisibility() == View.VISIBLE) {
                hideSlider(mSeekBarPanel, mSeekBarPanelAnimator);
                hideSlider(mAutoTransferPanel, mAutoTransferAnimator);
            } else {
                showSlider(mSeekBarPanel, mSeekBarPanelAnimator);
                showSlider(mAutoTransferPanel, mAutoTransferAnimator);
                SimpleHandler.getInstance().postDelayed(mHideSliderRunnable, HIDE_SLIDER_DELAY);
            }
            if (mBtnTranslate != null) {
                mBtnTranslate.setVisibility(View.VISIBLE);
            }
        }

        private void onTapErrorText(int index) {
            if (mGalleryProvider != null) {
                mGalleryProvider.forceRequest(index);
            }
        }

        private void onLongPressPage(final int index) {
            showPageDialog(index);
        }

        @Override
        public void run() {
            switch (mKey) {
                case KEY_LAYOUT_MODE:
                    GalleryActivity.this.mLayoutMode = mValue;
                    updateSlider();
                    break;
                case KEY_SIZE:
                    GalleryActivity.this.mSize = mValue;
                    updateSlider();
                    updateProgress();
                    break;
                case KEY_CURRENT_INDEX:
                    GalleryActivity.this.mCurrentIndex = mValue;
                    if (mTranslationOverlay != null && mTranslationOverlay.getCurrentPageIndex() != mValue) {
                        mTranslationOverlay.clear();
                    }
                    updateSlider();
                    updateProgress();
                    // 翻页即查缓存，有译文就自动贴上
                    showCachedTranslation(mValue);
                    break;
                case KEY_TAP_MENU_AREA:
                    onTapMenuArea();
                    break;
                case KEY_TAP_SLIDER_AREA:
                    onTapSliderArea();
                    break;
                case KEY_TAP_ERROR_TEXT:
                    onTapErrorText(mValue);
                    break;
                case KEY_LONG_PRESS_PAGE:
                    onLongPressPage(mValue);
                    break;
            }
            mNotifyTaskPool.push(this);
        }
    }

    private class GalleryAdapter extends SimpleAdapter {

        public GalleryAdapter(@NonNull GLRootView glRootView, @NonNull GalleryProvider provider) {
            super(glRootView, provider);
        }

        @Override
        public void onDataChanged() {
            super.onDataChanged();

            if (mGalleryProvider != null) {
                int size = mGalleryProvider.size();
                NotifyTask task = mNotifyTaskPool.pop();
                if (task == null) {
                    task = new NotifyTask();
                }
                task.setData(NotifyTask.KEY_SIZE, size);
                SimpleHandler.getInstance().post(task);
            }
        }
    }

}
