package com.wxmyyds.discordautotranslate.hooks;

import androidx.annotation.Nullable;

import com.wxmyyds.discordautotranslate.core.BlankLines;
import com.wxmyyds.discordautotranslate.core.Reflect;
import com.wxmyyds.discordautotranslate.core.Segments;
import com.wxmyyds.discordautotranslate.core.TextExtractor;

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
     * <p>译文按结构对齐回填：URL 链接与 URL 文本块不参与翻译，原样保留；
     * 译文先按 URL 定位标记切分成间隔，再逐间隔对齐到可翻译片段
     * （段落块 1:1 → 非空行 1:1 → 段落级：每段译文进本段首片段、段内其余
     * 置空）。模型跨段落合并/增删内容导致结构对不上时整条保留原文，
     * 不让复读或错位内容进入节点树。</p>
     *
     * @param nodes        原始节点列表
     * @param translated   无标记译文（引擎侧已通过防损坏校验）
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
        // URL 链接在请求里是定位标记：先按译文中的 URL 出现位置把译文
        // 切成若干间隔，每个间隔再独立三级对齐（段落块 1:1 → 非空行 1:1
        // → 段落级：每段译文进本段首片段，段内其余置空；有格式的内联
        // 消息只能这样落位）。URL 丢失/改坏/多份回显或间隔对不上时
        // 整条保留原文。strip 是防御性的：正常链路下引擎校验已保证译文
        // 无分段标记，模型幻觉出的标记在这里被剥离，不会显示为字面量。
        String plain = normalize(Segments.strip(translated));
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
        // 片段全部非空，但原树尾部的空行/空白节点仍会随结构保留；
        // 不裁剪就会在译文下方留下原文遗留的空行。
        return trimTrailingBlank(result, 0);
    }

    /**
     * 统一换行符并去掉首尾空白：模型常以换行收尾（开头偶尔也会多一行），
     * 直接渲染就会在译文上下方留下多余空行。正文中间的空行保持不变。
     */
    static String normalize(String translated) {
        return translated.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    /**
     * 去掉列表末尾的空行与空文本节点。
     *
     * <p>只处理尾部：正文中间的空行可能是用户有意留白。容器的 copy 失败时
     * 保留原节点（宁可多一行空白，也不能丢弃已翻译的文字）。
     *
     * @param nodes    待裁剪的列表
     * @param depth    递归深度保护
     */
    private static List<Object> trimTrailingBlank(List<Object> nodes, int depth) {
        List<Object> out = new ArrayList<>(nodes);
        while (!out.isEmpty() && depth <= 12) {
            Object last = out.get(out.size() - 1);
            if (last == null) {
                out.remove(out.size() - 1);
                continue;
            }
            String simple = last.getClass().getSimpleName();
            if (BLANK_NODES.contains(simple)) {
                out.remove(out.size() - 1);
                continue;
            }
            if ("TextContentNode".equals(simple)) {
                String content = Reflect.callString(last, "getContent");
                if (content == null || content.trim().isEmpty()) {
                    out.remove(out.size() - 1);
                    continue;
                }
                break;
            }
            if (BULLET_LIST.equals(simple)) {
                // 列表项内部的行尾空白可以裁剪，但项目符号本身可见，不能删项目。
                Object itemsRaw = Reflect.call(
                        Reflect.findMethod(last.getClass(), "getItems", 0), last);
                if (!(itemsRaw instanceof List)) {
                    break;
                }
                List<?> items = (List<?>) itemsRaw;
                List<Object> newItems = new ArrayList<>(items.size());
                boolean itemsChanged = false;
                for (Object item : items) {
                    if (!(item instanceof List)) {
                        newItems = null;
                        break;
                    }
                    List<Object> itemNodes = new ArrayList<>((List<?>) item);
                    List<Object> trimmedItem = trimTrailingBlank(itemNodes, depth + 1);
                    if (trimmedItem.size() != itemNodes.size()) {
                        itemsChanged = true;
                    }
                    newItems.add(trimmedItem);
                }
                if (newItems == null) {
                    break;
                }
                // 内容全为空白、只剩项目符号的尾项一并去掉。
                while (!newItems.isEmpty() && ((List<?>) newItems.get(newItems.size() - 1)).isEmpty()) {
                    newItems.remove(newItems.size() - 1);
                    itemsChanged = true;
                }
                if (!itemsChanged) {
                    break;
                }
                if (newItems.isEmpty()) {
                    out.remove(out.size() - 1); // 列表项全为空白：整个列表不渲染
                    continue;
                }
                Object rebuiltItems = copyWith(last, newItems);
                if (rebuiltItems == null) {
                    break;
                }
                out.set(out.size() - 1, rebuiltItems);
                break;
            }
            if (!TextExtractor.isTextContainer(simple)) {
                // 提及/emoji/代码等可见结构：不能当作空白裁掉。
                break;
            }
            List<?> children = childListOf(last, simple);
            if (children == null) {
                break;
            }
            List<Object> childList = new ArrayList<>(children.size());
            for (Object child : children) {
                childList.add(child);
            }
            List<Object> trimmed = trimTrailingBlank(childList, depth + 1);
            if (trimmed.size() == children.size()) {
                break; // 容器本身以可见内容收尾
            }            if (trimmed.isEmpty()) {
                out.remove(out.size() - 1); // 容器内容全为空白：整节点去掉
                continue;
            }
            Object rebuilt = copyWith(last, trimmed);
            if (rebuilt == null) {
                break; // 裁剪失败：保留原样，不放弃译文
            }
            out.set(out.size() - 1, rebuilt);
            break;
        }
        return out;
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

    /**
     * 把译文对齐到计划的可翻译片段，返回与片段一一对应的译文列表。
     *
     * <p>对齐顺序：</p>
     * <ol>
     *   <li>URL 定位标记出现次数校验（多了=回显会重复显示，少了=丢失/改坏，
     *       都无法安全定位 → 整条拒绝）；</li>
     *   <li>请求含样式标记（{@code <xN>…</xN>}）时按标记扫描分配，样式
     *       精确落位；模型没保留/改坏标记时自动回退到无标记对齐
     *       （译文保留、样式退化为合并处理），不会拒译；</li>
     *   <li>无标记回退：URL 间隔切分 + 每个间隔三级对齐
     *       （见 {@link #alignSpanToParts}）。</li>
     * </ol>
     */
    @Nullable
    static List<String> alignTranslation(String plain, AlignmentPlan plan) {
        if (plan.groupIds.isEmpty()) {
            return null;
        }
        if (!plan.urls.isEmpty()) {
            java.util.Map<String, Integer> expected = new java.util.HashMap<>();
            for (String url : plan.urls) {
                expected.merge(url, 1, Integer::sum);
            }
            for (java.util.Map.Entry<String, Integer> e : expected.entrySet()) {
                int actual = countOccurrences(plain, e.getKey());
                if (actual != e.getValue()) {
                    return null;
                }
            }
        }
        if (plan.hasStyleMarkers()) {
            List<String> viaMarkers = alignByStyleMarkers(plain, plan);
            if (viaMarkers != null) {
                return viaMarkers;
            }
            plain = stripStyleTags(plain);
        }
        if (plan.urls.isEmpty()) {
            return alignSpanToParts(plain, plan.groupIds, plan.unsafe);
        }
        List<String> parts = new ArrayList<>(
                java.util.Collections.nCopies(plan.groupIds.size(), ""));
        int from = 0;
        int segStart = 0;
        for (int u = 0; u < plan.urls.size(); u++) {
            String url = plan.urls.get(u);
            int at = plain.indexOf(url, from);
            if (at < 0) {
                return null; // 理论不可达：出现次数已校验
            }
            int segEnd = plan.urlSegIndex.get(u);
            List<String> spanParts = alignSpanToParts(plain.substring(from, at),
                    plan.groupIds.subList(segStart, segEnd),
                    plan.unsafeIn(segStart, segEnd));
            if (spanParts == null) {
                return null;
            }
            copySpan(spanParts, parts, segStart);
            from = at + url.length();
            segStart = segEnd;
        }
        List<String> tailParts = alignSpanToParts(plain.substring(from),
                plan.groupIds.subList(segStart, plan.groupIds.size()),
                plan.unsafeIn(segStart, plan.groupIds.size()));
        if (tailParts == null) {
            return null;
        }
        copySpan(tailParts, parts, segStart);
        return parts;
    }

    /**
     * 按样式标记扫描译文：树序遍历令牌流，样式片段的译文取自
     * {@code <xN>…</xN>} 之间，其余文本按标记间隔分配给相邻的普通片段。
     * 任何标记缺失/错位/空内容都返回 null 触发回退。
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
                    return null; // 样式标记内没有译文：标记不可信，回退
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

    /** 把一段间隔译文分配给若干待填片段；分配失败返回 false（触发回退）。 */
    private static boolean assignRun(String run, List<Integer> pending,
                                     List<String> parts, AlignmentPlan plan) {
        if (pending.isEmpty()) {
            return run.trim().isEmpty();
        }
        int first = pending.get(0);
        int last = pending.get(pending.size() - 1);
        List<String> runParts = alignSpanToParts(run,
                plan.groupIds.subList(first, last + 1),
                plan.unsafeIn(first, last + 1));
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

    private static final java.util.regex.Pattern STYLE_TAG =
            java.util.regex.Pattern.compile("</?x\\d+>");

    /** 剥离译文里的样式标记（保留标记间内容），供无标记回退路径使用。 */
    private static String stripStyleTags(String text) {
        return STYLE_TAG.matcher(text).replaceAll("");
    }

    /**
     * 把间隔片段写入整体片段列表。段落级对齐产生的空片段是设计行为
     * （译文合并进首片段、段内其余置空），照常写入；模型真正丢内容
     * 会表现为间隔结构对不上而拒绝，不在这里判断。
     */
    private static void copySpan(List<String> spanParts, List<String> parts, int segStart) {
        for (int i = 0; i < spanParts.size(); i++) {
            String p = spanParts.get(i);
            if (p != null && !p.trim().isEmpty()) {
                parts.set(segStart + i, p);
            }
        }
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
     * 把一个 URL 间隔内的译文对齐到该间隔的片段（{@code groups} 与片段
     * 一一对应）。优先级：段落块 1:1（保结构）→ 非空行 1:1（单行分段）→
     * 段落级对齐（每段译文进本段首片段，段内其余置空）。unsafe 段落
     * （片段之间夹有 emoji/行内代码/列表符号等不可翻译可见内容）拒用
     * 段落级对齐：强行对齐会孤立这些节点，实测链接与描述分离。
     * 全部对不上返回 null。
     */
    @Nullable
    static List<String> alignSpanToParts(String span, List<Integer> groups,
                                         java.util.BitSet unsafe) {
        int segs = groups.size();
        String trimmed = span.trim();
        if (segs == 0) {
            // 无片段可承载：间隔必须是空白；模型添加了多余内容即拒绝。
            return trimmed.isEmpty() ? new ArrayList<>() : null;
        }
        if (segs == 1) {
            if (trimmed.isEmpty()) {
                return null;
            }
            List<String> out = new ArrayList<>(1);
            out.add(trimmed);
            return out;
        }
        List<String> chunks = translatableChunks(span);
        if (chunks.size() == segs) {
            return chunks;
        }
        String[] lines = span.split("\n", -1);
        if (lines.length == segs) {
            boolean allNonBlank = true;
            for (String line : lines) {
                if (line.trim().isEmpty()) {
                    // 空行顶掉某个片段的译文 = 该片段内容丢失，不能用行对齐。
                    allNonBlank = false;
                    break;
                }
            }
            if (allNonBlank) {
                List<String> out = new ArrayList<>(segs);
                for (String line : lines) {
                    out.add(line);
                }
                return out;
            }
        }
        int paraCount = distinctCount(groups);
        if (chunks.size() == paraCount && unsafe.isEmpty()) {
            List<String> out = new ArrayList<>(
                    java.util.Collections.nCopies(segs, ""));
            int j = 0;
            int lastGroup = -1;
            for (int i = 0; i < segs && j < chunks.size(); i++) {
                int g = groups.get(i);
                if (g != lastGroup) {
                    out.set(i, chunks.get(j++));
                    lastGroup = g;
                }
            }
            return out;
        }
        return null;
    }

    private static int distinctCount(List<Integer> groups) {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int g : groups) {
            seen.add(g);
        }
        return seen.size();
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
     * 回填对齐计划：按树序枚举可翻译片段与 URL 定位标记。
     *
     * <p>{@code groupIds} 给出每个可翻译片段所属的显示段落（0 起，枚举
     * 顺序与 {@link #countTextNodes}/rebuild 完全一致）；{@code urls} 是
     * 按树序的 URL 原文（链接节点显示文本与节点内 URL 文本块），请求里
     * 它们就是模型要原样保留的定位标记；{@code unsafe} 标记「同段两个
     * 片段之间夹有不可翻译的可见内容」（emoji/行内代码/提及/列表符号；
     * URL 已升级为定位标记，不在此列）。段落级对齐会清空非首片段，夹在
     * 中间的可见节点会被孤立（实测：链接与描述分离），标记过的段落拒用
     * 该级对齐。</p>
     */
    static final class AlignmentPlan {
        /** 每个可翻译片段的段落组号（0 起，与片段枚举同序）。 */
        final List<Integer> groupIds = new ArrayList<>();
        /** 按树序的 URL 定位标记（原样 URL 文本）。 */
        final List<String> urls = new ArrayList<>();
        /** 与 {@code urls} 平行：该 URL 之前的可翻译片段数（间隔边界）。 */
        final List<Integer> urlSegIndex = new ArrayList<>();
        /**
         * 段落组号 → 段落内夹有不可翻译可见内容的标记。
         *
         * <p>历史机制，现已恒为空：URL 升级为定位标记（在段落对齐之前
         * 切分）；emoji/提及/行内代码是自渲染节点（段落合并时原位保留、
         * 无内容丢失）。字段保留给 {@link #alignSpanToParts} 的防御性
         * 检查，不再填充。</p>
         */
        final java.util.BitSet unsafe = new java.util.BitSet();
        /** 按树序的令牌流：URL 定位标记与可翻译片段。 */
        final List<PlanToken> tokens = new ArrayList<>();
        /** 样式标记总数（>0 表示请求里含 {@code <xN>} 标记）。 */
        int styleMarkers;

        boolean hasStyleMarkers() {
            return styleMarkers > 0;
        }

        int paragraphCount() {
            return groupIds.isEmpty() ? 0 : groupIds.get(groupIds.size() - 1) + 1;
        }

        /** 取 [segStart, segEnd) 范围内片段所在段落的 unsafe 标记子集。 */
        java.util.BitSet unsafeIn(int segStart, int segEnd) {
            java.util.BitSet out = new java.util.BitSet();
            for (int i = segStart; i < segEnd; i++) {
                int g = groupIds.get(i);
                if (unsafe.get(g)) {
                    out.set(g);
                }
            }
            return out;
        }
    }

    /** 树序令牌：URL 定位标记或可翻译片段（样式号 &gt;0 表示包在 {@code <xN>…</xN>} 里）。 */
    static final class PlanToken {
        final String url;   // 非 null = URL 定位标记
        final int segIndex; // URL 令牌为 -1
        final int styleId;  // 0 = 无样式

        PlanToken(String url) {
            this.url = url;
            this.segIndex = -1;
            this.styleId = 0;
        }

        PlanToken(int segIndex, int styleId) {
            this.url = null;
            this.segIndex = segIndex;
            this.styleId = styleId;
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
                w.sep.append('\n');
                continue;
            }
            List<?> inner = childListOf(n, simple);
            if (inner != null) {
                if (BULLET_LIST.equals(simple)) {
                    for (Object item : inner) {
                        w.sep.append("\n• ");
                        if (item instanceof List) {
                            walkPlan((List<?>) item, w, depth + 1, styled);
                        } else if (item != null) {
                            walkPlan(java.util.Collections.singletonList(item),
                                    w, depth + 1, styled);
                        }
                    }
                    continue;
                }
                walkPlan(inner, w, depth + 1, styled || TextExtractor.isInlineStyleContainer(simple));
                continue;
            }
            w.sep.append(' '); // 提及/emoji 等不透明节点
        }
    }

    private static void addUrlToken(PlanWalk w, String url) {
        w.plan.urls.add(url);
        w.plan.urlSegIndex.add(w.plan.groupIds.size());
        w.plan.tokens.add(new PlanToken(url));
    }

    /**
     * 记录一个可翻译片段：空行分隔推进段落组。
     *
     * <p>不再标记 unsafe 段落：URL 已升级为定位标记（在段落对齐之前切分），
     * emoji/提及/行内代码是自渲染节点（合并译文时原位保留、无内容丢失），
     * 段落级对齐不需要再因它们拒绝（实测 40 片段消息因 unsafe=3 被整条
     * 拒绝、保持英文）。{@link AlignmentPlan#unsafe} 保留为
     * {@link #alignSpanToParts} 的防御性检查，恒为空。</p>
     */
    private static void addSegment(PlanWalk w) {
        if (!w.plan.groupIds.isEmpty() && countNewlines(w.sep) >= 2) {
            w.group++;
        }
        w.plan.groupIds.add(w.group);
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
