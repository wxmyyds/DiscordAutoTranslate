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

    /** 段内夹带的标记碎片（开/闭均算）：协议被破坏，会把标记泄漏成字面量。 */
    private static final Pattern STRAY_MARKER = Pattern.compile("</?s\\s*\\d");

    /**
     * 模型幻觉出的模板占位符（如 {@code XXXXXX}）。
     *
     * <p>实测小模型在长重复文本上会输出 {@code SENSENOVA_..._HHMMSS_XXXXXX}
     * 这类占位符；正常译文不会出现 4 个以上连续大写 X。</p>
     */
    private static final Pattern PLACEHOLDER_RUN = Pattern.compile("X{4,}");

    /** 单次请求回调：发送给定文本并返回协议提取后的译文；失败抛异常。 */
    public interface RoundTrip {
        String roundTrip(String text) throws Exception;
    }

    /** {@link #tryParse} 的结果：要么 parts 非空，要么 violation 说明拒绝原因。 */
    public static final class Result {
        /** 按编号顺序排列的片段；协议违规时为 null。 */
        @Nullable
        public final List<String> parts;
        /** 违规原因（供日志定位）；解析成功时为 null。 */
        @Nullable
        public final String violation;

        Result(@Nullable List<String> parts, @Nullable String violation) {
            this.parts = parts;
            this.violation = violation;
        }

        public boolean ok() {
            return parts != null;
        }
    }

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
     * @see #tryParse
     */
    @Nullable
    public static List<String> parse(@Nullable String translated, int parts) {
        return tryParse(translated, parts).parts;
    }

    /**
     * 请求文本里有多少个分段标记（请求由本模块生成，形态规整，
     * 数量即 {@link TextExtractor} 提取侧给出的段数）。
     */
    public static int markCount(@Nullable String text) {
        if (text == null) {
            return 0;
        }
        Matcher m = OPEN.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /**
     * 严格解析并报告违规原因。
     *
     * <p>编号必须按 {@code 1..parts} 的文档顺序逐一出现，每段内容非空、
     * 不夹带标记碎片、不含模板占位符，末段之后也不得残留标记碎片。
     * 任何一条不满足即协议被模型破坏——此时输出不可信（实测：内容复读、
     * 尾部乱码、标记泄漏、占位符幻觉），调用方必须安全回退。</p>
     *
     * @return 成功时 parts 非空；失败时 violation 说明原因
     */
    public static Result tryParse(@Nullable String translated, int parts) {
        if (translated == null || parts <= 0) {
            return new Result(null, "empty input");
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
                return new Result(null, "bad index");
            }
            if (index < 1 || index > parts) {
                return new Result(null, "index " + index + " out of range 1.." + parts);
            }
            if (index != found + 1) {
                return new Result(null, "index " + index + " out of order (expected "
                        + (found + 1) + ")");
            }
            int contentStart = open.end();
            int close = findClose(translated, index, contentStart);
            if (close < 0) {
                return new Result(null, "missing close tag for <s" + index + ">");
            }
            String content = translated.substring(contentStart, close);
            if (content.trim().isEmpty()) {
                // 被标记的段落在提取侧永不为空（空段落不占标记）；
                // 空段 = 模型丢弃了该段内容。
                return new Result(null, "segment " + index + " is empty");
            }
            if (STRAY_MARKER.matcher(content).find()) {
                return new Result(null, "stray marker inside segment " + index);
            }
            if (PLACEHOLDER_RUN.matcher(content).find()) {
                return new Result(null, "placeholder run inside segment " + index);
            }
            slots[index] = content.trim();
            found++;
            searchFrom = close;
            tailFrom = skipClose(translated, index, close);
        }
        if (found != parts) {
            return new Result(null, "found " + found + " of " + parts + " segments");
        }
        if (STRAY_MARKER.matcher(translated).region(tailFrom, translated.length()).find()) {
            return new Result(null, "stray marker after last segment");
        }
        List<String> out = new ArrayList<>(parts);
        for (int i = 1; i <= parts; i++) {
            if (slots[i] == null) {
                return new Result(null, "segment " + i + " missing");
            }
            out.add(slots[i]);
        }
        return new Result(out, null);
    }

    /**
     * 分段请求的完整翻译策略：标记协议校验失败时自动降级一次。
     *
     * <p>小模型在多段消息上经常丢失或改写标记（实测 parts=12 的消息回得
     * 支离破碎），直接整条放弃会让消息永远显示原文。策略改为：</p>
     * <ol>
     *   <li>按原请求发送；译文通过 {@link #tryParse} 校验则原样返回；</li>
     *   <li>校验失败则用<b>无标记纯文本</b>重发一次（模型没有协议可破坏，
     *       走久经验证的整段翻译路径），重试译文须通过
     *       {@link #plainTextViolation} 防损坏校验；</li>
     *   <li>重试译文也不合格则抛 {@link TranslationException}（PARSE），
     *       宁可显示原文也不显示损坏内容。</li>
     * </ol>
     *
     * @param requestText 本次请求文本（可能带分段标记）
     * @param rt          单次请求回调（内部处理协议/认证/提取）
     * @return 校验通过的译文（带标记或无标记）
     */
    public static String translateWithFallback(String requestText, RoundTrip rt)
            throws Exception {
        String translated = rt.roundTrip(requestText);
        if (!isSegmented(requestText)) {
            return translated;
        }
        int parts = markCount(requestText);
        Result r = tryParse(translated, parts);
        if (r.parts != null) {
            return translated;
        }
        XLog.i("segment protocol violated (" + r.violation
                + "); retrying once without markers");
        String plain = strip(requestText);
        if (plain.trim().isEmpty()) {
            // 理论上不可达（分段请求必有非空内容）：保守返回首答。
            return translated;
        }
        String retried = rt.roundTrip(plain);
        String violation = plainTextViolation(retried, plain);
        if (violation != null) {
            throw new TranslationException(TranslationException.Kind.PARSE,
                    "无标记重试译文未通过校验：" + violation);
        }
        return retried;
    }

    /**
     * 无标记译文的防损坏校验：非空、无残留标记、无占位符、长度比例合理。
     *
     * <p>长度比例只对较长的原文（≥40 字符）生效：正常中英互译长度在
     * 0.25×～3× 之间，复读刷屏会显著超出，内容丢失会显著不足；
     * 过短的文本比例噪声太大，不做校验。</p>
     *
     * @return 违规描述；合格返回 null
     */
    @Nullable
    static String plainTextViolation(@Nullable String translated, String source) {
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
