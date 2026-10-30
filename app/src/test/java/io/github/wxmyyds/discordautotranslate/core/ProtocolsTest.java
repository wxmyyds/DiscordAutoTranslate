package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * 内置供应商协议的请求构造、端点解析与译文提取。
 *
 * <p>断言一律先解析 JSON 再取字段（org.json 不保证键序），
 * 不做字符串整体比较。系统提示词与原文经过请求体必须无损往返，
 * 该保证延续自旧版模板引擎测试（JsonTemplateTest）。</p>
 */
public class ProtocolsTest {

    private static String requestBody(String protocol, String text, String prompt, String model)
            throws TranslationException {
        boolean streaming = Protocols.supportsStreaming(protocol) && Config.DEF_STREAMING;
        return Protocols.requestBody(protocol, text, prompt, model, streaming);
    }

    private static String extractByFixtureMode(String protocol, String body)
            throws TranslationException {
        String response = body == null ? "" : body.trim();
        boolean streaming = Protocols.supportsStreaming(protocol)
                && (response.startsWith("data:") || response.startsWith("event:"));
        return Protocols.extractTranslation(protocol, body, streaming);
    }

    // ------------------------------------------------------------ 请求体

    /** Compatible 请求体不带 frequency_penalty：频率惩罚抑制重复 token，
     * 会让模型跳过/改写广告类消息里大段重复的段落（实测 7 组重复块被压掉若干组）。 */
    @Test
    public void compatibleBodyMatchesLegacyDefaultTemplate() throws Exception {
        String body = requestBody(Config.PROTOCOL_OPENAI_COMPATIBLE,
                "hii everyone", Config.DEF_SYSTEM_PROMPT, Config.DEF_MODEL);
        JSONObject json = new JSONObject(body);
        assertEquals(Config.DEF_MODEL, json.getString("model"));
        assertEquals(0, json.getDouble("temperature"), 0.0);
        assertFalse("频率惩罚与逐块照译重复内容冲突", json.has("frequency_penalty"));
        // 流式：非流式接口生成完成前零字节返回，慢速供应商上长消息会被
        // 总时长超时掐断；流式下读超时按字节间隔计算。
        assertTrue("Compatible 协议必须请求流式", json.getBoolean("stream"));
        JSONArray messages = json.getJSONArray("messages");
        assertEquals(2, messages.length());
        assertEquals("system", messages.getJSONObject(0).getString("role"));
        // 系统提示词 = 用户配置 + 请求层追加的包装协议说明（WRAPPER_RULE）。
        assertEquals(Config.DEF_SYSTEM_PROMPT + "\n\n" + Protocols.WRAPPER_RULE,
                messages.getJSONObject(0).getString("content"));
        assertEquals("user", messages.getJSONObject(1).getString("role"));
        // 待翻译正文整体包进 <translate> 标签。
        assertEquals(Protocols.WRAPPER_OPEN + "hii everyone" + Protocols.WRAPPER_CLOSE,
                messages.getJSONObject(1).getString("content"));
    }

    /** 关闭流式后，Compatible 请求与解析都使用非流式 JSON 模式。 */
    @Test
    public void compatibleCanUseNonStreamingRequestAndParsing() throws Exception {
        JSONObject body = new JSONObject(Protocols.requestBody(
                Config.PROTOCOL_OPENAI_COMPATIBLE, "hello", "prompt", "model", false));
        assertFalse(body.getBoolean("stream"));
        assertEquals("你好", Protocols.extractTranslation(Config.PROTOCOL_OPENAI_COMPATIBLE,
                "{\"choices\":[{\"message\":{\"content\":\"你好\"}}]}", false));
    }

    /** Responses 请求体用 instructions/input；不得携带官方接口不接受的 frequency_penalty。 */
    @Test
    public void responsesBodyUsesInstructionsAndInput() throws Exception {
        String body = requestBody(Config.PROTOCOL_OPENAI_RESPONSES,
                "hii everyone", Config.DEF_SYSTEM_PROMPT, Config.DEF_MODEL);
        JSONObject json = new JSONObject(body);
        assertEquals(Config.DEF_MODEL, json.getString("model"));
        assertEquals(Config.DEF_SYSTEM_PROMPT + "\n\n" + Protocols.WRAPPER_RULE,
                json.getString("instructions"));
        assertEquals(Protocols.WRAPPER_OPEN + "hii everyone" + Protocols.WRAPPER_CLOSE,
                json.getString("input"));
        assertEquals(0, json.getDouble("temperature"), 0.0);
        assertFalse("Responses 接口不接受 frequency_penalty", json.has("frequency_penalty"));
        assertFalse("Responses 接口没有 messages 字段", json.has("messages"));
        assertFalse("Responses 协议暂不支持流式，不携带 stream", json.has("stream"));
    }

