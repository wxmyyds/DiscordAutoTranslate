package io.github.wxmyyds.discordautotranslate.hooks;

import androidx.annotation.Nullable;

import io.github.wxmyyds.discordautotranslate.core.BlankLines;
import io.github.wxmyyds.discordautotranslate.core.Reflect;
import io.github.wxmyyds.discordautotranslate.core.Segments;
import io.github.wxmyyds.discordautotranslate.core.TextExtractor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Discord 消息内容结构的反射访问层（需求 §7 阶段 3）。
 *
 * <p>背景（已核对 smali 源码）：
 * {@code TextUtilsKt.toSpannable(StructurableText, ...)} 是所有文本渲染的唯一入口，
 * 且只有两个分支：
 * <ul>
 *   <li>{@code AnnotatedStructurableText} → 遍历 {@code List<ContentNode>} 渲染；</li>
 *   <li>{@code PrimitiveStructurableText} → 整体当纯文本渲染；</li>
 *   <li>其他类型 → {@code throw RuntimeException}。</li>
 * </ul>
 *
 * <p>早期做法是把带 Markdown 的消息整体替换成 {@code PrimitiveStructurableText}，
 * 不会崩溃（渲染器接受该类型），但会丢失全部 Markdown 格式。
 * 本类改为保留 {@code AnnotatedStructurableText} 类型，
 * 只把其中的 {@code TextContentNode}（自然语言）替换为译文节点，
 * 结构化节点（提及/表情/链接/代码块等）原样保留，从而保住格式。</p>
 *
 * <p>节点重建采用 Kotlin data class 的通用能力：
 * <ul>
 *   <li>用 {@code componentN()} 取出原节点的全部字段；</li>
 *   <li>只替换其中的内容字段（List 或 String）；</li>
 *   <li>调用 {@code copy(...)} 重建节点，保留其余字段（如 Heading 的 level、
 *       Link 的 LinkContextData、CodeBlock 的 lang）。</li>
 * </ul>
 * 只遍历与 {@link TextExtractor} 相同的格式容器；提及、频道、emoji 和未知节点
 * 即使含有子列表也整节点保留，不参与译文分配。</p>
 */
public final class ContentNodes {

    /** 代码类节点：内容不翻译，但需要保留。 */
    private static final java.util.Set<String> CODE_NODES = new java.util.HashSet<>(java.util.Arrays.asList(
            "InlineCodeContentNode",
            "CodeBlockContentNode"
    ));

    /** 列表节点：items 而非 content。 */
    private static final String BULLET_LIST = "BulletListContentNode";

    private ContentNodes() {
    }

    /** 会被宿主渲染为纯空白的节点（它们不留文本，只占一行高度）。 */
    private static final java.util.Set<String> BLANK_NODES = new java.util.HashSet<>(java.util.Arrays.asList(
            "NewLineContentNode",
            "LineBreakContentNode"
    ));

    // ---------------------------------------------------------------- 类型判断

    public static boolean isAnnotated(@Nullable Object structurableText) {
        return classNameOf(structurableText).endsWith("AnnotatedStructurableText");
    }

    public static boolean isPrimitive(@Nullable Object structurableText) {
        return classNameOf(structurableText).endsWith("PrimitiveStructurableText");
    }

    private static String classNameOf(@Nullable Object o) {
        return o == null ? "" : o.getClass().getName();
    }

    /** 取 AnnotatedStructurableText 的 List<ContentNode>；失败返回 null。 */
    @Nullable
    public static List<?> listOf(@Nullable Object structurableText) {
        if (!isAnnotated(structurableText)) {
            return null;
        }
        Object v = Reflect.call(
                Reflect.findMethod(
                        structurableText.getClass(), "getContent", 0), structurableText);
        return v instanceof List ? (List<?>) v : null;
    }

    // ---------------------------------------------------------------- 变换

