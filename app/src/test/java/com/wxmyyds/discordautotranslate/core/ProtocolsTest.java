package com.wxmyyds.discordautotranslate.core;

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
 * 这是旧版模板引擎测试（JsonTemplateTest）核心保证的协议层延续。</p>
 */
public class ProtocolsTest {

    // ------------------------------------------------------------ 请求体

    /** Compatible 请求体必须与旧版默认模板逐字段等价，老用户升级后请求行为不变。 */
    @Test
    public void compatibleBodyMatchesLegacyDefaultTemplate() throws Exception {
        String body = Protocols.requestBody(Config.PROTOCOL_OPENAI_COMPATIBLE,
                "hii everyone", Config.DEF_SYSTEM_PROMPT, Config.DEF_MODEL);
        JSONObject json = new JSONObject(body);
        assertEquals(Config.DEF_MODEL, json.getString("model"));
        assertEquals(0, json.getDouble("temperature"), 0.0);
        assertEquals(0.2, json.getDouble("frequency_penalty"), 0.0);
        // 流式：非流式接口生成完成前零字节返回，慢速供应商上长消息会被
        // 总时长超时掐断；流式下读超时按字节间隔计算。
        assertTrue("Compatible 协议必须请求流式", json.getBoolean("stream"));
        // 思考等级 low：翻译不需要深思考，推理型模型默认档会把输出预算
        // 全花在思考上（实测 55s 零正文）。
        assertEquals(Protocols.REASONING_EFFORT_LOW, json.getString("reasoning_effort"));
        JSONArray messages = json.getJSONArray("messages");
        assertEquals(2, messages.length());
        assertEquals("system", messages.getJSONObject(0).getString("role"));
        assertEquals(Config.DEF_SYSTEM_PROMPT,
                messages.getJSONObject(0).getString("content"));
        assertEquals("user", messages.getJSONObject(1).getString("role"));
        assertEquals("hii everyone", messages.getJSONObject(1).getString("content"));
    }

    /** Responses 请求体用 instructions/input；不得携带官方接口不接受的 frequency_penalty。 */
    @Test
    public void responsesBodyUsesInstructionsAndInput() throws Exception {
        String body = Protocols.requestBody(Config.PROTOCOL_OPENAI_RESPONSES,
                "hii everyone", Config.DEF_SYSTEM_PROMPT, Config.DEF_MODEL);
        JSONObject json = new JSONObject(body);
        assertEquals(Config.DEF_MODEL, json.getString("model"));
        assertEquals(Config.DEF_SYSTEM_PROMPT, json.getString("instructions"));
        assertEquals("hii everyone", json.getString("input"));
        assertEquals(0, json.getDouble("temperature"), 0.0);
        assertFalse("Responses 接口不接受 frequency_penalty", json.has("frequency_penalty"));
        assertFalse("Responses 接口没有 messages 字段", json.has("messages"));
        assertFalse("Responses 协议暂不支持流式，不携带 stream", json.has("stream"));
        assertFalse("Responses 协议不携带思考等级参数", json.has("reasoning_effort"));
    }

