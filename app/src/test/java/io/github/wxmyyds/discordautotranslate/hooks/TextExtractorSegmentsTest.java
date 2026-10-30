package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.wxmyyds.discordautotranslate.core.TextExtractor;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link TextExtractor} 产出的「纯文本请求正文」与「可翻译片段计数」。
 *
 * <p>约束：纯文本必须保持既有形态（语言检测、内容哈希与单链路请求都
 * 依赖它）；URL 定位标记与样式标记只出现在纯文本里。片段计数必须与
 * 回填消费口径（{@code ContentNodes.countTextNodes}/rebuild 的
 * countSegments）严格一致，否则片段会串位。</p>
 */
public class TextExtractorSegmentsTest {

    // ---------------------------------------------------------------- 假节点模型

    public static final class TextContentNode {
        private final String content;

        public TextContentNode(String content) {
            this.content = content;
        }

        public String getContent() {
            return content;
        }
    }

    public static final class NewLineContentNode {
    }

    public static final class EmojiContentNode {
        private final String id;

        public EmojiContentNode(String id) {
            this.id = id;
        }
    }

    public static final class ParagraphContentNode {
        private final List<Object> content;

        public ParagraphContentNode(List<Object> content) {
            this.content = content;
        }

        public List<Object> getContent() {
            return content;
        }
    }

    public static final class StrongContentNode {
        private final List<Object> content;

        public StrongContentNode(List<Object> content) {
            this.content = content;
        }

        public List<Object> getContent() {
            return content;
        }
    }

    public static final class InlineCodeContentNode {
        private final String content;

        public InlineCodeContentNode(String content) {
            this.content = content;
        }

        public String getContent() {
            return content;
        }
    }

    public static final class LinkContentNode {
        private final List<Object> content;

        public LinkContentNode(List<Object> content) {
            this.content = content;
        }

        public List<Object> getContent() {
            return content;
        }
    }

    public static final class BulletListContentNode {
        private final List<List<Object>> items;

        public BulletListContentNode(List<List<Object>> items) {
            this.items = items;
        }

        public List<List<Object>> getItems() {
            return items;
        }
    }

    public static final class AnnotatedStructurableText {
        private final List<Object> content;

        public AnnotatedStructurableText(List<Object> content) {
            this.content = content;
        }

        public List<Object> getContent() {
            return content;
        }
    }

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

    // ---------------------------------------------------------------- 测试

    @Test
    public void singleTextNodeCountsOneSegment() {
        TextExtractor.Result r = TextExtractor.extract(
                new AnnotatedStructurableText(list(new TextContentNode("hello world"))));

        assertNotNull(r);
        assertEquals("hello world", r.text);
        assertEquals("单节点消息是一个片段", 1, r.segmentCount);
    }

    @Test
    public void twoTextNodesCountTwoSegments() {
        TextExtractor.Result r = TextExtractor.extract(new AnnotatedStructurableText(
                list(new TextContentNode("hi"), new NewLineContentNode(),
                        new TextContentNode("hi"))));

        assertNotNull(r);
        assertEquals("纯文本形态不变", "hi\nhi", r.text);
        assertEquals(2, r.segmentCount);
    }

    @Test
    public void styledContainerTextKeepsStyleMarkersInPlainRequest() {
        TextExtractor.Result r = TextExtractor.extract(new AnnotatedStructurableText(
                list(new TextContentNode("hello "),
                        new StrongContentNode(list(new TextContentNode("world"))),
                        new TextContentNode(" foo"))));

        assertNotNull(r);
        // 纯文本请求里样式片段包样式标记 <xN>…</xN>（模型按规则 7 原样保留，
        // 回填按标记把译文精确落回样式节点）。
        assertEquals("hello <x1>world</x1> foo", r.text);
        assertEquals(3, r.segmentCount);
    }

    @Test
    public void codeAndOpaqueNodesStayOutOfRequest() {
        TextExtractor.Result r = TextExtractor.extract(new AnnotatedStructurableText(
                list(new TextContentNode("run"),
                        new InlineCodeContentNode("npm install"),
                        new EmojiContentNode("1"),
                        new TextContentNode("now"))));

        assertNotNull(r);
        assertFalse("代码不得进入请求正文", r.text.contains("npm install"));
        assertTrue("两侧文本必须仍在请求里", r.text.contains("run"));
        assertEquals("代码与 emoji 不占片段", 2, r.segmentCount);
    }

    @Test
    public void bulletListItemsCountPerTextNode() {
        List<List<Object>> items = new ArrayList<>();
        items.add(list(new TextContentNode("Read the guide")));
        items.add(list(new TextContentNode("Follow the examples")));
        TextExtractor.Result r = TextExtractor.extract(
                new AnnotatedStructurableText(list(new BulletListContentNode(items))));

        assertNotNull(r);
        assertEquals("\n• Read the guide\n• Follow the examples", r.text);
        assertEquals(2, r.segmentCount);
    }

    @Test
    public void blankTextNodeConsumesNoSegment() {
        // 空串/纯空白文本节点承载的是段落结构，不是正文，不占片段。
        TextExtractor.Result r = TextExtractor.extract(new AnnotatedStructurableText(
                list(new TextContentNode("hello"), new TextContentNode(""),
                        new TextContentNode("world"))));

        assertNotNull(r);
        assertEquals("helloworld", r.text);
        assertEquals(2, r.segmentCount);
    }

