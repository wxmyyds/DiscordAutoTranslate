package com.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 配置读取的回落规则。
 *
 * <p>核心约定：<b>未配置的项一律回落默认值</b>。
 * “未配置”包括：键不存在、值为 {@code null}、值为空白字符串、
 * 以及正整数项小于等于 0。</p>
 *
 * <p>为什么必须这样：设置页每个输入框在保存时都会写入，
 * 用户清空某一格再保存就会存下空字符串。若把空字符串当有效值，
 * 会出现“模型名为空 / 目标语言为空 / 响应路径为空”之类
 * 难查的静默失效。</p>
 */
public class ConfigTest {

    /** 空配置（什么都没存）应全部使用默认值。 */
    @Test
    public void emptyPrefsUseAllDefaults() {
        Config.Snapshot s = Config.read(new MemoryPrefs());
        assertFalse(s.verboseLog);
        assertEquals(Config.DEF_ENABLED, s.enabled);
        assertEquals(Config.DEF_API_URL, s.apiUrl);
        assertEquals(Config.DEF_MODEL, s.model);
        assertEquals(Config.DEF_TARGET_LANG, s.targetLang);
        assertEquals(Config.DEF_SYSTEM_PROMPT, s.systemPrompt);
        assertEquals(Config.DEF_BODY_TEMPLATE, s.bodyTemplate);
        assertEquals(Config.DEF_RESPONSE_PATH, s.responsePath);
        assertEquals(Config.DEF_TIMEOUT_MS, s.timeoutMs);
        assertEquals(Config.DEF_MAX_CONCURRENCY, s.maxConcurrency);
    }

    @Test
    public void wrongTypedPreferenceFallsBackPerField() {
        Config.Snapshot s = Config.read(new MemoryPrefs()
                .put(Config.KEY_API_URL, 17)
                .put(Config.KEY_ENABLED, "true")
                .put(Config.KEY_TIMEOUT_MS, "15000")
                .put(Config.KEY_MODEL, "kept-model")
                .put(Config.KEY_MAX_CONCURRENCY, 6));

        assertEquals(Config.DEF_API_URL, s.apiUrl);
        assertEquals(Config.DEF_ENABLED, s.enabled);
        assertEquals(Config.DEF_TIMEOUT_MS, s.timeoutMs);
        assertEquals("kept-model", s.model);
        assertEquals(6, s.maxConcurrency);
    }

    @Test
    public void versionEncodingHasUnambiguousFieldBoundaries() {
        MemoryPrefs first = new MemoryPrefs()
                .put(Config.KEY_API_URL, "a|b")
                .put(Config.KEY_MODEL, "c");
        MemoryPrefs second = new MemoryPrefs()
                .put(Config.KEY_API_URL, "a")
                .put(Config.KEY_MODEL, "b|c");
        assertNotEquals(Config.read(first).version(), Config.read(second).version());
    }

    /**
     * 密钥与额外请求头会改变服务端归属与响应内容，必须参与配置版本，
     * 否则凭据变更后仍会复用旧缓存与旧在途结果。
     */
    @Test
    public void credentialChangeChangesConfigVersion() {
        String base = Config.read(new MemoryPrefs()).version();
        String keyChanged = Config.read(new MemoryPrefs()
                .put(Config.KEY_API_KEY, "sk-test-key")).version();
        String headersChanged = Config.read(new MemoryPrefs()
                .put(Config.KEY_EXTRA_HEADERS, "X-Tenant: acme")).version();

        assertNotEquals("api key change must change version", base, keyChanged);
        assertNotEquals("extra header change must change version", base, headersChanged);
        // 版本字符串本身不得包含原始密钥。
        assertFalse(keyChanged.contains("sk-test-key"));
    }

    /** 密钥与额外请求头可为空；其他关键字段空白时回落默认。 */
    @Test
    public void blankStringsFallBackToDefaults() {
        MemoryPrefs p = new MemoryPrefs()
                .put(Config.KEY_API_URL, "")
                .put(Config.KEY_MODEL, "   ")
                .put(Config.KEY_TARGET_LANG, "")
                .put(Config.KEY_SYSTEM_PROMPT, "  \n ")
                .put(Config.KEY_BODY_TEMPLATE, "")
                .put(Config.KEY_RESPONSE_PATH, "\t");

        Config.Snapshot s = Config.read(p);
        assertEquals(Config.DEF_API_URL, s.apiUrl);
        assertEquals(Config.DEF_MODEL, s.model);
        assertEquals(Config.DEF_TARGET_LANG, s.targetLang);
        assertEquals(Config.DEF_SYSTEM_PROMPT, s.systemPrompt);
        assertEquals(Config.DEF_BODY_TEMPLATE, s.bodyTemplate);
        assertEquals(Config.DEF_RESPONSE_PATH, s.responsePath);
    }

