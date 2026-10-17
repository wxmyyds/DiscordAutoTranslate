package com.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * {@link JsonPath} 的提取逻辑与错误归类（需求 §5.5、§8）。
 */
public class JsonPathTest {

    private static final String OPENAI_RESPONSE =
            "{\"id\":\"x\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"你好世界\"}}]}";

    private static final String DEEPL_LIKE =
            "{\"translations\":[{\"detected_source_language\":\"EN\",\"text\":\"你好\"}]}";

    private static final String GOOGLE_LIKE =
            "{\"data\":{\"translations\":[{\"translatedText\":\"你好\"}]}}";

    @Test
    public void extractsOpenAiStylePath() throws Exception {
        assertEquals("你好世界",
                JsonPath.extractString(OPENAI_RESPONSE, "choices.0.message.content"));
    }

    @Test
    public void extractsDeepLStylePath() throws Exception {
        assertEquals("你好", JsonPath.extractString(DEEPL_LIKE, "translations.0.text"));
    }

    @Test
    public void extractsGoogleStylePath() throws Exception {
        assertEquals("你好", JsonPath.extractString(GOOGLE_LIKE, "data.translations.0.translatedText"));
    }

    @Test
    public void extractsTopLevelField() throws Exception {
        assertEquals("v", JsonPath.extractString("{\"result\":\"v\"}", "result"));
    }

    @Test
    public void nonStringValueIsStringified() throws Exception {
        // 便于用户发现路径指到了结构体而不是文本
        String v = JsonPath.extractString("{\"a\":{\"b\":1}}", "a");
        assertTrue(v.contains("\"b\""));
    }

    @Test
    public void numberValueIsStringified() throws Exception {
        assertEquals("42", JsonPath.extractString("{\"a\":42}", "a"));
    }

    @Test
    public void emptyPathIsConfigError() {
        try {
            JsonPath.extractString("{}", "");
            fail("expected CONFIG error");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.CONFIG, e.kind());
        }
    }

    @Test
    public void nullPathIsConfigError() {
        try {
            JsonPath.extractString("{}", null);
            fail("expected CONFIG error");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.CONFIG, e.kind());
        }
    }

    @Test
    public void malformedJsonIsParseError() {
        try {
            JsonPath.extractString("not json", "a");
            fail("expected PARSE error");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.PARSE, e.kind());
        }
    }

    @Test
    public void emptyBodyIsParseError() {
        try {
            JsonPath.extractString("   ", "a");
            fail("expected PARSE error");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.PARSE, e.kind());
        }
    }

    @Test
    public void missingFieldIsParseError() {
        try {
            JsonPath.extractString("{\"a\":1}", "b");
            fail("expected PARSE error");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.PARSE, e.kind());
        }
    }

    @Test
    public void arrayIndexOutOfBoundsIsParseError() {
        try {
            JsonPath.extractString(OPENAI_RESPONSE, "choices.5.message.content");
            fail("expected PARSE error");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.PARSE, e.kind());
        }
    }

    @Test
    public void indexingObjectWithNumberIsParseError() {
        try {
            JsonPath.extractString("{\"a\":{\"b\":1}}", "a.0");
            fail("expected PARSE error");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.PARSE, e.kind());
        }
    }

    @Test
    public void traversingIntoScalarIsParseError() {
        try {
            JsonPath.extractString("{\"a\":1}", "a.b");
            fail("expected PARSE error");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.PARSE, e.kind());
        }
    }

    @Test
    public void nullValueIsParseError() {
        try {
            JsonPath.extractString("{\"a\":null}", "a");
            fail("expected PARSE error");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.PARSE, e.kind());
        }
    }

    @Test
    public void validateJsonAcceptsAndRejects() throws Exception {
        JsonPath.validateJson("{\"a\":[1,2]}", "x");
        try {
            JsonPath.validateJson("{oops", "x");
            fail("expected CONFIG error");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.CONFIG, e.kind());
        }
    }

    @Test
    public void errorMessageMentionsFailingSegment() {
        try {
            JsonPath.extractString(OPENAI_RESPONSE, "choices.0.wrong.content");
            fail("expected PARSE error");
        } catch (TranslationException e) {
            assertTrue(e.getMessage().contains("wrong"));
        }
    }
}
