package io.github.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * 响应译文的按路径提取（需求 §5.5 的“JSON 响应中译文的提取路径”）。
 *
 * <p>路径语法：以 {@code .} 分隔的段。
 * <ul>
 *   <li>对象字段：直接写字段名，例如 {@code choices}。</li>
 *   <li>数组下标：写十进制整数，例如 {@code 0}。</li>
 *   <li>示例：{@code choices.0.message.content}</li>
 * </ul>
 *
 * <p>限制：字段名本身包含 {@code .} 的情况无法用本语法表达；
 * 第一版按此简化处理，并在取不到值时给出明确错误。</p>
 */
public final class JsonPath {

    private JsonPath() {
    }

    /**
     * 从 JSON 文本中按路径提取字符串值。
     *
     * @throws TranslationException 解析失败或路径取不到值
     */
    public static String extractString(String json, @Nullable String path) throws TranslationException {
        if (path == null || path.trim().isEmpty()) {
            throw new TranslationException(TranslationException.Kind.CONFIG,
                    "响应提取路径为空");
        }
        Object root = parse(json);
        Object cur = root;
        String[] segments = path.trim().split("\\.");

        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];
            if (cur == null) {
                throw notFound(path, i, seg, "上一段为空");
            }
            if (cur instanceof JSONObject) {
                JSONObject obj = (JSONObject) cur;
                if (!obj.has(seg)) {
                    throw notFound(path, i, seg, "字段不存在");
                }
                cur = obj.opt(seg);
            } else if (cur instanceof JSONArray) {
                JSONArray arr = (JSONArray) cur;
                int idx = parseInt(seg, path);
                if (idx < 0 || idx >= arr.length()) {
                    throw notFound(path, i, seg, "下标越界（数组长度 " + arr.length() + "）");
                }
                cur = arr.opt(idx);
            } else {
                throw notFound(path, i, seg, "当前值不是对象也不是数组");
            }
        }

        return stringify(cur, path);
    }

    /** 校验一段文本是否为完整合法 JSON（允许尾随空白）。 */
    public static void validateJson(String text, String what) throws TranslationException {
        if (text == null) {
            throw new TranslationException(TranslationException.Kind.CONFIG,
                    what + "不是完整合法 JSON：输入为空");
        }
        try {
            JSONTokener tokener = new JSONTokener(text);
            tokener.nextValue();
            if (tokener.nextClean() != 0) {
                throw new JSONException("JSON 后存在额外内容");
            }
        } catch (JSONException e) {
            throw new TranslationException(TranslationException.Kind.CONFIG,
                    what + "不是完整合法 JSON：" + e.getMessage(), e);
        }
    }

    /** 解析完整 JSON 文本；只允许尾随空白。供协议层做结构化提取复用。 */
    static Object parse(String json) throws TranslationException {
        if (json == null || json.trim().isEmpty()) {
            throw new TranslationException(TranslationException.Kind.PARSE, "响应体为空");
        }
        try {
            JSONTokener tokener = new JSONTokener(json);
            Object value = tokener.nextValue();
            if (tokener.nextClean() != 0) {
                throw new JSONException("JSON 后存在额外内容");
            }
            return value;
        } catch (JSONException e) {
            throw new TranslationException(TranslationException.Kind.PARSE,
                    "响应不是完整合法 JSON", e);
        }
    }

    @Nullable
    private static String stringify(@Nullable Object value, String path) throws TranslationException {
        if (value == null || value == JSONObject.NULL) {
            throw new TranslationException(TranslationException.Kind.PARSE,
                    "路径 " + path + " 的值为 null");
        }
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof JSONObject || value instanceof JSONArray) {
            // 取到结构化值时返回其 JSON 文本，便于诊断路径配置错误。
            return value.toString();
        }
        return String.valueOf(value);
    }

    private static int parseInt(String seg, String path) throws TranslationException {
        try {
            return Integer.parseInt(seg);
        } catch (NumberFormatException e) {
            throw new TranslationException(TranslationException.Kind.PARSE,
                    "路径 " + path + " 中的段 '" + seg + "' 既不是字段名也不是合法数组下标", e);
        }
    }

    private static TranslationException notFound(String path, int index, String seg, String why) {
        return new TranslationException(TranslationException.Kind.PARSE,
                "按路径 " + path + " 提取失败：第 " + (index + 1) + " 段 '" + seg + "' " + why);
    }
}
