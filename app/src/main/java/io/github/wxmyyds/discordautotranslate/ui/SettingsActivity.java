package io.github.wxmyyds.discordautotranslate.ui;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.wxmyyds.discordautotranslate.R;
import io.github.wxmyyds.discordautotranslate.core.Config;
import io.github.wxmyyds.discordautotranslate.core.Protocols;
import io.github.wxmyyds.discordautotranslate.core.MemoryPrefs;
import io.github.wxmyyds.discordautotranslate.translate.ApiTester;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import io.github.libxposed.service.XposedService;

/** 设置界面（需求 §5.7）。 */
public class SettingsActivity extends Activity implements XposedServiceConnectionHolder.Listener {

    private static final String LOCAL_PREFS_NAME = "settings_before_activation";
    private static final String KEY_LOCAL_INITIALIZED = "settings_initialized";
    private static final String[] CONFIG_KEYS = {
            Config.KEY_ENABLED, Config.KEY_API_URL, Config.KEY_API_KEY, Config.KEY_MODEL,
            Config.KEY_TARGET_LANG, Config.KEY_EXTRA_HEADERS, Config.KEY_SYSTEM_PROMPT,
            Config.KEY_PROTOCOL, Config.KEY_STREAMING, Config.KEY_TIMEOUT_MS,
            Config.KEY_MAX_CONCURRENCY
    };

    private static final String[] TARGET_LANGUAGE_VALUES = {
            "简体中文", "繁體中文", "English", "日本語", "한국어", "Русский",
            "Українська", "Español", "Français", "Deutsch", "Português", "Italiano",
            "العربية", "فارسی", "हिन्दी", "বাংলা", "ไทย", "עברית", "Ελληνικά",
            "Tiếng Việt", "Türkçe", "Bahasa Indonesia"
    };
    private static final String[] TARGET_LANGUAGE_LABELS_EN = {
            "Simplified Chinese", "Traditional Chinese", "English", "Japanese", "Korean",
            "Russian", "Ukrainian", "Spanish", "French", "German", "Portuguese", "Italian",
            "Arabic", "Persian", "Hindi", "Bengali", "Thai", "Hebrew", "Greek",
            "Vietnamese", "Turkish", "Indonesian"
    };
    private static final String[] TARGET_LANGUAGE_LABELS_ZH = {
            "简体中文", "繁體中文", "英语", "日语", "韩语", "俄语", "乌克兰语", "西班牙语",
            "法语", "德语", "葡萄牙语", "意大利语", "阿拉伯语", "波斯语", "印地语",
            "孟加拉语", "泰语", "希伯来语", "希腊语", "越南语", "土耳其语", "印尼语"
    };

    /** 下拉框显示顺序：0=OpenAI-Responses，1=OpenAI-Compatible，2=Anthropic-Messages。 */
    private static final String[] PROTOCOL_ENTRIES =
            {"OpenAI-Responses", "OpenAI-Compatible", "Anthropic-Messages"};

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService testExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor();

    @Nullable
    private XposedService service;
    private SharedPreferences prefs;
    @Nullable
    private SharedPreferences remotePrefs;
    @Nullable
    private Future<?> testFuture;

    private long testGeneration;
    private long serviceGeneration;
    private boolean saveInProgress;
    private boolean syncInProgress;
    private boolean destroyed;

    private Spinner protocolSpinner;
    private Spinner targetLanguageSpinner;
    private ArrayAdapter<String> targetLanguageAdapter;
    private final List<String> targetLanguageValues = new ArrayList<>();
    private boolean chineseUi;
    private boolean loadingUi;
    private boolean hasUnsavedChanges;
    @Nullable
    private String baselineProtocol;
    @Nullable
    private String baselineTargetLanguage;
    private EditText urlInput;
    private EditText keyInput;
    private EditText modelInput;
    private EditText headersInput;
    private EditText systemPromptInput;
    private EditText timeoutInput;
    private EditText concurrencyInput;
    private CheckBox enabledInput;
    private CheckBox streamingInput;
    private TextView statusText;
    private TextView resultText;
    private EditText testTextInput;
    private Button saveButton;
    private Button configCheckButton;
    private Button testConnectionButton;
    private Button testTranslationButton;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        chineseUi = isChineseSystemLanguage();
        setTitle(R.string.module_name);
        setContentView(buildContentView());
        prefs = getSharedPreferences(LOCAL_PREFS_NAME, MODE_PRIVATE);
        loadIntoUi();
        installDirtyTracking();
        updateSaveButtonState();
        updateStatus(getString(R.string.status_local_ready));
        XposedServiceConnectionHolder.bind(this);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        XposedServiceConnectionHolder.unbind(this);
        cancelPendingTest();
        testExecutor.shutdownNow();
        saveExecutor.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------ 服务连接

