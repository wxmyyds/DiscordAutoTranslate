package com.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 译文净化与防损坏校验。
 *
 * <p><b>历史背景</b>：本类曾实现「分段标记协议」——请求给每个可翻译文本节点
 * 套 {@code <sN>…</sN>} 标记、译文按编号一一取回。单链路重构（一次无标记
 * 纯文本请求 + 渲染层按结构对齐回填，见 {@code ContentNodes}）后，请求侧
 * 机器已删除，这里只保留两件事：</p>
 * <ul>
 *   <li>{@link #plainTextViolation}：引擎侧防损坏校验——残留标记、模板占位符、
 *       长度比例异常（复读/大面积丢失）的译文直接判失败，宁可显示原文也不把
 *       损坏内容送进节点树；</li>
 *   <li>{@link #strip}：防御性剥离译文中的分段标记。正常链路下引擎校验已
 *       保证译文无标记，这里是渲染侧的最后防线（标记若不剥离，会作为
 *       {@code <s1>} 字面量直接显示在消息里）。</li>
 * </ul>
 */
public final class Segments {

    /** 形如 {@code <s3>} 的开标记。 */
    private static final Pattern OPEN = Pattern.compile("<s\\s*(\\d+)\\s*>");

    /** 段内夹带的标记碎片（开/闭均算）：协议被破坏，会把标记泄漏成字面量。 */
    private static final Pattern STRAY_MARKER = Pattern.compile("</?s\\s*\\d");

    /**
     * 模型幻觉出的模板占位符（如 {@code XXXXXX}）。
     *
     * <p>实测小模型在长重复文本上会输出 {@code SENSENOVA_..._HHMMSS_XXXXXX}
     * 这类占位符；正常译文不会出现 4 个以上连续大写 X。</p>
     */
    private static final Pattern PLACEHOLDER_RUN = Pattern.compile("X{4,}");

    private Segments() {
    }

    /**
     * 去掉文本中所有分段标记，只保留译文内容。
     *
     * <p>标记之间的内容按顺序拼接，保留标记外的字符（换行等）。
     * 正常链路下引擎校验已保证译文无标记；若模型凭空幻觉出标记，
     * 这里负责不让它们以字面量显示给用户。</p>
     */
    public static String strip(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            Matcher open = OPEN.matcher(text);
            open.region(i, text.length());
            if (!open.find()) {
                out.append(text, i, text.length());
                break;
            }
            out.append(text, i, open.start());
            int index;
            try {
                index = Integer.parseInt(open.group(1));
            } catch (NumberFormatException e) {
                i = open.end();
                continue;
            }
            int contentStart = open.end();
            int close = findClose(text, index, contentStart);
            if (close < 0) {
                // 闭合标记缺失：丢弃这个开标记，其余内容照常保留。
                i = contentStart;
                continue;
            }
            out.append(text, contentStart, close);
            i = skipClose(text, index, close);
        }
        return out.toString();
    }

    /** 返回闭合标记之后的起始下标。 */
    private static int skipClose(String text, int index, int closeStart) {
        Matcher m = Pattern.compile("</s\\s*" + index + "\\s*>").matcher(text);
        m.region(closeStart, text.length());
        return m.lookingAt() ? m.end() : closeStart;
    }

    /**
     * 无标记译文的防损坏校验：非空、无残留标记、无占位符、长度比例合理。
     *
     * <p>单链路（无标记纯文本请求）在引擎侧返回前调用：复读刷屏（长度
     * 暴涨）、内容大面积丢失（长度骤减）、标记泄漏与模板占位符都在这里
     * 拦下，判失败由用户手动重试，而不是把损坏译文送进节点树。</p>
     *
     * <p>长度比例只对较长的原文（≥40 字符）生效：正常中英互译长度在
     * 0.25×～3× 之间，复读刷屏会显著超出，内容丢失会显著不足；
     * 过短的文本比例噪声太大，不做校验。</p>
     *
     * @return 违规描述；合格返回 null
     */
    @Nullable
    public static String plainTextViolation(@Nullable String translated, String source) {
        if (translated == null || translated.trim().isEmpty()) {
            return "译文为空";
        }
        if (STRAY_MARKER.matcher(translated).find()) {
            return "译文中残留分段标记";
        }
        if (PLACEHOLDER_RUN.matcher(translated).find()) {
            return "译文含模板占位符";
        }
        int n = source.length();
        if (n >= 40) {
            int m = translated.length();
            if (m * 4 < n) {
                return "译文长度异常（" + m + "/" + n + "）：疑似内容丢失";
            }
            if (m > n * 3) {
                return "译文长度异常（" + m + "/" + n + "）：疑似复读";
            }
        }
        return null;
    }

    /** 找到与开标记配对的下一个 {@code </sN>}。 */
    private static int findClose(String text, int index, int from) {
        String close = "</s" + index + ">";
        int best = text.indexOf(close, from);
        if (best >= 0) {
            return best;
        }
        // 容忍模型在闭合标记内加了空白，例如 </s1 >
        Matcher m = Pattern.compile("</s\\s*" + index + "\\s*>").matcher(text);
        return m.find(from) ? m.start() : -1;
    }
}
