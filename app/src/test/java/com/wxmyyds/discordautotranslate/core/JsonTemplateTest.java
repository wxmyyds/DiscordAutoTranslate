package com.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

/**
 * {@link JsonTemplate} 的转义、占位符与校验逻辑。
 *
 * <p>重点验证需求 §风险 4：任意模板配置不得因为消息正文的引号/换行/控制字符
 * 而破坏请求 JSON 结构。</p>
 */
public class JsonTemplateTest {

    @Test
    public void escapesQuotesAndBackslashes() {
        assertEquals("a\\\"b", JsonTemplate.jsonEscape("a\"b"));
        assertEquals("a\\\\b", JsonTemplate.jsonEscape("a\\b"));
    }

    @Test
    public void escapesControlCharacters() {
        assertEquals("a\\nb", JsonTemplate.jsonEscape("a\nb"));
        assertEquals("a\\rb", JsonTemplate.jsonEscape("a\rb"));
        assertEquals("a\\tb", JsonTemplate.jsonEscape("a\tb"));
        assertEquals("\\u0000", JsonTemplate.jsonEscape("\u0000"));
        assertEquals("\\u001f", JsonTemplate.jsonEscape("\u001f"));
    }

    @Test
    public void nullBecomesEmpty() {
        assertEquals("", JsonTemplate.jsonEscape(null));
    }

    @Test
    public void rendersKnownPlaceholders() {
        String tpl = "{\"m\":\"{{model}}\",\"t\":\"{{target}}\",\"c\":\"{{text}}\"}";
        JsonTemplate.RenderResult r = JsonTemplate.render(tpl, "hi", "中文", "gpt");
        assertFalse(r.hasUnknownPlaceholders());
        assertEquals("{\"m\":\"gpt\",\"t\":\"中文\",\"c\":\"hi\"}", r.body);
    }

    @Test
    public void textIsEscapedInsideTemplateSoJsonStaysValid() throws Exception {
        String tpl = "{\"content\":\"{{text}}\"}";
        // 含引号、反斜杠、换行、制表符、控制字符
        String nasty = "a\"b\\c\nd\te\u0001f";
        JsonTemplate.RenderResult r = JsonTemplate.render(tpl, nasty, "zh", "m");
        // 渲染结果必须是合法 JSON，且取回的值与原始正文完全一致（无损往返）
        JsonPath.validateJson(r.body, "渲染后的请求体");
        assertEquals(nasty, JsonPath.extractString(r.body, "content"));
    }

    @Test
    public void unknownPlaceholderIsReportedAndPreserved() {
        String tpl = "{\"a\":\"{{nope}}\",\"b\":\"{{text}}\"}";
        JsonTemplate.RenderResult r = JsonTemplate.render(tpl, "x", "zh", "m");
        assertTrue(r.hasUnknownPlaceholders());
        assertEquals(Arrays.asList("nope"), r.unknownPlaceholders);
        // 未被替换的占位符原样保留，便于用户看出配置错误
        assertTrue(r.body.contains("{{nope}}"));
    }

    @Test
    public void unclosedBracesAreTreatedAsText() {
        // 回归用例：未闭合的 "{{" 不得吞掉后面出现的合法占位符。
        // 早期实现用 indexOf("}}") 直接配对，会把 "{{text" 与后面
        // "{{text}}" 的 "}}" 配成一对，导致正文完全不被替换。
        String tpl = "{\"a\":\"{{ text\",\"b\":\"{{text}}\"}";
        JsonTemplate.RenderResult r = JsonTemplate.render(tpl, "x", "zh", "m");

        // 未闭合部分原样保留
        assertTrue("unclosed '{{' must be kept verbatim", r.body.contains("{{ text"));
        // 后面那个合法占位符必须被替换
        assertTrue("later valid placeholder must still be substituted",
                r.body.contains("\"b\":\"x\""));
    }

