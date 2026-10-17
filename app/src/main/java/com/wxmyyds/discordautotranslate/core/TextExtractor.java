package com.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * 从 Discord 的 {@code StructurableText} 结构中提取纯文本。
 *
 * <p>结构说明（Discord 340.13 已确认）：
 * <ul>
 *   <li>{@code StructurableText} 是抽象类，两个实现：
 *       {@code PrimitiveStructurableText(String content)} 与
 *       {@code AnnotatedStructurableText(List content)}。</li>
 *   <li>{@code AnnotatedStructurableText.content} 是 {@code List<ContentNode>}，
 *       节点类型位于 {@code com.discord.chat.bridge.contentnode}。</li>
 * </ul>
 *
 * <p>本类只依赖“类名 + getter 名称前缀”，不做编译期依赖，因此对混淆和版本差异更耐受。</p>
 */
public final class TextExtractor {

    /** 提取结果。 */
    public static final class Result {
        /** 可翻译的纯文本（已含换行与占位符）。 */
        public final String text;
        /** 文本节点数量，用于判断消息是否包含自然语言。 */
        public final int textNodeCount;
        /** 不可解析的结构节点数量（提及、表情等）。 */
        public final int opaqueNodeCount;
        /** 是否为不可翻译结构（例如纯代码块消息）。 */
        public final boolean codeHeavy;

        Result(String text, int textNodeCount, int opaqueNodeCount, boolean codeHeavy) {
            this.text = text;
            this.textNodeCount = textNodeCount;
            this.opaqueNodeCount = opaqueNodeCount;
            this.codeHeavy = codeHeavy;
        }
    }

    private static final Result EMPTY = new Result("", 0, 0, false);

    private TextExtractor() {
    }

    @Nullable
    public static Result extract(@Nullable Object structurableText) {
        if (structurableText == null) {
            return null;
        }
        String cls = structurableText.getClass().getName();

        if (cls.endsWith("PrimitiveStructurableText")) {
            String s = Reflect.callString(structurableText, "getContent");
            if (s == null) {
                return EMPTY;
            }
            return new Result(s, s.isEmpty() ? 0 : 1, 0, false);
        }

        if (cls.endsWith("AnnotatedStructurableText")) {
            Object list = Reflect.call(Reflect.findMethod(
                    structurableText.getClass(), "getContent", 0), structurableText);
            if (!(list instanceof List)) {
                return EMPTY;
            }
            StringBuilder sb = new StringBuilder();
            int[] counters = new int[3]; // text, opaque, codeBlocks
            walkList((List<?>) list, sb, counters);
            return new Result(sb.toString(), counters[0], counters[1], counters[2] > 0);
        }

        XLog.d("unknown StructurableText impl: " + cls);
        return null;
    }

    private static void walkList(List<?> nodes, StringBuilder out, int[] counters) {
        if (nodes == null) {
            return;
        }
        for (Object node : nodes) {
            walkNode(node, out, counters);
        }
    }

    private static void walkNode(@Nullable Object node, StringBuilder out, int[] counters) {
        if (node == null) {
            return;
        }
        String name = node.getClass().getSimpleName();

        switch (name) {
            case "TextContentNode": {
                String s = Reflect.callString(node, "getContent");
                if (s != null) {
                    out.append(s);
                    counters[0]++;
                }
                return;
            }
            case "InlineCodeContentNode": {
                String s = Reflect.callString(node, "getContent");
                if (s != null) {
                    // 行内代码保留原文，不参与翻译，但保留在文本中以便语言判断。
                    out.append('`').append(s).append('`');
                    counters[0]++;
                }
                return;
            }
            case "CodeBlockContentNode": {
                counters[2]++;
                String s = Reflect.callString(node, "getContent");
                if (s != null) {
                    out.append('\n').append(s).append('\n');
                }
                return;
            }
            case "NewLineContentNode":
            case "LineBreakContentNode":
                out.append('\n');
                return;
            case "ParagraphContentNode":
            case "BlockQuoteContentNode":
            case "HeadingContentNode":
            case "LHeadingContentNode":
            case "SubtextContentNode":
            case "StrongContentNode":
            case "EmphasisContentNode":
            case "UnderlineContentNode":
            case "StrikethroughContentNode":
            case "SpoilerContentNode":
            case "LinkContentNode": {
                Object inner = Reflect.call(
                        Reflect.findMethod(node.getClass(), "getContent", 0), node);
                if (inner instanceof List) {
                    walkList((List<?>) inner, out, counters);
                }
                return;
            }
            case "BulletListContentNode": {
                Object items = Reflect.call(
                        Reflect.findMethod(node.getClass(), "getItems", 0), node);
                if (items instanceof List) {
                    for (Object it : (List<?>) items) {
                        out.append('\n').append("• ");
                        walkNode(it, out, counters);
                    }
                }
                return;
            }
            default: {
                // 提及、表情、时间戳等：以占位符形式保留，避免破坏语义，并计入 opaque。
                counters[1]++;
                String placeholder = placeholderFor(name);
                if (placeholder != null) {
                    out.append(placeholder);
                }
            }
        }
    }

    @Nullable
    private static String placeholderFor(String simpleName) {
        if (simpleName.contains("Mention")) {
            return " @mention ";
        }
        if (simpleName.contains("Emoji")) {
            return " :emoji: ";
        }
        if (simpleName.contains("Timestamp")) {
            return " [time] ";
        }
        if (simpleName.contains("Channel")) {
            return " #channel ";
        }
        return " ";
    }

    /** 便捷方法：只取文本。 */
    @Nullable
    public static String textOf(@Nullable Object structurableText) {
        Result r = extract(structurableText);
        return r == null ? null : r.text;
    }
}
