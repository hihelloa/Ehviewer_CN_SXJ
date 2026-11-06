package com.hippo.ehviewer.ui.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.translation.LlmTranslator;

/**
 * 翻译引擎设置：内置免费接口 / 用户自填的大模型 API。
 * 对应布局 xml/translation_settings.xml
 */
public class TranslationFragment extends BasePreferenceFragmentCompat {

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        addPreferencesFromResource(R.xml.translation_settings);

        ListPreference engine = findPreference(Settings.KEY_TRANSLATION_ENGINE);
        if (engine != null) {
            engine.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean llm = String.valueOf(Settings.TRANSLATION_ENGINE_LLM)
                        .equals(String.valueOf(newValue));
                setApiEnabled(llm);
                return true;
            });
        }

        EditTextPreference baseUrl = findPreference(Settings.KEY_TRANSLATION_API_BASE_URL);
        if (baseUrl != null) {
            baseUrl.setOnPreferenceChangeListener((preference, newValue) -> {
                mHandler.postDelayed(this::refreshSummary, 100);
                return true;
            });
        }

        EditTextPreference apiKey = findPreference(Settings.KEY_TRANSLATION_API_KEY);
        if (apiKey != null) {
            // 输入框里遮蔽显示，避免设置页/截图泄露 Key
            apiKey.setOnBindEditTextListener(editText ->
                    editText.setInputType(InputType.TYPE_CLASS_TEXT
                            | InputType.TYPE_TEXT_VARIATION_PASSWORD));
            apiKey.setOnPreferenceChangeListener((preference, newValue) -> {
                mHandler.postDelayed(this::refreshSummary, 100);
                return true;
            });
        }

        EditTextPreference model = findPreference(Settings.KEY_TRANSLATION_API_MODEL);
        if (model != null) {
            model.setOnPreferenceChangeListener((preference, newValue) -> {
                mHandler.postDelayed(this::refreshSummary, 100);
                return true;
            });
        }

        Preference test = findPreference("translation_api_test");
        if (test != null) {
            test.setOnPreferenceClickListener(preference -> {
                runTest(preference);
                return true;
            });
        }

        refreshSummary();
        ensureDefaults();
        setApiEnabled(Settings.getTranslationEngine() == Settings.TRANSLATION_ENGINE_LLM);
    }

    /**
     * 首次进入时把默认值写进偏好，否则列表项显示空、输入框也是空的。
     * 只补没存过的项，用户改过的值不动。
     */
    private void ensureDefaults() {
        ListPreference engine = findPreference(Settings.KEY_TRANSLATION_ENGINE);
        if (engine != null && engine.getValue() == null) {
            engine.setValue(String.valueOf(Settings.getTranslationEngine()));
        }
        EditTextPreference baseUrl = findPreference(Settings.KEY_TRANSLATION_API_BASE_URL);
        if (baseUrl != null && baseUrl.getText() == null) {
            baseUrl.setText(Settings.getTranslationApiBaseUrl());
        }
        EditTextPreference model = findPreference(Settings.KEY_TRANSLATION_API_MODEL);
        if (model != null && model.getText() == null) {
            model.setText(Settings.getTranslationApiModel());
        }
        refreshSummary();
    }

    private void refreshSummary() {
        EditTextPreference baseUrl = findPreference(Settings.KEY_TRANSLATION_API_BASE_URL);
        if (baseUrl != null) {
            baseUrl.setSummary(Settings.getTranslationApiBaseUrl());
        }
        EditTextPreference apiKey = findPreference(Settings.KEY_TRANSLATION_API_KEY);
        if (apiKey != null) {
            apiKey.setSummary(Settings.getTranslationApiKey().trim().isEmpty()
                    ? R.string.settings_translation_api_key_unset
                    : R.string.settings_translation_api_key_set);
        }
        EditTextPreference model = findPreference(Settings.KEY_TRANSLATION_API_MODEL);
        if (model != null) {
            model.setSummary(Settings.getTranslationApiModel());
        }
    }

    private void setApiEnabled(boolean enabled) {
        setEnabled(Settings.KEY_TRANSLATION_API_BASE_URL, enabled);
        setEnabled(Settings.KEY_TRANSLATION_API_KEY, enabled);
        setEnabled(Settings.KEY_TRANSLATION_API_MODEL, enabled);
        setEnabled("translation_api_test", enabled);
    }

    private void setEnabled(@Nullable String key, boolean enabled) {
        Preference preference = findPreference(key);
        if (preference != null) {
            preference.setEnabled(enabled);
        }
    }

    private void runTest(Preference preference) {
        if (Settings.getTranslationApiKey().trim().isEmpty()) {
            Toast.makeText(requireContext(),
                    R.string.settings_translation_api_key_unset, Toast.LENGTH_SHORT).show();
            return;
        }
        preference.setEnabled(false);
        preference.setSummary(R.string.settings_translation_api_testing);
        new Thread(() -> {
            StringBuilder error = new StringBuilder();
            String result = LlmTranslator.testConnection(error);
            mHandler.post(() -> {
                if (!isAdded()) {
                    return;
                }
                preference.setEnabled(true);
                if (result == null) {
                    preference.setSummary(getString(R.string.settings_translation_api_test_failed,
                            error.toString()));
                    Toast.makeText(requireContext(),
                            getString(R.string.settings_translation_api_test_failed, error.toString()),
                            Toast.LENGTH_LONG).show();
                } else {
                    String oneLine = result.replace('\n', ' ');
                    preference.setSummary(getString(R.string.settings_translation_api_test_ok, oneLine));
                    Toast.makeText(requireContext(),
                            getString(R.string.settings_translation_api_test_ok, oneLine),
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "translation-api-test").start();
    }
}
