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
        /**
         * 可翻译的纯文本（含换行与 URL 链接定位标记；结构节点仅贡献空白分隔）。
         *
         * <p>它是无标记请求（单链路）的正文，也用于语言检测与内容哈希。
         * URL 链接显示文本原样进入请求充当定位标记：模型必须原样保留它，
         * 回填按它切分对齐并删除回显，显示永远用原链接节点——URL 字符
         * 不会损坏，也不会重复显示。</p>
         */
        public final String text;
        /**
         * 可翻译片段数（与回填消费口径一致：空白块与 URL 块不计，见
         * {@link #countSegments}）。仅用于把多片段消息的缓存条目与单片段
         * 消息隔离（见 HookBridge 的缓存后缀），不参与请求构造。
         */
        public final int segmentCount;
        /** 文本节点数量，用于判断消息是否包含自然语言。 */
        public final int textNodeCount;
        /** 不可解析的结构节点数量（提及、表情等）。 */
        public final int opaqueNodeCount;
        /** 是否为不可翻译结构（例如纯代码块消息）。 */
        public final boolean codeHeavy;

        Result(String text, int segmentCount, int textNodeCount,
               int opaqueNodeCount, boolean codeHeavy) {
            this.text = text;
            this.segmentCount = segmentCount;
            this.textNodeCount = textNodeCount;
            this.opaqueNodeCount = opaqueNodeCount;
            this.codeHeavy = codeHeavy;
        }
    }

    private static final Result EMPTY = new Result("", 0, 0, 0, false);
    private static final int MAX_DEPTH = 12;

    /**
     * URL 形态判定：带 scheme 的完整 URL、www 前缀，或「无空白且以
     * 点+字母后缀（可带端口/路径）收尾」的裸域名（如 {@code gofile.io/d/xxx}）。
     *
     * <p>URL 绝不能交给模型翻译：模型会重新生成它，造成字符损坏
     * （实测 {@code IJYuv3wZ} 被改成 {@code lJYuv3wZ}）。判定只对整段文本做
     * 全匹配，普通句子（含空白）与版本号（{@code v1.2}）不会误判。</p>
     */
    private static final java.util.regex.Pattern URL_LIKE =
            java.util.regex.Pattern.compile(
                    "\\A(https?://|www\\.)\\S+\\z"
                            + "|\\A\\S+\\.[A-Za-z]{2,}(:\\d+)?(/\\S*)?\\z");

    private TextExtractor() {
    }

    /** 文本（整段）是否为 URL。是 URL 的内容不进请求、不翻译、原样保留。 */
    public static boolean isUrlLike(@Nullable String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return false;
        }
        return URL_LIKE.matcher(t).matches();
    }

    /**
     * 链接节点的显示文本是否为 URL。
     *
     * <p>裸 URL 消息在 Discord 里渲染为 {@code LinkContentNode}，显示文本就是
     * URL 本身。这类节点整体按不可翻译结构处理（与行内代码同口径）：
     * 显示文本原样进请求充当<b>定位标记</b>（模型据此在译文里保留 URL 位置，
     * 回填按它切分对齐并删除回显，显示永远用原节点）；带自然语言显示文本的
     * 链接（掩码链接）不受影响，仍正常翻译。</p>
     */
    public static boolean linkDisplayIsUrl(Object linkNode) {
        return urlLinkDisplayText(linkNode) != null;
    }

    /**
     * 取链接节点显示文本中的 URL（整段是 URL 形态时返回去空白后的 URL，
     * 否则返回 null）。URL 文本同时是请求里的定位标记：模型必须原样保留它，
     * 回填据此确定 URL 前后各自的译文边界。
     */
    @Nullable
    public static String urlLinkDisplayText(Object linkNode) {
        Object inner = Reflect.call(
                Reflect.findMethod(linkNode.getClass(), "getContent", 0), linkNode);
        if (!(inner instanceof List)) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        appendDescendantText((List<?>) inner, sb, 0);
        String s = sb.toString().trim();
        return isUrlLike(s) ? s : null;
    }

    /** 拼接子树内全部 TextContentNode 文本（用于识别链接显示文本），非文本节点跳过。 */
    private static void appendDescendantText(List<?> nodes, StringBuilder out, int depth) {
        if (nodes == null || depth > MAX_DEPTH) {
            return;
        }
        for (Object node : nodes) {
            if (node == null) {
                continue;
            }
            String name = node.getClass().getSimpleName();
            if ("TextContentNode".equals(name)) {
                String s = Reflect.callString(node, "getContent");
                if (s != null) {
                    out.append(s);
                }
                continue;
            }
            if (isTextContainer(name)) {
                Object inner = Reflect.call(
                        Reflect.findMethod(node.getClass(), "getContent", 0), node);
                if (inner instanceof List) {
                    appendDescendantText((List<?>) inner, out, depth + 1);
                }
            }
        }
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
            // 纯文本消息整体就是一个片段：countSegments 的空行拆分口径
            // 不适用于它（历史上这类消息从不进入多片段缓存命名空间）。
            return new Result(s, s.isEmpty() ? 0 : 1, s.isEmpty() ? 0 : 1, 0, false);
        }

        if (cls.endsWith("AnnotatedStructurableText")) {
            Object list = Reflect.call(Reflect.findMethod(
                    structurableText.getClass(), "getContent", 0), structurableText);
            if (!(list instanceof List)) {
                return EMPTY;
            }
            StringBuilder sb = new StringBuilder();
            // text, opaque, codeBlocks, segments（可翻译片段数）
            int[] counters = new int[4];
            walkList((List<?>) list, sb, counters, 0);
            return new Result(sb.toString(), counters[3], counters[0], counters[1],
                    counters[2] > 0);
        }

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

    /**
     * 行内样式容器：其内的可翻译片段在请求里包样式标记
     * {@code <xN>…</xN>}（模型按提示词原样保留，回填按标记把译文
     * 精确落回样式节点，实测斜体/加粗不再丢失）。块级容器（段落/
     * 标题/引用）不在此列：它们各自成段，段落对齐已能保真。
     */
    public static boolean isInlineStyleContainer(String simpleName) {
        switch (simpleName) {
            case "StrongContentNode":
            case "EmphasisContentNode":
            case "UnderlineContentNode":
            case "StrikethroughContentNode":
            case "SpoilerContentNode":
                return true;
            default:
                return false;
        }
    }

    private static void walkList(List<?> nodes, StringBuilder out, int[] counters, int depth) {
        walkList(nodes, out, counters, depth, new int[1], false);
    }

    @SuppressWarnings("ParameterName")
    private static void walkList(List<?> nodes, StringBuilder out, int[] counters,
                                 int depth, int[] styleSeq, boolean styled) {
        if (nodes == null || depth > MAX_DEPTH) {
            return;
        }
        for (Object node : nodes) {
            walkNode(node, out, counters, depth, styleSeq, styled);
        }
    }

    // ------------------------------------------------- 片段计数与空白边界

    /**
     * 一个文本节点会被拆成多少个可翻译片段（空段落与 URL 不计）。
     *
     * <p>提取（{@code Result.segmentCount}）、计数与重建三步必须用同一个
     * 口径，否则片段会串位。口径：去掉节点首尾空白后按空行切分，非空白且
     * 非 URL 的块各算一个。</p>
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
            if (!BlankLines.isBlank(chunk) && !isUrlLike(chunk)) {
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

    private static void walkNode(@Nullable Object node, StringBuilder out, int[] counters,
                                 int depth, int[] styleSeq, boolean styled) {
        if (node == null || depth > MAX_DEPTH) {
            return;
        }
        String name = node.getClass().getSimpleName();
        if (isTextContainer(name)) {
            if ("LinkContentNode".equals(name) && linkDisplayIsUrl(node)) {
                // URL 链接显示文本原样进请求，充当定位标记：模型看不见 URL
                // 位置就无法在译文里保真它，回填也无法按 URL 切分对齐
                // （实测：正文结构对不上被整条拒绝，消息永远显示英文）。
                // 显示仍由原节点渲染；模型改坏/丢失/合并 URL 时回填拒绝，
                // 整条保留原文，URL 字符永不损坏显示。
                String url = urlLinkDisplayText(node);
                counters[1]++;
                out.append(' ').append(url).append(' ');
                return;
            }
            Object inner = Reflect.call(
                    Reflect.findMethod(node.getClass(), "getContent", 0), node);
            if (inner instanceof List) {
                walkList((List<?>) inner, out, counters, depth + 1, styleSeq,
                        styled || isInlineStyleContainer(name));
            }
            return;
        }

        switch (name) {
            case "TextContentNode": {
                String s = Reflect.callString(node, "getContent");
                if (s == null) {
                    return;
                }
                // 片段计数与消费（ContentNodes.countTextNodes/rebuild）同口径：
                // 空白块与 URL 块不算可翻译片段。
                counters[3] += countSegments(s);
                if (!styled || BlankLines.isBlank(s)) {
                    // 无样式，或样式容器内的纯空白节点（实测 ** … ** 的尾随
                    // 空格会被宿主拆成这样的节点，此时 lead > tail，
                    // substring(lead, tail) 会越界）：整段原样进请求，
                    // 空白不属于任何可翻译片段。
                    out.append(s);
                    counters[0]++;
                    return;
                }
                // 样式片段：核心内容包 <xN>…</xN>，首尾空白与节点内空行、
                // URL 块留在标记外（结构由原节点提供）。序号全局递增，
                // 与 ContentNodes 计划的枚举顺序一致。
                counters[0]++;
                int lead = leadingWhitespace(s);
                int tail = trailingWhitespaceStart(s);
                out.append(s, 0, lead);
                BlankLines.Split split = BlankLines.split(s.substring(lead, tail));
                for (int i = 0; i < split.chunks.size(); i++) {
                    if (i > 0) {
                        out.append(split.separators.get(i - 1));
                    }
                    String chunk = split.chunks.get(i);
                    if (BlankLines.isBlank(chunk) || isUrlLike(chunk)) {
                        out.append(chunk);
                        continue;
                    }
                    styleSeq[0]++;
                    out.append("<x").append(styleSeq[0]).append('>')
                            .append(chunk)
                            .append("</x").append(styleSeq[0]).append('>');
                }
                out.append(s, tail, s.length());
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
                            walkList((List<?>) item, out, counters, depth + 1,
                                    styleSeq, styled);
                        } else {
                            walkNode(item, out, counters, depth + 1, styleSeq, styled);
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