    /** null 值同样回落默认值。 */
    @Test
    public void nullValuesFallBackToDefaults() {
        MemoryPrefs p = new MemoryPrefs()
                .put(Config.KEY_API_URL, null)
                .put(Config.KEY_RESPONSE_PATH, null);

        Config.Snapshot s = Config.read(p);
        assertEquals(Config.DEF_API_URL, s.apiUrl);
        assertEquals(Config.DEF_RESPONSE_PATH, s.responsePath);
    }

    /** 用户填了有效值时必须用用户的值，不能被默认值覆盖。 */
    @Test
    public void explicitValuesAreKept() {
        MemoryPrefs p = new MemoryPrefs()
                .put(Config.KEY_API_URL, "https://example.com/v1/chat/completions")
                .put(Config.KEY_MODEL, "my-model")
                .put(Config.KEY_TARGET_LANG, "日本語")
                .put(Config.KEY_SYSTEM_PROMPT, "只翻译。")
                .put(Config.KEY_RESPONSE_PATH, "data.text");

        Config.Snapshot s = Config.read(p);
        assertEquals("https://example.com/v1/chat/completions", s.apiUrl);
        assertEquals("my-model", s.model);
        assertEquals("日本語", s.targetLang);
        assertEquals("只翻译。", s.systemPrompt);
        assertEquals("data.text", s.responsePath);
        // 默认值不得生效
        assertNotEquals(Config.DEF_MODEL, s.model);
    }

    @Test
    public void verboseLoggingStillNeverIncludesMessageBody() {
        String sensitive = "unique-private-message-body";
        XLog.setVerbose(false);
        assertFalse(XLog.text(sensitive).contains(sensitive));
        XLog.setVerbose(true);
        try {
            assertFalse(XLog.text(sensitive).contains(sensitive));
        } finally {
            XLog.setVerbose(false);
        }
    }

    /** 超时与并发为 0 或负数时回落默认值（否则请求会直接失败）。 */
    @Test
    public void nonPositiveIntsFallBackToDefaults() {
        assertEquals(Config.DEF_TIMEOUT_MS,
                Config.read(new MemoryPrefs().put(Config.KEY_TIMEOUT_MS, 0)).timeoutMs);
        assertEquals(Config.DEF_TIMEOUT_MS,
                Config.read(new MemoryPrefs().put(Config.KEY_TIMEOUT_MS, -1)).timeoutMs);
        assertEquals(Config.DEF_MAX_CONCURRENCY,
                Config.read(new MemoryPrefs().put(Config.KEY_MAX_CONCURRENCY, 0)).maxConcurrency);
        assertEquals(Config.DEF_MAX_CONCURRENCY,
                Config.read(new MemoryPrefs().put(Config.KEY_MAX_CONCURRENCY, -5)).maxConcurrency);
    }

    /** 正常的正整数配置必须保留。 */
    @Test
    public void positiveIntsAreKept() {
        Config.Snapshot s = Config.read(new MemoryPrefs()
                .put(Config.KEY_TIMEOUT_MS, 30000)
                .put(Config.KEY_MAX_CONCURRENCY, 8));
        assertEquals(30000, s.timeoutMs);
        assertEquals(8, s.maxConcurrency);
    }

    /**
     * API 密钥与额外请求头的默认值本身即为空串。
     * 因此“空白回落默认值”对它们是等价的，不会破坏
     * “无需认证 / 无额外请求头”的正常用法。
     */
    @Test
    public void emptyApiKeyAndHeadersStayEmpty() {
        Config.Snapshot s = Config.read(new MemoryPrefs()
                .put(Config.KEY_API_KEY, "")
                .put(Config.KEY_EXTRA_HEADERS, "  "));
        assertEquals("", s.apiKey);
        assertEquals("", s.extraHeaders);
        assertTrue(s.apiKey.isEmpty());
    }
}
