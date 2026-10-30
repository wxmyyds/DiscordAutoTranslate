package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.github.wxmyyds.discordautotranslate.core.EnglishDetector;
import io.github.wxmyyds.discordautotranslate.core.TextExtractor;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link ContentNodes} 的节点树重建逻辑（需求 §7 阶段 3：保留 Markdown）。
 *
 * <p>测试策略：Discord 的真实节点类在单测环境不存在，但 {@link ContentNodes}
 * 完全通过反射（{@code getContent}/{@code getItems}/{@code componentN}/{@code copy}/
 * {@code <init>(String)}）操作节点。因此这里定义同名的假节点类来模拟
 * Kotlin data class 的结构，让被测的反射代码路径真正执行起来。</p>
 *
 * <p>注意：假类的 {@code getSimpleName()} 必须与 Discord 的类名一致，
 * 因为 {@code ContentNodes} 按类名分类节点。</p>
 */
public class ContentNodesTest {

    // ---------------------------------------------------------------- 假节点模型
    // 类名必须与 Discord 一致，ContentNodes 依赖 getSimpleName()。

    /** 纯文本叶子节点。 */
    public static final class TextContentNode {
        private final String content;

        public TextContentNode(String content) {
            this.content = content;
        }

        public String getContent() {
            return content;
        }

        public String component1() {
            return content;
        }

        public TextContentNode copy(String content) {
            return new TextContentNode(content);
        }

        @Override
        public String toString() {
            return "Text{" + content + "}";
        }
    }

    /** 行内代码：内容不应被翻译。 */
    public static final class InlineCodeContentNode {
        private final String content;

        public InlineCodeContentNode(String content) {
            this.content = content;
        }

        public String getContent() {
            return content;
        }

        public String component1() {
            return content;
        }

        public InlineCodeContentNode copy(String content) {
            return new InlineCodeContentNode(content);
        }

        @Override
        public String toString() {
            return "Code{" + content + "}";
        }
    }

    /** 代码块：内容不应被翻译。 */
    public static final class CodeBlockContentNode {
        private final String content;
        private final String lang;
        private final boolean inQuote;

        public CodeBlockContentNode(String content, String lang, boolean inQuote) {
            this.content = content;
            this.lang = lang;
            this.inQuote = inQuote;
        }

        public String getContent() {
            return content;
        }

        public String component1() {
            return content;
        }

        public String component2() {
            return lang;
        }

        public boolean component3() {
            return inQuote;
        }

        public CodeBlockContentNode copy(String content, String lang, boolean inQuote) {
            return new CodeBlockContentNode(content, lang, inQuote);
        }
    }

    /** 无子列表的叶子节点（表情、提及等）。 */
    public static final class EmojiContentNode {
        private final String id;

        public EmojiContentNode(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }

    /** 换行结构节点：宿主渲染为单个换行符号，本身不带文本。 */
    public static final class NewLineContentNode {
    }

    /** 行内换行节点（与 NewLine 同义）。 */
    public static final class LineBreakContentNode {
    }

    /** 带列表的容器节点（模拟 Paragraph）。 */
    public static final class ParagraphContentNode {
        private final List<Object> content;

        public ParagraphContentNode(List<Object> content) {
            this.content = content;
        }

        public List<Object> getContent() {
            return content;
        }

        public List<Object> component1() {
            return content;
        }

        public ParagraphContentNode copy(List<Object> content) {
            return new ParagraphContentNode(content);
        }
    }

    /** 带额外字段的容器节点（模拟 Heading，必须保留 level）。 */
    public static final class HeadingContentNode {
        private final List<Object> content;
        private final int level;

        public HeadingContentNode(List<Object> content, int level) {
            this.content = content;
            this.level = level;
        }

        public List<Object> getContent() {
            return content;
        }

        public List<Object> component1() {
            return content;
        }

        public int component2() {
            return level;
        }

        public HeadingContentNode copy(List<Object> content, int level) {
            return new HeadingContentNode(content, level);
        }
    }

    /** 带样式的文本容器。 */
    public static final class StrongContentNode {
        private final List<Object> content;

        public StrongContentNode(List<Object> content) {
            this.content = content;
        }

        public List<Object> getContent() {
            return content;
        }

        public List<Object> component1() {
            return content;
        }

        public StrongContentNode copy(List<Object> content) {
            return new StrongContentNode(content);
        }
    }

    /** 斜体容器（Discord 的 *x* / ***x*** 内层）。 */
    public static final class EmphasisContentNode {
        private final List<Object> content;

        public EmphasisContentNode(List<Object> content) {
            this.content = content;
        }

        public List<Object> getContent() {
            return content;
        }

        public List<Object> component1() {
            return content;
        }

        public EmphasisContentNode copy(List<Object> content) {
            return new EmphasisContentNode(content);
        }
    }

    /** 提及叶子节点。 */
    public static final class UserMentionContentNode {
        private final String userId;

        public UserMentionContentNode(String userId) {
            this.userId = userId;
        }
    }

    /** Link 节点：除内容外还有 LinkContextData，必须原样保留。 */
    public static final class LinkContentNode {
        private final List<Object> content;
        private final Object linkContextData;

        public LinkContentNode(List<Object> content, Object linkContextData) {
            this.content = content;
            this.linkContextData = linkContextData;
        }

        public List<Object> getContent() {
            return content;
        }

        public List<Object> component1() {
            return content;
        }

        public Object component2() {
            return linkContextData;
        }

        public LinkContentNode copy(List<Object> content, Object linkContextData) {
            return new LinkContentNode(content, linkContextData);
        }
    }

    /** Bullet list：items 是逐项保存的 List<List<ContentNode>>。 */
    public static final class BulletListContentNode {
        private final Long start;
        private final boolean ordered;
        private final List<List<Object>> items;

        public BulletListContentNode(Long start, boolean ordered, List<List<Object>> items) {
            this.start = start;
            this.ordered = ordered;
            this.items = items;
        }

