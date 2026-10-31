package com.hippo.ehviewer.translation;

import android.app.Activity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.hippo.ehviewer.R;

/**
 * 整本翻译进度对话框。支持"后台运行"（仅关闭对话框，翻译继续）。
 */
public class TranslationProgressDialog {

    public static void show(@NonNull Activity activity, @NonNull String key) {
        if (activity.isFinishing()) {
            return;
        }
        BatchMangaTranslator translator = BatchMangaTranslator.getInstance();
        boolean running = translator.isRunning(key);
        if (!running) {
            return;
        }

        int padding = dp(activity, 24);
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(padding, dp(activity, 8), padding, 0);

        TextView status = new TextView(activity);
        status.setTextSize(14);
        status.setText(activity.getString(R.string.translate_progress_message,
                translator.getFinished(key), Math.max(translator.getTotal(key), 1)));
        container.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ProgressBar progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barParams.topMargin = dp(activity, 12);
        progressBar.setMax(Math.max(translator.getTotal(key), 1));
        progressBar.setProgress(translator.getFinished(key));
        container.addView(progressBar, barParams);

        final BatchMangaTranslator.Listener[] holder = new BatchMangaTranslator.Listener[1];
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.translate_progress_title)
                .setView(container)
                .setNegativeButton(R.string.translate_dialog_background, null)
                .setPositiveButton(R.string.translate_dialog_cancel, (d, which) -> translator.cancel(key))
                .create();

        BatchMangaTranslator.Listener listener = new BatchMangaTranslator.Listener() {
            @Override
            public void onBatchProgress(@NonNull String taskKey, int finished, int total, int failed, int currentIndex) {
                if (!key.equals(taskKey)) {
                    return;
                }
                status.setText(activity.getString(R.string.translate_progress_message, finished, Math.max(total, 1)));
                progressBar.setMax(Math.max(total, 1));
                progressBar.setProgress(finished);
            }

            @Override
            public void onPageRetry(@NonNull String taskKey, int pageIndex, int attempt) {
                if (!key.equals(taskKey)) {
                    return;
                }
                status.setText("第 " + (pageIndex + 1) + " 页下载失败，正在重试（第 " + attempt + " 次）…");
            }

            @Override
            public void onBatchFinished(@NonNull String taskKey, int finished, int total, int failed, boolean cancelled) {
                if (!key.equals(taskKey)) {
                    return;
                }
                if (holder[0] != null) {
                    translator.removeListener(holder[0]);
                }
                if (activity.isFinishing()) {
                    return;
                }
                if (dialog.isShowing()) {
                    dialog.dismiss();
                }
                if (cancelled) {
                    Toast.makeText(activity, activity.getString(R.string.translate_cancelled, finished, total),
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(activity, activity.getString(R.string.translate_finished, finished, failed),
                            Toast.LENGTH_LONG).show();
                }
            }
        };
        holder[0] = listener;
        translator.addListener(listener);

        dialog.setOnDismissListener(d -> {
            if (holder[0] != null) {
                translator.removeListener(holder[0]);
            }
        });
        dialog.show();
        // 极端情况下任务已瞬间结束，避免留下一个不会消失的对话框
        if (!BatchMangaTranslator.getInstance().isRunning(key) && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    private static int dp(@NonNull Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
