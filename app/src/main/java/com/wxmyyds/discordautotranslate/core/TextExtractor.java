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
        /**
         * 带分段标记的请求文本：每个可翻译文本节点包在 {@code <sN>…</sN>} 内。
         *
         * <p>只有当消息存在 ≥2 个可翻译文本节点时才非 null；单节点消息不需要
         * 标记，直接使用 {@link #text} 即可。纯文本仍用于语言检测与内容哈希，
         * 因此标记不会影响既有判定。</p>
         */
        @Nullable
        public final String segmented;
        /** 文本节点数量，用于判断消息是否包含自然语言。 */
        public final int textNodeCount;
        /** 不可解析的结构节点数量（提及、表情等）。 */
        public final int opaqueNodeCount;
        /** 是否为不可翻译结构（例如纯代码块消息）。 */
        public final boolean codeHeavy;

        Result(String text, @Nullable String segmented, int textNodeCount,
               int opaqueNodeCount, boolean codeHeavy) {
            this.text = text;
            this.segmented = segmented;
            this.textNodeCount = textNodeCount;
            this.opaqueNodeCount = opaqueNodeCount;
            this.codeHeavy = codeHeavy;
        }
    }

    private static final Result EMPTY = new Result("", null, 0, 0, false);
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
            return new Result(s, null, s.isEmpty() ? 0 : 1, 0, false);
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
            // 同一棵树再走一遍生成带标记文本。两遍分开是为了让纯文本保持
            // 既有形态（语言检测与内容哈希都依赖它），标记只进请求。
            StringBuilder marked = new StringBuilder();
            int[] markCounters = new int[3];
            int[] segment = new int[]{0};
            walkListMarked((List<?>) list, marked, markCounters, segment, 0);
            // 只有标记数与可翻译文本节点数完全相等时才启用标记：
            // 两者错位会导致回填时片段对不上节点，此时宁可不标记。
            // 单节点消息不需要标记（无段落结构可保）。
            String segmented = segment[0] >= 2 && segment[0] == counters[0]
                    ? marked.toString() : null;
            return new Result(sb.toString(), segmented, counters[0], counters[1],
                    counters[2] > 0);
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

    // ------------------------------------------------- 带标记文本（保留段落与样式）

    /**
     * 与 {@link #walkList} 判定规则完全一致，但把每个可翻译文本节点包在分段标记里。
     *
     * <p>结构字符（换行、列表项符号、代码与不可翻译节点留下的空格）留在标记之外，
     * 回填时它们仍由原节点渲染，因此标记不会污染看起来的正文。</p>
     */
    private static void walkListMarked(List<?> nodes, StringBuilder out, int[] counters,
                                       int[] segment, int depth) {
        if (nodes == null || depth > MAX_DEPTH) {
            return;
        }
        for (Object node : nodes) {
            walkNodeMarked(node, out, counters, segment, depth);
        }
    }

    private static void walkNodeMarked(@Nullable Object node, StringBuilder out, int[] counters,
                                       int[] segment, int depth) {
        if (node == null || depth > MAX_DEPTH) {
            return;
        }
        String name = node.getClass().getSimpleName();
        if (isTextContainer(name)) {
            Object inner = Reflect.call(
                    Reflect.findMethod(node.getClass(), "getContent", 0), node);
            if (inner instanceof List) {
                walkListMarked((List<?>) inner, out, counters, segment, depth + 1);
            }
            return;
        }

        switch (name) {
            case "TextContentNode": {
                String s = Reflect.callString(node, "getContent");
                if (s != null) {
                    // 编号必须与 countTextNodes 一一对应：它按「非 null」计数，
                    // 因此空串也要占一个标记，否则两边的片段数会错位。
                    segment[0]++;
                    out.append(Segments.wrap(segment[0], s));
                    counters[0]++;
                }
                return;
            }
            case "InlineCodeContentNode": {
                counters[1]++;
                out.append(' ');
                return;
            }
            case "CodeBlockContentNode": {
                counters[2]++;
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
                            walkListMarked((List<?>) item, out, counters, segment, depth + 1);
                        } else {
                            walkNodeMarked(item, out, counters, segment, depth + 1);
                        }
                    }
                }
                return;
            }
            default: {
                counters[1]++;
                out.append(' ');
            }
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
