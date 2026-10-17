package com.wxmyyds.discordautotranslate.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通用 JSON 请求模板引擎（需求 §5.5）。
 *
 * <p>模板占位符：{@code {{name}}}，渲染时替换为对应值。
 * 内置变量：
 * <ul>
 *   <li>{@code {{text}}} —— 待翻译原文（自动做 JSON 字符串转义）</li>
 *   <li>{@code {{target}}} —— 目标语言</li>
 *   <li>{@code {{model}}} —— 模型名</li>
 * </ul>
 *
 * <p>默认模板按 OpenAI 兼容 {@code /chat/completions} 设计（需求 §9.5 确认项）。</p>
 *
 * <h3>转义规则（关键安全点）</h3>
 * <p>消息正文可能包含引号、反斜杠、换行、制表符以及 {@code U+0000..U+001F} 控制字符。
 * 若直接拼接会破坏 JSON 结构，导致请求被服务端拒绝或语义错乱。
 * 因此 {@code {{text}}} 的替换值一律经过 {@link #jsonEscape(String)} 处理，
 * 由模板作者直接把它放进引号内使用（默认模板即如此）。</p>
 *
 * <p>未识别的 {@code {{xxx}}} 占位符会保留原样，并在 {@link #render} 返回的
 * {@link RenderResult#unknownPlaceholders} 中报告，由调用方决定是否视为配置错误。</p>
 */
public final class JsonTemplate {

    /** 渲染结果。 */
    public static final class RenderResult {
        public final String body;
        /** 模板中出现但未被替换的占位符名（去重，保持出现顺序）。 */
        public final java.util.List<String> unknownPlaceholders;

        RenderResult(String body, java.util.List<String> unknown) {
            this.body = body;
            this.unknownPlaceholders = unknown;
        }

        public boolean hasUnknownPlaceholders() {
            return !unknownPlaceholders.isEmpty();
        }
    }

    private JsonTemplate() {
    }

    /**
     * 渲染请求体。
     *
     * @param template    模板文本
     * @param text        原文（会自动 JSON 转义）
     * @param targetLang  目标语言（会自动 JSON 转义）
     * @param model       模型名（会自动 JSON 转义）
     */
    public static RenderResult render(String template, String text,
                                      String targetLang, String model) {
        if (template == null) {
            template = "";
        }
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("text", jsonEscape(text));
        vars.put("target", jsonEscape(targetLang));
        vars.put("model", jsonEscape(model));

        StringBuilder out = new StringBuilder(template.length() + 64);
        java.util.List<String> unknown = new java.util.ArrayList<>();
        int i = 0;
        final int n = template.length();

        while (i < n) {
            int open = template.indexOf("{{", i);
            if (open < 0) {
                out.append(template, i, n);
                break;
            }
            out.append(template, i, open);
            int close = template.indexOf("}}", open + 2);
            if (close < 0) {
                // 没有闭合，按普通文本处理，避免吞掉内容。
                out.append(template, open, n);
                break;
            }
            String name = template.substring(open + 2, close).trim();
            String value = vars.get(name);
            if (value != null) {
                out.append(value);
            } else {
                // 未知占位符原样保留并记录。
                if (!unknown.contains(name)) {
                    unknown.add(name);
                }
                out.append(template, open, close + 2);
            }
            i = close + 2;
        }
        return new RenderResult(out.toString(), unknown);
    }

    /**
     * JSON 字符串转义（不含首尾引号）。
     *
     * <p>覆盖 RFC 8259 要求的转义：{@code " \ } 与所有控制字符。
     * 同时转义 {@code /} 之外的常见可读性问题不处理，保持可读。</p>
     */
    public static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /**
     * 校验模板：占位符是否都被识别、渲染后是否为合法 JSON。
     *
     * @throws TranslationException 校验不通过
     */
    public static void validate(String template, String targetLang, String model)
            throws TranslationException {
        if (template == null || template.trim().isEmpty()) {
            throw new TranslationException(TranslationException.Kind.CONFIG, "请求模板为空");
        }
        if (!template.contains("{{text}}")) {
            throw new TranslationException(TranslationException.Kind.CONFIG,
                    "请求模板缺少 {{text}} 占位符，消息正文不会被发送");
        }
        RenderResult r = render(template, "test", targetLang, model);
        if (r.hasUnknownPlaceholders()) {
            throw new TranslationException(TranslationException.Kind.CONFIG,
                    "请求模板含未知占位符：" + String.join(", ", r.unknownPlaceholders));
        }
        JsonPath.validateJson(r.body, "渲染后的请求体");
    }
}