    @Override
    public void onServiceConnected(@NonNull XposedService connectedService) {
        if (destroyed || prefs == null) {
            return;
        }
        service = connectedService;
        serviceGeneration++;
        syncInProgress = false;
        try {
            remotePrefs = connectedService.getRemotePreferences(Config.GROUP);
        } catch (Throwable t) {
            remotePrefs = null;
            updateStatus(getString(R.string.status_sync_pending));
            updateSaveButtonState();
            return;
        }

        syncInProgress = true;
        updateSaveButtonState();
        updateStatus(getString(R.string.status_syncing));
        final SharedPreferences local = prefs;
        final SharedPreferences remote = remotePrefs;
        try {
            saveExecutor.execute(() -> {
                boolean synced = false;
                try {
                    if (local.getBoolean(KEY_LOCAL_INITIALIZED, false)) {
                        synced = copyConfigPreferences(local, remote, false);
                    } else {
                        synced = copyConfigPreferences(remote, local, true);
                    }
                } catch (Throwable ignored) {
                    synced = false;
                }
                final boolean syncResult = synced;
                mainHandler.post(() -> {
                    if (destroyed || generation != serviceGeneration
                            || local != prefs || remote != remotePrefs) {
                        return;
                    }
                    syncInProgress = false;
                    if (!hasUnsavedChanges) {
                        loadIntoUi();
                    }
                    updateSaveButtonState();
                    updateStatus(syncResult
                            ? getString(R.string.status_connected,
                                    connectedService.getFrameworkVersion(),
                                    connectedService.getApiVersion())
                            : getString(R.string.status_sync_failed));
                });
            });
        } catch (Throwable t) {
            syncInProgress = false;
            updateSaveButtonState();
            updateStatus(getString(R.string.status_sync_failed));
        }
    }

    @Override
    public void onServiceDied(@NonNull XposedService deadService) {
        if (destroyed || service != deadService) {
            return;
        }
        service = null;
        remotePrefs = null;
        serviceGeneration++;
        saveInProgress = false;
        syncInProgress = false;
        updateSaveButtonState();
        updateStatus(getString(R.string.status_local_ready));
    }

    // ------------------------------------------------------------ UI 构建

    private View buildContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        statusText = new TextView(this);
        statusText.setTextSize(12f);
        root.addView(statusText, matchWrap());

        enabledInput = new CheckBox(this);
        enabledInput.setText(R.string.label_enabled);
        root.addView(enabledInput, matchWrap());

