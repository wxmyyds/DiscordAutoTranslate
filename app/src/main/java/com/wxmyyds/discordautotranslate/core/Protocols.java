package com.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 内置供应商协议：按 {@link Config#KEY_PROTOCOL} 构造请求体、
 * 解析请求端点并从响应中提取译文。
 *
 * <p>取代旧版「JSON 请求模板 + 译文提取路径」的通用配置：
 * 每个协议的请求格式与解析逻辑固定，用户不再手写 JSON。</p>
 *
 * <ul>
 *   <li>{@link Config#PROTOCOL_OPENAI_COMPATIBLE}：{@code POST /v1/chat/completions}，
 *       请求体为 {@code messages}（system + user）+ {@code temperature:0} +
 *       {@code frequency_penalty:0.2} + {@code stream:true}（采样参数与旧版默认
 *       模板逐字段等价；流式是为避免慢速供应商上长消息被总时长超时掐断），
 *       译文从 SSE 帧累积 {@code choices.0.delta.content}，非流式 JSON 回退到
 *       {@code choices.0.message.content}。</li>
 *   <li>{@link Config#PROTOCOL_OPENAI_RESPONSES}：{@code POST /v1/responses}，
 *       请求体为 {@code instructions} + {@code input} + {@code temperature:0}。
 *       不携带 {@code frequency_penalty}：OpenAI 官方 Responses 接口不接受该参数。
 *       保持非流式（其流式事件格式不同，暂未支持）。
 *       译文在 {@code output} 数组中按类型扫描（推理模型会把 reasoning 项排在
 *       message 项之前，不能固定取 {@code output.0}）。</li>
 * </ul>
 *
 * <p>请求体用 org.json 构造，引号/换行/控制字符的转义由其保证，
 * 对应旧版 {@code JsonTemplate.jsonEscape} 的职责。</p>
 */
public final class Protocols {

    /** 旧版默认模板的采样参数：temperature 0 降低采样随机性（不保证服务端完全确定）。 */
    private static final int TEMPERATURE = 0;

    /** 轻度频率惩罚仅抑制重复，不承担“只翻译”约束；兼容接口需支持此参数。 */
    private static final double FREQUENCY_PENALTY = 0.2;

    /**
     * 思考等级（OpenAI 事实标准字段）。翻译任务不需要深思考：推理型模型
     * 在默认/高思考下会把输出预算全花在思考上（实测 55s/196KB 帧零正文），
     * low 档实测秒级返回正文。不支持该字段的服务通常忽略它；个别严格校验的
     * 服务（如 OpenAI 非推理模型）会回 400，引擎侧收到 400 会去参重发一次。
     */
    public static final String REASONING_EFFORT_LOW = "low";

    private static final String SUFFIX_CHAT_COMPLETIONS = "/chat/completions";
    private static final String SUFFIX_RESPONSES = "/responses";

    private Protocols() {
    }

    /**
     * 构造请求体 JSON。
     *
     * <p>Compatible 协议携带 {@code stream:true}：非流式接口在生成完成前
     * 零字节返回，慢速供应商上长消息会被总时长超时掐断（实测 15s 读超时，
     * 同供应商聊天客户端流式发送很快）；流式下 token 持续到达，读超时按
     * 字节间隔计算，总生成时长不再受限。Responses 协议保持非流式
     * （其流式事件格式不同，暂未支持）。</p>
     *
     * @throws TranslationException 理论上仅在 org.json 拒绝值时发生，统一归类为配置错误
     */
    public static String requestBody(String protocol, String text,
                                     @Nullable String systemPrompt, String model)
            throws TranslationException {
        return requestBody(protocol, text, systemPrompt, model, true);
    }

    /**
     * 构造请求体 JSON（可控制是否携带思考等级参数）。
     *
     * @param suppressThinking true 时携带 {@code reasoning_effort:"low"}；
     *                          false 供引擎在服务端拒绝该参数（HTTP 400）后
     *                          去参重发使用
     */
    public static String requestBody(String protocol, String text,
                                     @Nullable String systemPrompt, String model,
                                     boolean suppressThinking)
            throws TranslationException {
        String safeText = text == null ? "" : text;
        String safePrompt = systemPrompt == null ? "" : systemPrompt;
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            if (Config.PROTOCOL_OPENAI_RESPONSES.equals(protocol)) {
                body.put("instructions", safePrompt);
                body.put("input", safeText);
                body.put("temperature", TEMPERATURE);
            } else {
                JSONArray messages = new JSONArray();
                JSONObject system = new JSONObject();
                system.put("role", "system");
                system.put("content", safePrompt);
                JSONObject user = new JSONObject();
                user.put("role", "user");
                user.put("content", safeText);
                messages.put(system);
                messages.put(user);
                body.put("messages", messages);
                body.put("temperature", TEMPERATURE);
                body.put("frequency_penalty", FREQUENCY_PENALTY);
                body.put("stream", true);
                if (suppressThinking) {
                    body.put("reasoning_effort", REASONING_EFFORT_LOW);
                }
            }
            return body.toString();
        } catch (JSONException e) {
            throw new TranslationException(TranslationException.Kind.CONFIG,
                    "构造请求体失败：" + e.getMessage(), e);
        }
    }

    /**
     * 解析当前协议实际请求的端点 URL。
     *
     * <p>API URL 仍由用户填写完整端点；但用户切换协议后旧端点若仍指向
     * 另一协议（{@code /chat/completions} 与 {@code /responses} 互换），
     * 请求必然失败。这里在请求前把已知的另一协议后缀替换为当前协议后缀，
     * 其余 URL（自定义网关路径等）原样使用。</p>
     */
    public static String endpoint(String protocol, String apiUrl) {
        if (apiUrl == null) {
            return "";
        }
        String url = apiUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.isEmpty()) {
            return apiUrl;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (Config.PROTOCOL_OPENAI_RESPONSES.equals(protocol)) {
            if (lower.endsWith(SUFFIX_CHAT_COMPLETIONS)) {
                return url.substring(0, url.length() - SUFFIX_CHAT_COMPLETIONS.length())
                        + SUFFIX_RESPONSES;
            }
        } else if (lower.endsWith(SUFFIX_RESPONSES)) {
            return url.substring(0, url.length() - SUFFIX_RESPONSES.length())
                    + SUFFIX_CHAT_COMPLETIONS;
        }
        return url;
    }

    /**
     * 从响应体提取译文。
     *
     * <p>SSE 流式响应（{@code data:} 帧开头）按帧累积
     * {@code choices.0.delta.content}；非流式 JSON（服务端忽略 stream 参数）
     * 走原有路径。</p>
     */
    public static String extractTranslation(String protocol, String responseBody)
            throws TranslationException {
        if (Config.PROTOCOL_OPENAI_RESPONSES.equals(protocol)) {
            return extractResponsesText(responseBody);
        }
        if (responseBody != null && responseBody.trim().startsWith("data:")) {
            return extractStreamedChatText(responseBody);
        }
        return JsonPath.extractString(responseBody, "choices.0.message.content");
    }

    /**
     * 累积 chat/completions 流式帧里的 delta 文本。
     *
     * <p>帧形态：{@code data: {"choices":[{"delta":{"content":"片"}}]}}，
     * 以 {@code data: [DONE]} 结束。role 帧/usage 帧的 delta.content 为空，
     * 跳过即可；错误帧没有 content，累积结果为空由引擎报“译文为空”。</p>
     */
    private static String extractStreamedChatText(String responseBody)
            throws TranslationException {
        StringBuilder out = new StringBuilder();
        try {
            for (String rawLine : responseBody.split("\n", -1)) {
                String line = rawLine.trim();
                if (!line.startsWith("data:")) {
                    continue; // 注释行（: keep-alive）与空行
                }
                String payload = line.substring("data:".length()).trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) {
                    continue;
                }
                JSONObject frame = new JSONObject(payload);
                JSONArray choices = frame.optJSONArray("choices");
                if (choices == null || choices.length() == 0) {
                    continue;
                }
                JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                if (delta == null) {
                    continue;
                }
                String piece = delta.optString("content", "");
                if (!piece.isEmpty()) {
                    out.append(piece);
                }
            }
        } catch (JSONException e) {
            throw new TranslationException(TranslationException.Kind.PARSE,
                    "流式响应帧不是合法 JSON：" + e.getMessage(), e);
        }
        return out.toString();
    }

    /**
     * Responses 协议提取：在 {@code output} 数组中找 {@code type=="message"} 的项，
     * 再在其 {@code content} 数组中找 {@code type=="output_text"} 的文本。
     *
     * <p>结构依据 OpenAI 官方参考（推理模型的 reasoning 项排在 message 项之前），
     * 因此按下标类型扫描而非固定路径。找到的文本即使为空也原样返回，
     * 由引擎统一给出“提取到的译文为空”错误。</p>
     */
    private static String extractResponsesText(String responseBody) throws TranslationException {
        Object root = JsonPath.parse(responseBody);
        if (!(root instanceof JSONObject)) {
            throw new TranslationException(TranslationException.Kind.PARSE,
                    "Responses 响应不是 JSON 对象");
        }
        JSONArray output = ((JSONObject) root).optJSONArray("output");
        if (output == null) {
            throw new TranslationException(TranslationException.Kind.PARSE,
                    "Responses 响应缺少 output 数组");
        }
        String fallback = null;
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            if (item == null || !"message".equals(item.optString("type"))) {
                continue;
            }
            JSONArray content = item.optJSONArray("content");
            if (content == null) {
                continue;
            }
            for (int j = 0; j < content.length(); j++) {
                JSONObject part = content.optJSONObject(j);
                if (part == null || !"output_text".equals(part.optString("type"))) {
                    continue;
                }
                String text = part.optString("text");
                if (!text.trim().isEmpty()) {
                    return text;
                }
                if (fallback == null) {
                    fallback = text;
                }
            }
        }
        if (fallback != null) {
            return fallback;
        }
        throw new TranslationException(TranslationException.Kind.PARSE,
                "Responses 响应中未找到 message/output_text 文本");
    }
}
