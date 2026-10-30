package io.github.wxmyyds.discordautotranslate.ui;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
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

import io.github.wxmyyds.discordautotranslate.core.Config;
import io.github.wxmyyds.discordautotranslate.core.MemoryPrefs;
import io.github.wxmyyds.discordautotranslate.translate.ApiTester;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import io.github.libxposed.service.XposedService;

/** 设置界面（需求 §5.7）。 */
public class SettingsActivity extends Activity implements XposedServiceConnectionHolder.Listener {

    /** 下拉框显示顺序：0=OpenAI-Responses，1=OpenAI-Compatible，2=Anthropic-Messages。 */
    private static final String[] PROTOCOL_ENTRIES =
            {"OpenAI-Responses", "OpenAI-Compatible", "Anthropic-Messages"};

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService testExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor();

    @Nullable
    private XposedService service;
    @Nullable
    private SharedPreferences prefs;
    @Nullable
    private Future<?> testFuture;

    private long testGeneration;
    private long serviceGeneration;
    private boolean saveInProgress;
    private boolean destroyed;

    private Spinner protocolSpinner;
    private EditText urlInput;
    private EditText keyInput;
    private EditText modelInput;
    private EditText targetLangInput;
    private EditText headersInput;
    private EditText systemPromptInput;
    private EditText timeoutInput;
    private EditText concurrencyInput;
    private CheckBox enabledInput;
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
        setTitle("Discord 自动翻译");
        setContentView(buildContentView());
        updateStatus("正在连接 LSPosed 服务…");
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
        if (destroyed) {
            return;
        }
        service = connectedService;
        serviceGeneration++;
        saveInProgress = false;
        try {
            prefs = connectedService.getRemotePreferences(Config.GROUP);
            loadIntoUi();
            updateSaveButtonState();
            updateStatus("已连接 LSPosed " + connectedService.getFrameworkVersion()
                    + "（API " + connectedService.getApiVersion() + "）");
        } catch (Throwable t) {
            prefs = null;
            updateSaveButtonState();
            updateStatus("读取配置失败：" + t.getClass().getSimpleName());
        }
    }

    @Override
    public void onServiceDied(@NonNull XposedService deadService) {
        if (destroyed || service != deadService) {
            return;
        }
        service = null;
        prefs = null;
        serviceGeneration++;
        saveInProgress = false;
        updateSaveButtonState();
        updateStatus("LSPosed 服务已断开，配置无法保存。");
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
        enabledInput.setText("启用自动翻译");
        root.addView(enabledInput, matchWrap());

        // 供应商协议：请求体与译文解析按所选协议内置实现，
        // 不再让用户手写 JSON 请求模板与译文提取路径。
        TextView protocolLabel = new TextView(this);
        protocolLabel.setText("供应商协议");
        protocolLabel.setPadding(0, dp(8), 0, 0);
        root.addView(protocolLabel, matchWrap());
        protocolSpinner = new Spinner(this);
        ArrayAdapter<String> protocolAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, PROTOCOL_ENTRIES);
        protocolAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        protocolSpinner.setAdapter(protocolAdapter);
        protocolSpinner.setSelection(positionOf(Config.DEF_PROTOCOL));
        root.addView(protocolSpinner, matchWrap());
        TextView protocolHint = new TextView(this);
        protocolHint.setTextSize(12f);
        protocolHint.setText("API URL 只填到 /v1 的基础地址，请求时按所选协议追加端点："
                + "OpenAI-Compatible 追加 /chat/completions，OpenAI-Responses 追加 /responses，"
                + "Anthropic-Messages 追加 /messages，切换协议无需修改 URL。"
                + "旧配置里填写的完整端点需删掉后缀只留基础地址。");
        root.addView(protocolHint, matchWrap());

        // 注意：TYPE_TEXT_VARIATION_* 必须与 TYPE_CLASS_TEXT 组合使用。
        urlInput = addField(root, "API URL", Config.DEF_API_URL,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        keyInput = addField(root, "API 密钥（仅保存在本机）", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        modelInput = addField(root, "模型名", Config.DEF_MODEL, InputType.TYPE_CLASS_TEXT);
        targetLangInput = addField(root, "目标语言", Config.DEF_TARGET_LANG, InputType.TYPE_CLASS_TEXT);
        headersInput = addField(root, "额外请求头（每行 Name: Value）", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        systemPromptInput = addField(root, "系统提示词（多行）",
                Config.DEF_SYSTEM_PROMPT,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        timeoutInput = addField(root, "超时（毫秒）", String.valueOf(Config.DEF_TIMEOUT_MS),
                InputType.TYPE_CLASS_NUMBER);
        concurrencyInput = addField(root, "最大并发", String.valueOf(Config.DEF_MAX_CONCURRENCY),
                InputType.TYPE_CLASS_NUMBER);


        testTextInput = addField(root, "测试文本（“测试翻译”使用）",
                ApiTester.DEFAULT_TEST_TEXT,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);

        TextView privacy = new TextView(this);
        privacy.setTextSize(12f);
        privacy.setText("隐私提示：启用后，消息正文会发送到上面配置的第三方翻译服务。"
                + "密钥仅保存在本机，不会写入日志。");
        root.addView(privacy, matchWrap());

        saveButton = new Button(this);
        saveButton.setText("保存");
        saveButton.setEnabled(false);
        saveButton.setOnClickListener(v -> save());
        root.addView(saveButton, matchWrap());

        LinearLayout testRow = new LinearLayout(this);
        testRow.setOrientation(LinearLayout.HORIZONTAL);

        configCheckButton = new Button(this);
        configCheckButton.setText("配置检查");
        configCheckButton.setOnClickListener(v -> runSelfCheck());
        testRow.addView(configCheckButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        testConnectionButton = new Button(this);
        testConnectionButton.setText("测试连接");
        testConnectionButton.setOnClickListener(v -> runTestTranslation(true));
        testRow.addView(testConnectionButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        testTranslationButton = new Button(this);
        testTranslationButton.setText("测试翻译");
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

    /** 下拉框位置 → 协议存储值。未知位置回落默认协议。 */
    private static String protocolAt(int position) {
        if (position == 0) {
            return Config.PROTOCOL_OPENAI_RESPONSES;
        }
        if (position == 2) {
            return Config.PROTOCOL_ANTHROPIC_MESSAGES;
        }
        return Config.PROTOCOL_OPENAI_COMPATIBLE;
    }

    /** 协议存储值 → 下拉框位置；未知值按默认协议处理。 */
    private static int positionOf(String protocol) {
        if (Config.PROTOCOL_OPENAI_RESPONSES.equals(protocol)) {
            return 0;
        }
        if (Config.PROTOCOL_ANTHROPIC_MESSAGES.equals(protocol)) {
            return 2;
        }
        return 1;
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
        Config.Snapshot snapshot = Config.read(currentPrefs);
        enabledInput.setChecked(snapshot.enabled);
        protocolSpinner.setSelection(positionOf(snapshot.protocol));
        // 等于默认值的填空项留空，让灰色占位符（hint 即默认值）展示默认值，
        // 不把默认值填成实际文本。
        setTextOrPlaceholder(urlInput, snapshot.apiUrl, Config.DEF_API_URL);
        setTextOrPlaceholder(keyInput, snapshot.apiKey, Config.DEF_API_KEY);
        setTextOrPlaceholder(modelInput, snapshot.model, Config.DEF_MODEL);
        setTextOrPlaceholder(targetLangInput, snapshot.targetLang, Config.DEF_TARGET_LANG);
        setTextOrPlaceholder(headersInput, snapshot.extraHeaders, Config.DEF_EXTRA_HEADERS);
        setTextOrPlaceholder(systemPromptInput, snapshot.systemPrompt, Config.DEF_SYSTEM_PROMPT);
        setTextOrPlaceholder(timeoutInput, String.valueOf(snapshot.timeoutMs),
                String.valueOf(Config.DEF_TIMEOUT_MS));
        setTextOrPlaceholder(concurrencyInput, String.valueOf(snapshot.maxConcurrency),
                String.valueOf(Config.DEF_MAX_CONCURRENCY));
    }

    private void save() {
        if (destroyed || prefs == null || service == null) {
            updateStatus("未连接 LSPosed 服务，无法保存。请确认模块已在 LSPosed 中启用并勾选作用域。");
            return;
        }
        commitSnapshot(snapshotFromUi());
    }

    private void commitSnapshot(Config.Snapshot snapshot) {
        final SharedPreferences targetPrefs = prefs;
        final XposedService targetService = service;
        final long generation = serviceGeneration;
        if (destroyed || targetPrefs == null || targetService == null) {
            updateStatus("LSPosed 服务已断开，配置无法保存。");
            return;
        }

        final SharedPreferences.Editor editor;
        try {
            editor = targetPrefs.edit();
            editor.putBoolean(Config.KEY_ENABLED, snapshot.enabled);
            editor.putString(Config.KEY_PROTOCOL, snapshot.protocol);
            // 等于默认值的填空项删除已存键而不是写入：默认值不固化为本机内容，
            // 之后模块调整默认值时未自定义的配置自动跟随，清空输入框再保存
            // 也能回到默认值。
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
        } catch (Throwable t) {
            updateStatus("保存失败：" + t.getClass().getSimpleName());
            return;
        }

        saveInProgress = true;
        updateSaveButtonState();
        updateStatus("正在保存配置…");
        try {
            saveExecutor.execute(() -> {
                boolean committed = false;
                Throwable failure = null;
                try {
                    committed = editor.commit();
                } catch (Throwable t) {
                    failure = t;
                }
                final boolean commitResult = committed;
                final Throwable commitFailure = failure;
                mainHandler.post(() -> {
                    if (destroyed || generation != serviceGeneration
                            || targetPrefs != prefs || targetService != service) {
                        return;
                    }
                    saveInProgress = false;
                    updateSaveButtonState();
                    if (commitFailure != null) {
                        updateStatus("保存失败：" + commitFailure.getClass().getSimpleName());
                    } else if (!commitResult) {
                        updateStatus("保存失败：配置未能持久化。");
                    } else {
                        toast("配置已保存。");
                        // 目标进程不保证会收到跨进程变更通知；不声称已即时生效。
                        updateStatus("配置已保存；若 Discord 正在运行，新配置可能需要重启 Discord 后才生效。");
                    }
                });
            });
        } catch (Throwable t) {
            saveInProgress = false;
            updateSaveButtonState();
            updateStatus("保存失败：" + t.getClass().getSimpleName());
        }
    }

    private void updateSaveButtonState() {
        if (saveButton != null) {
            saveButton.setEnabled(!destroyed && service != null && prefs != null && !saveInProgress);
        }
    }

    private static int parseInt(EditText editText, int defaultValue) {
        try {
            return Integer.parseInt(editText.getText().toString().trim());
        } catch (Throwable t) {
            return defaultValue;
        }
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

    // ------------------------------------------------------------ 测试功能

    /** 用界面当前值构造快照，配置检查、网络测试和保存共用同一套字段读取。 */
    private Config.Snapshot snapshotFromUi() {
        MemoryPrefs memoryPrefs = new MemoryPrefs();
        memoryPrefs.put(Config.KEY_ENABLED, enabledInput.isChecked());
        memoryPrefs.put(Config.KEY_PROTOCOL,
                protocolAt(protocolSpinner.getSelectedItemPosition()));
        memoryPrefs.put(Config.KEY_API_URL, urlInput.getText().toString().trim());
        memoryPrefs.put(Config.KEY_API_KEY, keyInput.getText().toString().trim());
        memoryPrefs.put(Config.KEY_MODEL, modelInput.getText().toString().trim());
        memoryPrefs.put(Config.KEY_TARGET_LANG, targetLangInput.getText().toString().trim());
        memoryPrefs.put(Config.KEY_EXTRA_HEADERS, headersInput.getText().toString());
        memoryPrefs.put(Config.KEY_SYSTEM_PROMPT, systemPromptInput.getText().toString());
        memoryPrefs.put(Config.KEY_TIMEOUT_MS, parseInt(timeoutInput, Config.DEF_TIMEOUT_MS));
        memoryPrefs.put(Config.KEY_MAX_CONCURRENCY,
                parseInt(concurrencyInput, Config.DEF_MAX_CONCURRENCY));
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
            resultText.setText("请先输入测试文本。");
            return;
        }

        final long generation = ++testGeneration;
        resultText.setText("请求中…");
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
            resultText.setText("测试未能启动：" + t.getClass().getSimpleName());
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
            resultText.setText("测试失败："
                    + (failure == null ? "未知错误" : failure.getClass().getSimpleName()));
            updateStatus("测试失败，详见下方结果。");
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
        message.append(result.ok ? "✓ " : "✗ ").append(result.message);
        if (result.translated != null) {
            message.append("\n译文：").append(result.translated);
        }
        resultText.setText(message.toString());
        updateStatus(result.ok ? "测试通过。" : "测试失败，详见下方结果。");
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
