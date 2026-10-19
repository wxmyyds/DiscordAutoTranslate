package com.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.wxmyyds.discordautotranslate.core.EnglishDetector;
import com.wxmyyds.discordautotranslate.core.TextExtractor;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link ContentNodes} 的节点树重建逻辑（需求 §7 阶段 3：保留 Markdown）。
 *
 * <p>测试策略：Discord 的真实节点类在单测环境不存在，但 {@link ContentNodes}
 * 完全通过反射（{@code getContent}/{@code getItems}/{@code componentN}/{@code copy}/
 * {@code <init>(String)}）操作节点。因此这里定义<b>同名</b>的假节点类来模拟
 * Kotlin data class 的结构，从而真正执行被测的反射代码路径，而不是只测表面。</p>
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

    /** 模拟宿主版本变化：有可读子节点，但没有兼容的 copy 方法。 */
    public static final class NoCopyContainer {
        private final List<Object> content;

        public NoCopyContainer(List<Object> content) {
            this.content = content;
        }

        public List<Object> getContent() {
            return content;
        }
    }

    /** 模拟 copy 调用失败，而不是只有缺少方法的情况。 */
    public static final class ThrowingCopyContainer {
        private final List<Object> content;

        public ThrowingCopyContainer(List<Object> content) {
            this.content = content;
        }

        public List<Object> getContent() {
            return content;
        }

        public ThrowingCopyContainer copy(List<Object> content) {
            throw new IllegalStateException("simulated copy failure");
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
        // Paragraph > [Text, Emoji, Text]
        List<Object> inner = list(
                new TextContentNode("Hello "),
                new EmojiContentNode("123"),
                new TextContentNode("world"));
        List<Object> nodes = list(new ParagraphContentNode(inner));

        List<Object> out = ContentNodes.replaceText(nodes, "你好世界");

        assertNotNull(out);
        assertEquals(1, out.size());
        ParagraphContentNode para = (ParagraphContentNode) out.get(0);

        // 结构必须保留：仍然是 3 个子节点，且表情节点原样复用（同一实例）
        assertEquals("段落结构必须保留", 3, para.getContent().size());
        assertSame("结构化节点必须原样复用", inner.get(1), para.getContent().get(1));
        assertTrue(para.getContent().get(1) instanceof EmojiContentNode);

        // 不变量：替换后所有文本节点的拼接应等于译文。
        // （译文无换行时不会为了凑节点数而把句子硬切碎，因此首节点承载全部、
        //   其余为空串；这是刻意设计，避免产生读不通的断句。）
        StringBuilder joined = new StringBuilder();
        for (Object o : para.getContent()) {
            if (o instanceof TextContentNode) {
                joined.append(((TextContentNode) o).getContent());
            }
        }
        assertEquals("译文内容必须完整保留", "你好世界", joined.toString());
    }

    @Test
    public void sharedTextInstanceDoesNotLoseTranslationToLaterEmptyPart() {
        TextContentNode shared = new TextContentNode("hello");
        EmojiContentNode emoji = new EmojiContentNode("1");
        List<Object> nodes = list(shared, emoji, shared);

        List<Object> out = ContentNodes.replaceText(nodes, "你好");

        assertNotNull(out);
        assertEquals("你好", ((TextContentNode) out.get(0)).getContent());
        assertEquals("", ((TextContentNode) out.get(2)).getContent());
        assertSame(emoji, out.get(1));
        assertEquals("hello", shared.getContent());
        assertSame(shared, nodes.get(0));
        assertSame(shared, nodes.get(2));
    }

    @Test
    public void sharedTextInstanceReceivesSeparateMultilineParts() {
        TextContentNode shared = new TextContentNode("hello");
        List<Object> out = ContentNodes.replaceText(list(shared, shared), "第一\n第二");

        assertNotNull(out);
        assertEquals("第一", ((TextContentNode) out.get(0)).getContent());
        assertEquals("第二", ((TextContentNode) out.get(1)).getContent());
    }

    @Test
    public void sharedContainerIsRebuiltSeparatelyAtEachPosition() {
        TextContentNode text = new TextContentNode("hello");
        ParagraphContentNode shared = new ParagraphContentNode(list(text));
        List<Object> out = ContentNodes.replaceText(list(shared, shared), "你好");

        assertNotNull(out);
        ParagraphContentNode first = (ParagraphContentNode) out.get(0);
        ParagraphContentNode second = (ParagraphContentNode) out.get(1);
        assertEquals("你好", ((TextContentNode) first.getContent().get(0)).getContent());
        assertEquals("", ((TextContentNode) second.getContent().get(0)).getContent());
        assertSame(text, shared.getContent().get(0));
        assertEquals("hello", text.getContent());
    }

    @Test
    public void failedFirstContainerDoesNotClearRemainingTextAndLeaveOnlyEmoji() {
        TextContentNode spacer = new TextContentNode("");
        NoCopyContainer container = new NoCopyContainer(list(spacer));
        TextContentNode visible = new TextContentNode("hello world");
        EmojiContentNode emoji = new EmojiContentNode("1");
        List<Object> nodes = list(container, visible, emoji);

        // 旧实现丢弃首片段的译文却把 visible 变为空串，只剩 emoji。
        assertNull(ContentNodes.replaceText(nodes, "你好世界"));
        assertEquals("hello world", visible.getContent());
        assertSame(container, nodes.get(0));
        assertSame(visible, nodes.get(1));
        assertSame(emoji, nodes.get(2));
        assertSame(spacer, container.getContent().get(0));
    }

    @Test
    public void throwingCopyAbortsWholeReplacement() {
        ThrowingCopyContainer first = new ThrowingCopyContainer(list(new TextContentNode("")));
        TextContentNode visible = new TextContentNode("hello");
        List<Object> nodes = list(first, visible, new EmojiContentNode("1"));

        assertNull(ContentNodes.replaceText(nodes, "你好"));
        assertEquals("hello", visible.getContent());
    }

    @Test
    public void failedLaterContainerDiscardsEarlierSuccessfulRebuild() {
        TextContentNode first = new TextContentNode("first");
        NoCopyContainer last = new NoCopyContainer(list(new TextContentNode("last")));
        List<Object> nodes = list(new ParagraphContentNode(list(first)), last);

        assertNull(ContentNodes.replaceText(nodes, "第一\n最后"));
        assertEquals("first", first.getContent());
        assertEquals("last", ((TextContentNode) last.getContent().get(0)).getContent());
    }

    @Test
    public void failedParentCopyDiscardsRebuiltDescendants() {
        NoCopyContainer parent = new NoCopyContainer(list(
                new StrongContentNode(list(new TextContentNode("hello")))));
        assertNull(ContentNodes.replaceText(list(parent), "你好"));
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
        // 译文含换行且行数匹配文本节点数时，各节点分别承载一段，保持段落结构
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
    public void doesNotTranslateInlineCode() {
        List<Object> nodes = list(
                new TextContentNode("run "),
                new InlineCodeContentNode("npm install"),
                new TextContentNode(" now"));
        List<Object> out = ContentNodes.replaceText(nodes, "运行 npm install 现在");
        assertNotNull(out);
        // 行内代码节点原样保留
        assertSame("行内代码不得被翻译",
                nodes.get(1), out.get(1));
        assertEquals("npm install", ((InlineCodeContentNode) out.get(1)).getContent());
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
                "first translated\nstyled translated\nnested translated\nsecond translated");

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

    @Test
    public void describesNodeList() {
        String d = ContentNodes.describe(list(new TextContentNode("a"), new EmojiContentNode("b")));
        assertEquals("[TextContentNode, EmojiContentNode]", d);
        assertEquals("<null>", ContentNodes.describe(null));
    }

    // ---------------------------------------------------------------- 译文切分

    @Test
    public void splitSinglePartIsUnchanged() {
        assertEquals(Arrays.asList("αβγ"), ContentNodes.splitTranslation("αβγ", 1));
    }

    @Test
    public void splitByLinesWhenEnoughLines() {
        List<String> parts = ContentNodes.splitTranslation("第一行\n第二行", 2);
        assertEquals(2, parts.size());
        assertEquals("第一行", parts.get(0));
        assertEquals("第二行", parts.get(1));
    }

    @Test
    public void splitMergesExtraLinesIntoLastPart() {
        List<String> parts = ContentNodes.splitTranslation("a\nb\nc\nd", 2);
        assertEquals(2, parts.size());
        assertEquals("a", parts.get(0));
        assertEquals("b\nc\nd", parts.get(1));
    }

    @Test
    public void splitPadsWhenTooFewLines() {
        // 行数不足时不得把句子硬切碎：首段承载全部，其余留空
        List<String> parts = ContentNodes.splitTranslation("只有一行", 3);
        assertEquals(3, parts.size());
        assertEquals("只有一行", parts.get(0));
        assertEquals("", parts.get(1));
        assertEquals("", parts.get(2));
    }
}