    /**
     * 把节点列表中的自然语言文本片段替换为译文。
     *
     * <p>译文按唯一结构规则回填：URL、emoji 与样式标记必须按请求原样保留；
     * 每个列表项按一行对应，其余文本按段落块逐片段对应。任一锚点或结构
     * 不匹配就拒绝整条回填，原节点树不变。</p>
     *
     * @param nodes        原始节点列表
     * @param translated   协议返回的译文（引擎侧已通过防损坏校验）
     * @return 新的节点列表；无需变化、对齐失败或任一节点重建失败时返回
     *         null，调用方应保留原文
     */
    @Nullable
    public static List<Object> replaceText(@Nullable List<?> nodes, String translated) {
        if (nodes == null || translated == null || translated.trim().isEmpty()) {
            return null;
        }
        int targetCount = countTextNodes(nodes, 0);
        if (targetCount == 0) {
            return null;
        }
        // 锚点必须按原数量及顺序出现；各间隔只执行所属结构的唯一切分规则。
        if (!Segments.strip(translated).equals(translated)) {
            return null;
        }
        String plain = normalize(translated);
        if (plain.isEmpty()) {
            // 剔除后什么都不剩：不能清空消息，保留原文。
            return null;
        }
        AlignmentPlan plan = buildPlan(nodes);
        List<String> parts = alignTranslation(plain, plan);
        if (parts == null || parts.size() != targetCount) {
            return null;
        }
        // 按节点出现的位置分配片段，不能以实例身份为键：共享节点的后一次
        // 空片段会覆盖前一次译文，造成文字全部消失但 emoji 仍保留。
        Replacement replacement = new Replacement(parts);
        List<Object> result = new ArrayList<>(nodes.size());
        for (Object n : nodes) {
            result.add(rebuild(n, replacement, 0));
        }
        // 容器复制失败可能丢弃已重建的子节点；不得把其他已清空的文本节点
        // 作为部分成功的结果交给宿主，必须整棵树替换成功。
        if (replacement.failed || replacement.next != targetCount) {
            return null;
        }
        return result;
    }