    /** Messages 请求体：system 顶层字段 + 仅 user 消息 + 必填 max_tokens + 流式。 */
    @Test
    public void messagesBodyUsesTopLevelSystemAndMaxTokens() throws Exception {
        String body = requestBody(Config.PROTOCOL_ANTHROPIC_MESSAGES,
                "hii everyone", Config.DEF_SYSTEM_PROMPT, Config.DEF_MODEL);
        JSONObject json = new JSONObject(body);
        assertEquals(Config.DEF_MODEL, json.getString("model"));
        assertEquals(Config.DEF_SYSTEM_PROMPT + "\n\n" + Protocols.WRAPPER_RULE,
                json.getString("system"));
        JSONArray messages = json.getJSONArray("messages");
        assertEquals(1, messages.length());
        assertEquals("user", messages.getJSONObject(0).getString("role"));
        assertEquals(Protocols.WRAPPER_OPEN + "hii everyone" + Protocols.WRAPPER_CLOSE,
                messages.getJSONObject(0).getString("content"));
        assertTrue("max_tokens 是 Messages 协议必填项", json.getInt("max_tokens") > 0);
        assertEquals(0, json.getDouble("temperature"), 0.0);
        assertTrue("Messages 同样请求流式", json.getBoolean("stream"));
        assertFalse("没有 OpenAI 系的 instructions 字段", json.has("instructions"));
    }

    // ------------------------------------------------------------ 流式响应提取

