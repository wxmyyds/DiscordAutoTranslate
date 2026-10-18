package com.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

/**
 * 日志脱敏边界：日志可能出现于 logcat 与 LSPosed 日志两处，一旦写入就无法撤回，
 * 因此这里锁定“绝不输出密钥前缀/长度特征”和“URL 只保留 scheme + host”。
 */
public class XLogTest {

    @Test
    public void redactNeverRevealsKeyPrefixOrLength() {
        String key = "sk-proj-abcdefghijklmnop";
        String redacted = XLog.redact(key);
        assertFalse("must not leak key characters", redacted.contains("sk-"));
        assertFalse("must not leak exact length", redacted.contains(String.valueOf(key.length())));
        assertEquals("<configured>", redacted);
        assertEquals("<empty>", XLog.redact(""));
        assertEquals("<empty>", XLog.redact(null));
    }

    @Test
    public void safeUrlDropsCredentialsPathAndQuery() {
        String url = "https://user:password@api.example.com:8443/v1/chat/completions?token=SECRET";
        String safe = XLog.safeUrl(url);
        assertFalse("must not leak userinfo", safe.contains("password"));
        assertFalse("must not leak path", safe.contains("chat"));
        assertFalse("must not leak query", safe.contains("SECRET"));
        assertEquals("https://api.example.com:8443", safe);
    }

    @Test
    public void safeUrlHandlesUnparseableInput() {
        assertEquals("<empty>", XLog.safeUrl(null));
        assertEquals("<empty>", XLog.safeUrl(""));
        assertEquals("<unparseable>", XLog.safeUrl("not a url"));
    }

    @Test
    public void bodySummaryNeverContainsText() {
        String body = "some private message";
        assertFalse(XLog.text(body).contains(body));
        assertEquals("<null>", XLog.text(null));
    }
}