    @Test
    public void blankLineNodeKeepsItsNewlines() {
        // 真机实测的节点树形态：段落分隔就是内容为 "\n\n" 的文本节点。
        TextExtractor.Result r = TextExtractor.extract(new AnnotatedStructurableText(
                list(new TextContentNode("hello"), new TextContentNode("\n\n"),
                        new TextContentNode("world"))));

        assertNotNull(r);
        assertEquals("hello\n\nworld", r.text);
        assertEquals(2, r.segmentCount);
    }

    @Test
    public void blankLineInsideOneNodeSplitsSegments() {
        // 纯文本消息 "hello\n\nworld" 是一个节点，但有两个可翻译段落。
        TextExtractor.Result r = TextExtractor.extract(new AnnotatedStructurableText(
                list(new TextContentNode("hello\n\nworld"))));

        assertNotNull(r);
        assertEquals("hello\n\nworld", r.text);
        assertEquals(2, r.segmentCount);
    }

    @Test
    public void singleNewlineInsideNodeIsOneSegment() {
        // 单个换行不是段落分隔，仍属于同一段落。
        TextExtractor.Result r = TextExtractor.extract(new AnnotatedStructurableText(
                list(new TextContentNode("first\nsecond"), new TextContentNode("third"))));

        assertNotNull(r);
        assertEquals("first\nsecondthird", r.text);
        assertEquals(2, r.segmentCount);
    }

    @Test
    public void leadingAndTrailingWhitespaceIsKeptInRequest() {
        // 首尾空白属于结构（缩进/段间距），原样进入请求。
        TextExtractor.Result r = TextExtractor.extract(new AnnotatedStructurableText(
                list(new TextContentNode("  hello  "), new TextContentNode("world"))));

        assertNotNull(r);
        assertEquals("  hello  world", r.text);
        assertEquals(2, r.segmentCount);
    }

    @Test
    public void nullContentNodeIsNotCounted() {
        TextExtractor.Result r = TextExtractor.extract(new AnnotatedStructurableText(
                list(new TextContentNode(null), new TextContentNode("hello"),
                        new TextContentNode("world"))));

        assertNotNull(r);
        assertEquals(2, r.textNodeCount);
        assertEquals("helloworld", r.text);
        assertEquals(2, r.segmentCount);
    }

    @Test
    public void primitiveTextIsSingleSegment() {
        // 纯文本消息历史上从不进入多片段缓存命名空间（segmentCount 恒 < 2）。
        TextExtractor.Result r = TextExtractor.extract(
                new PrimitiveStructurableText("hello\nworld"));

        assertNotNull(r);
        assertEquals("hello\nworld", r.text);
        assertEquals(1, r.segmentCount);
    }

    @Test
    public void urlLinkIsPositionMarkerInPlainRequest() {
        // 裸 URL 链接的显示文本是请求里的定位标记：原样携带（模型按规则 6
        // 保留，回填按它切分对齐并删除回显，显示永远用原节点）。
        TextExtractor.Result r = TextExtractor.extract(new AnnotatedStructurableText(list(
                new TextContentNode("hello"),
                new NewLineContentNode(),
                new LinkContentNode(list(new TextContentNode("https://gofile.io/d/rfh2yimO"))),
                new NewLineContentNode(),
                new TextContentNode("world"))));

        assertNotNull(r);
        assertEquals("hello\n https://gofile.io/d/rfh2yimO \nworld", r.text);
        assertEquals("URL 链接节点不占片段", 2, r.segmentCount);
    }

    @Test
    public void nonUrlLinkTextTranslatesLikeOtherText() {
        // 掩码链接的显示文本是自然语言，照常作为片段翻译。
        TextExtractor.Result r = TextExtractor.extract(new AnnotatedStructurableText(list(
                new LinkContentNode(list(new TextContentNode("this guide"))),
                new TextContentNode(" is good"))));

        assertNotNull(r);
        assertEquals("this guide is good", r.text);
        assertEquals(2, r.segmentCount);
    }

    @Test
    public void urlChunkInsideTextNodeIsKeptVerbatim() {
        // 文本节点内部的裸 URL 块不占片段、原样保留，计数与回填同口径。
        TextExtractor.Result r = TextExtractor.extract(new AnnotatedStructurableText(list(
                new TextContentNode("hello\n\nhttps://example.com/a\n\nworld"))));

        assertNotNull(r);
        assertEquals("hello\n\nhttps://example.com/a\n\nworld", r.text);
        assertEquals(2, r.segmentCount);
    }

    @Test
    public void isUrlLikeMatchesUrlsOnly() {
        assertTrue(TextExtractor.isUrlLike("https://gofile.io/d/IJYuv3wZ"));
        assertTrue(TextExtractor.isUrlLike("http://a.b/c"));
        assertTrue(TextExtractor.isUrlLike("www.example.com"));
        assertTrue(TextExtractor.isUrlLike("gofile.io/d/rfh2yimO"));
        assertTrue(TextExtractor.isUrlLike("example.com"));
        assertFalse("含空格的不是 URL", TextExtractor.isUrlLike("click here"));
        assertFalse("普通句子不是 URL", TextExtractor.isUrlLike("hello world foo"));
        assertFalse("版本号不是 URL", TextExtractor.isUrlLike("v1.2"));
        assertFalse("缩写不是 URL", TextExtractor.isUrlLike("e.g."));
        assertFalse(TextExtractor.isUrlLike(""));
        assertFalse(TextExtractor.isUrlLike(null));
    }
}