        public List<List<Object>> getItems() {
            return items;
        }

        public Long component1() {
            return start;
        }

        public boolean component2() {
            return ordered;
        }

        public List<List<Object>> component3() {
            return items;
        }

        public BulletListContentNode copy(Long start, boolean ordered, List<List<Object>> items) {
            return new BulletListContentNode(start, ordered, items);
        }
    }

    /** 假 AnnotatedStructurableText，类名需以该串结尾。 */
    public static final class AnnotatedStructurableText {
        private final List<Object> content;

        public AnnotatedStructurableText(List<Object> content) {
            this.content = content;
        }

        public List<Object> getContent() {
            return content;
        }
    }

    /** 假 PrimitiveStructurableText。 */
    public static final class PrimitiveStructurableText {
        private final String content;

        public PrimitiveStructurableText(String content) {
            this.content = content;
        }

        public String getContent() {
            return content;
        }
    }

    /** 已知格式容器的兼容性故障；未知容器现在应整节点保留。 */
    public static final class NoCopyContainer {
        public static final class ParagraphContentNode {
            private final List<Object> content;

            public ParagraphContentNode(List<Object> content) {
                this.content = content;
            }

            public List<Object> getContent() {
                return content;
            }
        }
    }

    /** 模拟已知格式容器的 copy 调用失败。 */
    public static final class ThrowingCopyContainer {
        public static final class ParagraphContentNode {
            private final List<Object> content;

            public ParagraphContentNode(List<Object> content) {
                this.content = content;
            }

            public List<Object> getContent() {
                return content;
            }

            public ParagraphContentNode copy(List<Object> content) {
                throw new IllegalStateException("simulated copy failure");
            }
        }
    }

    public static final class MissingTextConstructor {
        /** 简单类名仍为 TextContentNode，但不提供单 String 构造函数。 */
        public static final class TextContentNode {
            private final String content;

            public TextContentNode(String content, boolean ignored) {
                this.content = content;
            }

            public String getContent() {
                return content;
            }
        }
    }