    /** 引擎在服务端拒绝 reasoning_effort（HTTP 400）后用该形态去参重发。 */
    @Test
    public void compatibleBodyWithoutThinkingEffortOmitsTheField() throws Exception {
        String body = Protocols.requestBody(Config.PROTOCOL_OPENAI_COMPATIBLE,
                "hii", null, "m", false);
        JSONObject json = new JSONObject(body);
        assertFalse(json.has("reasoning_effort"));
        assertTrue(json.getBoolean("stream"));
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
                Protocols.extractTranslation(Config.PROTOCOL_OPENAI_COMPATIBLE, sse));
    }

    /** 服务端忽略 stream 参数回普通 JSON 时，提取路径保持原样。 */
    @Test
    public void nonStreamedJsonResponseStillExtractsViaMessageContent() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"普通\"}}]}";
        assertEquals("普通",
                Protocols.extractTranslation(Config.PROTOCOL_OPENAI_COMPATIBLE, json));
    }

    /** 只收到 role/usage 帧时累积为空，由引擎报“提取到的译文为空”。 */
    @Test
    public void streamedResponseWithoutContentDeltasIsEmpty() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n"
                + "data: {\"usage\":{\"total_tokens\":10},\"choices\":[]}\n"
                + "data: [DONE]\n";
        assertEquals("",
                Protocols.extractTranslation(Config.PROTOCOL_OPENAI_COMPATIBLE, sse));
    }

    /**
     * 提示词与原文含引号、换行、反斜杠时必须无损往返（协议层负责 JSON 转义）。
     */
    @Test
    public void hostileTextRoundTripsThroughBothProtocols() throws Exception {
        String nasty = "Hello, \"world\"\\\nline2\t\u0001";
        String prompt = "规则 1：不得输出 \"翻译结果：\" 前缀\n第二行";

        JSONObject compat = new JSONObject(Protocols.requestBody(
                Config.PROTOCOL_OPENAI_COMPATIBLE, nasty, prompt, "m"));
        assertEquals(nasty, compat.getJSONArray("messages").getJSONObject(1)
                .getString("content"));
        assertEquals(prompt, compat.getJSONArray("messages").getJSONObject(0)
                .getString("content"));

        JSONObject responses = new JSONObject(Protocols.requestBody(
                Config.PROTOCOL_OPENAI_RESPONSES, nasty, prompt, "m"));
        assertEquals(nasty, responses.getString("input"));
        assertEquals(prompt, responses.getString("instructions"));
    }

    // ------------------------------------------------------------ 端点解析

    @Test
    public void endpointSwapsKnownSuffixWhenProtocolDiffers() {
        assertEquals("https://api.openai.com/v1/responses",
                Protocols.endpoint(Config.PROTOCOL_OPENAI_RESPONSES,
                        "https://api.openai.com/v1/chat/completions"));
        assertEquals("https://api.openai.com/v1/chat/completions",
                Protocols.endpoint(Config.PROTOCOL_OPENAI_COMPATIBLE,
                        "https://api.openai.com/v1/responses"));
    }

    /** 同协议或未知后缀（自定义网关）一律不改写；尾随斜杠仅做规整。 */
    @Test
    public void endpointLeavesMatchingOrUnknownUrlsAlone() {
        assertEquals("https://api.openai.com/v1/responses",
                Protocols.endpoint(Config.PROTOCOL_OPENAI_RESPONSES,
                        "https://api.openai.com/v1/responses/"));
        assertEquals("https://gw.example.com/translate",
                Protocols.endpoint(Config.PROTOCOL_OPENAI_RESPONSES,
                        "https://gw.example.com/translate"));
        assertEquals("https://api.openai.com/v1/chat/completions",
                Protocols.endpoint(Config.PROTOCOL_OPENAI_COMPATIBLE,
                        "https://api.openai.com/v1/chat/completions/"));
        // 后缀匹配不区分大小写。
        assertEquals("https://api.openai.com/v1/responses",
                Protocols.endpoint(Config.PROTOCOL_OPENAI_RESPONSES,
                        "https://api.openai.com/v1/Chat/Completions"));
    }

    // ------------------------------------------------------------ 译文提取

    /** Compatible 提取路径固定为 choices.0.message.content。 */
    @Test
    public void compatibleExtractionReadsFirstChoiceContent() throws Exception {
        String resp = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"你好\"}}]}";
        assertEquals("你好",
                Protocols.extractTranslation(Config.PROTOCOL_OPENAI_COMPATIBLE, resp));
    }

    /**
     * Responses 响应的 reasoning 项排在 message 项之前（推理模型官方结构），
     * 必须按类型扫描而不是固定 output.0 下标。
     */
    @Test
    public void responsesExtractionSkipsReasoningItems() throws Exception {
        String resp = "{\"id\":\"resp_1\",\"object\":\"response\",\"output\":["
                + "{\"id\":\"rs_1\",\"type\":\"reasoning\",\"content\":[],\"summary\":[]},"
                + "{\"id\":\"msg_1\",\"type\":\"message\",\"status\":\"completed\","
                + "\"content\":[{\"type\":\"output_text\",\"annotations\":[],"
                + "\"logprobs\":[],\"text\":\"你好，世界\"}],\"role\":\"assistant\"}]}";
        assertEquals("你好，世界",
                Protocols.extractTranslation(Config.PROTOCOL_OPENAI_RESPONSES, resp));
    }

    /** 多段 output_text 时取第一段非空文本。 */
    @Test
    public void responsesExtractionPrefersFirstNonEmptyText() throws Exception {
        String resp = "{\"output\":[{\"type\":\"message\",\"content\":["
                + "{\"type\":\"output_text\",\"text\":\"\"},"
                + "{\"type\":\"output_text\",\"text\":\"第二段\"}]}]}";
        assertEquals("第二段",
                Protocols.extractTranslation(Config.PROTOCOL_OPENAI_RESPONSES, resp));
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
            Protocols.extractTranslation(Config.PROTOCOL_OPENAI_RESPONSES, body);
            fail("应抛出 TranslationException: " + body);
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.PARSE, e.kind());
        }
    }

    // ------------------------------------------------------------ 空值防御

    @Test
    public void nullInputsAreDefendedWithoutCrashing() throws Exception {
        JSONObject json = new JSONObject(Protocols.requestBody(
                Config.PROTOCOL_OPENAI_COMPATIBLE, null, null, "m"));
        assertEquals("", json.getJSONArray("messages").getJSONObject(1).getString("content"));
        assertEquals("", json.getJSONArray("messages").getJSONObject(0).getString("content"));
        assertEquals("", Protocols.endpoint(Config.PROTOCOL_OPENAI_COMPATIBLE, null));
        // 空白 URL 原样返回；实际请求前由 JsonApiEngine.validateConfig 拦截。
        assertEquals("  ", Protocols.endpoint(Config.PROTOCOL_OPENAI_COMPATIBLE, "  "));
    }
}