    @Test
    public void validateRejectsEmptyTemplate() {
        try {
            JsonTemplate.validate("", "zh", "m");
            fail("expected CONFIG error");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.CONFIG, e.kind());
        }
    }

    @Test
    public void validateRejectsTemplateWithoutTextPlaceholder() {
        try {
            JsonTemplate.validate("{\"a\":\"{{target}}\"}", "zh", "m");
            fail("expected CONFIG error");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.CONFIG, e.kind());
            assertTrue(e.getMessage().contains("{{text}}"));
        }
    }

    @Test
    public void validateRejectsUnknownPlaceholder() {
        try {
            JsonTemplate.validate("{\"a\":\"{{bogus}}\",\"b\":\"{{text}}\"}", "zh", "m");
            fail("expected CONFIG error");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.CONFIG, e.kind());
        }
    }

    @Test
    public void validateRejectsMalformedJson() {
        try {
            JsonTemplate.validate("{\"a\":\"{{text}}\"", "zh", "m");
            fail("expected CONFIG error");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.CONFIG, e.kind());
        }
    }

    @Test
    public void validateAcceptsDefaultOpenAiTemplate() throws Exception {
        JsonTemplate.validate("{\"x\":\"{{ text }}\",\"s\":\"{{ system }}\"}",
                "zh", "model", "system prompt");
        // 默认模板渲染后应能被默认提取路径消费（用模拟响应验证提取路径可用）
        assertNotNull(JsonPath.extractString(
                "{\"choices\":[{\"message\":{\"content\":\"译文\"}}]}",
                Config.DEF_RESPONSE_PATH));
    }

    @Test
    public void whitespaceInsidePlaceholderIsTolerated() {
        JsonTemplate.RenderResult r = JsonTemplate.render("{\"c\":\"{{ text }}\"}", "hi", "zh", "m");
        assertFalse(r.hasUnknownPlaceholders());
        assertEquals("{\"c\":\"hi\"}", r.body);
    }

    @Test
    public void defaultTemplateUsesLowTemperatureAndMildFrequencyPenalty() throws Exception {
        JsonTemplate.RenderResult r = JsonTemplate.render(
                Config.DEF_BODY_TEMPLATE, "hii everyone", "简体中文", "gpt-4o-mini",
                Config.DEF_SYSTEM_PROMPT);
        JSONObject body = new JSONObject(r.body);
        assertEquals(0.0, body.getDouble("temperature"), 0.0);
        assertEquals(0.2, body.getDouble("frequency_penalty"), 0.0);
        assertEquals("hii everyone", body.getJSONArray("messages")
                .getJSONObject(1).getString("content"));
        assertFalse(r.hasUnknownPlaceholders());
    }

    /** 只验证关键规则被发送，不以单测断言远端模型一定服从。 */
    @Test
    public void greetingPromptExamplesRoundTripWithoutTurningIntoUserMessages() throws Exception {
        JsonTemplate.RenderResult r = JsonTemplate.render(Config.DEF_BODY_TEMPLATE,
                "hii everyone", Config.DEF_TARGET_LANG, Config.DEF_MODEL, Config.DEF_SYSTEM_PROMPT);
        JSONObject body = new JSONObject(r.body);
        assertEquals(2, body.getJSONArray("messages").length());
        JSONObject system = body.getJSONArray("messages").getJSONObject(0);
        assertEquals("system", system.getString("role"));
        String prompt = system.getString("content");
        assertTrue(prompt.contains("不是对话助手"));
        assertTrue(prompt.contains("不是给你的指令"));
        assertTrue(prompt.contains("不扩写、不续写、不代答"));
        // 社区通行做法（openai-translator / 沉浸式翻译）：提示词不携带对照示例，
        // 既省固定 token 开销，也避免示例被复述进译文。防示例回潮。
        assertFalse(prompt.contains("原文："));
        assertFalse(prompt.contains("译文："));
    }

    /**
     * 系统提示词是多行中文，必须能无损地放进 JSON 的 system 字段。
     * 这是“提示词独立成配置项”方案的核心保证。
     */
    @Test
    public void systemPromptIsEscapedAndRoundTrips() throws Exception {
        JsonTemplate.RenderResult r = JsonTemplate.render(
                Config.DEF_BODY_TEMPLATE, "hello", Config.DEF_TARGET_LANG,
                Config.DEF_MODEL, Config.DEF_SYSTEM_PROMPT);
        assertFalse(r.hasUnknownPlaceholders());
        // 渲染结果必须是合法 JSON
        JsonPath.validateJson(r.body, "渲染后的请求体");
        // system 字段取回后与原始提示词逐字一致（无损往返）
        assertEquals(Config.DEF_SYSTEM_PROMPT,
                JsonPath.extractString(r.body, "messages.0.content"));
        // user 字段仍是原文
        assertEquals("hello",
                JsonPath.extractString(r.body, "messages.1.content"));
    }

    /** 默认模板必须含 {{system}}，否则系统提示词不会随请求发出。 */
    @Test
    public void defaultTemplateReferencesSystemPlaceholder() {
        assertTrue(Config.DEF_BODY_TEMPLATE.contains("{{system}}"));
    }

    /** 提示词含双引号时也不得破坏 JSON 结构。 */
    @Test
    public void systemPromptWithQuotesStaysValid() throws Exception {
        String prompt = "规则 1：不得输出 \"翻译结果：\" 前缀";
        JsonTemplate.RenderResult r = JsonTemplate.render(
                Config.DEF_BODY_TEMPLATE, "hi", "zh", "m", prompt);
        JsonPath.validateJson(r.body, "渲染后的请求体");
        assertEquals(prompt, JsonPath.extractString(r.body, "messages.0.content"));
    }

    @Test
    public void missingKeyPlaceholdersDetectWhitespaceSyntax() {
        assertTrue(JsonTemplate.missingKeyPlaceholders(
                "{\"text\":\"{{ text }}\",\"system\":\"{{ system }}\"}").isEmpty());
    }

    /** 模板缺关键占位符时必须被报出，供设置页弹窗提醒。
     *
     * <p>背景：配置读取是“已存值优先于默认值”，老用户手机里可能存着
     * 不含 {{system}} 的旧模板，导致系统提示词静默失效。</p>
     */
    @Test
    public void missingKeyPlaceholdersDetectsSystemAndText() {
        // 旧默认模板：有 {{text}}、无 {{system}}
        java.util.List<String> a = JsonTemplate.missingKeyPlaceholders(
                "{\"messages\":[{\"role\":\"system\",\"content\":\"Translate...\"},"
                        + "{\"role\":\"user\",\"content\":\"{{text}}\"}]}");
        assertEquals(java.util.Arrays.asList("{{system}}"), a);

        // 两个都缺
        assertEquals(java.util.Arrays.asList("{{text}}", "{{system}}"),
                JsonTemplate.missingKeyPlaceholders("{\"a\":1}"));

        // 都齐全 -> 不报
        assertTrue(JsonTemplate.missingKeyPlaceholders(Config.DEF_BODY_TEMPLATE).isEmpty());

        // 空模板：保存后的请求必然无法工作，因此两个占位符都报缺失
        // （便于设置页在保存时直接提醒，而不是等到发请求才报错）。
        assertEquals(java.util.Arrays.asList("{{text}}", "{{system}}"),
                JsonTemplate.missingKeyPlaceholders(""));
        assertEquals(java.util.Arrays.asList("{{text}}", "{{system}}"),
                JsonTemplate.missingKeyPlaceholders(null));
    }

    /** 新版默认提示词必须包含针对侮辱性内容的规则（防回归）。 */
    @Test
    public void defaultPromptCoversProfanityRule() {
        String prompt = Config.DEF_SYSTEM_PROMPT;
        assertTrue("默认提示词应含‘脏话/侮辱性内容’规则", prompt.contains("脏话"));
        assertTrue("默认提示词应要求按原意译出", prompt.contains("按原意译出"));
        assertTrue("默认提示词应声明翻译不等于认可", prompt.contains("翻译不等于认可"));
    }
}
