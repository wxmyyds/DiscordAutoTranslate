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
     * <p>译文按结构对齐回填：URL 链接与 URL 文本块不参与翻译，原样保留；
     * 译文先按 URL 定位标记切分成间隔，再逐间隔对齐到可翻译片段
     * （段落块 1:1 → 整行 1:1 → 非空行 1:1 → 段落级：每段译文进本段首片段、
     * 段内其余置空）。模型跨段落合并/增删内容导致结构对不上时整条保留原文，
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
        // 切成若干间隔，每个间隔再独立四级对齐（段落块 1:1 → 整行 1:1
        // → 非空行 1:1 → 段落级：每段译文进本段首片段，段内其余置空；
        // 有格式的内联消息只能这样落位）。URL 丢失/改坏/多份回显或间隔对不上时
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
     *   <li>请求含样式标记（{@code <xN>…</xN>}）时按标记扫描分配，样式
     *       精确落位；模型没保留/改坏标记时自动回退到无标记对齐
     *       （译文保留、样式退化为合并处理），不会拒译；</li>
     *   <li>无标记回退：按定位标记切分间隔 + 每个间隔四级对齐
     *       （见 {@link #alignSpanToParts}）。定位标记包括 URL 和 emoji：
     *       它们本身不消费译文片段（URL 由原节点渲染，emoji 由原节点绘制）。
     *       URL 锚点必须原样保留，丢失/改写/重复时整条拒绝；emoji 锚点
     *       丢失/改写/乱序时降级回填（剥离锚点回显后忽略 emoji 切分，
     *       继续按块/行结构对齐）：emoji 显示本就由原节点提供，不依赖
     *       模型回显；行/段落结构对不上仍会拒绝，不会把错位译文送进
     *       节点树（上报：角色提及 + 标题 + 两行自定义 emoji 的消息，
     *       模型丢弃锚点时整条保持英文）。</li>
     * </ol>
     */
    @Nullable
    static List<String> alignTranslation(String plain, AlignmentPlan plan) {
        if (plan.groupIds.isEmpty() || !urlsMatch(plain, plan)) {
            return null;
        }
        if (plan.hasStyleMarkers()) {
            List<String> viaMarkers = alignByStyleMarkers(plain, plan);
            if (viaMarkers != null) {
                return viaMarkers;
            }
            plain = stripStyleTags(plain);
        }
        // 无标记回退：按 URL 与 emoji 锚点精确切分；锚点异常时改走保守降级路径。
        // 没有 emoji 锚点时保持原精确路径，不重复跑降级对齐。
        if (plan.emojiAnchors.isEmpty()) {
            return alignTokens(plain, plan, false);
        }
        if (emojiAnchorsMatch(plain, plan)) {
            // 锚点数量与请求一致：先走精确切分，成功则样式/位置保真。
            List<String> viaAnchors = alignTokens(plain, plan, false);
            if (viaAnchors != null) {
                return viaAnchors;
            }
        }
        // 降级（锚点数量不符，或精确切分失败）：不再因 emoji 锚点异常整条拒绝。
        // emoji 不消费译文片段、显示由原节点负责，剥离锚点回显后忽略
        // emoji 切分继续对齐；降级内只接受块/行逐一对得上的译文。
        return alignTokens(stripEmojiAnchors(plain, plan), plan, true);
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
                if (t.segIndex < plan.listFlags.size()
                        && Boolean.TRUE.equals(plan.listFlags.get(t.segIndex))) {
                    // 列表项内的样式片段：剔除模型回显的项目符号，
                    // 显示符号由原节点渲染。
                    styled = stripListMarker(styled);
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
                plan.listFlagsIn(first, last + 1),
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

    /** emoji 锚点是否按原数量出现（数量对得上才走精确切分；否则降级）。 */
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
     * 剔除译文中模型回显的 emoji 锚点（降级路径专用）。
     *
     * <p>emoji 显示由原节点提供，锚点文本无论保留与否都不能进入译文
     * 文本节点，否则与节点绘制重复。剥离后合并连续空白（不动换行，
     * 段落结构仍按原样校验）。自定义 emoji 的锚点是 {@code :name:} 形态
     * 的 token，普通译文不会包含，误剥风险可忽略。</p>
     */
    private static String stripEmojiAnchors(String plain, AlignmentPlan plan) {
        String out = plain;
        for (String anchor : plan.emojiAnchors) {
            out = out.replace(anchor, "");
        }
        // 锚点常占行首（自定义 emoji 在行头），剔除后留下的行首空白要去掉：
        // 回填时节点自带的行首空白会重新拼上，不清会累加出多余空格。
        // 行内连续空白压缩为一个，换行不动（段落结构仍按原样校验）。
        return out.replaceAll("(?m)^[ \\t]+", "").replaceAll("[ \\t]{2,}", " ");
    }

    /**
     * 按树序令牌流切分译文并对齐片段。
     *
     * <p>定位标记（URL / emoji）不消费译文片段。按树序依次定位标记，标记
     * 之间的文本交给 {@link #alignSpanToParts} 分配。{@code ignoreEmoji} 为
     * true 时跳过 emoji 标记并禁用段落级合并；URL 始终按树序严格定位。</p>
     */
    @Nullable
    private static List<String> alignTokens(String plain, AlignmentPlan plan,
                                            boolean ignoreEmoji) {
        boolean hasAnchors = !plan.urls.isEmpty()
                || (!ignoreEmoji && !plan.emojiAnchors.isEmpty());
        if (!hasAnchors) {
            return alignSpanToParts(plain, plan.groupIds, plan.listFlags, plan.unsafe,
                    !ignoreEmoji);
        }
        // 逐个令牌定位。锚点只切间隔、不产出片段；片段在其后的间隔里分配。
        List<String> parts = new ArrayList<>(
                java.util.Collections.nCopies(plan.groupIds.size(), ""));
        int from = 0;
        int segStart = 0;
        int urlIndex = 0;
        int emojiIndex = 0;
        for (PlanToken t : plan.tokens) {
            if (t.url == null || (ignoreEmoji && t.emoji)) {
                continue; // 可翻译片段令牌，或降级时忽略的 emoji 令牌
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
                    plan.unsafeIn(segStart, segEnd), !ignoreEmoji);
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
                plan.unsafeIn(segStart, plan.groupIds.size()), !ignoreEmoji);
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
     * 把一个 URL 间隔内的译文对齐到该间隔的片段（{@code groups} 与片段
     * 一一对应）。优先级：段落块 1:1（保结构）→ 整行 1:1 → 非空行 1:1
     * （仅含列表项的间隔：列表项之间只有单换行，逐行对应）→ 段落级对齐
     * （每段译文进本段首片段，段内其余置空）。unsafe 段落
     * （片段之间夹有 emoji/行内代码/列表符号等不可翻译可见内容）拒用
     * 段落级对齐：强行对齐会孤立这些节点，实测链接与描述分离。
     * 全部对不上返回 null。
     *
     * <p>{@code listFlags} 与 {@code groups} 平行，标记该片段是否位于
     * 列表项内：列表项片段会剥离模型回显的项目符号（{@code - }、
     * {@code • }、{@code 1. } 等），显示符号由原节点渲染，否则会重复。</p>
     */
    @Nullable
    static List<String> alignSpanToParts(String span, List<Integer> groups,
                                         java.util.BitSet unsafe) {
        return alignSpanToParts(span, groups, null, unsafe, true);
    }

    @Nullable
    static List<String> alignSpanToParts(String span, List<Integer> groups,
                                         @Nullable List<Boolean> listFlags,
                                         java.util.BitSet unsafe) {
        return alignSpanToParts(span, groups, listFlags, unsafe, true);
    }

    /**
     * @param allowParagraphMerge 是否允许最后一级「段落级对齐」（每段译文进本段
     *                            首片段、段内其余置空）。emoji 锚点降级路径必须
     *                            关闭它：被降级跳过的正是 emoji 这类夹在片段之间
     *                            的可见内容，合并会清空除首片段外的所有片段，
     *                            表现为 emoji 后的文字直接消失。
     */
    @Nullable
    static List<String> alignSpanToParts(String span, List<Integer> groups,
                                         @Nullable List<Boolean> listFlags,
                                         java.util.BitSet unsafe,
                                         boolean allowParagraphMerge) {
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
            return stripListMarkers(out, listFlags);
        }
        List<String> chunks = translatableChunks(span);
        if (chunks.size() == segs) {
            return stripListMarkers(chunks, listFlags);
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
                return stripListMarkers(new ArrayList<>(java.util.Arrays.asList(lines)),
                        listFlags);
            }
        }
        // 非空行 1:1（仅限含列表项的间隔）：列表项之间的分隔只有一个换行，
        // 模型保留「每项一行」时，空行块会把整张列表算成一块，与逐项片段
        // 对不上；非空行数则恰好等于片段数。它比段落级更严格（要求逐行完全
        // 对应），因此优先。限定列表片段是为了不改动普通段落既有的严格结构
        // 校验（不含列表的消息走上面的层级，不会落到这一级）。
        if (hasListSegment(listFlags)) {
            List<String> nonBlank = new ArrayList<>(segs);
            for (String line : span.split("\n", -1)) {
                if (!line.trim().isEmpty()) {
                    nonBlank.add(line);
                }
            }
            if (nonBlank.size() == segs) {
                return stripListMarkers(nonBlank, listFlags);
            }
        }
        int paraCount = distinctCount(groups);
        if (allowParagraphMerge && chunks.size() == paraCount && unsafe.isEmpty()) {
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
            return stripListMarkers(out, listFlags);
        }
        return null;
    }

    private static boolean hasListSegment(@Nullable List<Boolean> listFlags) {
        if (listFlags == null) {
            return false;
        }
        for (Boolean flag : listFlags) {
            if (Boolean.TRUE.equals(flag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 剥离列表项译文行首的项目符号。
     *
     * <p>请求正文里的列表项带 {@code • } 前缀（宿主渲染的符号），模型常把它
     * 原样回显；节点本身会再渲染一次符号，不剥离就会显示成两个。只对
     * {@code listFlags} 标记为列表项的片段生效，普通段落里以符号开头的文本
     * 不受影响。</p>
     */
    private static List<String> stripListMarkers(List<String> parts,
                                                 @Nullable List<Boolean> listFlags) {
        if (listFlags == null) {
            return parts;
        }
        List<String> out = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            String p = parts.get(i);
            boolean list = i < listFlags.size() && Boolean.TRUE.equals(listFlags.get(i));
            out.add(list ? stripListMarker(p) : p);
        }
        return out;
    }

    /** 行首项目符号：{@code • }、{@code - }、{@code 1. } 等（需有分隔空白）。 */
    private static final java.util.regex.Pattern LIST_MARKER =
            java.util.regex.Pattern.compile(
                    "^\\s*(?:[-*+•◦·▪▫]\\s+|\\d+[.)]\\s+)");

    /**
     * 剔除行首项目符号。剥离后什么都不剩时保留原行：宁可多显示一个符号，
     * 也不能把列表项的文本清空。
     */
    private static String stripListMarker(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        String stripped = LIST_MARKER.matcher(line).replaceFirst("");
        return stripped.trim().isEmpty() ? line : stripped;
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
        /** 与 {@code groupIds} 平行：该片段是否位于列表项内（回填时剥离回显符号）。 */
        final List<Boolean> listFlags = new ArrayList<>();
        /** 按树序的 URL 定位标记（原样 URL 文本）。 */
        final List<String> urls = new ArrayList<>();
        /** 与 {@code urls} 平行：该 URL 之前的可翻译片段数（间隔边界）。 */
        final List<Integer> urlSegIndex = new ArrayList<>();
        /** 按树序的 emoji 定位标记：Unicode 字符或宿主提供的自定义 emoji alt。 */
        final List<String> emojiAnchors = new ArrayList<>();
        /** 与 {@code emojiAnchors} 平行：该 emoji 之前的可翻译片段数（间隔边界）。 */
        final List<Integer> emojiSegIndex = new ArrayList<>();
        /**
         * 段落组号 → 段内两个可翻译片段之间夹有可见结构的标记。
         * 段落级对齐会清空非首片段，若中间有未定位的可见节点会被孤立，
         * 标记过的段落因此拒绝该级对齐。URL 与 emoji 已作为定位标记，
         * 对齐时先按它们切分间隔。
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
                    // 列表项之间只有单换行（{@code "\n• "}），旧逻辑让整张列表
                    // 属于同一个段落组，于是段落级对齐把整块列表译文塞进首项、
                    // 其余项置空（实测列表只剩一行）。每个列表项各自成段，
                    // 并标记列表片段，供回填时剔除模型回显的项目符号。
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
     * <p>URL 与 emoji 都作为定位标记在段落对齐前切分，不触发 unsafe；
     * 其他未建模的可见节点仍由 unsafe 机制保护。列表项内的片段额外标记
     * {@code listFlags}，供回填时剔除重复的项目符号。列表项边界由
     * {@code forceNewGroup} 推进段落组（列表项间只有单换行，不会自然
     * 形成空行边界）。</p>
     */
    private static void addSegment(PlanWalk w) {
        if (w.forceNewGroup) {
            w.group++;
            w.forceNewGroup = false;
        } else if (!w.plan.groupIds.isEmpty() && countNewlines(w.sep) >= 2) {
            w.group++;
        }
        w.plan.groupIds.add(w.group);
        w.plan.listFlags.add(w.listLevel > 0);
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
