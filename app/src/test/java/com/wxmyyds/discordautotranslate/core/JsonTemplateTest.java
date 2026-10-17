package com.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
        JsonTemplate.validate(Config.DEF_BODY_TEMPLATE,
                Config.DEF_TARGET_LANG, Config.DEF_MODEL);
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
    public void defaultTemplateUsesTemperatureZeroForDeterminism() {
        JsonTemplate.RenderResult r = JsonTemplate.render(
                Config.DEF_BODY_TEMPLATE, "hi", "简体中文", "gpt-4o-mini");
        assertTrue(r.body.contains("\"temperature\":0"));
        assertFalse(r.hasUnknownPlaceholders());
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

    /**
     * 旧默认模板（无 {{system}}）在读取时必须自动迁移为新默认模板。
     * 否则用户手机里存的旧模板会静默盖掉新增的“系统提示词”配置项。
     */
    @Test
    public void legacyDefaultTemplateIsMigrated() throws Exception {
        // 用 MemoryPrefs 模拟“老用户”手机里已存的旧默认模板。
        MemoryPrefs p = new MemoryPrefs();
        p.put(Config.KEY_BODY_TEMPLATE, Config.LEGACY_DEF_BODY_TEMPLATE);
        p.put(Config.KEY_SYSTEM_PROMPT, Config.DEF_SYSTEM_PROMPT);

        Config.Snapshot s = Config.read(p);
        assertEquals(Config.DEF_BODY_TEMPLATE, s.bodyTemplate);
        // 迁移后模板必须引用 {{system}}，否则提示词不会被发送。
        assertTrue(s.bodyTemplate.contains("{{system}}"));
        assertEquals(Config.DEF_SYSTEM_PROMPT, s.systemPrompt);
        // 迁移后的模板渲染出的请求体必须真正带上提示词。
        JsonTemplate.RenderResult r = JsonTemplate.render(
                s.bodyTemplate, "Dumbass", Config.DEF_TARGET_LANG,
                Config.DEF_MODEL, s.systemPrompt);
        assertEquals(Config.DEF_SYSTEM_PROMPT,
                JsonPath.extractString(r.body, "messages.0.content"));
        assertEquals("Dumbass", JsonPath.extractString(r.body, "messages.1.content"));
    }

    /** 用户自己手写的模板（即使是旧内容）不被自动改写。 */
    @Test
    public void customTemplateIsNotMigrated() {
        MemoryPrefs p = new MemoryPrefs();
        String custom = "{\"c\":\"{{text}}\",\"s\":\"{{system}}\"}";
        p.put(Config.KEY_BODY_TEMPLATE, custom);
        assertEquals(custom, Config.read(p).bodyTemplate);
    }

    /** 新版默认提示词必须包含针对侮辱性单词的规则（防回归）。 */
    @Test
    public void defaultPromptCoversProfanityRule() {
        String prompt = Config.DEF_SYSTEM_PROMPT;
        assertTrue("默认提示词应含‘脏话/侮辱性词汇’规则", prompt.contains("脏话"));
        assertTrue("默认提示词应明确禁止解释词义", prompt.contains("解释词义"));
        assertTrue("默认提示词应明确禁止道德说教", prompt.contains("道德"));
        assertTrue("默认提示词应给出 Dumbass 示例", prompt.contains("Dumbass"));
    }
}
