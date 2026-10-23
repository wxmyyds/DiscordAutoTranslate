package com.wxmyyds.discordautotranslate.hooks;

import androidx.annotation.Nullable;

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
     * 对应的片段，于是换行结构与加粗/标题等样式容器都按原样保留。模型没有
     * 遵守标记协议时回退到按换行切分；两者都不改变节点树结构。</p>
     *
     * @param nodes        原始节点列表
     * @param translated   译文（可能是带标记的模型原文，也可能是纯文本）
     * @return 新的节点列表；无需变化或任一节点重建失败时返回 null，调用方应保留原文
     */
    @Nullable
    public static List<Object> replaceText(@Nullable List<?> nodes, String translated) {
        if (nodes == null || translated == null || translated.trim().isEmpty()) {
            return null;
        }
        int targetCount = countTextNodes(nodes, 0);
        if (targetCount == 0) {
            XLog.d("no TextContentNode found; nothing to substitute");
            return null;
        }
        // 优先按分段标记回填：模型逐段翻译并保留 <sN> 标记时，
        // 每个文本节点拿到自己的片段，样式容器与换行结构自然保留。
        // 解析失败（模型漏写/改写/合并标记）则回退到按换行切分的旧逻辑。
        List<String> parts = Segments.parse(translated, targetCount);
        if (parts == null) {
            // 解析失败时先剔除标记：否则 <s1> 会被当作普通文字渲染到消息里。
            String plain = Segments.isSegmented(translated)
                    ? Segments.strip(translated) : translated;
            if (plain == null) {
                plain = "";
            }
            if (Segments.isSegmented(translated)) {
                XLog.d("segment markers incomplete; falling back to line split");
            }
            parts = splitTranslation(normalize(plain), targetCount);
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
        // 译文行数通常少于原文，多余位置会被补成空串；原换行结构节点也会保留。
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

    /** 递归统计自然语言文本节点，每次出现分别计数（包括共享实例）。 */
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
                // 与提取阶段一致：null 内容不会进入请求，也不占用译文片段。
                if (Reflect.callString(n, "getContent") != null) {
                    count++;
                }
                continue;
            }
            if (CODE_NODES.contains(simple)) {
                continue; // 代码不翻译
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
            if (Reflect.callString(node, "getContent") == null) {
                return node;
            }
            if (replacement.next >= replacement.parts.size()) {
                replacement.failed = true;
                return node;
            }
            String repl = replacement.parts.get(replacement.next++);
            Object created = createTextNode(node, repl);
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
     * 把译文切分成与原文本节点数量一致的片段。
     *
     * <p>模型通常会把多段内容合并返回，此处按换行优先切分；不足时补空串。
     * 切分只是尽力保持段落结构，即使不完美也不影响可读性。</p>
     */
    static List<String> splitTranslation(String translated, int parts) {
        List<String> out = new ArrayList<>(parts);
        if (parts <= 1) {
            out.add(translated);
            return out;
        }
        String[] lines = translated.split("\n", -1);
        if (lines.length >= parts) {
            // 行数足够：按行分配，多余的行合并进最后一段。
            int head = parts - 1;
            for (int i = 0; i < head; i++) {
                out.add(lines[i]);
            }
            StringBuilder tail = new StringBuilder();
            for (int i = head; i < lines.length; i++) {
                if (tail.length() > 0) {
                    tail.append('\n');
                }
                tail.append(lines[i]);
            }
            out.add(tail.toString());
            return out;
        }
        // 行数不足：首段承载全部，其余留空（避免把句子硬切碎）。
        out.add(translated);
        for (int i = 1; i < parts; i++) {
            out.add("");
        }
        return out;
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
