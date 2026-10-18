package com.wxmyyds.discordautotranslate.hooks;

import androidx.annotation.Nullable;

import com.wxmyyds.discordautotranslate.core.Reflect;
import com.wxmyyds.discordautotranslate.core.XLog;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

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
 * 这样新增节点类型时无需改动代码。</p>
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
     * @param nodes        原始节点列表
     * @param translated   译文（纯文本；换行会被还原为换行）
     * @return 新的节点列表；无需变化时返回 null
     */
    @Nullable
    public static List<Object> replaceText(@Nullable List<?> nodes, String translated) {
        if (nodes == null) {
            return null;
        }
        // 收集承载自然语言的 TextContentNode（原文顺序）。
        List<Object> targets = new ArrayList<>();
        collectTextNodes(nodes, targets, 0);
        if (targets.isEmpty()) {
            XLog.d("no TextContentNode found; nothing to substitute");
            return null;
        }
        // 把译文按原有文本节点数量切分，尽量保持段落结构。
        List<String> parts = splitTranslation(translated, targets.size());

        Map<Object, String> replacements = new IdentityHashMap<>();
        for (int i = 0; i < targets.size(); i++) {
            replacements.put(targets.get(i), parts.get(i));
        }

        List<Object> result = new ArrayList<>(nodes.size());
        boolean[] changed = new boolean[1];
        for (Object n : nodes) {
            result.add(rebuild(n, replacements, changed, 0));
        }
        return changed[0] ? result : null;
    }

    /** 递归收集自然语言文本节点（按原文顺序）。 */
    private static void collectTextNodes(@Nullable List<?> nodes, List<Object> out, int depth) {
        if (nodes == null || depth > 12) {
            return;
        }
        for (Object n : nodes) {
            if (n == null) {
                continue;
            }
            if (n instanceof List<?>) {
                collectTextNodes((List<?>) n, out, depth + 1);
                continue;
            }
            String simple = n.getClass().getSimpleName();
            if ("TextContentNode".equals(simple)) {
                out.add(n);
                continue;
            }
            if (CODE_NODES.contains(simple)) {
                continue; // 代码不翻译
            }
            List<?> inner = childListOf(n, simple);
            if (inner != null) {
                collectTextNodes(inner, out, depth + 1);
            }
        }
    }

    /** 取出节点的子列表：容器的 content，或列表节点的 items。 */
    @Nullable
    private static List<?> childListOf(Object node, String simpleName) {
        String getter = BULLET_LIST.equals(simpleName) ? "getItems" : "getContent";
        Object v = Reflect.call(
                Reflect.findMethod(
                        node.getClass(), getter, 0), node);
        return v instanceof List ? (List<?>) v : null;
    }

    /**
     * 重建单个节点：递归处理子列表，并把命中的文本节点替换为译文。
     */
    private static Object rebuild(@Nullable Object node, Map<Object, String> replacements,
                                  boolean[] changed, int depth) {
        if (node == null || depth > 12) {
            return node;
        }
        if (node instanceof List<?>) {
            List<?> children = (List<?>) node;
            List<Object> rebuiltChildren = new ArrayList<>(children.size());
            boolean localChanged = false;
            for (Object child : children) {
                Object rebuiltChild = rebuild(child, replacements, changed, depth + 1);
                if (rebuiltChild != child) {
                    localChanged = true;
                }
                rebuiltChildren.add(rebuiltChild);
            }
            return localChanged ? rebuiltChildren : node;
        }

        String simple = node.getClass().getSimpleName();

        // 命中的文本节点：直接换成承载译文的同类型节点。
        String repl = replacements.get(node);
        if (repl != null) {
            Object created = createTextNode(node, repl);
            if (created != null) {
                changed[0] = true;
                return created;
            }
            return node;
        }

        // 代码节点不进入。
        if (CODE_NODES.contains(simple)) {
            return node;
        }

        String listGetter = BULLET_LIST.equals(simple) ? "getItems" : "getContent";
        Object childList = Reflect.call(
                Reflect.findMethod(
                        node.getClass(), listGetter, 0), node);
        if (!(childList instanceof List)) {
            return node; // 无子列表（例如表情、提及），原样返回
        }

        List<?> children = (List<?>) childList;
        List<Object> newChildren = new ArrayList<>(children.size());
        boolean localChanged = false;
        for (Object c : children) {
            Object nc = rebuild(c, replacements, changed, depth + 1);
            if (nc != c) {
                localChanged = true;
            }
            newChildren.add(nc != null ? nc : c);
        }
        if (!localChanged) {
            return node;
        }
        Object rebuilt = copyWith(node, newChildren);
        return rebuilt != null ? rebuilt : node;
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