    /** 流式帧按 delta 累积成完整译文；role/空帧与注释行不得混入。 */
    @Test
    public void streamedResponseAccumulatesDeltas() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n"
                + "\n"
                + ": keep-alive\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"，世界\"}}]}\n"
                + "data: {\"choices\":[{\"delta\":{}}]}\n"
                + "data: {\"choices\":[{\"finish_reason\":\"stop\",\"delta\":{}}]}\n"
                + "data: [DONE]\n";
        assertEquals("你好，世界",
                extractByFixtureMode(Config.PROTOCOL_OPENAI_COMPATIBLE, sse));
    }

    /** 流式模式不接受服务端忽略 stream 参数后返回的普通 JSON。 */
    @Test
    public void streamingModeRejectsNonStreamingJson() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"普通\"}}]}";
        try {
            Protocols.extractTranslation(Config.PROTOCOL_OPENAI_COMPATIBLE, json, true);
            fail("流式模式必须拒绝 JSON 响应");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.PARSE, e.kind());
        }
    }

    /** 只收到 role/usage 帧时累积为空，由引擎报“提取到的译文为空”。 */
    @Test
    public void streamedResponseWithoutContentDeltasIsEmpty() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n"
                + "data: {\"usage\":{\"total_tokens\":10},\"choices\":[]}\n"
                + "data: [DONE]\n";
        assertEquals("",
                extractByFixtureMode(Config.PROTOCOL_OPENAI_COMPATIBLE, sse));
    }

    /** 流断在半路（网络中断/服务端断开）：部分 delta 拼出的译文缺尾巴，必须失败。 */
    @Test
    public void streamCutMidwayIsRejectedNotPartiallyExtracted() {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"，世\"}}]}\n";
        try {
            extractByFixtureMode(Config.PROTOCOL_OPENAI_COMPATIBLE, sse);
            fail("残缺流必须失败");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.PARSE, e.kind());
        }
    }

    /** finish_reason=length：流走完了但生成被 max_tokens 掐断，译文不完整。 */
    @Test
    public void finishReasonLengthIsRejectedAsTruncated() {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n"
                + "data: {\"choices\":[{\"finish_reason\":\"length\",\"delta\":{}}]}\n"
                + "data: [DONE]\n";
        try {
            extractByFixtureMode(Config.PROTOCOL_OPENAI_COMPATIBLE, sse);
            fail("截断译文必须失败");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.PARSE, e.kind());
        }
    }

    // ------------------------------------------------------------ Anthropic Messages 提取

    /** 非流式 Messages 响应：拼接 content 里全部 text 块，thinking 块跳过。 */
    @Test
    public void messagesExtractionConcatenatesTextBlocksOnly() throws Exception {
        String resp = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"thinking\",\"thinking\":\"推理\"},"
                + "{\"type\":\"text\",\"text\":\"你好\"},"
                + "{\"type\":\"text\",\"text\":\"，世界\"}],"
                + "\"model\":\"claude\",\"stop_reason\":\"end_turn\"}";
        assertEquals("你好，世界",
                Protocols.extractTranslation(Config.PROTOCOL_ANTHROPIC_MESSAGES, resp, false));
    }

    /** stop_reason=max_tokens：输出被 max_tokens 掐断，非流式同样拒绝。 */
    @Test
    public void messagesStopReasonMaxTokensIsRejected() {
        String resp = "{\"content\":[{\"type\":\"text\",\"text\":\"你好\"}],"
                + "\"stop_reason\":\"max_tokens\"}";
        try {
            extractByFixtureMode(Config.PROTOCOL_ANTHROPIC_MESSAGES, resp);
            fail("截断译文必须失败");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.PARSE, e.kind());
        }
    }

    /** 关闭流式后，Anthropic Messages 请求体与响应解析都使用非流式模式。 */
    @Test
    public void messagesCanUseNonStreamingRequestAndParsing() throws Exception {
        JSONObject body = new JSONObject(Protocols.requestBody(
                Config.PROTOCOL_ANTHROPIC_MESSAGES, "hello", "prompt", "model", false));
        assertFalse(body.getBoolean("stream"));
        assertEquals("你好", Protocols.extractTranslation(Config.PROTOCOL_ANTHROPIC_MESSAGES,
                "{\"content\":[{\"type\":\"text\",\"text\":\"你好\"}],"
                        + "\"stop_reason\":\"end_turn\"}", false));
    }

    @Test
    public void messagesStreamingModeRejectsJsonResponse() {
        String json = "{\"content\":[{\"type\":\"text\",\"text\":\"你好\"}],"
                + "\"stop_reason\":\"end_turn\"}";
        try {
            Protocols.extractTranslation(Config.PROTOCOL_ANTHROPIC_MESSAGES, json, true);
            fail("流式模式必须拒绝 JSON 响应");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.PARSE, e.kind());
        }
    }

    /** 流式按 content_block_delta 增量累积；event: 行与 thinking 增量不混入。 */
    @Test
    public void streamedMessagesEventsAccumulateTextDeltas() throws Exception {
        String sse = "event: message_start\n"
                + "data: {\"type\":\"message_start\",\"message\":{}}\n"
                + "\n"
                + "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"你好\"}}\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"推理\"}}\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"，世界\"}}\n"
                + "event: message_delta\n"
                + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n"
                + "event: message_stop\n"
                + "data: {\"type\":\"message_stop\"}\n";
        assertEquals("你好，世界",
                extractByFixtureMode(Config.PROTOCOL_ANTHROPIC_MESSAGES, sse));
    }

    /** 流断在半路（无 message_stop 也无 stop_reason）必须失败，与 chat 流同规则。 */
    @Test
    public void messagesStreamCutMidwayIsRejected() {
        String sse = "data: {\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"你好\"}}\n";
        try {
            extractByFixtureMode(Config.PROTOCOL_ANTHROPIC_MESSAGES, sse);
            fail("残缺流必须失败");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.PARSE, e.kind());
        }
    }

    /** finish_reason 不能替代协议唯一的 [DONE] 结束帧。 */
    @Test
    public void finishReasonWithoutDoneMarkerIsRejected() {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n"
                + "data: {\"choices\":[{\"finish_reason\":\"stop\",\"delta\":{}}]}\n";
        try {
            Protocols.extractTranslation(Config.PROTOCOL_OPENAI_COMPATIBLE, sse, true);
            fail("finish_reason 不能替代 [DONE]");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.PARSE, e.kind());
        }
    }

    /** 中间帧的 finish_reason 显式为 null：不得被当成 "null" 字符串而误判截断。 */
    @Test
    public void explicitNullFinishReasonDoesNotFailTheGate() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"你\"},"
                + "\"finish_reason\":null}]}\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"好\"},"
                + "\"finish_reason\":null}]}\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n"
                + "data: [DONE]\n";
        assertEquals("你好",
                extractByFixtureMode(Config.PROTOCOL_OPENAI_COMPATIBLE, sse));
    }

    /** role 帧的 content 显式为 null：不得作为 "null" 字符串混入译文。 */
    @Test
    public void explicitNullContentDeltaDoesNotEmitNullLiteral() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\","
                + "\"content\":null}}]}\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n"
                + "data: [DONE]\n";
        assertEquals("你好",
                extractByFixtureMode(Config.PROTOCOL_OPENAI_COMPATIBLE, sse));
    }

    /**
     * 提示词与原文含引号、换行、反斜杠时必须无损往返（协议层负责 JSON 转义）。
     */
    @Test
    public void hostileTextRoundTripsThroughBothProtocols() throws Exception {
        String nasty = "Hello, \"world\"\\\nline2\t\u0001";
        String prompt = "规则 1：不得输出 \"翻译结果：\" 前缀\n第二行";

        JSONObject compat = new JSONObject(requestBody(
                Config.PROTOCOL_OPENAI_COMPATIBLE, nasty, prompt, "m"));
        assertEquals(Protocols.WRAPPER_OPEN + nasty + Protocols.WRAPPER_CLOSE,
                compat.getJSONArray("messages").getJSONObject(1).getString("content"));
        assertEquals(prompt + "\n\n" + Protocols.WRAPPER_RULE,
                compat.getJSONArray("messages").getJSONObject(0).getString("content"));

        JSONObject responses = new JSONObject(requestBody(
                Config.PROTOCOL_OPENAI_RESPONSES, nasty, prompt, "m"));
        assertEquals(Protocols.WRAPPER_OPEN + nasty + Protocols.WRAPPER_CLOSE,
                responses.getString("input"));
        assertEquals(prompt + "\n\n" + Protocols.WRAPPER_RULE,
                responses.getString("instructions"));
    }

    // ------------------------------------------------------------ 端点解析

    /** API URL 是基础地址：端点后缀由所选协议追加，切换协议不用改 URL。 */
    @Test
    public void endpointAppendsSuffixPerProtocolToBaseUrl() {
        assertEquals("https://api.openai.com/v1/chat/completions",
                Protocols.endpoint(Config.PROTOCOL_OPENAI_COMPATIBLE,
                        "https://api.openai.com/v1"));
        assertEquals("https://api.openai.com/v1/responses",
                Protocols.endpoint(Config.PROTOCOL_OPENAI_RESPONSES,
                        "https://api.openai.com/v1"));
        assertEquals("https://api.anthropic.com/v1/messages",
                Protocols.endpoint(Config.PROTOCOL_ANTHROPIC_MESSAGES,
                        "https://api.anthropic.com/v1"));
        // 默认值本身也是基础地址形态。
        assertEquals("https://api.openai.com/v1/chat/completions",
                Protocols.endpoint(Config.PROTOCOL_OPENAI_COMPATIBLE, Config.DEF_API_URL));
    }

    /** 尾随斜杠仅做规整；基础路径不必以 /v1 结尾，自定义网关基础路径同样直接追加。 */
    @Test
    public void endpointNormalizesTrailingSlashAndAppendsToCustomBase() {
        assertEquals("https://api.openai.com/v1/responses",
                Protocols.endpoint(Config.PROTOCOL_OPENAI_RESPONSES,
                        "https://api.openai.com/v1/"));
        assertEquals("https://gw.example.com/translate/chat/completions",
                Protocols.endpoint(Config.PROTOCOL_OPENAI_COMPATIBLE,
                        "https://gw.example.com/translate"));
    }

    // ------------------------------------------------------------ 译文提取

    /** Compatible 提取路径固定为 choices.0.message.content。 */
    @Test
    public void compatibleExtractionReadsFirstChoiceContent() throws Exception {
        String resp = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"你好\"}}]}";
        assertEquals("你好",
                extractByFixtureMode(Config.PROTOCOL_OPENAI_COMPATIBLE, resp));
    }

    /** 模型回显包装标签时严格拒绝，不剥离后继续回填。 */
    @Test
    public void echoedWrapperTagsAreRejected() throws Exception {
        String resp = "{\"choices\":[{\"message\":{\"content\":"
                + "\"<translate>\\n你好\\n</translate>\"}}]}";
        try {
            extractByFixtureMode(Config.PROTOCOL_OPENAI_COMPATIBLE, resp);
            fail("包装标签必须明确失败");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.PARSE, e.kind());
        }
    }

    /** 剥离后仍残留标签 = 输出结构损坏，按解析失败拒绝（宁显原文不显字面量）。 */
    @Test
    public void residualWrapperTagsAreRejectedAsParseError() {
        String resp = "{\"choices\":[{\"message\":{\"content\":"
                + "\"前言 <translate>你好</translate>\"}}]}";
        try {
            extractByFixtureMode(Config.PROTOCOL_OPENAI_COMPATIBLE, resp);
            fail("残留包装标签必须失败");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.PARSE, e.kind());
        }
    }

    /**
     * Responses 响应的 reasoning 项排在 message 项之前（推理模型官方结构），
     * 必须按类型扫描，不能写死 output.0 下标。
     */
    @Test
    public void responsesExtractionSkipsReasoningItems() throws Exception {
        String resp = "{\"id\":\"resp_1\",\"object\":\"response\",\"output\":["
                + "{\"id\":\"rs_1\",\"type\":\"reasoning\",\"content\":[],\"summary\":[]},"
                + "{\"id\":\"msg_1\",\"type\":\"message\",\"status\":\"completed\","
                + "\"content\":[{\"type\":\"output_text\",\"annotations\":[],"
                + "\"logprobs\":[],\"text\":\"你好，世界\"}],\"role\":\"assistant\"}]}";
        assertEquals("你好，世界",
                extractByFixtureMode(Config.PROTOCOL_OPENAI_RESPONSES, resp));
    }

    /** 多段 output_text 时只取响应顺序中的第一个候选，不跳过空文本。 */
    @Test
    public void responsesExtractionUsesFirstTextCandidate() throws Exception {
        String resp = "{\"output\":[{\"type\":\"message\",\"content\":["
                + "{\"type\":\"output_text\",\"text\":\"\"},"
                + "{\"type\":\"output_text\",\"text\":\"第二段\"}]}]}";
        assertEquals("", extractByFixtureMode(Config.PROTOCOL_OPENAI_RESPONSES, resp));
    }

    /** 找不到可译文本、缺 output 数组、响应非 JSON 对象都是明确的解析错误。 */
    @Test
    public void responsesExtractionRejectsUnusableBodies() {
        assertParseError("{\"output\":[]}");
        assertParseError("{\"output\":[{\"type\":\"reasoning\",\"content\":[]}]}");
        assertParseError("{\"id\":\"resp_1\"}");
        assertParseError("[1,2]");
    }

    private static void assertParseError(String body) {
        try {
            extractByFixtureMode(Config.PROTOCOL_OPENAI_RESPONSES, body);
            fail("应抛出 TranslationException: " + body);
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.PARSE, e.kind());
        }
    }

    // ------------------------------------------------------------ 空值防御

    @Test
    public void nullRequestInputsAreRejected() throws Exception {
        try {
            Protocols.requestBody(Config.PROTOCOL_OPENAI_COMPATIBLE, null,
                    null, "m", false);
            fail("空原文必须失败");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.EMPTY, e.kind());
        }
        try {
            Protocols.requestBody(Config.PROTOCOL_OPENAI_COMPATIBLE, "hello",
                    "prompt", null, false);
            fail("空模型必须失败");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.CONFIG, e.kind());
        }
        assertEndpointInvalid(null);
        assertEndpointInvalid("  ");
    }

    @Test
    public void invalidExtraHeaderIsRejected() {
        try {
            Http.Headers.parse("Authorization Bearer key");
            fail("非法请求头必须明确失败");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.CONFIG, e.kind());
        }
    }

    private static void assertEndpointInvalid(String url) {
        boolean rejected = false;
        try {
            Protocols.endpoint(Config.PROTOCOL_OPENAI_COMPATIBLE, url);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        assertTrue(rejected);
    }

    @Test
    public void unknownProtocolIsRejected() {
        try {
            Protocols.extractTranslation("unknown", "{}", false);
            fail("未知协议必须明确失败");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.CONFIG, e.kind());
        }
    }
}
