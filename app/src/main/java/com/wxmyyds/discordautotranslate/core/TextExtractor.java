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
        /** 可翻译的纯文本（含换行；原样保留的结构节点仅贡献空白分隔）。 */
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
    private static final int MAX_DEPTH = 12;

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
            walkList((List<?>) list, sb, counters, 0);
            return new Result(sb.toString(), counters[0], counters[1], counters[2] > 0);
        }

        XLog.d("unknown StructurableText impl: " + cls);
        return null;
    }

    /**
     * 允许递归翻译的格式容器（列表节点另行处理）。
     * 提取与重建必须共享此边界：提及/频道/emoji 即使带有 content 子列表，
     * 也不能将其显示名称当成正文或译文的存放位置。
     */
    public static boolean isTextContainer(String simpleName) {
        switch (simpleName) {
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
            case "LinkContentNode":
                return true;
            default:
                return false;
        }
    }

    private static void walkList(List<?> nodes, StringBuilder out, int[] counters, int depth) {
        if (nodes == null || depth > MAX_DEPTH) {
            return;
        }
        for (Object node : nodes) {
            walkNode(node, out, counters, depth);
        }
    }

    private static void walkNode(@Nullable Object node, StringBuilder out, int[] counters, int depth) {
        if (node == null || depth > MAX_DEPTH) {
            return;
        }
        String name = node.getClass().getSimpleName();
        if (isTextContainer(name)) {
            Object inner = Reflect.call(
                    Reflect.findMethod(node.getClass(), "getContent", 0), node);
            if (inner instanceof List) {
                walkList((List<?>) inner, out, counters, depth + 1);
            }
            return;
        }

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
                // 代码内容不发给模型：模型会原样复述它，而节点树里代码本来就保留，
                // 从而造成同一段代码重复出现。计入 opaque（不可翻译结构）。
                counters[1]++;
                out.append(' ');
                return;
            }
            case "CodeBlockContentNode": {
                counters[2]++;
                // 同上：代码块内容由节点树原样保留，不进请求正文。
                out.append('\n');
                return;
            }
            case "NewLineContentNode":
            case "LineBreakContentNode":
                out.append('\n');
                return;
            case "BulletListContentNode": {
                Object items = Reflect.call(
                        Reflect.findMethod(node.getClass(), "getItems", 0), node);
                if (items instanceof List) {
                    for (Object item : (List<?>) items) {
                        out.append('\n').append("• ");
                        if (item instanceof List) {
                            walkList((List<?>) item, out, counters, depth + 1);
                        } else {
                            walkNode(item, out, counters, depth + 1);
                        }
                    }
                }
                return;
            }
            default: {
                // 提及、频道、emoji、时间戳等由原节点渲染，不再发送 @mention
                // 等可被模型复述/翻译的占位词，否则会与保留节点重复显示。
                // 留空格避免节点两侧的单词被拼接；opaque 计数仍参与语言检测。
                counters[1]++;
                out.append(' ');
            }
        }
    }
}
