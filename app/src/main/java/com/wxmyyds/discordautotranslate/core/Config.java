package com.wxmyyds.discordautotranslate.core;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

/**
 * 模块配置的键名、默认值与快照。
 *
 * <p>存储方式：使用 libxposed 的 remote preferences（{@code getRemotePreferences}）。
 * 目标进程侧通过 {@code XposedInterface.getRemotePreferences(group)} 读取；
 * 独立设置 Activity 侧通过 {@code XposedService.getRemotePreferences(group)} 读写。
 * 两侧必须使用同一个 {@link #GROUP}。</p>
 *
 * <p>需求 §5.6：API 密钥仅保存在本机、不写入日志、不硬编码。</p>
 */
public final class Config {

    /** 远程配置组名，模块侧与设置侧必须一致。 */
    public static final String GROUP = "settings";

    // ---- 总开关 ----
    public static final String KEY_ENABLED = "auto_translate_enabled";
    public static final boolean DEF_ENABLED = true;

    // ---- API 配置（默认适配 OpenAI 兼容 /chat/completions，见需求确认项）----
    public static final String KEY_API_URL = "api_url";
    public static final String DEF_API_URL = "https://api.openai.com/v1/chat/completions";

    public static final String KEY_API_KEY = "api_key";
    public static final String DEF_API_KEY = "";

    /** 模型名，OpenAI 兼容接口需要。 */
    public static final String KEY_MODEL = "api_model";
    public static final String DEF_MODEL = "gpt-4o-mini";

    /** 目标语言。 */
    public static final String KEY_TARGET_LANG = "target_lang";
    public static final String DEF_TARGET_LANG = "简体中文";

    /** 额外的请求头，格式：每行 {@code Name: Value}。 */
    public static final String KEY_EXTRA_HEADERS = "extra_headers";
    public static final String DEF_EXTRA_HEADERS = "";

    // ---- 通用 JSON 模板（需求 §5.5）----
    /** 请求体模板，{@code {{text}}} 与 {@code {{target}}} 为占位符。 */
    public static final String KEY_BODY_TEMPLATE = "body_template";
    public static final String DEF_BODY_TEMPLATE =
            "{\"model\":\"{{model}}\",\"messages\":[{\"role\":\"system\",\"content\":\"Translate the user's message into {{target}}. Output only the translation, without quotes or explanation.\"},{\"role\":\"user\",\"content\":\"{{text}}\"}],\"temperature\":0}";

    /** 响应中译文的提取路径，点号分隔，数组用下标，例如 {@code choices.0.message.content}。 */
    public static final String KEY_RESPONSE_PATH = "response_path";
    public static final String DEF_RESPONSE_PATH = "choices.0.message.content";

    // ---- 超时与并发 ----
    public static final String KEY_TIMEOUT_MS = "timeout_ms";
    public static final int DEF_TIMEOUT_MS = 15000;

    public static final String KEY_MAX_CONCURRENCY = "max_concurrency";
    public static final int DEF_MAX_CONCURRENCY = 4;

    public static final String KEY_VERBOSE_LOG = "verbose_log";
    public static final boolean DEF_VERBOSE_LOG = false;

    private Config() {
    }

    /** 配置快照，读取一次后供本次使用，避免渲染路径反复访问 SharedPreferences。 */
    public static class Snapshot {
        public final boolean enabled;
        public final String apiUrl;
        public final String apiKey;
        public final String model;
        public final String targetLang;
        public final String extraHeaders;
        public final String bodyTemplate;
        public final String responsePath;
        public final int timeoutMs;
        public final int maxConcurrency;
        public final boolean verboseLog;

        Snapshot(SharedPreferences p) {
            enabled = p.getBoolean(KEY_ENABLED, DEF_ENABLED);
            apiUrl = str(p, KEY_API_URL, DEF_API_URL);
            apiKey = str(p, KEY_API_KEY, DEF_API_KEY);
            model = str(p, KEY_MODEL, DEF_MODEL);
            targetLang = str(p, KEY_TARGET_LANG, DEF_TARGET_LANG);
            extraHeaders = str(p, KEY_EXTRA_HEADERS, DEF_EXTRA_HEADERS);
            bodyTemplate = str(p, KEY_BODY_TEMPLATE, DEF_BODY_TEMPLATE);
            responsePath = str(p, KEY_RESPONSE_PATH, DEF_RESPONSE_PATH);
            timeoutMs = p.getInt(KEY_TIMEOUT_MS, DEF_TIMEOUT_MS);
            maxConcurrency = p.getInt(KEY_MAX_CONCURRENCY, DEF_MAX_CONCURRENCY);
            verboseLog = p.getBoolean(KEY_VERBOSE_LOG, DEF_VERBOSE_LOG);
        }

        private static String str(SharedPreferences p, String key, String def) {
            String v = p.getString(key, def);
            return v == null ? def : v;
        }

        /**
         * 配置版本：影响翻译结果的配置项参与哈希。
         * 配置变化后旧译文不会与新配置的请求混淆（需求 §5.6）。
         */
        public String version() {
            return apiUrl + "|" + model + "|" + targetLang + "|" + responsePath + "|" + bodyTemplate;
        }

        /** 是否已具备发起请求的最小配置。 */
        public boolean isUsable() {
            return !apiUrl.isEmpty();
        }
    }

    /** 从 SharedPreferences 读取快照；prefs 为 null 时返回默认值快照。 */
    public static Snapshot read(@Nullable SharedPreferences prefs) {
        if (prefs == null) {
            return new Defaults();
        }
        try {
            return new Snapshot(prefs);
        } catch (Throwable t) {
            XLog.w("read config failed, fallback to defaults", t);
            return new Defaults();
        }
    }

    /** 无存储可用时的默认快照。 */
    private static final class Defaults extends Snapshot {
        Defaults() {
            super(new android.content.SharedPreferences() {
                @Override
                public java.util.Map<String, ?> getAll() {
                    return java.util.Collections.emptyMap();
                }

                @Nullable
                @Override
                public String getString(String key, @Nullable String defValue) {
                    return defValue;
                }

                @Nullable
                @Override
                public java.util.Set<String> getStringSet(String key, @Nullable java.util.Set<String> defValues) {
                    return defValues;
                }

                @Override
                public int getInt(String key, int defValue) {
                    return defValue;
                }

                @Override
                public long getLong(String key, long defValue) {
                    return defValue;
                }

                @Override
                public float getFloat(String key, float defValue) {
                    return defValue;
                }

                @Override
                public boolean getBoolean(String key, boolean defValue) {
                    return defValue;
                }

                @Override
                public boolean contains(String key) {
                    return false;
                }

                @Override
                public Editor edit() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
                }

                @Override
                public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
                }
            });
        }
    }
}
