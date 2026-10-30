package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 配置读取与显式无效配置行为。
 *
 * <p>未保存的键使用默认值；已保存但类型/取值不合法的键使整个快照无效，
 * 不再静默替换成默认值或其它协议。</p>
 */
public class ConfigTest {

    /** 空配置（什么都没存）应全部使用默认值。 */
    @Test
    public void emptyPrefsUseAllDefaults() {
        Config.Snapshot s = Config.read(new MemoryPrefs());
        assertTrue(s.configError == null);
        assertEquals(Config.DEF_ENABLED, s.enabled);
        assertEquals(Config.DEF_API_URL, s.apiUrl);
        assertEquals(Config.DEF_MODEL, s.model);
        assertEquals(Config.DEF_TARGET_LANG, s.targetLang);
        assertEquals(Config.DEF_SYSTEM_PROMPT, s.systemPrompt);
        assertEquals(Config.DEF_PROTOCOL, s.protocol);
        assertEquals(Config.DEF_TIMEOUT_MS, s.timeoutMs);
        assertEquals(Config.DEF_MAX_CONCURRENCY, s.maxConcurrency);
    }

    @Test
    public void wrongTypedPreferenceMarksSnapshotInvalid() {
        Config.Snapshot s = Config.read(new MemoryPrefs()
                .put(Config.KEY_API_URL, 17)
                .put(Config.KEY_ENABLED, "true")
                .put(Config.KEY_TIMEOUT_MS, "15000")
                .put(Config.KEY_MODEL, "kept-model")
                .put(Config.KEY_MAX_CONCURRENCY, 6));

        assertTrue(s.configError != null);
        assertFalse(s.isUsable());
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

    /** 密钥与额外请求头可为空；必填字符串空白为配置错误。 */
    @Test
    public void blankRequiredStringsMarkSnapshotInvalid() {
        Config.Snapshot s = Config.read(new MemoryPrefs()
                .put(Config.KEY_API_URL, "")
                .put(Config.KEY_MODEL, "   ")
                .put(Config.KEY_TARGET_LANG, "")
                .put(Config.KEY_SYSTEM_PROMPT, "  \n "));
        assertTrue(s.configError != null);
        assertFalse(s.isUsable());
    }

    /** 显式 null 值是配置错误，不被替换成默认值。 */
    @Test
    public void explicitNullValuesMarkSnapshotInvalid() {
        Config.Snapshot s = Config.read(new MemoryPrefs()
                .put(Config.KEY_API_URL, null)
                .put(Config.KEY_PROTOCOL, null));
        assertTrue(s.configError != null);
        assertFalse(s.isUsable());
    }

    /** 协议只接受已知值；显式未知值或类型异常都使快照无效。 */
    @Test
    public void protocolRejectsUnknownAndWrongTypedValues() {
        assertEquals(Config.PROTOCOL_OPENAI_RESPONSES, Config.read(new MemoryPrefs()
                .put(Config.KEY_PROTOCOL, Config.PROTOCOL_OPENAI_RESPONSES)).protocol);
        assertEquals(Config.PROTOCOL_OPENAI_COMPATIBLE, Config.read(new MemoryPrefs()
                .put(Config.KEY_PROTOCOL, Config.PROTOCOL_OPENAI_COMPATIBLE)).protocol);
        assertEquals(Config.PROTOCOL_ANTHROPIC_MESSAGES, Config.read(new MemoryPrefs()
                .put(Config.KEY_PROTOCOL, Config.PROTOCOL_ANTHROPIC_MESSAGES)).protocol);
        assertTrue(Config.read(new MemoryPrefs().put(Config.KEY_PROTOCOL, "gemini"))
                .configError != null);
        assertTrue(Config.read(new MemoryPrefs().put(Config.KEY_PROTOCOL, " "))
                .configError != null);
        assertTrue(Config.read(new MemoryPrefs().put(Config.KEY_PROTOCOL, 17))
                .configError != null);
    }

    /** 协议改变请求与解析方式，必须参与配置版本，切换后旧缓存/在途结果失效。 */
    @Test
    public void protocolChangeChangesConfigVersion() {
        String base = Config.read(new MemoryPrefs()).version();
        String switched = Config.read(new MemoryPrefs()
                .put(Config.KEY_PROTOCOL, Config.PROTOCOL_OPENAI_RESPONSES)).version();
        assertNotEquals("protocol change must change version", base, switched);
    }

    @Test
    public void streamingDefaultsToEnabledAndChangesConfigVersion() {
        Config.Snapshot defaults = Config.read(new MemoryPrefs());
        Config.Snapshot nonStreaming = Config.read(new MemoryPrefs()
                .put(Config.KEY_STREAMING, false));

        assertTrue(defaults.streaming);
        assertFalse(nonStreaming.streaming);
        assertNotEquals(defaults.version(), nonStreaming.version());
    }

    /** 用户填了有效值时必须用用户的值，不能被默认值覆盖。 */
    @Test
    public void explicitValuesAreKept() {
        MemoryPrefs p = new MemoryPrefs()
                .put(Config.KEY_API_URL, "https://example.com/v1/chat/completions")
                .put(Config.KEY_MODEL, "my-model")
                .put(Config.KEY_TARGET_LANG, "日本語")
                .put(Config.KEY_SYSTEM_PROMPT, "只翻译。")
                .put(Config.KEY_PROTOCOL, Config.PROTOCOL_OPENAI_RESPONSES);

        Config.Snapshot s = Config.read(p);
        assertEquals("https://example.com/v1/chat/completions", s.apiUrl);
        assertEquals("my-model", s.model);
        assertEquals("日本語", s.targetLang);
        assertEquals("只翻译。", s.systemPrompt);
        assertEquals(Config.PROTOCOL_OPENAI_RESPONSES, s.protocol);
        assertNotEquals(Config.DEF_MODEL, s.model);
        assertNotEquals(Config.DEF_PROTOCOL, s.protocol);
    }

    @Test
    public void savedPromptAndProtocolAreNotSilentlyOverwritten() {
        String prompt = "My custom translation instructions";
        Config.Snapshot snapshot = Config.read(new MemoryPrefs()
                .put(Config.KEY_SYSTEM_PROMPT, prompt)
                .put(Config.KEY_PROTOCOL, Config.PROTOCOL_OPENAI_RESPONSES));

        assertEquals(prompt, snapshot.systemPrompt);
        assertEquals(Config.PROTOCOL_OPENAI_RESPONSES, snapshot.protocol);
        Config.Snapshot defaults = Config.read(new MemoryPrefs());
        assertNotEquals(snapshot.version(), defaults.version());
    }

    /** 超时与并发为 0 或负数时使配置无效。 */
    @Test
    public void nonPositiveIntsMarkSnapshotInvalid() {
        assertTrue(Config.read(new MemoryPrefs().put(Config.KEY_TIMEOUT_MS, 0))
                .configError != null);
        assertTrue(Config.read(new MemoryPrefs().put(Config.KEY_TIMEOUT_MS, -1))
                .configError != null);
        assertTrue(Config.read(new MemoryPrefs().put(Config.KEY_MAX_CONCURRENCY, 0))
                .configError != null);
        assertTrue(Config.read(new MemoryPrefs().put(Config.KEY_MAX_CONCURRENCY, -5))
                .configError != null);
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
     * 因此空值是无需认证、无需额外请求头的有效配置。
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