        // 供应商协议：请求体与译文解析按所选协议内置实现，
        // 不再让用户手写 JSON 请求模板与译文提取路径。
        TextView protocolLabel = new TextView(this);
        protocolLabel.setText(R.string.label_protocol);
        protocolLabel.setPadding(0, dp(8), 0, 0);
        root.addView(protocolLabel, matchWrap());
        protocolSpinner = new Spinner(this);
        ArrayAdapter<String> protocolAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, PROTOCOL_ENTRIES);
        protocolAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        protocolSpinner.setAdapter(protocolAdapter);
        protocolSpinner.setSelection(positionOf(Config.DEF_PROTOCOL));
        root.addView(protocolSpinner, matchWrap());
        protocolSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateStreamingAvailability();
                markSpinnerDirty();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                updateStreamingAvailability();
                markSpinnerDirty();
            }
        });
        TextView protocolHint = new TextView(this);
        protocolHint.setTextSize(12f);
        protocolHint.setText(R.string.hint_protocol);
        root.addView(protocolHint, matchWrap());

        streamingInput = new CheckBox(this);
        streamingInput.setText(R.string.label_streaming);
        root.addView(streamingInput, matchWrap());

        // 注意：TYPE_TEXT_VARIATION_* 必须与 TYPE_CLASS_TEXT 组合使用。
        urlInput = addField(root, getString(R.string.label_api_url),
                getString(R.string.hint_api_url),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        keyInput = addField(root, getString(R.string.label_api_key), "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        modelInput = addField(root, getString(R.string.label_model), Config.DEF_MODEL,
                InputType.TYPE_CLASS_TEXT);
        TextView targetLanguageLabel = new TextView(this);
        targetLanguageLabel.setText(R.string.label_target_language);
        targetLanguageLabel.setPadding(0, dp(8), 0, 0);
        root.addView(targetLanguageLabel, matchWrap());
        targetLanguageSpinner = new Spinner(this);
        targetLanguageValues.clear();
        targetLanguageValues.addAll(Arrays.asList(TARGET_LANGUAGE_VALUES));
        String[] labels = chineseUi ? TARGET_LANGUAGE_LABELS_ZH : TARGET_LANGUAGE_LABELS_EN;
        targetLanguageAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new ArrayList<>(Arrays.asList(labels)));
        targetLanguageAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        targetLanguageSpinner.setAdapter(targetLanguageAdapter);
        targetLanguageSpinner.setSelection(targetLanguagePosition(Config.DEF_TARGET_LANG));
        targetLanguageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                markSpinnerDirty();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                markSpinnerDirty();
            }
        });
        root.addView(targetLanguageSpinner, matchWrap());
        headersInput = addField(root, getString(R.string.label_headers), "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        systemPromptInput = addField(root, getString(R.string.label_system_prompt),
                getString(R.string.hint_system_prompt),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        timeoutInput = addField(root, getString(R.string.label_timeout),
                String.valueOf(Config.DEF_TIMEOUT_MS), InputType.TYPE_CLASS_NUMBER);
        concurrencyInput = addField(root, getString(R.string.label_concurrency),
                String.valueOf(Config.DEF_MAX_CONCURRENCY), InputType.TYPE_CLASS_NUMBER);


        testTextInput = addField(root, getString(R.string.label_test_text),
                getString(R.string.hint_test_text),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);

        TextView privacy = new TextView(this);
        privacy.setTextSize(12f);
        privacy.setText(R.string.privacy_notice);
        root.addView(privacy, matchWrap());

        saveButton = new Button(this);
        saveButton.setText(R.string.button_save);
        saveButton.setEnabled(false);
        saveButton.setOnClickListener(v -> save());
        root.addView(saveButton, matchWrap());

        LinearLayout testRow = new LinearLayout(this);
        testRow.setOrientation(LinearLayout.HORIZONTAL);

        configCheckButton = new Button(this);
        configCheckButton.setText(R.string.button_config_check);
        configCheckButton.setOnClickListener(v -> runSelfCheck());
        testRow.addView(configCheckButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        testConnectionButton = new Button(this);
        testConnectionButton.setText(R.string.button_test_connection);
        testConnectionButton.setOnClickListener(v -> runTestTranslation(true));
        testRow.addView(testConnectionButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        testTranslationButton = new Button(this);
        testTranslationButton.setText(R.string.button_test_translation);
        testTranslationButton.setOnClickListener(v -> runTestTranslation(false));
        testRow.addView(testTranslationButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(testRow, matchWrap());

        resultText = new TextView(this);
        resultText.setTextSize(12f);
        resultText.setPadding(0, dp(8), 0, 0);
        root.addView(resultText, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private EditText addField(LinearLayout parent, String label, String hint, int inputType) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setPadding(0, dp(8), 0, 0);
        parent.addView(tv, matchWrap());

        EditText et = new EditText(this);
        et.setHint(hint);
        et.setInputType(inputType);
        parent.addView(et, matchWrap());
        return et;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** 下拉框位置 → 协议存储值；无效位置返回 null 供配置校验拒绝。 */
    @Nullable
    private static String protocolAt(int position) {
        if (position == 0) {
            return Config.PROTOCOL_OPENAI_RESPONSES;
        }
        if (position == 1) {
            return Config.PROTOCOL_OPENAI_COMPATIBLE;
        }
        if (position == 2) {
            return Config.PROTOCOL_ANTHROPIC_MESSAGES;
        }
        return null;
    }

    /** 协议存储值 → 下拉框位置；未知值返回无选中位置。 */
    private static int positionOf(String protocol) {
        if (Config.PROTOCOL_OPENAI_RESPONSES.equals(protocol)) {
            return 0;
        }
        if (Config.PROTOCOL_OPENAI_COMPATIBLE.equals(protocol)) {
            return 1;
        }
        if (Config.PROTOCOL_ANTHROPIC_MESSAGES.equals(protocol)) {
            return 2;
        }
        return AdapterView.INVALID_POSITION;
    }

    private void installDirtyTracking() {
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                markDirty();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        };
        urlInput.addTextChangedListener(watcher);
        keyInput.addTextChangedListener(watcher);
        modelInput.addTextChangedListener(watcher);
        headersInput.addTextChangedListener(watcher);
        systemPromptInput.addTextChangedListener(watcher);
        timeoutInput.addTextChangedListener(watcher);
        concurrencyInput.addTextChangedListener(watcher);
        testTextInput.addTextChangedListener(watcher);
        enabledInput.setOnCheckedChangeListener((button, checked) -> markDirty());
        streamingInput.setOnCheckedChangeListener((button, checked) -> markDirty());
    }

    private void markDirty() {
        if (!loadingUi) {
            hasUnsavedChanges = true;
        }
    }

    private void markSpinnerDirty() {
        if (loadingUi) {
            return;
        }
        String protocol = protocolAt(protocolSpinner.getSelectedItemPosition());
        String targetLanguage = selectedTargetLanguage();
        if (!sameValue(protocol, baselineProtocol)
                || !sameValue(targetLanguage, baselineTargetLanguage)) {
            hasUnsavedChanges = true;
        }
    }

    private static boolean sameValue(@Nullable String left, @Nullable String right) {
        return left == null ? right == null : left.equals(right);
    }

    private void updateStreamingAvailability() {
        if (streamingInput != null && protocolSpinner != null) {
            int position = protocolSpinner.getSelectedItemPosition();
            boolean hasSelection = position >= 0 && position < 3;
            streamingInput.setEnabled(hasSelection && Protocols.supportsStreaming(
                    protocolAt(position)));
        }
    }

    /** 显示旧配置中的列表外语言，避免升级后静默改写用户设置。 */
    private void ensureTargetLanguageAvailable(String language) {
        if (language == null || targetLanguageAdapter == null
                || targetLanguageValues.contains(language)) {
            return;
        }
        targetLanguageValues.add(language);
        targetLanguageAdapter.add(getString(R.string.legacy_language));
    }

    private int targetLanguagePosition(String language) {
        return targetLanguageValues.indexOf(language);
    }

    @Nullable
    private String selectedTargetLanguage() {
        int position = targetLanguageSpinner.getSelectedItemPosition();
        return position >= 0 && position < targetLanguageValues.size()
                ? targetLanguageValues.get(position) : null;
    }

    private boolean isChineseSystemLanguage() {
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        return "zh".equalsIgnoreCase(locale.getLanguage());
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------ 读写

    private void loadIntoUi() {
        SharedPreferences currentPrefs = prefs;
        if (currentPrefs == null) {
            return;
        }
        loadingUi = true;
        try {
            Config.Snapshot snapshot = Config.read(currentPrefs);
            if (snapshot.configError != null) {
                updateStatus(getString(R.string.status_invalid_config));
            }
            enabledInput.setChecked(snapshot.enabled);
            protocolSpinner.setSelection(positionOf(snapshot.protocol));
            streamingInput.setChecked(snapshot.streaming);
            updateStreamingAvailability();
            setTextOrPlaceholder(urlInput, snapshot.apiUrl, Config.DEF_API_URL);
            setTextOrPlaceholder(keyInput, snapshot.apiKey, Config.DEF_API_KEY);
            setTextOrPlaceholder(modelInput, snapshot.model, Config.DEF_MODEL);
            ensureTargetLanguageAvailable(snapshot.targetLang);
            targetLanguageSpinner.setSelection(targetLanguagePosition(snapshot.targetLang));
            setTextOrPlaceholder(headersInput, snapshot.extraHeaders, Config.DEF_EXTRA_HEADERS);
            setTextOrPlaceholder(systemPromptInput, snapshot.systemPrompt, Config.DEF_SYSTEM_PROMPT);
            setTextOrPlaceholder(timeoutInput, String.valueOf(snapshot.timeoutMs),
                    String.valueOf(Config.DEF_TIMEOUT_MS));
            setTextOrPlaceholder(concurrencyInput, String.valueOf(snapshot.maxConcurrency),
                    String.valueOf(Config.DEF_MAX_CONCURRENCY));
            baselineProtocol = snapshot.protocol;
            baselineTargetLanguage = snapshot.targetLang;
            hasUnsavedChanges = false;
        } finally {
            loadingUi = false;
        }
    }

    private void save() {
        if (destroyed || prefs == null) {
            updateStatus(getString(R.string.status_save_persist_failed));
            return;
        }
        Config.Snapshot snapshot = snapshotFromUi();
        if (snapshot.configError != null) {
            updateStatus(getString(R.string.status_invalid_config));
            return;
        }
        commitSnapshot(snapshot);
    }

    private void commitSnapshot(Config.Snapshot snapshot) {
        final SharedPreferences local = prefs;
        final SharedPreferences remote = remotePrefs;
        if (destroyed || local == null) {
            updateStatus(getString(R.string.status_save_persist_failed));
            return;
        }

        final SharedPreferences.Editor editor;
        try {
            editor = local.edit();
            writeSnapshot(editor, snapshot);
            editor.putBoolean(KEY_LOCAL_INITIALIZED, true);
        } catch (Throwable t) {
            updateStatus(getString(R.string.status_save_failed,
                    t.getClass().getSimpleName()));
            return;
        }

        saveInProgress = true;
        updateSaveButtonState();
        updateStatus(getString(R.string.status_saving));
        try {
            saveExecutor.execute(() -> {
                boolean localCommitted = false;
                boolean remoteCommitted = false;
                Throwable failure = null;
                try {
                    localCommitted = editor.commit();
                    if (localCommitted && remote != null) {
                        remoteCommitted = copyConfigPreferences(local, remote, false);
                    }
                } catch (Throwable t) {
                    failure = t;
                }
                final boolean localResult = localCommitted;
                final boolean remoteResult = remoteCommitted;
                final Throwable saveFailure = failure;
                mainHandler.post(() -> {
                    if (destroyed || local != prefs) {
                        return;
                    }
                    saveInProgress = false;
                    updateSaveButtonState();
                    if (saveFailure != null && !localResult) {
                        updateStatus(getString(R.string.status_save_failed,
                                saveFailure.getClass().getSimpleName()));
                    } else if (!localResult) {
                        updateStatus(getString(R.string.status_save_persist_failed));
                    } else {
                        toast(getString(R.string.toast_saved));
                        updateStatus(getString(remoteResult
                                ? R.string.status_saved_restart : R.string.status_sync_pending));
                    }
                });
            });
        } catch (Throwable t) {
            saveInProgress = false;
            updateSaveButtonState();
            updateStatus(getString(R.string.status_save_failed,
                    t.getClass().getSimpleName()));
        }
    }

    private static void writeSnapshot(SharedPreferences.Editor editor,
                                     Config.Snapshot snapshot) {
        editor.putBoolean(Config.KEY_ENABLED, snapshot.enabled);
        editor.putString(Config.KEY_PROTOCOL, snapshot.protocol);
        editor.putBoolean(Config.KEY_STREAMING, snapshot.streaming);
        putUnlessDefault(editor, Config.KEY_API_URL, snapshot.apiUrl, Config.DEF_API_URL);
        putUnlessDefault(editor, Config.KEY_API_KEY, snapshot.apiKey, Config.DEF_API_KEY);
        putUnlessDefault(editor, Config.KEY_MODEL, snapshot.model, Config.DEF_MODEL);
        putUnlessDefault(editor, Config.KEY_TARGET_LANG, snapshot.targetLang,
                Config.DEF_TARGET_LANG);
        putUnlessDefault(editor, Config.KEY_EXTRA_HEADERS, snapshot.extraHeaders,
                Config.DEF_EXTRA_HEADERS);
        putUnlessDefault(editor, Config.KEY_SYSTEM_PROMPT, snapshot.systemPrompt,
                Config.DEF_SYSTEM_PROMPT);
        putUnlessDefault(editor, Config.KEY_TIMEOUT_MS, snapshot.timeoutMs,
                Config.DEF_TIMEOUT_MS);
        putUnlessDefault(editor, Config.KEY_MAX_CONCURRENCY, snapshot.maxConcurrency,
                Config.DEF_MAX_CONCURRENCY);
    }

    private void updateSaveButtonState() {
        if (saveButton != null) {
            saveButton.setEnabled(!destroyed && prefs != null
                    && !saveInProgress && !syncInProgress);
        }
    }

    private static Object parseIntInput(EditText editText, int defaultValue) {
        String value = editText.getText().toString().trim();
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private static String textOrDefault(EditText editText, String defaultValue) {
        String value = editText.getText().toString().trim();
        return value.isEmpty() ? defaultValue : value;
    }

    /** 值等于默认值时清空输入框，灰色占位符（hint）展示默认值；自定义值才写入。 */
    private static void setTextOrPlaceholder(EditText field, String value, String def) {
        field.setText(def.equals(value) ? "" : value);
    }

    private static void putUnlessDefault(SharedPreferences.Editor editor, String key,
                                         String value, String def) {
        if (def.equals(value)) {
            editor.remove(key);
        } else {
            editor.putString(key, value);
        }
    }

    private static void putUnlessDefault(SharedPreferences.Editor editor, String key,
                                         int value, int def) {
        if (value == def) {
            editor.remove(key);
        } else {
            editor.putInt(key, value);
        }
    }

    private static boolean copyConfigPreferences(SharedPreferences source,
                                                SharedPreferences target,
                                                boolean markInitialized) {
        Map<String, ?> values = source.getAll();
        SharedPreferences.Editor editor = target.edit();
        for (String key : CONFIG_KEYS) {
            if (!values.containsKey(key)) {
                editor.remove(key);
                continue;
            }
            putPreferenceValue(editor, key, values.get(key));
        }
        if (markInitialized) {
            editor.putBoolean(KEY_LOCAL_INITIALIZED, true);
        }
        return editor.commit();
    }

    private static void putPreferenceValue(SharedPreferences.Editor editor,
                                           String key, Object value) {
        if (value instanceof String) {
            editor.putString(key, (String) value);
        } else if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof Integer) {
            editor.putInt(key, (Integer) value);
        } else if (value instanceof Long) {
            editor.putLong(key, (Long) value);
        } else if (value instanceof Float) {
            editor.putFloat(key, (Float) value);
        } else {
            throw new IllegalArgumentException("Unsupported preference type");
        }
    }

    private static String promptOrDefault(EditText editText, String defaultValue) {
        String value = editText.getText().toString();
        return value.trim().isEmpty() ? defaultValue : value;
    }


    private Config.Snapshot snapshotFromUi() {
        MemoryPrefs memoryPrefs = new MemoryPrefs();
        memoryPrefs.put(Config.KEY_ENABLED, enabledInput.isChecked());
        memoryPrefs.put(Config.KEY_PROTOCOL,
                protocolAt(protocolSpinner.getSelectedItemPosition()));
        memoryPrefs.put(Config.KEY_STREAMING, streamingInput.isChecked());
        memoryPrefs.put(Config.KEY_API_URL, textOrDefault(urlInput, Config.DEF_API_URL));
        memoryPrefs.put(Config.KEY_API_KEY, keyInput.getText().toString().trim());
        memoryPrefs.put(Config.KEY_MODEL, textOrDefault(modelInput, Config.DEF_MODEL));
        memoryPrefs.put(Config.KEY_TARGET_LANG, selectedTargetLanguage());
        memoryPrefs.put(Config.KEY_EXTRA_HEADERS, headersInput.getText().toString());
        memoryPrefs.put(Config.KEY_SYSTEM_PROMPT,
                promptOrDefault(systemPromptInput, Config.DEF_SYSTEM_PROMPT));
        memoryPrefs.put(Config.KEY_TIMEOUT_MS, parseIntInput(timeoutInput, Config.DEF_TIMEOUT_MS));
        memoryPrefs.put(Config.KEY_MAX_CONCURRENCY,
                parseIntInput(concurrencyInput, Config.DEF_MAX_CONCURRENCY));
        return Config.read(memoryPrefs);
    }

    /** 离线配置检查：不发网络请求。 */
    private void runSelfCheck() {
        showResult(ApiTester.selfCheck(snapshotFromUi()));
    }

    /**
     * 真实请求测试，使用本页面独立的单任务 executor。
     *
     * @param connectionTest true=使用固定测试文本，false=使用测试文本输入框
     */
    private void runTestTranslation(boolean connectionTest) {
        if (destroyed || testFuture != null) {
            return;
        }
        Config.Snapshot snapshot = snapshotFromUi();
        final String text = connectionTest
                ? ApiTester.DEFAULT_TEST_TEXT : testTextInput.getText().toString();
        if (!connectionTest && text.trim().isEmpty()) {
            resultText.setText(R.string.status_enter_test_text);
            return;
        }

        final long generation = ++testGeneration;
        resultText.setText(R.string.status_requesting);
        setTestButtonsEnabled(false);

        FutureTask<ApiTester.Result> task = new FutureTask<ApiTester.Result>(() -> connectionTest
                ? ApiTester.testConnection(snapshot)
                : ApiTester.testTranslation(snapshot, text)) {
            @Override
            protected void done() {
                final ApiTester.Result result;
                final Throwable failure;
                try {
                    result = get();
                    failure = null;
                } catch (CancellationException e) {
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    mainHandler.post(() -> finishTest(generation, this, null, e));
                    return;
                } catch (ExecutionException e) {
                    mainHandler.post(() -> finishTest(generation, this, null,
                            e.getCause() == null ? e : e.getCause()));
                    return;
                }
                mainHandler.post(() -> finishTest(generation, this, result, failure));
            }
        };
        testFuture = task;
        try {
            testExecutor.execute(task);
        } catch (Throwable t) {
            testFuture = null;
            setTestButtonsEnabled(true);
            resultText.setText(getString(R.string.status_test_not_started,
                    t.getClass().getSimpleName()));
        }
    }

    private void finishTest(long generation, Future<?> task,
                            @Nullable ApiTester.Result result, @Nullable Throwable failure) {
        if (destroyed || generation != testGeneration || testFuture != task) {
            return;
        }
        testFuture = null;
        setTestButtonsEnabled(true);
        if (result != null) {
            showResult(result);
        } else {
            resultText.setText(getString(R.string.status_test_failed_detail,
                    failure == null ? getString(R.string.unknown_error)
                            : failure.getClass().getSimpleName()));
            updateStatus(getString(R.string.status_test_failed));
        }
    }

    private void cancelPendingTest() {
        testGeneration++;
        Future<?> pending = testFuture;
        testFuture = null;
        if (pending != null) {
            // Future 取消会发出中断请求；不假定网络实现一定能因此中断 HTTP I/O。
            pending.cancel(true);
        }
        setTestButtonsEnabled(true);
    }

    private void setTestButtonsEnabled(boolean enabled) {
        if (configCheckButton != null) {
            configCheckButton.setEnabled(enabled);
        }
        if (testConnectionButton != null) {
            testConnectionButton.setEnabled(enabled);
        }
        if (testTranslationButton != null) {
            testTranslationButton.setEnabled(enabled);
        }
    }

    private void showResult(ApiTester.Result result) {
        StringBuilder message = new StringBuilder();
        if (result.ok) {
            message.append(getString(R.string.result_success, result.elapsedMs));
        } else {
            message.append(getString(R.string.result_failure));
        }
        if (result.translated != null) {
            message.append(getString(R.string.result_translation, result.translated));
        }
        resultText.setText(message.toString());
        updateStatus(getString(result.ok
                ? R.string.status_test_passed : R.string.status_test_failed));
    }

    private void updateStatus(String status) {
        if (!destroyed && statusText != null) {
            statusText.setText(status);
        }
    }

    private void toast(String message) {
        if (!destroyed) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
    }
}
