package com.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

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

    /** Bullet list：内容字段是 items。 */
    public static final class BulletListContentNode {
        private final Long start;
        private final boolean ordered;
        private final List<Object> items;

        public BulletListContentNode(Long start, boolean ordered, List<Object> items) {
            this.start = start;
            this.ordered = ordered;
            this.items = items;
        }

        public List<Object> getItems() {
            return items;
        }

        public Long component1() {
            return start;
        }

        public boolean component2() {
            return ordered;
        }

        public List<Object> component3() {
            return items;
        }

        public BulletListContentNode copy(Long start, boolean ordered, List<Object> items) {
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

    private static List<Object> list(Object... items) {
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
        List<Object> items = list(
                new TextContentNode("first"),
                new TextContentNode("second"));
        List<Object> nodes = list(new BulletListContentNode(null, false, items));
        List<Object> out = ContentNodes.replaceText(nodes, "第一\n第二");
        assertNotNull(out);
        BulletListContentNode b = (BulletListContentNode) out.get(0);
        assertFalse("ordered 必须保留", b.component2());
        assertEquals(2, b.getItems().size());
        assertEquals("第一", ((TextContentNode) b.getItems().get(0)).getContent());
        assertEquals("第二", ((TextContentNode) b.getItems().get(1)).getContent());
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

    // ---------------------------------------------------------------- 工厂

    @Test
    public void factoryRejectsUnknownTypes() {
        // 渲染器只接受 Primitive/Annotated，其他类型必须拒绝替换
        assertFalse(StructurableTextFactory.isRenderableType(new EmojiContentNode("x")));
        assertFalse(StructurableTextFactory.isRenderableType(null));
    }

    @Test
    public void factoryAcceptsRenderableTypes() {
        assertTrue(StructurableTextFactory.isRenderableType(new PrimitiveStructurableText("x")));
        assertTrue(StructurableTextFactory.isRenderableType(
                new AnnotatedStructurableText(list())));
    }
}