    /**
     * 统一换行符并去掉首尾空白：模型常以换行收尾（开头偶尔也会多一行），
     * 直接渲染就会在译文上下方留下多余空行。正文中间的空行保持不变。
     */
    static String normalize(String translated) {
        return translated.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private static final class Replacement {
        final List<String> parts;
        int next;
        boolean failed;

        Replacement(List<String> parts) {
            this.parts = parts;
        }
    }

    /**
     * 递归统计可翻译片段数，每次出现分别计数（包括共享实例）。
     *
     * <p>口径与提取阶段完全一致：一个文本节点可能因为内部空行被拆成多个片段，
     * 只含空白的节点不计数。提取、计划、回填三套遍历的口径必须一致，
     * 否则片段串位。</p>
     */
    private static int countTextNodes(@Nullable List<?> nodes, int depth) {
        if (nodes == null || depth > 12) {
            return 0;
        }
        int count = 0;
        for (Object n : nodes) {
            if (n == null) {
                continue;
            }
            if (n instanceof List<?>) {
                count += countTextNodes((List<?>) n, depth + 1);
                continue;
            }
            String simple = n.getClass().getSimpleName();
            if ("TextContentNode".equals(simple)) {
                count += TextExtractor.countSegments(Reflect.callString(n, "getContent"));
                continue;
            }
            if (CODE_NODES.contains(simple)) {
                continue; // 代码不翻译
            }
            if ("LinkContentNode".equals(simple) && TextExtractor.linkDisplayIsUrl(n)) {
                continue; // 显示文本为 URL 的链接不翻译，与提取阶段同口径
            }
            List<?> inner = childListOf(n, simple);
            if (inner != null) {
                count += countTextNodes(inner, depth + 1);
            }
        }
        return count;
    }

    /**
     * 取出节点的子列表：容器的 content，或列表节点的 items。
     */
    @Nullable
    private static List<?> childListOf(Object node, String simpleName) {
        if (!BULLET_LIST.equals(simpleName) && !TextExtractor.isTextContainer(simpleName)) {
            return null;
        }
        String getter = BULLET_LIST.equals(simpleName) ? "getItems" : "getContent";
        Object v = Reflect.call(
                Reflect.findMethod(
                        node.getClass(), getter, 0), node);
        return v instanceof List ? (List<?>) v : null;
    }

    /**
     * 重建单个节点：递归处理子列表，并把命中的文本节点替换为译文。
     */
    private static Object rebuild(@Nullable Object node, Replacement replacement, int depth) {
        if (node == null || depth > 12) {
            return node;
        }
        if (node instanceof List<?>) {
            List<?> children = (List<?>) node;
            List<Object> rebuiltChildren = new ArrayList<>(children.size());
            boolean localChanged = false;
            for (Object child : children) {
                Object rebuiltChild = rebuild(child, replacement, depth + 1);
                if (rebuiltChild != child) {
                    localChanged = true;
                }
                rebuiltChildren.add(rebuiltChild);
            }
            return localChanged ? rebuiltChildren : node;
        }

        String simple = node.getClass().getSimpleName();

        // 按出现位置消费译文片段，同一实例在不同位置可以承载不同片段。
        if ("TextContentNode".equals(simple)) {
            String original = Reflect.callString(node, "getContent");
            if (original == null) {
                return node;
            }
            int needed = TextExtractor.countSegments(original);
            if (needed == 0) {
                // 空白/空行结构节点：不参与翻译，原样保留。
                return node;
            }
            if (replacement.next + needed > replacement.parts.size()) {
                replacement.failed = true;
                return node;
            }
            // 保留首尾空白，并按原空行位置拼回译文片段：
            // 段落分隔由原节点提供，不依赖模型是否保留。
            int lead = TextExtractor.leadingWhitespace(original);
            int tail = TextExtractor.trailingWhitespaceStart(original);
            BlankLines.Split split = BlankLines.split(original.substring(lead, tail));
            StringBuilder rebuilt = new StringBuilder();
            rebuilt.append(original, 0, lead);
            for (int i = 0; i < split.chunks.size(); i++) {
                if (i > 0) {
                    rebuilt.append(split.separators.get(i - 1));
                }
                String chunk = split.chunks.get(i);
                if (BlankLines.isBlank(chunk) || TextExtractor.isUrlLike(chunk)) {
                    // 空白块或 URL 块不消费片段，原样保留（与计数口径一致）。
                    rebuilt.append(chunk);
                    continue;
                }
                rebuilt.append(replacement.parts.get(replacement.next++));
            }
            rebuilt.append(original, tail, original.length());
            Object created = createTextNode(node, rebuilt.toString());
            if (created != null) {
                return created;
            }
            replacement.failed = true;
            return node;
        }

        if (CODE_NODES.contains(simple)) {
            return node;
        }

        // 显示文本为 URL 的链接不进入：URL 必须由原节点原样渲染，
        // 不能让模型重新生成（实测会发生字符损坏）。
        if ("LinkContentNode".equals(simple) && TextExtractor.linkDisplayIsUrl(node)) {
            return node;
        }

        List<?> children = childListOf(node, simple);
        if (children == null) {
            return node; // 不是正文格式容器（例如提及/频道/emoji），原样保留
        }

        List<Object> newChildren = new ArrayList<>(children.size());
        boolean localChanged = false;
        for (Object c : children) {
            Object nc = rebuild(c, replacement, depth + 1);
            if (nc != c) {
                localChanged = true;
            }
            newChildren.add(nc != null ? nc : c);
        }
        if (!localChanged) {
            return node;
        }
        Object rebuilt = copyWith(node, newChildren);
        if (rebuilt == null) {
            replacement.failed = true;
            return node;
        }
        return rebuilt;
    }

    /**
     * 用 {@code copy(...)} 重建节点，只替换内容列表，其余字段原样保留。
     */
    @Nullable
    private static Object copyWith(Object node, List<Object> newChildren) {
        Class<?> cls = node.getClass();
        Method copy = findCopyMethod(cls);
        if (copy == null) {
            return null;
        }
        // 逐个参数：内容字段用新列表，其他字段用 componentN 原值。
        Class<?>[] types = copy.getParameterTypes();
        Object[] args = new Object[types.length];
        int contentIndex = -1;
        for (int i = 0; i < types.length; i++) {
            if (List.class.isAssignableFrom(types[i]) && contentIndex < 0) {
                contentIndex = i;
                args[i] = newChildren;
                continue;
            }
            args[i] = component(node, i + 1);
        }
        if (contentIndex < 0) {
            return null;
        }
        Object out = Reflect.call(copy, node, args);
        return out;
    }

    /** 新建承载译文的文本节点，类型跟随原节点（保持 TextContentNode 语义）。 */
    @Nullable
    private static Object createTextNode(Object original, String text) {
        try {
            java.lang.reflect.Constructor<?> ctor =
                    original.getClass().getDeclaredConstructor(String.class);
            ctor.setAccessible(true);
            return ctor.newInstance(text);
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static Method findCopyMethod(Class<?> cls) {
        for (Method m : cls.getDeclaredMethods()) {
            if ("copy".equals(m.getName()) && m.getParameterCount() > 0) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    /** 取 Kotlin data class 的第 n 个字段（componentN 从 1 开始）。 */
    @Nullable
    private static Object component(Object node, int n) {
        Method m = Reflect.findMethod(
                node.getClass(), "component" + n, 0);
        return Reflect.call(m, node);
    }

    // ---------------------------------------------------------------- 译文切分

    @Nullable
    static List<String> alignTranslation(String plain, AlignmentPlan plan) {
        if (plan.groupIds.isEmpty() || !urlsMatch(plain, plan)
                || !emojiAnchorsMatch(plain, plan)) {
            return null;
        }
        if (plan.hasStyleMarkers()) {
            return alignByStyleMarkers(plain, plan);
        }
        return alignTokens(plain, plan);
    }

    /**
     * 按样式标记扫描译文：树序遍历令牌流，样式片段的译文取自
     * {@code <xN>…</xN>} 之间，其余文本按标记间隔分配给相邻的普通片段。
     * 任何标记缺失、错位或空内容都会拒绝整条回填。
     */
    @Nullable
    private static List<String> alignByStyleMarkers(String plain, AlignmentPlan plan) {
        List<String> parts = new ArrayList<>(
                java.util.Collections.nCopies(plan.groupIds.size(), ""));
        List<Integer> pending = new ArrayList<>();
        int cursor = 0;
        for (PlanToken t : plan.tokens) {
            if (t.url != null) {
                int at = plain.indexOf(t.url, cursor);
                if (at < 0 || !assignRun(plain.substring(cursor, at), pending, parts, plan)) {
                    return null;
                }
                pending.clear();
                cursor = at + t.url.length();
            } else if (t.styleId > 0) {
                String open = "<x" + t.styleId + ">";
                int at = plain.indexOf(open, cursor);
                if (at < 0 || !assignRun(plain.substring(cursor, at), pending, parts, plan)) {
                    return null;
                }
                pending.clear();
                String close = "</x" + t.styleId + ">";
                int end = plain.indexOf(close, at + open.length());
                if (end < 0) {
                    return null;
                }
                String styled = plain.substring(at + open.length(), end).trim();
                if (styled.isEmpty()) {
                    return null;
                }
                if (t.segIndex < plan.listFlags.size()
                        && Boolean.TRUE.equals(plan.listFlags.get(t.segIndex))) {
                    styled = stripListMarker(styled);
                    if (styled == null) {
                        return null;
                    }
                }
                parts.set(t.segIndex, styled);
                cursor = end + close.length();
            } else {
                pending.add(t.segIndex);
            }
        }
        if (!assignRun(plain.substring(cursor), pending, parts, plan)) {
            return null;
        }
        return parts;
    }

    /** 把一段结构对应的译文分配给待填片段；结构不符时拒绝整条回填。 */
    private static boolean assignRun(String run, List<Integer> pending,
                                     List<String> parts, AlignmentPlan plan) {
        if (pending.isEmpty()) {
            return run.trim().isEmpty();
        }
        int first = pending.get(0);
        int last = pending.get(pending.size() - 1);
        List<String> runParts = alignSpanToParts(run,
                plan.groupIds.subList(first, last + 1),
                plan.listFlagsIn(first, last + 1),
                plan.lineBreakFlagsIn(first, last + 1));
        if (runParts == null) {
            return false;
        }
        for (int i = 0; i < runParts.size(); i++) {
            String p = runParts.get(i);
            if (p != null && !p.trim().isEmpty()) {
                parts.set(pending.get(i), p);
            }
        }
        return true;
    }

    /**
     * 写入已完成的一一对齐结果。
     */
    private static void copySpan(List<String> spanParts, List<String> parts, int segStart) {
        for (int i = 0; i < spanParts.size(); i++) {
            String p = spanParts.get(i);
            if (p != null && !p.trim().isEmpty()) {
                parts.set(segStart + i, p);
            }
        }
    }

    /** URL 锚点必须按原数量出现；模型改写或丢失时不回填错位译文。 */
    private static boolean urlsMatch(String plain, AlignmentPlan plan) {
        java.util.Map<String, Integer> expected = new java.util.HashMap<>();
        for (String anchor : plan.urls) {
            expected.merge(anchor, 1, Integer::sum);
        }
        for (java.util.Map.Entry<String, Integer> e : expected.entrySet()) {
            if (countOccurrences(plain, e.getKey()) != e.getValue()) {
                return false;
            }
        }
        return true;
    }

    /** emoji 锚点必须按原数量出现，否则拒绝对齐。 */
    private static boolean emojiAnchorsMatch(String plain, AlignmentPlan plan) {
        java.util.Map<String, Integer> expected = new java.util.HashMap<>();
        for (String anchor : plan.emojiAnchors) {
            expected.merge(anchor, 1, Integer::sum);
        }
        for (java.util.Map.Entry<String, Integer> e : expected.entrySet()) {
            if (countOccurrences(plain, e.getKey()) != e.getValue()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 按树序令牌流切分译文并对齐片段。
     *
     * <p>定位标记（URL / emoji）不消费译文片段。按树序依次定位标记，
     * 标记之间的文本交给 {@link #alignSpanToParts} 按结构类型唯一切分。</p>
     */
    @Nullable
    private static List<String> alignTokens(String plain, AlignmentPlan plan) {
        boolean hasAnchors = !plan.urls.isEmpty() || !plan.emojiAnchors.isEmpty();
        if (!hasAnchors) {
            return alignSpanToParts(plain, plan.groupIds, plan.listFlags,
                    plan.lineBreakFlagsIn(0, plan.groupIds.size()));
        }
        // 逐个令牌定位。锚点只切间隔、不产出片段；片段在其后的间隔里分配。
        List<String> parts = new ArrayList<>(
                java.util.Collections.nCopies(plan.groupIds.size(), ""));
        int from = 0;
        int segStart = 0;
        int urlIndex = 0;
        int emojiIndex = 0;
        for (PlanToken t : plan.tokens) {
            if (t.url == null) {
                continue;
            }
            String anchor = t.url;
            int at = plain.indexOf(anchor, from);
            if (at < 0) {
                return null; // 数量校验后仍未找到，表示锚点顺序改变
            }
            int segEnd = t.emoji
                    ? plan.emojiSegIndex.get(emojiIndex++)
                    : plan.urlSegIndex.get(urlIndex++);
            List<String> spanParts = alignSpanToParts(plain.substring(from, at),
                    plan.groupIds.subList(segStart, segEnd),
                    plan.listFlagsIn(segStart, segEnd),
                    plan.lineBreakFlagsIn(segStart, segEnd));
            if (spanParts == null) {
                return null;
            }
            copySpan(spanParts, parts, segStart);
            from = at + anchor.length();
            segStart = segEnd;
        }
        List<String> tailParts = alignSpanToParts(plain.substring(from),
                plan.groupIds.subList(segStart, plan.groupIds.size()),
                plan.listFlagsIn(segStart, plan.groupIds.size()),
                plan.lineBreakFlagsIn(segStart, plan.groupIds.size()));
        if (tailParts == null) {
            return null;
        }
        copySpan(tailParts, parts, segStart);
        return parts;
    }

    private static int countOccurrences(String text, String needle) {
        int n = 0;
        int at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) {
            n++;
            at += needle.length();
        }
        return n;
    }

    /**
     * 按结构类型执行唯一切分：列表项逐行、显式换行逐行，其余按段落块逐片段对应。
     * 任一数量或结构不匹配都拒绝整条回填。
     */
    @Nullable
    static List<String> alignSpanToParts(String span, List<Integer> groups,
                                         @Nullable List<Boolean> listFlags) {
        return alignSpanToParts(span, groups, listFlags, null);
    }

    @Nullable
    static List<String> alignSpanToParts(String span, List<Integer> groups,
                                         @Nullable List<Boolean> listFlags,
                                         @Nullable List<Boolean> lineBreakBefore) {
        int segments = groups.size();
        if (segments == 0) {
            return span.trim().isEmpty() ? new ArrayList<>() : null;
        }
        List<String> chunks = translatableChunks(span);
        List<String> parts = new ArrayList<>(
                java.util.Collections.nCopies(segments, ""));
        int segment = 0;
        int chunk = 0;
        while (segment < segments) {
            if (chunk >= chunks.size()) {
                return null;
            }
            int end = segment + 1;
            while (end < segments) {
                if (isLineBreakBefore(lineBreakBefore, end)) {
                    end++;
                } else if (groups.get(end).equals(groups.get(end - 1))) {
                    return null;
                } else {
                    break;
                }
            }

            String block = chunks.get(chunk++);
            if (end - segment > 1) {
                String[] lines = block.split("\n", -1);
                if (lines.length != end - segment) {
                    return null;
                }
                for (int i = segment; i < end; i++) {
                    String line = lines[i - segment];
                    if (line.trim().isEmpty()) {
                        return null;
                    }
                    if (isListSegment(listFlags, i)) {
                        line = stripListMarker(line);
                        if (line == null) {
                            return null;
                        }
                    }
                    parts.set(i, line);
                }
            } else {
                String value = block;
                if (isListSegment(listFlags, segment)) {
                    value = stripListMarker(value);
                    if (value == null) {
                        return null;
                    }
                }
                parts.set(segment, value);
            }
            segment = end;
        }
        return chunk == chunks.size() ? parts : null;
    }

    private static boolean isListSegment(@Nullable List<Boolean> listFlags, int index) {
        return listFlags != null && index < listFlags.size()
                && Boolean.TRUE.equals(listFlags.get(index));
    }

    private static boolean isLineBreakBefore(@Nullable List<Boolean> flags, int index) {
        return flags != null && index < flags.size() && Boolean.TRUE.equals(flags.get(index));
    }

    /** 行首项目符号：{@code • }、{@code - }、{@code 1. } 等（需有分隔空白）。 */
    private static final java.util.regex.Pattern LIST_MARKER =
            java.util.regex.Pattern.compile(
                    "^\\s*(?:[-*+•◦·▪▫]\\s+|\\d+[.)]\\s+)");

    /**
     * 剔除行首项目符号。若剥除后为空，拒绝本次回填。
     */
    private static String stripListMarker(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        String stripped = LIST_MARKER.matcher(line).replaceFirst("");
        return stripped.trim().isEmpty() ? null : stripped;
    }

    /**
     * 译文的可翻译段落块：空行切分，剔除空白块与 URL 块，与回填消费片段
     * 的口径（{@link TextExtractor#countSegments}）一致，日志与切分共用。
     */
    private static List<String> translatableChunks(String translated) {
        List<String> chunks = new ArrayList<>();
        for (String chunk : BlankLines.split(translated).chunks) {
            String trimmed = chunk.trim();
            if (!trimmed.isEmpty() && !TextExtractor.isUrlLike(trimmed)) {
                chunks.add(trimmed);
            }
        }
        return chunks;
    }

    /**
     * 回填对齐计划：按树序枚举可翻译片段及必须精确保留的定位/样式标记。
     *
     * <p>{@code groupIds} 与片段消费顺序一致；URL、emoji 按原数量和顺序保留，
     * 样式片段保留对应的 {@code <xN>} 标记。</p>
     */
    static final class AlignmentPlan {
        /** 每个可翻译片段的段落组号（0 起，与片段枚举同序）。 */
        final List<Integer> groupIds = new ArrayList<>();
        /** 与 {@code groupIds} 平行：该片段是否位于列表项内。 */
        final List<Boolean> listFlags = new ArrayList<>();
        /** 与片段平行：当前片段前是否有显式换行节点。 */
        final List<Boolean> lineBreakBefore = new ArrayList<>();
        /** 按树序的 URL 定位标记（原样 URL 文本）。 */
        final List<String> urls = new ArrayList<>();
        /** 与 {@code urls} 平行：该 URL 之前的可翻译片段数（间隔边界）。 */
        final List<Integer> urlSegIndex = new ArrayList<>();
        /** 按树序的 emoji 定位标记：Unicode 字符或宿主提供的自定义 emoji alt。 */
        final List<String> emojiAnchors = new ArrayList<>();
        /** 与 {@code emojiAnchors} 平行：该 emoji 之前的可翻译片段数（间隔边界）。 */
        final List<Integer> emojiSegIndex = new ArrayList<>();
        /** 按树序的定位与可翻译片段令牌。 */
        final List<PlanToken> tokens = new ArrayList<>();
        /** 样式标记总数（>0 表示请求里含 {@code <xN>} 标记）。 */
        int styleMarkers;

        boolean hasStyleMarkers() {
            return styleMarkers > 0;
        }

        List<Boolean> lineBreakFlagsIn(int segStart, int segEnd) {
            return new ArrayList<>(lineBreakBefore.subList(segStart, segEnd));
        }

        /** [segStart, segEnd) 范围片段对应的列表项标记子列表。 */
        List<Boolean> listFlagsIn(int segStart, int segEnd) {
            return new ArrayList<>(listFlags.subList(segStart, segEnd));
        }
    }

    /** 树序令牌：URL 定位标记 / emoji / 可翻译片段（样式号 &gt;0 表示包在 {@code <xN>…</xN>} 里）。 */
    static final class PlanToken {
        final String url;   // 非 null = URL 或 emoji 定位标记
        final int segIndex; // URL 令牌为 -1
        final int styleId;  // 0 = 无样式
        final boolean emoji; // true = emoji，false = URL

        PlanToken(String anchor, boolean isEmoji) {
            this.url = anchor;
            this.segIndex = -1;
            this.styleId = 0;
            this.emoji = isEmoji;
        }

        PlanToken(int segIndex, int styleId) {
            this.url = null;
            this.segIndex = segIndex;
            this.styleId = styleId;
            this.emoji = false;
        }
    }

    private static AlignmentPlan buildPlan(List<?> nodes) {
        PlanWalk w = new PlanWalk();
        walkPlan(nodes, w, 0, false);
        return w.plan;
    }

    private static final class PlanWalk {
        final AlignmentPlan plan = new AlignmentPlan();
        int group = 0;
        final StringBuilder sep = new StringBuilder();
        /** 当前嵌套列表层数（>0 表示正在列表项内）：用于标记列表片段。 */
        int listLevel;
        /** 下一个片段强制新开段落组（列表项 / 列表后正文的边界）。 */
        boolean forceNewGroup;
        /** 当前是否遇到显式换行边界。 */
        boolean lineBreakBefore;
        /** 样式标记序号（与 TextExtractor.walkNode 同口径递增）。 */
        final int[] styleSeq = new int[1];
    }

    private static void walkPlan(@Nullable List<?> nodes, PlanWalk w, int depth, boolean styled) {
        if (nodes == null || depth > 12) {
            return;
        }
        for (Object n : nodes) {
            if (n == null) {
                continue;
            }
            if (n instanceof List) {
                walkPlan((List<?>) n, w, depth + 1, styled);
                continue;
            }
            String simple = n.getClass().getSimpleName();
            if ("TextContentNode".equals(simple)) {
                String s = Reflect.callString(n, "getContent");
                if (s == null) {
                    continue;
                }
                if (BlankLines.isBlank(s)) {
                    w.sep.append(s); // 空行结构节点：段落分隔的一部分
                    continue;
                }
                int lead = TextExtractor.leadingWhitespace(s);
                int tail = TextExtractor.trailingWhitespaceStart(s);
                w.sep.append(s, 0, lead);
                BlankLines.Split split = BlankLines.split(s.substring(lead, tail));
                for (int i = 0; i < split.chunks.size(); i++) {
                    if (i > 0) {
                        w.sep.append(split.separators.get(i - 1)); // 节点内空行：硬边界
                    }
                    String chunk = split.chunks.get(i);
                    if (BlankLines.isBlank(chunk)) {
                        w.sep.append(chunk);
                        continue;
                    }
                    if (TextExtractor.isUrlLike(chunk)) {
                        // 节点内的 URL 文本块：定位标记，与链接节点同口径。
                        addUrlToken(w, chunk.trim());
                        w.sep.append(chunk);
                        continue;
                    }
                    int segIndex = w.plan.groupIds.size();
                    addSegment(w);
                    int styleId = 0;
                    if (styled) {
                        w.styleSeq[0]++;
                        styleId = w.styleSeq[0];
                        w.plan.styleMarkers++;
                    }
                    w.plan.tokens.add(new PlanToken(segIndex, styleId));
                }
                w.sep.append(s, tail, s.length());
                continue;
            }
            if (CODE_NODES.contains(simple)) {
                w.sep.append("InlineCodeContentNode".equals(simple) ? ' ' : '\n');
                continue;
            }
            if ("LinkContentNode".equals(simple) && TextExtractor.linkDisplayIsUrl(n)) {
                addUrlToken(w, TextExtractor.urlLinkDisplayText(n));
                w.sep.append(' ');
                continue;
            }
            if (BLANK_NODES.contains(simple)) {
                w.lineBreakBefore = true;
                w.sep.append('\n');
                continue;
            }
            // 提及/emoji 等不透明节点：emoji 作为定位标记提前处理，其余留空格。
            String emoji = TextExtractor.nodeEmojiText(n);
            if (emoji != null) {
                addEmojiToken(w, emoji);
                w.sep.append(' ').append(emoji).append(' ');
                continue;
            }
            List<?> inner = childListOf(n, simple);
            if (inner != null) {
                if (BULLET_LIST.equals(simple)) {
                    // 每个列表项独立成段并对应一行；列表项前缀由原节点渲染，
                    // 回填时仅移除模型回显的符号。
                    int before = w.plan.groupIds.size();
                    for (Object item : inner) {
                        if (!w.plan.groupIds.isEmpty()) {
                            w.forceNewGroup = true;
                        }
                        w.sep.append("\n• ");
                        w.listLevel++;
                        if (item instanceof List) {
                            walkPlan((List<?>) item, w, depth + 1, styled);
                        } else if (item != null) {
                            walkPlan(java.util.Collections.singletonList(item),
                                    w, depth + 1, styled);
                        }
                        w.listLevel--;
                    }
                    // 列表后的正文另属一段：列表项分隔只有单换行，不设边界
                    // 会把紧随其后的段落并进最后一项。列表本身没有可翻译
                    // 片段时不设边界，避免多切一个空段落组。
                    if (w.plan.groupIds.size() > before) {
                        w.forceNewGroup = true;
                    }
                    continue;
                }
                walkPlan(inner, w, depth + 1, styled || TextExtractor.isInlineStyleContainer(simple));
                continue;
            }
            // 提及等其余不透明节点（emoji 已作为定位标记提前处理）。
            w.sep.append(' ');
        }
    }

    /**
     * 记录一个 emoji 定位标记。
     *
     * <p>emoji 本身不消费译文片段（显示由原节点完成），但它在请求正文里
     * 占一个位置，因此把它当作与 URL 同级的定位标记：对齐时按 emoji 切分
     * 译文间隔，每个间隔各自对齐。</p>
     */
    private static void addEmojiToken(PlanWalk w, String emoji) {
        w.plan.emojiAnchors.add(emoji);
        w.plan.emojiSegIndex.add(w.plan.groupIds.size());
        w.plan.tokens.add(new PlanToken(emoji, true));
    }

    private static void addUrlToken(PlanWalk w, String url) {
        w.plan.urls.add(url);
        w.plan.urlSegIndex.add(w.plan.groupIds.size());
        w.plan.tokens.add(new PlanToken(url, false));
    }

    /**
     * 记录一个可翻译片段：空行分隔推进段落组。
     *
     * <p>URL 与 emoji 都作为必须保留的定位标记；列表项按独立行分组。
     * {@code forceNewGroup} 由列表项边界推进段落组。</p>
     */
    private static void addSegment(PlanWalk w) {
        int separatorNewlines = countNewlines(w.sep);
        boolean lineBoundary = separatorNewlines < 2
                && (w.forceNewGroup || w.lineBreakBefore);
        if (w.forceNewGroup) {
            w.group++;
            w.forceNewGroup = false;
        } else if (!w.plan.groupIds.isEmpty() && separatorNewlines >= 2) {
            w.group++;
        }
        w.plan.groupIds.add(w.group);
        w.plan.listFlags.add(w.listLevel > 0);
        w.plan.lineBreakBefore.add(lineBoundary);
        w.lineBreakBefore = false;
        w.sep.setLength(0);
    }

    private static int countNewlines(CharSequence s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    /** 拼接节点列表为 JSON 数组文本，用于诊断与单测。 */
    public static String describe(@Nullable List<?> nodes) {
        if (nodes == null) {
            return "<null>";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Object n = nodes.get(i);
            sb.append(n == null ? "null" : n.getClass().getSimpleName());
        }
        return sb.append(']').toString();
    }
}
