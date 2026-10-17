package com.wxmyyds.discordautotranslate.ui;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.wxmyyds.discordautotranslate.core.Config;
import com.wxmyyds.discordautotranslate.core.MemoryPrefs;
import com.wxmyyds.discordautotranslate.translate.ApiTester;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 设置界面（需求 §5.7）。
 *
 * <p>为什么是独立 Activity 而不是 Discord 内设置项：Discord 340.13 的设置界面由
 * React Native/Hermes 渲染（{@code MainActivity extends ReactActivity}，APK 内没有任何
 * 原生 Settings Activity）。在不注入或改写 JS bundle 的前提下无法稳定插入设置项，
 * 因此按需求 §5.7 的备用方案采用独立设置 Activity。</p>
 *
 * <p>配置存储走 libxposed 的 remote preferences：本 Activity 通过
 * {@link XposedService#getRemotePreferences(String)} 读写，目标进程内的模块通过
 * {@code XposedInterface.getRemotePreferences(String)} 读取同一份数据。两侧使用相同的
 * {@link Config#GROUP}。</p>
 */
public class SettingsActivity extends Activity implements XposedServiceHelper.OnServiceListener {

    @Nullable
    private XposedService service;
    @Nullable
    private SharedPreferences prefs;

    private EditText urlInput;
    private EditText keyInput;
    private EditText modelInput;
    private EditText targetLangInput;
    private EditText headersInput;
    private EditText bodyTemplateInput;
    private EditText responsePathInput;
    private EditText timeoutInput;
    private EditText concurrencyInput;
    private CheckBox enabledInput;
    private CheckBox verboseInput;
    private TextView statusText;
    private TextView resultText;
    private EditText testTextInput;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Discord 自动翻译");
        setContentView(buildContentView());

        // 注册 libxposed 服务监听；服务绑定前界面只读且提示未连接。
        XposedServiceHelper.registerListener(this);
        updateStatus("正在连接 LSPosed 服务…");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    // ------------------------------------------------------------ 服务回调

    @Override
    public void onServiceBind(@NonNull XposedService s) {
        this.service = s;
        try {
            this.prefs = s.getRemotePreferences(Config.GROUP);
        } catch (Throwable t) {
            this.prefs = null;
            updateStatus("读取配置失败：" + t.getClass().getSimpleName());
            return;
        }
        runOnUiThread(() -> {
            loadIntoUi();
            updateStatus("已连接 LSPosed " + s.getFrameworkVersion()
                    + "（API " + s.getApiVersion() + "）");
        });
    }

    @Override
    public void onServiceDied(@NonNull XposedService s) {
        this.service = null;
        runOnUiThread(() -> updateStatus("LSPosed 服务已断开，配置无法保存。"));
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

        urlInput = addField(root, "API URL", Config.DEF_API_URL, InputType.TYPE_TEXT_VARIATION_URI);
        keyInput = addField(root, "API 密钥（仅保存在本机）", "", InputType.TYPE_TEXT_VARIATION_PASSWORD);
        modelInput = addField(root, "模型名", Config.DEF_MODEL, InputType.TYPE_CLASS_TEXT);
        targetLangInput = addField(root, "目标语言", Config.DEF_TARGET_LANG, InputType.TYPE_CLASS_TEXT);
        headersInput = addField(root, "额外请求头（每行 Name: Value）", "", InputType.TYPE_CLASS_TEXT);
        bodyTemplateInput = addField(root, "JSON 请求模板（{{text}} / {{target}} / {{model}}）",
                Config.DEF_BODY_TEMPLATE, InputType.TYPE_CLASS_TEXT);
        responsePathInput = addField(root, "响应译文提取路径", Config.DEF_RESPONSE_PATH, InputType.TYPE_CLASS_TEXT);
        timeoutInput = addField(root, "超时（毫秒）", String.valueOf(Config.DEF_TIMEOUT_MS),
                InputType.TYPE_CLASS_NUMBER);
        concurrencyInput = addField(root, "最大并发", String.valueOf(Config.DEF_MAX_CONCURRENCY),
                InputType.TYPE_CLASS_NUMBER);

        verboseInput = new CheckBox(this);
        verboseInput.setText("详细日志（会记录消息正文，仅排障时开启）");
        root.addView(verboseInput, matchWrap());

        testTextInput = addField(root, "测试文本（“测试翻译”使用）",
                ApiTester.DEFAULT_TEST_TEXT, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);

        TextView privacy = new TextView(this);
        privacy.setTextSize(12f);
        privacy.setText("隐私提示：启用后，消息正文会发送到上面配置的第三方翻译服务。"
                + "密钥仅保存在本机，不会写入日志。");
        root.addView(privacy, matchWrap());

        Button save = new Button(this);
        save.setText("保存");
        save.setOnClickListener(v -> save());
        root.addView(save, matchWrap());

        // 测试按钮（需求 §3.1、§5.5）：用界面当前填写值而非已保存值进行测试，
        // 方便在保存前先验证配置是否正确。
        LinearLayout testRow = new LinearLayout(this);
        testRow.setOrientation(LinearLayout.HORIZONTAL);

        Button check = new Button(this);
        check.setText("配置检查");
        check.setOnClickListener(v -> runSelfCheck());
        testRow.addView(check, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button testConn = new Button(this);
        testConn.setText("测试连接");
        testConn.setOnClickListener(v -> runTestTranslation(true));
        testRow.addView(testConn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button testTranslate = new Button(this);
        testTranslate.setText("测试翻译");
        testTranslate.setOnClickListener(v -> runTestTranslation(false));
        testRow.addView(testTranslate, new LinearLayout.LayoutParams(0,
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

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------ 读写

    private void loadIntoUi() {
        SharedPreferences p = prefs;
        if (p == null) {
            return;
        }
        Config.Snapshot s = Config.read(p);
        enabledInput.setChecked(s.enabled);
        urlInput.setText(s.apiUrl);
        keyInput.setText(s.apiKey);
        modelInput.setText(s.model);
        targetLangInput.setText(s.targetLang);
        headersInput.setText(s.extraHeaders);
        bodyTemplateInput.setText(s.bodyTemplate);
        responsePathInput.setText(s.responsePath);
        timeoutInput.setText(String.valueOf(s.timeoutMs));
        concurrencyInput.setText(String.valueOf(s.maxConcurrency));
        verboseInput.setChecked(s.verboseLog);
    }

    private void save() {
        SharedPreferences p = prefs;
        if (p == null) {
            updateStatus("未连接 LSPosed 服务，无法保存。请确认模块已在 LSPosed 中启用并勾选作用域。");
            return;
        }
        try {
            SharedPreferences.Editor ed = p.edit();
            ed.putBoolean(Config.KEY_ENABLED, enabledInput.isChecked());
            ed.putString(Config.KEY_API_URL, urlInput.getText().toString().trim());
            ed.putString(Config.KEY_API_KEY, keyInput.getText().toString().trim());
            ed.putString(Config.KEY_MODEL, modelInput.getText().toString().trim());
            ed.putString(Config.KEY_TARGET_LANG, targetLangInput.getText().toString().trim());
            ed.putString(Config.KEY_EXTRA_HEADERS, headersInput.getText().toString());
            ed.putString(Config.KEY_BODY_TEMPLATE, bodyTemplateInput.getText().toString());
            ed.putString(Config.KEY_RESPONSE_PATH, responsePathInput.getText().toString().trim());
            ed.putInt(Config.KEY_TIMEOUT_MS, parseInt(timeoutInput, Config.DEF_TIMEOUT_MS));
            ed.putInt(Config.KEY_MAX_CONCURRENCY, parseInt(concurrencyInput, Config.DEF_MAX_CONCURRENCY));
            ed.putBoolean(Config.KEY_VERBOSE_LOG, verboseInput.isChecked());
            ed.apply();
            toast("已保存。新消息将按新配置翻译。");
            updateStatus("配置已保存。");
        } catch (Throwable t) {
            updateStatus("保存失败：" + t.getClass().getSimpleName());
        }
    }

    private static int parseInt(EditText et, int def) {
        try {
            return Integer.parseInt(et.getText().toString().trim());
        } catch (Throwable t) {
            return def;
        }
    }

    // ------------------------------------------------------------ 测试功能

    /** 用界面当前值构造配置快照（不依赖已保存值，也不发起写入）。 */
    private Config.Snapshot snapshotFromUi() {
        MemoryPrefs p = new MemoryPrefs();
        p.put(Config.KEY_ENABLED, enabledInput.isChecked());
        p.put(Config.KEY_API_URL, urlInput.getText().toString().trim());
        p.put(Config.KEY_API_KEY, keyInput.getText().toString().trim());
        p.put(Config.KEY_MODEL, modelInput.getText().toString().trim());
        p.put(Config.KEY_TARGET_LANG, targetLangInput.getText().toString().trim());
        p.put(Config.KEY_EXTRA_HEADERS, headersInput.getText().toString());
        p.put(Config.KEY_BODY_TEMPLATE, bodyTemplateInput.getText().toString());
        p.put(Config.KEY_RESPONSE_PATH, responsePathInput.getText().toString().trim());
        p.put(Config.KEY_TIMEOUT_MS, parseInt(timeoutInput, Config.DEF_TIMEOUT_MS));
        p.put(Config.KEY_MAX_CONCURRENCY, parseInt(concurrencyInput, Config.DEF_MAX_CONCURRENCY));
        p.put(Config.KEY_VERBOSE_LOG, verboseInput.isChecked());
        return Config.read(p);
    }

    /** 离线配置检查：不发网络请求。 */
    private void runSelfCheck() {
        showResult(ApiTester.selfCheck(snapshotFromUi()));
    }

    /**
     * 真实请求测试。
     *
     * @param connectionTest true=用固定测试文本（等价于连接测试），
     *                       false=用界面下方“测试文本”输入框的内容
     */
    private void runTestTranslation(boolean connectionTest) {
        Config.Snapshot cfg = snapshotFromUi();
        final String text = connectionTest
                ? ApiTester.DEFAULT_TEST_TEXT
                : testTextInput.getText().toString();

        if (!connectionTest && text.trim().isEmpty()) {
            resultText.setText("请先输入测试文本。");
            return;
        }
        resultText.setText("请求中…");
        // 网络请求不得阻塞 UI 线程。
        new Thread(() -> {
            final ApiTester.Result r = connectionTest
                    ? ApiTester.testConnection(cfg)
                    : ApiTester.testTranslation(cfg, text);
            runOnUiThread(() -> showResult(r));
        }, "dat-api-test").start();
    }

    private void showResult(ApiTester.Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.ok ? "✓ " : "✗ ").append(r.message);
        if (r.translated != null) {
            sb.append("\n译文：").append(r.translated);
        }
        resultText.setText(sb.toString());
        // 仅提示是否成功，不把密钥或正文写入状态行。
        updateStatus(r.ok ? "测试通过。" : "测试失败，详见下方结果。");
    }

    private void updateStatus(String s) {
        if (statusText != null) {
            statusText.setText(s);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
