package com.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分段标记协议：让一次请求同时保真「段落结构」与「内联样式」。
 *
 * <p><b>要解决的问题</b>：一条消息的正文往往由多个 {@code TextContentNode} 组成，
 * 它们之间靠 {@code NewLineContentNode} / {@code LineBreakContentNode} 分隔，
 * 并各自嵌在 {@code Strong} / {@code Heading} / {@code BlockQuote} 等样式容器里。
 * 旧实现把整条译文塞进第一个文本节点、其余置空，于是：</p>
 * <ul>
 *   <li>被置空的文本节点连同其外层样式容器一起被当作尾部空白裁掉——<b>样式丢失</b>；</li>
 *   <li>中间的换行节点同样被裁掉——<b>多行被合并成一行</b>。</li>
 * </ul>
 *
 * <p><b>做法</b>：发出请求时给每个可翻译文本节点套上 {@code <sN>…</sN>} 标记
 * （{@code N} 从 1 开始，按出现顺序编号），节点之间的结构字符（换行、列表项符号、
 * 提及/表情留下的空格）留在标记之外。模型逐段翻译并保留标记，回填时按编号
 * 一一取回，于是每个文本节点都拿到自己的译文片段，样式容器与换行结构自然保留。</p>
 *
 * <p><b>边界</b>：只有当一条消息存在 ≥2 个可翻译文本节点时才加标记；单节点消息
 * 保持原样，避免给常见情况引入无意义的标记噪音。解析是全有或全无：任何缺号、
 * 重复、错序、空段、标记夹带或占位符都判定协议被模型破坏，调用方必须保留
 * 原文（不替换），绝不能把损坏的译文显示给用户。</p>
 */
public final class Segments {

    /** 附在系统提示词末尾的协议说明；仅在本次请求含标记时追加。 */
    public static final String RULE =
            "The input is split into segments, each wrapped as <sN>...</sN> where N is a number.\n"
            + "Translate the text inside every segment and keep the segment structure exactly:\n"
            + "1. Keep every <sN> tag and its number unchanged; do not merge, drop, reorder, "
            + "renumber, or add tags.\n"
            + "2. Translate each segment in place; the segments together form one message, "
            + "so keep the overall meaning, tone and terminology consistent across them.\n"
            + "3. Keep the characters outside the tags (line breaks, list markers, spaces) "
            + "where they are.\n"
            + "4. Output only the tagged translation, nothing else.";

    /** 形如 {@code <s3>} 的开标记。 */
    private static final Pattern OPEN = Pattern.compile("<s\\s*(\\d+)\\s*>");

    /** 段内容里夹带的标记碎片（开/闭均算）：协议被破坏，会把标记泄漏成字面量。 */
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

    /** 包住第 {@code index} 段（从 1 开始）的文本。 */
    public static String wrap(int index, String content) {
        String tag = "<s" + index + ">";
        return tag + content + "</s" + index + ">";
    }

    /** 文本里是否已含本协议的标记（用于决定是否追加 {@link #RULE}）。 */
    public static boolean isSegmented(@Nullable String text) {
        return text != null && OPEN.matcher(text).find();
    }

    /**
     * 去掉文本中所有分段标记，只保留译文内容。
     *
     * <p>用于解析失败后的回退：此时标记不会被回填消费，若原样送去渲染，
     * {@code <s1>} 会作为字面量直接显示在消息里。标记之间的内容按顺序拼接，
     * 保留标记外的字符（换行等），使回退结果与无标记请求基本一致。</p>
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
     * 从译文里解析出 {@code parts} 个片段。
     *
     * <p>校验是严格的：编号必须按 {@code 1..parts} 的文档顺序逐一出现，
     * 每段内容非空、不夹带标记碎片、不含模板占位符，末段之后也不得残留
     * 标记碎片。任何一条不满足都返回 {@code null}——协议已被模型破坏，
     * 此时输出不可信（实测：内容复读、尾部乱码、标记泄漏、占位符幻觉），
     * 调用方必须安全回退为保留原文，不能把损坏结果切分后硬塞回节点树。</p>
     *
     * @param translated 模型返回的带标记译文
     * @param parts      期望的片段数（= 原文可翻译段落块数，见
     *                   {@link TextExtractor#countSegments}）
     * @return 按编号顺序排列的片段（每段去掉首尾空白——提取侧的块本来就是
     *         边缘无空白的，段内边缘空白是模型噪音）；任何违规返回 {@code null}
     */
    @Nullable
    public static List<String> parse(@Nullable String translated, int parts) {
        if (translated == null || parts <= 0) {
            return null;
        }
        Matcher open = OPEN.matcher(translated);
        String[] slots = new String[parts + 1];
        int found = 0;
        int searchFrom = 0;
        int tailFrom = 0;
        while (open.find(searchFrom)) {
            int index;
            try {
                index = Integer.parseInt(open.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
            if (index < 1 || index > parts) {
                return null; // 越界编号：协议被破坏
            }
            if (index != found + 1) {
                // 错序或重复：模型重排/改写了段落，编号与原文位置无法对齐。
                return null;
            }
            int contentStart = open.end();
            int close = findClose(translated, index, contentStart);
            if (close < 0) {
                return null;
            }
            String content = translated.substring(contentStart, close);
            if (content.trim().isEmpty()) {
                // 被标记的段落在提取侧永不为空（空段落不占标记）；
                // 空段 = 模型丢弃了该段内容。
                return null;
            }
            if (STRAY_MARKER.matcher(content).find()) {
                // 段内夹带 <sN> / </sN>：回填后标记会作为字面量显示。
                return null;
            }
            if (PLACEHOLDER_RUN.matcher(content).find()) {
                // 模型幻觉的模板占位符（XXXXXX 之类）。
                return null;
            }
            slots[index] = content.trim();
            found++;
            searchFrom = close;
            tailFrom = skipClose(translated, index, close);
        }
        if (found != parts) {
            return null;
        }
        if (STRAY_MARKER.matcher(translated).region(tailFrom, translated.length()).find()) {
            // 末段之后仍残留标记碎片：模型输出了额外/错位的段，
            // 正文可能已被挪到段外（会被静默丢弃），整体拒绝。
            return null;
        }
        List<String> out = new ArrayList<>(parts);
        for (int i = 1; i <= parts; i++) {
            if (slots[i] == null) {
                return null;
            }
            out.add(slots[i]);
        }
        return out;
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