    private static List<Object> list(Object... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    @SafeVarargs
    private static List<List<Object>> itemLists(List<Object>... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    // ---------------------------------------------------------------- 类型判断

    @Test
    public void detectsAnnotatedAndPrimitive() {
        assertTrue(ContentNodes.isAnnotated(new AnnotatedStructurableText(list())));
        assertFalse(ContentNodes.isAnnotated(new PrimitiveStructurableText("x")));
        assertTrue(ContentNodes.isPrimitive(new PrimitiveStructurableText("x")));
        assertFalse(ContentNodes.isPrimitive(new AnnotatedStructurableText(list())));
        assertFalse(ContentNodes.isAnnotated(null));
        assertFalse(ContentNodes.isPrimitive(null));
    }

    @Test
    public void listOfExtractsNodeList() {
        List<Object> nodes = list(new TextContentNode("a"));
        List<?> got = ContentNodes.listOf(new AnnotatedStructurableText(nodes));
        assertNotNull(got);
        assertEquals(1, got.size());
        assertNull(ContentNodes.listOf(new PrimitiveStructurableText("x")));
        assertNull(ContentNodes.listOf(null));
    }

    // ---------------------------------------------------------------- 文本替换

    @Test
    public void replacesPlainTextNode() {
        List<Object> nodes = list(new TextContentNode("Hello"));
        List<Object> out = ContentNodes.replaceText(nodes, "你好");
        assertNotNull(out);
        assertEquals(1, out.size());
        assertEquals("你好", ((TextContentNode) out.get(0)).getContent());
    }

    @Test
    public void preservesNestedFormattingStructure() {
        // Paragraph > [Text, Emoji, NewLine, Text]
        List<Object> inner = list(
                new TextContentNode("Hello "),
                new EmojiContentNode("123"),
                new NewLineContentNode(),
                new TextContentNode("world"));
        List<Object> nodes = list(new ParagraphContentNode(inner));

        List<Object> out = ContentNodes.replaceText(nodes, "你好\n世界");

        assertNotNull(out);
        assertEquals(1, out.size());
        ParagraphContentNode para = (ParagraphContentNode) out.get(0);

        // 结构化节点必须原样复用（同一实例），每个文本节点拿到自己的片段。
        assertEquals(4, para.getContent().size());
        assertSame("结构化节点必须原样复用", inner.get(1), para.getContent().get(1));
        assertTrue(para.getContent().get(1) instanceof EmojiContentNode);

        // 不变量：首尾空白属于原节点结构，重建时从原节点取回。
        // "Hello " 的尾随空格不交给模型，因此译文之后仍保留。
        assertEquals("你好 ", ((TextContentNode) para.getContent().get(0)).getContent());
        assertEquals("世界", ((TextContentNode) para.getContent().get(3)).getContent());
    }

    @Test
    public void sharedTextInstanceDoesNotLoseTranslationToLaterEmptyPart() {
        TextContentNode shared = new TextContentNode("hello");
        EmojiContentNode emoji = new EmojiContentNode("1");
        List<Object> nodes = list(shared, emoji, new NewLineContentNode(), shared);

        List<Object> out = ContentNodes.replaceText(nodes, "你好\n你好");

        assertNotNull(out);
        // 同一实例在不同位置按出现顺序各拿一个片段，互不覆盖。
        assertEquals(4, out.size());
        assertEquals("你好", ((TextContentNode) out.get(0)).getContent());
        assertSame(emoji, out.get(1));
        assertEquals("你好", ((TextContentNode) out.get(3)).getContent());
        assertEquals("hello", shared.getContent());
        assertSame(shared, nodes.get(0));
        assertSame(shared, nodes.get(3));
    }

    @Test
    public void sharedTextInstanceReceivesSeparateMultilineParts() {
        TextContentNode shared = new TextContentNode("hello");
        List<Object> out = ContentNodes.replaceText(list(shared, new NewLineContentNode(),
                        shared),
                "第一\n第二");

        assertNotNull(out);
        assertEquals("第一", ((TextContentNode) out.get(0)).getContent());
        assertEquals("第二", ((TextContentNode) out.get(2)).getContent());
    }

    @Test
    public void sharedContainerIsRebuiltSeparatelyAtEachPosition() {
        TextContentNode text = new TextContentNode("hello");
        ParagraphContentNode shared = new ParagraphContentNode(list(text));
        List<Object> out = ContentNodes.replaceText(
                list(shared, new NewLineContentNode(), shared), "你好\n呀");

        assertNotNull(out);
        // 两个容器位置各自重建，互不影响。
        assertEquals(3, out.size());
        ParagraphContentNode first = (ParagraphContentNode) out.get(0);
        assertEquals("你好", ((TextContentNode) first.getContent().get(0)).getContent());
        ParagraphContentNode second = (ParagraphContentNode) out.get(2);
        assertEquals("呀", ((TextContentNode) second.getContent().get(0)).getContent());
        assertSame(text, shared.getContent().get(0));
        assertEquals("hello", text.getContent());
    }

    @Test
    public void failedFirstContainerDoesNotClearRemainingTextAndLeaveOnlyEmoji() {
        // 容器内放可翻译文本，才能真正走到 copy 失败那条路（空文本节点不触发 copy）。
        TextContentNode inside = new TextContentNode("first");
        NoCopyContainer.ParagraphContentNode container =
                new NoCopyContainer.ParagraphContentNode(list(inside));
        TextContentNode visible = new TextContentNode("hello world");
        EmojiContentNode emoji = new EmojiContentNode("1");
        List<Object> nodes = list(container, new NewLineContentNode(), visible, emoji);

        // 首个容器复制失败时，不提交后续已构造的译文。
        assertNull(ContentNodes.replaceText(nodes, "第一\n你好世界"));
        assertEquals("hello world", visible.getContent());
        assertSame(container, nodes.get(0));
        assertSame(visible, nodes.get(2));
        assertSame(emoji, nodes.get(3));
        assertSame(inside, container.getContent().get(0));
    }

    @Test
    public void throwingCopyAbortsWholeReplacement() {
        ThrowingCopyContainer.ParagraphContentNode first =
                new ThrowingCopyContainer.ParagraphContentNode(list(new TextContentNode("first")));
        TextContentNode visible = new TextContentNode("hello");
        List<Object> nodes = list(first, new NewLineContentNode(), visible,
                new NewLineContentNode(), new EmojiContentNode("1"));

        assertNull(ContentNodes.replaceText(nodes, "第一\n你好"));
        assertEquals("hello", visible.getContent());
    }

    @Test
    public void failedLaterContainerDiscardsEarlierSuccessfulRebuild() {
        TextContentNode first = new TextContentNode("first");
        NoCopyContainer.ParagraphContentNode last =
                new NoCopyContainer.ParagraphContentNode(list(new TextContentNode("last")));
        List<Object> nodes = list(new ParagraphContentNode(list(first)),
                new TextContentNode("\n\n"), last);

        assertNull(ContentNodes.replaceText(nodes, "第一\n\n最后"));
        assertEquals("first", first.getContent());
        assertEquals("last", ((TextContentNode) last.getContent().get(0)).getContent());
    }

    @Test
    public void failedParentCopyDiscardsRebuiltDescendants() {
        NoCopyContainer.ParagraphContentNode parent = new NoCopyContainer.ParagraphContentNode(list(
                new StrongContentNode(list(new TextContentNode("hello")))));
        assertNull(ContentNodes.replaceText(list(parent), "<x1>你好</x1>"));
    }

    @Test
    public void missingTextConstructorAbortsWholeReplacement() {
        TextContentNode first = new TextContentNode("first");
        MissingTextConstructor.TextContentNode last =
                new MissingTextConstructor.TextContentNode("last", true);

        assertNull(ContentNodes.replaceText(list(first, last), "第一\n最后"));
        assertEquals("first", first.getContent());
        assertEquals("last", last.getContent());
    }

    @Test
    public void emptyTranslationNeverErasesOriginalText() {
        TextContentNode text = new TextContentNode("hello");
        List<Object> nodes = list(text, new EmojiContentNode("1"));

        assertNull(ContentNodes.replaceText(nodes, null));
        assertNull(ContentNodes.replaceText(nodes, ""));
        assertNull(ContentNodes.replaceText(nodes, " \n\t"));
        assertEquals("hello", text.getContent());
    }

    @Test
    public void splitsAcrossTextNodesWhenTranslationHasNewlines() {
        // 各节点分别承载一个片段，保持段落结构
        List<Object> nodes = list(
                new TextContentNode("para one"),
                new EmojiContentNode("9"),
                new TextContentNode("para two"));
        List<Object> out = ContentNodes.replaceText(nodes, "第一段\n第二段");
        assertNotNull(out);
        assertEquals("第一段", ((TextContentNode) out.get(0)).getContent());
        assertEquals("第二段", ((TextContentNode) out.get(2)).getContent());
        assertSame("表情节点仍原样保留", nodes.get(1), out.get(1));
    }

    @Test
    public void preservesExtraFieldsOnContainer() {
        List<Object> nodes = list(
                new HeadingContentNode(list(new TextContentNode("Title")), 2));
        List<Object> out = ContentNodes.replaceText(nodes, "标题");
        assertNotNull(out);
        HeadingContentNode h = (HeadingContentNode) out.get(0);
        assertEquals("level 必须保留", 2, h.component2());
        assertEquals("标题", ((TextContentNode) h.getContent().get(0)).getContent());
    }

    @Test
    public void preservesLinkContextData() {
        Object ctx = new Object(); // 代表 LinkContextData
        List<Object> nodes = list(new LinkContentNode(list(new TextContentNode("text")), ctx));
        List<Object> out = ContentNodes.replaceText(nodes, "链接");
        assertNotNull(out);
        LinkContentNode l = (LinkContentNode) out.get(0);
        assertSame("LinkContextData 必须保留原引用", ctx, l.component2());
    }

    @Test
    public void urlLinkIsPreservedVerbatimAndNotTranslated() {
        // 裸 URL 消息：链接显示文本就是 URL 本身，不能交给模型翻译。
        // 模型会重新生成 URL，实测 IJYuv3wZ 被改成 lJYuv3wZ。
        Object ctx = new Object();
        List<Object> nodes = list(
                new HeadingContentNode(list(new TextContentNode(
                        "# FREE CLAUDE CODE CRACK 🔥 2026 🔥 [OPUS 5.5]")), 1),
                new NewLineContentNode(),
                new LinkContentNode(list(new TextContentNode(
                        "https://gofile.io/d/IJYuv3wZ")), ctx));
        List<Object> out = ContentNodes.replaceText(nodes,
                "# 免费 CLAUDE CODE 破解 🔥 2026 🔥 [OPUS 5.5]\n"
                        + "https://gofile.io/d/IJYuv3wZ");

        assertNotNull(out);
        HeadingContentNode heading = (HeadingContentNode) out.get(0);
        assertEquals("标题片段正常回填",
                "# 免费 CLAUDE CODE 破解 🔥 2026 🔥 [OPUS 5.5]",
                ((TextContentNode) heading.getContent().get(0)).getContent());
        assertEquals(1, heading.component2());
        assertSame("URL 链接节点必须原样复用", nodes.get(2), out.get(2));
        assertEquals("URL 必须逐字符保留（大写 I 不得变小写 l）",
                "https://gofile.io/d/IJYuv3wZ",
                ((TextContentNode) ((LinkContentNode) out.get(2)).getContent().get(0))
                        .getContent());
    }

    @Test
    public void nonUrlLinkTextWithoutBoundaryIsRejected() {
        // 掩码链接的显示文本是自然语言，仍正常翻译（模型按行返回时逐行落位）。
        List<Object> nodes = list(
                new TextContentNode("see "),
                new LinkContentNode(list(new TextContentNode("this guide")), new Object()));
        assertNull("普通文本与链接文本没有边界标记时不猜测分配",
                ContentNodes.replaceText(nodes, "见\n本指南"));
    }

    @Test
    public void urlChunkInsideTextNodeIsKeptVerbatim() {
        // 未被 Discord 链接化的裸 URL 文本块同样不翻译、不占片段，
        // 空行结构来自原节点，保持不变。
        List<Object> nodes = list(new TextContentNode(
                "hello\n\nhttps://gofile.io/d/rfh2yimO\n\nworld"));
        List<Object> out = ContentNodes.replaceText(nodes,
                "你好\n\nhttps://gofile.io/d/rfh2yimO\n\n世界");

        assertNotNull(out);
        assertEquals("URL 块逐字符保留，段落结构不变",
                "你好\n\nhttps://gofile.io/d/rfh2yimO\n\n世界",
                ((TextContentNode) out.get(0)).getContent());
    }

    @Test
    public void corruptedLongTextOutputKeepsOriginalEntirely() {
        // 长重复文本实测故障形态：内容复读 + 占位符幻觉。占位符与残留标记
        // 在引擎侧校验拦截（见 SegmentsTest）；渲染层对缺 URL/结构不符的
        // 译文同样整条拒绝，不得把损坏译文切分后塞回节点树。
        List<Object> nodes = list(
                new TextContentNode("title one"), new NewLineContentNode(),
                new LinkContentNode(list(new TextContentNode("https://gofile.io/d/kUa0UhnS")),
                        new Object()),
                new NewLineContentNode(),
                new TextContentNode("title two"));
        String corrupted = "复读 复读 🔥🔥🔥 SENSENOVA_6_8_FLASH_LITE_YYYMMDD_HHMMSS_XXXXXX\n"
                + "复读 复读";

        assertNull(ContentNodes.replaceText(nodes, corrupted));
        assertEquals("title one", ((TextContentNode) nodes.get(0)).getContent());
        assertEquals("title two", ((TextContentNode) nodes.get(4)).getContent());
    }

    @Test
    public void inlineCodeBoundaryWithoutMarkerIsRejected() {
        InlineCodeContentNode code = new InlineCodeContentNode("npm install");
        List<Object> nodes = list(
                new TextContentNode("run "),
                code,
                new TextContentNode(" now"));
        assertNull("行内代码两侧没有定位标记时不猜测译文边界",
                ContentNodes.replaceText(nodes, "运行\n现在"));
        assertSame("行内代码原样保留", nodes.get(1), code);
    }

    @Test
    public void doesNotTranslateCodeBlock() {
        CodeBlockContentNode code = new CodeBlockContentNode("int a = 1;", "java", false);
        List<Object> nodes = list(new TextContentNode("see:"), code);
        List<Object> out = ContentNodes.replaceText(nodes, "看：");
        assertNotNull(out);
        assertSame("代码块不得被翻译", code, out.get(1));
        assertEquals("java", ((CodeBlockContentNode) out.get(1)).component2());
    }

    @Test
    public void handlesBulletListItems() {
        List<Object> firstItem = list(new TextContentNode("first"));
        List<Object> secondItem = list(new TextContentNode("second"));
        List<Object> nodes = list(new BulletListContentNode(
                3L, false, itemLists(firstItem, secondItem)));
        List<Object> out = ContentNodes.replaceText(nodes, "第一\n第二");
        assertNotNull(out);
        BulletListContentNode b = (BulletListContentNode) out.get(0);
        assertEquals("start 必须保留", Long.valueOf(3L), b.component1());
        assertFalse("ordered 必须保留", b.component2());
        assertEquals(2, b.getItems().size());
        assertTrue("每个列表项必须仍是 List", b.getItems().get(0) instanceof List);
        assertEquals("第一", ((TextContentNode) b.getItems().get(0).get(0)).getContent());
        assertEquals("第二", ((TextContentNode) b.getItems().get(1).get(0)).getContent());
    }

    @Test
    public void rebuildsNestedBulletListsWithoutFlatteningItems() {
        TextContentNode firstText = new TextContentNode("first");
        InlineCodeContentNode code = new InlineCodeContentNode("keep-code");
        UserMentionContentNode mention = new UserMentionContentNode("42");
        TextContentNode styledText = new TextContentNode("styled");
        StrongContentNode strong = new StrongContentNode(list(styledText));
        TextContentNode nestedText = new TextContentNode("nested");
        List<Object> nestedItem = list(nestedText);
        List<List<Object>> nestedItems = itemLists(nestedItem);
        BulletListContentNode nested = new BulletListContentNode(2L, true, nestedItems);
        List<Object> firstItem = list(firstText, code, mention, strong, nested);
        TextContentNode secondText = new TextContentNode("second");
        List<Object> secondItem = list(secondText);
        List<List<Object>> originalItems = itemLists(firstItem, secondItem);
        BulletListContentNode original = new BulletListContentNode(7L, false, originalItems);
        List<Object> nodes = list(original);

        List<Object> out = ContentNodes.replaceText(nodes,
                "first translated\n<x1>styled translated</x1>\nnested translated\nsecond translated");

        assertNotNull(out);
        BulletListContentNode rebuilt = (BulletListContentNode) out.get(0);
        assertEquals(Long.valueOf(7L), rebuilt.component1());
        assertFalse(rebuilt.component2());
        assertEquals(2, rebuilt.getItems().size());
        assertTrue("外层列表项仍为 List", rebuilt.getItems().get(0) instanceof List);
        List<Object> rebuiltFirstItem = rebuilt.getItems().get(0);
        assertEquals(5, rebuiltFirstItem.size());
        assertEquals("first translated", ((TextContentNode) rebuiltFirstItem.get(0)).getContent());
        assertSame("代码节点必须保留", code, rebuiltFirstItem.get(1));
        assertSame("提及节点必须保留", mention, rebuiltFirstItem.get(2));
        StrongContentNode rebuiltStrong = (StrongContentNode) rebuiltFirstItem.get(3);
        assertEquals("styled translated",
                ((TextContentNode) rebuiltStrong.getContent().get(0)).getContent());

        BulletListContentNode rebuiltNested = (BulletListContentNode) rebuiltFirstItem.get(4);
        assertEquals(Long.valueOf(2L), rebuiltNested.component1());
        assertTrue(rebuiltNested.component2());
        assertEquals(1, rebuiltNested.getItems().size());
        assertTrue("嵌套列表项仍为 List", rebuiltNested.getItems().get(0) instanceof List);
        assertEquals("nested translated",
                ((TextContentNode) rebuiltNested.getItems().get(0).get(0)).getContent());
        assertEquals("second translated", ((TextContentNode)
                rebuilt.getItems().get(1).get(0)).getContent());

        // 重建不得就地修改传入对象或把任一层 item list 摊平。
        assertSame(originalItems, original.getItems());
        assertSame(firstItem, original.getItems().get(0));
        assertEquals("first", firstText.getContent());
        assertSame(nestedItems, nested.getItems());
        assertEquals("nested", nestedText.getContent());
    }

    @Test
    public void extractsEnglishTextFromMessageContainingOnlyBulletLists() {
        BulletListContentNode bullets = new BulletListContentNode(null, false,
                itemLists(list(new TextContentNode("Read the guide")),
                        list(new TextContentNode("Follow the examples"))));

        TextExtractor.Result result = TextExtractor.extract(
                new AnnotatedStructurableText(list(bullets)));

        assertNotNull(result);
        assertEquals("\n• Read the guide\n• Follow the examples", result.text);
        assertEquals(2, result.textNodeCount);
        assertEquals(0, result.opaqueNodeCount);
        assertTrue(EnglishDetector.evaluate(result.text, result.textNodeCount,
                result.opaqueNodeCount, result.codeHeavy).shouldTranslate);
    }

    @Test
    public void extractsNestedBulletListTextWithoutCountingItemListsAsOpaque() {
        BulletListContentNode nested = new BulletListContentNode(null, true,
                itemLists(list(new TextContentNode("Nested instructions"))));
        BulletListContentNode bullets = new BulletListContentNode(null, false,
                itemLists(list(new TextContentNode("Read the guide"), nested),
                        list(new TextContentNode("Follow the examples"))));

        TextExtractor.Result result = TextExtractor.extract(
                new AnnotatedStructurableText(list(bullets)));

        assertNotNull(result);
        assertEquals("\n• Read the guide\n• Nested instructions\n• Follow the examples",
                result.text);
        assertEquals(3, result.textNodeCount);
        assertEquals(0, result.opaqueNodeCount);
        assertTrue(EnglishDetector.evaluate(result.text, result.textNodeCount,
                result.opaqueNodeCount, result.codeHeavy).shouldTranslate);
    }

    @Test
    public void inlineCodeIsNotSentToTheModel() {
        List<Object> nodes = list(
                new TextContentNode("run "),
                new InlineCodeContentNode("npm install"),
                new TextContentNode(" now"));

        TextExtractor.Result result = TextExtractor.extract(
                new AnnotatedStructurableText(nodes));

        assertNotNull(result);
        // 代码节点会被原样保留在节点树里；若把它也发给模型，
        // 模型会把代码复述进译文，导致同一段代码显示两次。
        assertFalse("inline code must not be sent verbatim",
                result.text.contains("npm install"));
        assertTrue("surrounding text must still be sent", result.text.contains("run"));
    }

    @Test
    public void returnsNullWhenNoTextNodes() {
        // 只有表情：无自然语言，不应产生替换
        List<Object> nodes = list(new EmojiContentNode("1"), new EmojiContentNode("2"));
        assertNull(ContentNodes.replaceText(nodes, "译文"));
    }

    @Test
    public void returnsNullForNullInput() {
        assertNull(ContentNodes.replaceText(null, "译文"));
    }

    @Test
    public void doesNotMutateOriginalList() {
        TextContentNode original = new TextContentNode("Hello");
        List<Object> nodes = list(original);
        List<Object> out = ContentNodes.replaceText(nodes, "你好");
        assertNotNull(out);
        assertEquals("原始列表不得被修改", "Hello", original.getContent());
        assertSame("原始节点不得被修改", original, nodes.get(0));
    }

    // ---------------------------------------------------------------- 逐节点回填与结构保留

    @Test
    public void perNodeBackfillKeepsOneLinePerTextNode() {
        // 原文两行（hi / hi）各自一个文本节点，中间是换行节点。
        // 显式换行节点定义唯一的逐行映射；原节点换行结构保留。
        List<Object> nodes = list(new TextContentNode("hi"), new NewLineContentNode(),
                new TextContentNode("hi"));

        List<Object> out = ContentNodes.replaceText(nodes, "你好\n你好");

        assertNotNull(out);
        assertEquals("两个文本节点之间的换行必须保留", 3, out.size());
        assertEquals("你好", ((TextContentNode) out.get(0)).getContent());
        assertTrue(out.get(1) instanceof NewLineContentNode);
        assertEquals("你好", ((TextContentNode) out.get(2)).getContent());
    }

    @Test
    public void styleMarkersKeepStrongContainerAndItsText() {
        // 原文 hello **world** foo：加粗是行内样式容器，请求里包 <x1> 标记，
        // 模型保留标记时译文按标记精确落回样式节点。
        TextContentNode world = new TextContentNode("world");
        StrongContentNode strong = new StrongContentNode(list(world));
        List<Object> nodes = list(new TextContentNode("hello "), strong,
                new TextContentNode(" foo"));

        List<Object> out = ContentNodes.replaceText(nodes,
                "你好 <x1>世界</x1> 呀");

        assertNotNull(out);
        assertEquals(3, out.size());
        // 节点首尾空格属于原节点结构，重建时从原节点取，因此仍为 "你好 "。
        assertEquals("你好 ", ((TextContentNode) out.get(0)).getContent());
        assertTrue("加粗容器必须保留", out.get(1) instanceof StrongContentNode);
        StrongContentNode rebuilt = (StrongContentNode) out.get(1);
        assertEquals("世界", ((TextContentNode) rebuilt.getContent().get(0)).getContent());
        assertEquals(" 呀", ((TextContentNode) out.get(2)).getContent());
    }

    @Test
    public void singleSegmentPreservesHeadingLevel() {
        List<Object> nodes = list(new HeadingContentNode(
                list(new TextContentNode("Title")), 2));

        List<Object> out = ContentNodes.replaceText(nodes, "标题");

        assertNotNull(out);
        HeadingContentNode h = (HeadingContentNode) out.get(0);
        assertEquals("level 必须保留", 2, h.component2());
        assertEquals("标题", ((TextContentNode) h.getContent().get(0)).getContent());
    }

    @Test
    public void mismatchedStructureKeepsOriginalText() {
        // 两个片段只构成一个段落，译文却有 3 个段落块 = 模型新增/复读内容：
        // 结构对不上，整条保留原文，绝不把多余内容塞进节点树。
        List<Object> nodes = list(new TextContentNode("hi"), new NewLineContentNode(),
                new TextContentNode("hi"));

        assertNull(ContentNodes.replaceText(nodes, "甲\n\n乙\n\n丙"));

        // 原节点树原样保留。
        assertEquals("hi", ((TextContentNode) nodes.get(0)).getContent());
        assertEquals("hi", ((TextContentNode) nodes.get(2)).getContent());
    }

    @Test
    public void markerlessMergedTranslationIsRejectedForExplicitLines() {
        // 显式换行节点定义逐行映射；只返回一行时整条拒绝。
        List<Object> nodes = list(new TextContentNode("hi"), new NewLineContentNode(),
                new TextContentNode("hi"));

        assertNull("少于源结构的行数时拒绝回填",
                ContentNodes.replaceText(nodes, "只有一段译文"));
        assertEquals("hi", ((TextContentNode) nodes.get(0)).getContent());
        assertEquals("hi", ((TextContentNode) nodes.get(2)).getContent());
    }

    @Test
    public void plainTranslationWithoutMarkersStillWorks() {
        // 未启用标记（单节点消息）或缓存命中旧译文时，必须是原来的整段回填行为。
        List<Object> nodes = list(new TextContentNode("hello"));

        List<Object> out = ContentNodes.replaceText(nodes, "你好");

        assertNotNull(out);
        assertEquals(1, out.size());
        assertEquals("你好", ((TextContentNode) out.get(0)).getContent());
    }

    // ---------------------------------------------------------------- 空行与段落

    @Test
    public void blankLineNodeIsNotTranslatedAndStaysAsIs() {
        // 真机实测的节点树形态：段落之间的分隔就是内容为 "\n\n" 的 TextContentNode。
        // 它不参与翻译，必须原样保留，否则译文里的空行会消失。
        List<Object> nodes = list(new TextContentNode("hello"),
                new TextContentNode("\n\n"),
                new TextContentNode("world"));

        List<Object> out = ContentNodes.replaceText(nodes,
                "你好\n\n世界");

        assertNotNull(out);
        assertEquals(3, out.size());
        assertEquals("你好", ((TextContentNode) out.get(0)).getContent());
        assertEquals("空行节点必须原样保留",
                "\n\n", ((TextContentNode) out.get(1)).getContent());
        assertEquals("世界", ((TextContentNode) out.get(2)).getContent());
    }

    @Test
    public void blankLineInsideOneTextNodeIsPreserved() {
        // 一条纯文本消息 "hello\n\nworld" 整体是一个节点，但有两个段落。
        // 空行必须留在标记外，重建时按原位置拼回。
        List<Object> nodes = list(new TextContentNode("hello\n\nworld"));

        List<Object> out = ContentNodes.replaceText(nodes, "你好\n\n世界");

        assertNotNull(out);
        assertEquals(1, out.size());
        assertEquals("段落结构必须保留",
                "你好\n\n世界", ((TextContentNode) out.get(0)).getContent());
    }

    @Test
    public void modelExtraWhitespaceAroundParagraphsStillKeepsStructure() {
        // 模型在译文段落前后多打空白（实测常见）也不影响结构：
        // 分隔符来自原节点，不是模型输出。
        List<Object> nodes = list(new TextContentNode("hello\n\nworld"));

        List<Object> out = ContentNodes.replaceText(nodes, "  你好 \n\n 世界  ");

        assertNotNull(out);
        assertEquals("你好\n\n世界", ((TextContentNode) out.get(0)).getContent());
    }

    @Test
    public void singleNewlineInsideNodeIsTranslatedAsOnePiece() {
        // 单个换行不是段落分隔，不得被当作切分点。
        List<Object> nodes = list(new TextContentNode("first\nsecond"));

        List<Object> out = ContentNodes.replaceText(nodes, "第一\n第二");

        assertNotNull(out);
        assertEquals("第一\n第二", ((TextContentNode) out.get(0)).getContent());
    }

    @Test
    public void leadingAndTrailingWhitespaceOfNodeIsPreserved() {
        // 节点首尾的空白属于结构（缩进/段落间距），不交给模型，也不会被译文吞掉。
        List<Object> nodes = list(new TextContentNode("  hello  "));

        List<Object> out = ContentNodes.replaceText(nodes, "你好");

        assertNotNull(out);
        assertEquals("  你好  ", ((TextContentNode) out.get(0)).getContent());
    }

    @Test
    public void describesNodeList() {
        String d = ContentNodes.describe(list(new TextContentNode("a"), new EmojiContentNode("b")));
        assertEquals("[TextContentNode, EmojiContentNode]", d);
        assertEquals("<null>", ContentNodes.describe(null));
    }

    // ---------------------------------------------------------------- 译文切分

    @Test
    public void splitSinglePartIsUnchanged() {
        assertEquals(Arrays.asList("αβγ"),
                ContentNodes.alignSpanToParts("αβγ", groups(0), null));
    }

    private static List<Integer> groups(int... ids) {
        List<Integer> out = new ArrayList<>(ids.length);
        for (int id : ids) {
            out.add(id);
        }
        return out;
    }

    @Test
    public void alignSpanByLinesWhenLineCountMatches() {
        // 相邻单行片段、模型用单换行分段：非空行数与片段数一致 → 按行 1:1。
        List<String> parts = ContentNodes.alignSpanToParts("第一行\n第二行",
                groups(0, 0), null, Arrays.asList(false, true));
        assertEquals(2, parts.size());
        assertEquals("第一行", parts.get(0));
        assertEquals("第二行", parts.get(1));
    }

    @Test
    public void alignSpanRejectsExtraLinesWhenStructureMismatches() {
        // 行数多于片段数且段落也对不上：必须拒绝，保留原文。
        assertNull(ContentNodes.alignSpanToParts("a\nb\nc\nd", groups(0, 1), null));
    }

    @Test
    public void alignSpanRejectsMergedTextForRepeatedGroup() {
        // 同段有多个译文片段但没有边界标记时，不把整段猜测性地塞进首片段。
        assertNull(ContentNodes.alignSpanToParts("只有一行", groups(0, 0, 0), null));
    }

    @Test
    public void alignSpanRejectsMergedTextAcrossRepeatedGroup() {
        // 同一段落组有多个片段时，缺少标记不能按段落将译文塞到第一个片段。
        assertNull(ContentNodes.alignSpanToParts("译一\n\n译二", groups(0, 0, 1), null));
    }

    @Test
    public void alignSpanRejectsAmbiguousTextForRepeatedGroup() {
        // 夹有未知可见结构的同段片段没有精确边界时拒绝回填。
        assertNull(ContentNodes.alignSpanToParts("整段一块", groups(0, 0), null));
    }

    @Test
    public void alignSpanRejectsBlankLineWhenLineCountMatches() {
        // 三个独立片段只回两块：跨段落合并 = 内容丢失，必须拒绝。
        assertNull(ContentNodes.alignSpanToParts("甲\n\n乙", groups(0, 1, 2), null));
    }

    @Test
    public void alignSpanByParagraphBlocksWhenCountsMatch() {
        // 段落块数与片段数一致：每块整体落到一个片段（块内单换行保留）。
        List<String> parts = ContentNodes.alignSpanToParts(
                "第一块\n第二行\n\n第二块", groups(0, 1), null);
        assertEquals(2, parts.size());
        assertEquals("第一块\n第二行", parts.get(0));
        assertEquals("第二块", parts.get(1));
    }

    @Test
    public void alignSpanRejectsWhenMoreParagraphBlocksThanTargets() {
        // 译文段落块多于片段数 = 模型新增/复读内容：拒绝回填（null），
        // 不得把多出的块堆进最后一个节点。
        assertNull(ContentNodes.alignSpanToParts("A\n\nB\n\nC", groups(0, 1), null));
    }

    @Test
    public void styleMarkerMissingRejectsBackfill() {
        // 同段内联片段必须由样式标记逐项定位；缺失标记时拒绝回填。
        List<Object> nodes = list(
                new StrongContentNode(list(new TextContentNode("bold text"))),
                new TextContentNode(" and plain"));

        assertNull("缺少样式标记时不猜测内联片段边界",
                ContentNodes.replaceText(nodes, "加粗文本 和纯文本"));
    }

    @Test
    public void multiParagraphFormattedPlacesEachParagraphInPlace() {
        // 两段消息：第一段由加粗片段+普通片段内联组成，第二段独立。
        // 模型按段落返回两块：第一块进第一段首片段（加粗），第二块进
        // 第二段。段落各就各位，不跨段堆叠。
        List<Object> nodes = list(
                new StrongContentNode(list(new TextContentNode("bold"))),
                new TextContentNode(" tail"),
                new TextContentNode("\n\n"),
                new TextContentNode("second para"));

        List<Object> out = ContentNodes.replaceText(nodes,
                "<x1>加粗</x1> 尾巴\n\n第二段");

        assertNotNull(out);
        assertEquals(4, out.size());
        StrongContentNode strong = (StrongContentNode) out.get(0);
        assertEquals("加粗", ((TextContentNode) strong.getContent().get(0)).getContent());
        assertEquals("段内普通片段精确回填", " 尾巴",
                ((TextContentNode) out.get(1)).getContent());
        assertEquals("空行结构保留", "\n\n", ((TextContentNode) out.get(2)).getContent());
        assertEquals("第二段", ((TextContentNode) out.get(3)).getContent());
    }

    // ---------------------------------------------------------------- 行数与尾部空白

    @Test
    public void mergedOneLineTranslationIsRejectedForMultipleSegments() {
        // 两个原节点只回一行译文：结构数量不匹配，必须保留原文。
        List<Object> nodes = list(new TextContentNode("first line"), new NewLineContentNode(),
                new TextContentNode("second line"));

        assertNull("缺少逐段译文时整条拒绝",
                ContentNodes.replaceText(nodes, "只有一行译文"));
        assertEquals("原节点保持不变", "first line",
                ((TextContentNode) nodes.get(0)).getContent());
    }

    @Test
    public void trailingBlankStructureIsPreservedWhenTranslationIsApplied() {
        for (Object blank : new Object[]{new NewLineContentNode(), new LineBreakContentNode(),
                new TextContentNode(""), new TextContentNode("   ")}) {
            List<Object> nodes = list(new TextContentNode("hello world"), blank);

            List<Object> out = ContentNodes.replaceText(nodes, "你好世界");

            assertNotNull(out);
            assertEquals("原尾部结构不做可选裁剪", 2, out.size());
            assertEquals("你好世界", ((TextContentNode) out.get(0)).getContent());
        }
    }

    @Test
    public void translationTrailingNewlinesAndCarriageReturnsAreNormalized() {
        assertEquals("你好世界", ContentNodes.normalize("你好世界\r\n"));
        assertEquals("你好\n世界", ContentNodes.normalize("你好\r\n世界\n\n  "));
        assertEquals("你好\n世界", ContentNodes.normalize("你好\r世界"));
        assertEquals("", ContentNodes.normalize("\r\n \t"));

        List<Object> nodes = list(new TextContentNode("hello world"));
        List<Object> out = ContentNodes.replaceText(nodes, "你好世界\r\n\n");

        assertNotNull(out);
        assertEquals(1, out.size());
        assertEquals("你好世界", ((TextContentNode) out.get(0)).getContent());
    }

    @Test
    public void trailingParagraphWithBlankContentIsPreserved() {
        ParagraphContentNode emptyTail = new ParagraphContentNode(
                list(new TextContentNode("")));
        List<Object> nodes = list(new TextContentNode("hello world"), emptyTail);

        List<Object> out = ContentNodes.replaceText(nodes, "你好世界");

        assertNotNull(out);
        assertEquals(2, out.size());
        assertEquals("你好世界", ((TextContentNode) out.get(0)).getContent());
        assertSame(emptyTail, out.get(1));
    }

    @Test
    public void visibleTailContentIsNeverTrimmed() {
        EmojiContentNode emoji = new EmojiContentNode("1");
        TextContentNode tail = new TextContentNode("bye");
        List<Object> nodes = list(new TextContentNode("hello"), emoji,
                new NewLineContentNode(), tail);

        List<Object> out = ContentNodes.replaceText(nodes, "你好\n再见");

        assertNotNull(out);
        assertEquals(4, out.size());
        assertSame(emoji, out.get(1));
        assertEquals("再见", ((TextContentNode) out.get(3)).getContent());
    }

    @Test
    public void bulletListItemTrailingBreakIsPreserved() {
        BulletListContentNode bullets = new BulletListContentNode(null, false,
                new ArrayList<>(Arrays.asList(
                        list(new TextContentNode("item one"), new NewLineContentNode()))));
        List<Object> nodes = list(bullets);

        List<Object> out = ContentNodes.replaceText(nodes, "第一项");

        assertNotNull(out);
        BulletListContentNode rebuilt = (BulletListContentNode) out.get(0);
        assertEquals("项目不能被删除", 1, rebuilt.getItems().size());
        assertEquals(2, rebuilt.getItems().get(0).size());
        assertEquals("第一项",
                ((TextContentNode) rebuilt.getItems().get(0).get(0)).getContent());
        assertTrue(rebuilt.getItems().get(0).get(1) instanceof NewLineContentNode);
    }

    @Test
    public void unchangedOpaqueTailDoesNotBlockTextReplacement() {
        // 尾部结构保持原样；重建只执行一次，不尝试裁剪副本。
        EmojiContentNode emoji = new EmojiContentNode("1");
        NewLineContentNode blank = new NewLineContentNode();
        NoCopyContainer.ParagraphContentNode tail =
                new NoCopyContainer.ParagraphContentNode(list(emoji, blank));
        List<Object> nodes = list(new TextContentNode("hello world"), tail);

        List<Object> out = ContentNodes.replaceText(nodes, "你好世界");

        assertNotNull("尾部结构保留，不影响已经完成的文本替换", out);
        assertEquals("你好世界", ((TextContentNode) out.get(0)).getContent());
        assertSame("无法复制的容器保持原样", tail, out.get(1));
        assertEquals(2, tail.getContent().size());
        assertSame(emoji, tail.getContent().get(0));
    }
}
