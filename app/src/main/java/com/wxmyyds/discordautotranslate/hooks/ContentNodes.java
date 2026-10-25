package com.wxmyyds.discordautotranslate.hooks;

import androidx.annotation.Nullable;

import com.wxmyyds.discordautotranslate.core.BlankLines;
import com.wxmyyds.discordautotranslate.core.Reflect;
import com.wxmyyds.discordautotranslate.core.Segments;
import com.wxmyyds.discordautotranslate.core.TextExtractor;
import com.wxmyyds.discordautotranslate.core.XLog;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Discord 消息内容结构的反射访问层（需求 §7 阶段 3）。
 *
 * <p>背景（已核对 smali 源码）：
 * {@code TextUtilsKt.toSpannable(StructurableText, ...)} 是<b>所有</b>文本渲染的唯一入口，
 * 且只有两个分支：
 * <ul>
 *   <li>{@code AnnotatedStructurableText} → 遍历 {@code List<ContentNode>} 渲染；</li>
 *   <li>{@code PrimitiveStructurableText} → 整体当纯文本渲染；</li>
 *   <li>其他类型 → {@code throw RuntimeException}。</li>
 * </ul>
 *
 * <p>关键设计取舍：早期做法是把带 Markdown 的消息整体替换成 {@code PrimitiveStructurableText}，
 * 虽然不会崩溃（渲染器接受该类型），但会<b>丢失全部 Markdown 格式</b>。
 * 本类改为保留 {@code AnnotatedStructurableText} 类型，
 * 只把其中的 {@code TextContentNode}（自然语言）替换为译文节点，
 * 结构化节点（提及/表情/链接/代码块等）原样保留，从而保住格式。</p>
 *
 * <p>节点重建采用 Kotlin data class 的通用能力而非逐个类型写死：
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
     * <p>译文优先按分段标记（{@code <sN>…</sN>}）回填：每个文本节点拿到自己
     * 对应的片段，于是换行结构与加粗/标题等样式容器都按原样保留。译文里
     * <b>带有标记但校验不过</b>（残缺/错序/夹带标记/占位符）= 协议被模型破坏，
     * 整条保留原文；<b>无标记</b>译文（单链路无标记请求的标准形态）按空行
     * 段落块对齐回填，段落块多于可翻译片段同样整条保留原文，绝不让复读或
     * 错位内容进入节点树。URL 链接与 URL 文本块不参与翻译，原样保留。</p>
     *
     * @param nodes        原始节点列表
     * @param translated   译文（可能是带标记的模型原文，也可能是纯文本）
     * @return 新的节点列表；无需变化、协议校验失败或任一节点重建失败时返回
     *         null，调用方应保留原文
     */
    @Nullable
    public static List<Object> replaceText(@Nullable List<?> nodes, String translated) {
        if (nodes == null || translated == null || translated.trim().isEmpty()) {
            return null;
        }
        int targetCount = countTextNodes(nodes, 0);
        if (targetCount == 0) {
            XLog.d("no translatable TextContentNode found; nothing to substitute");
            return null;
        }
        // 优先按分段标记回填：模型逐段翻译并保留 <sN> 标记时，
        // 每个段落块拿到自己的片段，样式容器与换行结构自然保留。
        List<String> parts = Segments.parse(translated, targetCount);
        if (parts == null) {
            // targetCount >= 2 等价于「本次请求带分段标记」。译文里仍有标记
            // 但校验不过（残缺/错序/夹带标记/占位符）= 协议被破坏且损坏形态
            // 可识别，整条保留原文，绝不把损坏译文塞回节点树。
            if (targetCount >= 2 && Segments.isSegmented(translated)) {
                XLog.d("segment protocol violated (parts=" + targetCount
                        + "); keeping original text");
                return null;
            }
            // 无标记译文：单链路（无标记纯文本请求）的标准形态，
            // 或单段请求。只有结构与片段数精确对应才回填（段落块 1:1 或
            // 非空行 1:1）；模型重排/增删段落时整条保留原文，
            // 不做启发式分配（那是乱序与内容丢失的来源）。
            String plain = normalize(Segments.strip(translated));
            if (plain.isEmpty()) {
                // 剔除后什么都不剩：不能清空消息，保留原文。
                return null;
            }
            parts = splitTranslation(plain, targetCount);
            if (parts == null) {
                XLog.d("markerless translation structure mismatch (targets="
                        + targetCount + "); keeping original text");
                return null;
            }
        }
        // 按节点出现的位置分配片段，不能以实例身份为键：共享节点的后一次
        // 空片段会覆盖前一次译文，造成文字全部消失但 emoji 仍保留。
        Replacement replacement = new Replacement(parts);
        List<Object> result = new ArrayList<>(nodes.size());
        for (Object n : nodes) {
            result.add(rebuild(n, replacement, 0));
        }
        // 容器复制失败可能丢弃已重建的子节点；不得把其他已清空的文本节点
        // 作为部分成功的结果交给宿主。只允许整棵树替换成功。
        if (replacement.failed || replacement.next != targetCount) {
            XLog.d("node replacement aborted: targets=" + targetCount
                    + " visited=" + replacement.next);
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
     * 递归统计<b>可翻译</b>片段数，每次出现分别计数（包括共享实例）。
     *
     * <p>口径与提取阶段完全一致：一个文本节点可能因为内部空行被拆成多个片段，
     * 只含空白的节点不计数。三者口径不一致会让片段串位。</p>
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

        // 代码节点不进入。
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
            XLog.d("no copy() on " + cls.getSimpleName());
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
            XLog.w("createTextNode failed for " + original.getClass().getSimpleName(), t);
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
     * 把译文切分成与原文本节点数量一致的片段（精确对应，或拒绝）。
     *
     * <p>回填消费片段的口径是「空行切分的非空白非 URL 段落块」（见
     * {@link TextExtractor#countSegments}）。结构对得上时片段与节点一一对应，
     * URL 链接仍与正确的段落相邻：</p>
     * <ol>
     *   <li>译文段落块数 == 片段数 → 每块落到自己的节点（保结构译文）；</li>
     *   <li>非空行数 == 片段数 → 按行 1:1（相邻单行节点、模型用单换行分段）；</li>
     *   <li>其余一律返回 null，整条保留原文。</li>
     * </ol>
     *
     * <p>不再做启发式分配（旧行为：行数富余按行分配、空行顶掉节点译文、
     * 多余行全部堆进最后一个节点、行数不足整段塞首节点）。模型重排段落
     * 时这些启发式就是实测的「译文乱序、节点被置空、消息膨胀」来源，
     * 流式与非流式共用同一切分逻辑，与传输方式无关。</p>
     *
     * @return 片段列表；结构与片段数不对应时返回 null（拒绝回填）
     */
    @Nullable
    static List<String> splitTranslation(String translated, int parts) {
        List<String> out = new ArrayList<>(parts);
        if (parts <= 1) {
            out.add(translated);
            return out;
        }
        List<String> chunks = new ArrayList<>();
        for (String chunk : BlankLines.split(translated).chunks) {
            String trimmed = chunk.trim();
            if (!trimmed.isEmpty() && !TextExtractor.isUrlLike(trimmed)) {
                chunks.add(trimmed);
            }
        }
        if (chunks.size() == parts) {
            return chunks;
        }
        String[] lines = translated.split("\n", -1);
        if (lines.length == parts) {
            for (String line : lines) {
                if (line.trim().isEmpty()) {
                    // 空行顶掉某个节点的译文 = 该节点内容丢失，必须拒绝。
                    return null;
                }
            }
            for (String line : lines) {
                out.add(line);
            }
            return out;
        }
        return null;
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
