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
            int[] segment = new int[]{0};
            walkListMarked((List<?>) list, marked, segment, 0);
            // 只有标记数至少为 2 时才启用：单段消息没有段落结构可保，
            // 加标记只会给模型增加噪音。
            //
            // 标记数本身由 walkNodeMarked 与 ContentNodes.countTextNodes 共用的
            // 口径（TextExtractor.countSegments）保证一致：两边都只给「非空白的
            // 段落块」编号。若这里再拿一个恒等的计数去校验，等于什么都没校验，
            // 所以只保留「至少 2 段」这个有意义的门槛。
            String segmented = segment[0] >= 2 ? marked.toString() : null;
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
     * 一个文本节点会被拆成多少个可翻译片段（空段落不计）。
     *
     * <p>提取（生成标记）、计数（消费片段）与重建三步必须用同一个口径，
     * 否则片段会串位。口径：去掉节点首尾空白后按空行切分，非空白的块各算一个。</p>
     */
    public static int countSegments(@Nullable String content) {
        if (content == null) {
            return 0;
        }
        int lead = leadingWhitespace(content);
        int tail = trailingWhitespaceStart(content);
        if (lead >= tail) {
            return 0; // 只含空白
        }
        BlankLines.Split split = BlankLines.split(content.substring(lead, tail));
        int count = 0;
        for (String chunk : split.chunks) {
            if (!BlankLines.isBlank(chunk)) {
                count++;
            }
        }
        return count;
    }

    /** 前导空白长度。 */
    public static int leadingWhitespace(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** 末尾空白之前的位置（即核心内容的结束下标，不含）。 */
    public static int trailingWhitespaceStart(String s) {
        int i = s.length();
        while (i > 0 && Character.isWhitespace(s.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    /**
     * 与 {@link #walkList} 判定规则一致，但把每个可翻译文本节点包在分段标记里。
     *
     * <p>结构字符（换行、列表项符号、代码与不可翻译节点留下的空格）留在标记之外，
     * 回填时它们仍由原节点渲染，因此标记不会污染看起来的正文。</p>
     */
    private static void walkListMarked(List<?> nodes, StringBuilder out,
                                       int[] segment, int depth) {
        if (nodes == null || depth > MAX_DEPTH) {
            return;
        }
        for (Object node : nodes) {
            walkNodeMarked(node, out, segment, depth);
        }
    }

    private static void walkNodeMarked(@Nullable Object node, StringBuilder out,
                                       int[] segment, int depth) {
        if (node == null || depth > MAX_DEPTH) {
            return;
        }
        String name = node.getClass().getSimpleName();
        if (isTextContainer(name)) {
            Object inner = Reflect.call(
                    Reflect.findMethod(node.getClass(), "getContent", 0), node);
            if (inner instanceof List) {
                walkListMarked((List<?>) inner, out, segment, depth + 1);
            }
            return;
        }

        switch (name) {
            case "TextContentNode": {
                String s = Reflect.callString(node, "getContent");
                if (s == null) {
                    return;
                }
                if (BlankLines.isBlank(s)) {
                    // 只含空白：它是段落/空行结构，不翻译、不加标记，
                    // 原样输出，与节点树保持一致。
                    out.append(s);
                    return;
                }
                // 首尾空白同样属于结构：留在标记外，只把核心内容交给模型。
                // 这样即使模型在译文里增删首尾空白，重建时也会用原节点的空白，
                // 不会把 "\n\n" 这种段落分隔弄丢。
                int lead = leadingWhitespace(s);
                int tail = trailingWhitespaceStart(s);
                String core = s.substring(lead, tail);
                out.append(s, 0, lead);
                // 文本内部可能自带空行（如纯文本消息 "你好\n\n世界"）。
                // 空行必须留在标记外，否则模型会把它们合并或挑走。
                BlankLines.Split split = BlankLines.split(core);
                for (int i = 0; i < split.chunks.size(); i++) {
                    if (i > 0) {
                        out.append(split.separators.get(i - 1));
                    }
                    String chunk = split.chunks.get(i);
                    if (BlankLines.isBlank(chunk)) {
                        // 空段落：不占标记，原样保留。
                        out.append(chunk);
                        continue;
                    }
                    segment[0]++;
                    out.append(Segments.wrap(segment[0], chunk));
                }
                out.append(s, tail, s.length());
                return;
            }
            case "InlineCodeContentNode": {
                out.append(' ');
                return;
            }
            case "CodeBlockContentNode": {
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
                            walkListMarked((List<?>) item, out, segment, depth + 1);
                        } else {
                            walkNodeMarked(item, out, segment, depth + 1);
                        }
                    }
                }
                return;
            }
            default:
                out.append(' ');
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
