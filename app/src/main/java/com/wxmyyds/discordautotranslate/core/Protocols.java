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
 *       {@code frequency_penalty:0.2}（与旧版默认模板逐字段等价），
 *       译文取 {@code choices.0.message.content}。</li>
 *   <li>{@link Config#PROTOCOL_OPENAI_RESPONSES}：{@code POST /v1/responses}，
 *       请求体为 {@code instructions} + {@code input} + {@code temperature:0}。
 *       不携带 {@code frequency_penalty}：OpenAI 官方 Responses 接口不接受该参数。
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

    private static final String SUFFIX_CHAT_COMPLETIONS = "/chat/completions";
    private static final String SUFFIX_RESPONSES = "/responses";

    private Protocols() {
    }

    /**
     * 构造请求体 JSON。
     *
     * @throws TranslationException 理论上仅在 org.json 拒绝值时发生，统一归类为配置错误
     */
    public static String requestBody(String protocol, String text,
                                     @Nullable String systemPrompt, String model)
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
     * @throws TranslationException 响应不是合法 JSON 或结构中取不到译文
     */
    public static String extractTranslation(String protocol, String responseBody)
            throws TranslationException {
        if (Config.PROTOCOL_OPENAI_RESPONSES.equals(protocol)) {
            return extractResponsesText(responseBody);
        }
        return JsonPath.extractString(responseBody, "choices.0.message.content");
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
