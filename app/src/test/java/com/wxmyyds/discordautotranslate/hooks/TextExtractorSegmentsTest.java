package com.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.wxmyyds.discordautotranslate.core.TextExtractor;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link TextExtractor} 同时产出「纯文本」与「带分段标记文本」的行为。
 *
 * <p>约束：纯文本必须保持既有形态（语言检测与内容哈希都依赖它），
 * 标记只影响发给模型的请求文本。标记数必须与可翻译文本节点数严格相等，
 * 否则回填会错位，此时应整体不启用标记。</p>
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
    public void singleTextNodeIsNotSegmented() {
        // 单节点没有段落结构可保，加标记只会给模型增加噪音。
        TextExtractor.Result r = TextExtractor.extract(
                new AnnotatedStructurableText(list(new TextContentNode("hello world"))));

        assertNotNull(r);
        assertEquals("hello world", r.text);
        assertNull("单节点不启用标记", r.segmented);
    }

    @Test
    public void twoTextNodesAreSegmentedInOrder() {
        TextExtractor.Result r = TextExtractor.extract(new AnnotatedStructurableText(
                list(new TextContentNode("hi"), new NewLineContentNode(),
                        new TextContentNode("hi"))));

        assertNotNull(r);
        assertEquals("纯文本形态不变", "hi\nhi", r.text);
        assertEquals("换行节点留在标记之外", "<s1>hi</s1>\n<s2>hi</s2>", r.segmented);
    }

    @Test
    public void styledContainersKeepTheirTextSegmented() {
        TextExtractor.Result r = TextExtractor.extract(new AnnotatedStructurableText(
                list(new TextContentNode("hello "),
                        new StrongContentNode(list(new TextContentNode("world"))),
                        new TextContentNode(" foo"))));

        assertNotNull(r);
        assertEquals("hello world foo", r.text);
        assertEquals("<s1>hello </s1><s2>world</s2><s3> foo</s3>", r.segmented);
    }

    @Test
    public void codeAndOpaqueNodesStayOutsideTags() {
        TextExtractor.Result r = TextExtractor.extract(new AnnotatedStructurableText(
                list(new TextContentNode("run"),
                        new InlineCodeContentNode("npm install"),
                        new EmojiContentNode("1"),
                        new TextContentNode("now"))));

        assertNotNull(r);
        assertFalse("代码不得进入标记内容", r.segmented.contains("npm install"));
        // 两个文本节点各占一个标记，代码与 emoji 只贡献标记外的分隔字符。
        assertTrue(r.segmented.startsWith("<s1>run</s1>"));
        assertTrue(r.segmented.endsWith("<s2>now</s2>"));
    }

    @Test
    public void bulletListItemsAreSegmentedPerTextNode() {
        List<List<Object>> items = new ArrayList<>();
        items.add(list(new TextContentNode("Read the guide")));
        items.add(list(new TextContentNode("Follow the examples")));
        TextExtractor.Result r = TextExtractor.extract(
                new AnnotatedStructurableText(list(new BulletListContentNode(items))));

        assertNotNull(r);
        assertEquals("\n• Read the guide\n• Follow the examples", r.text);
        assertEquals("列表项符号留在标记之外",
                "\n• <s1>Read the guide</s1>\n• <s2>Follow the examples</s2>", r.segmented);
    }

    @Test
    public void blankTextNodeIsNotSegmentedButStaysInPlace() {
        // 空串/纯空白文本节点承载的是段落结构，不是正文。
        // 把它包进标记会被模型当作内容改写（真机实测："\n\n" 被挑到串首后
        // 被 trim() 删掉，原文空行消失）。因此它不占标记，但位置必须保留。
        TextExtractor.Result r = TextExtractor.extract(new AnnotatedStructurableText(
                list(new TextContentNode("hello"), new TextContentNode(""),
                        new TextContentNode("world"))));

        assertNotNull(r);
        assertEquals("空白节点不占标记", "<s1>hello</s1><s2>world</s2>", r.segmented);
    }

    @Test
    public void blankLineNodeKeepsItsNewlinesOutsideTags() {
        // 真机实测的节点树形态：段落分隔就是内容为 "\n\n" 的文本节点。
        TextExtractor.Result r = TextExtractor.extract(new AnnotatedStructurableText(
                list(new TextContentNode("hello"), new TextContentNode("\n\n"),
                        new TextContentNode("world"))));

        assertNotNull(r);
        assertEquals("<s1>hello</s1>\n\n<s2>world</s2>", r.segmented);
    }

    @Test
    public void blankLineInsideOneNodeIsKeptOutsideTags() {
        // 纯文本消息 "hello\n\nworld" 是一个节点，但有两个可翻译段落。
        TextExtractor.Result r = TextExtractor.extract(new AnnotatedStructurableText(
                list(new TextContentNode("hello\n\nworld"))));

        assertNotNull(r);
        assertEquals("<s1>hello</s1>\n\n<s2>world</s2>", r.segmented);
    }

    @Test
    public void singleNewlineInsideNodeStaysInsideTheTag() {
        // 单个换行不是段落分隔，仍属于同一段落，由模型按正常换行翻译。
        TextExtractor.Result r = TextExtractor.extract(new AnnotatedStructurableText(
                list(new TextContentNode("first\nsecond"), new TextContentNode("third"))));

        assertNotNull(r);
        assertEquals("<s1>first\nsecond</s1><s2>third</s2>", r.segmented);
    }

    @Test
    public void leadingAndTrailingWhitespaceStaysOutsideTags() {
        // 首尾空白属于结构（缩进/段间距），不能交给模型。
        TextExtractor.Result r = TextExtractor.extract(new AnnotatedStructurableText(
                list(new TextContentNode("  hello  "), new TextContentNode("world"))));

        assertNotNull(r);
        assertEquals("  <s1>hello</s1>  <s2>world</s2>", r.segmented);
    }

    @Test
    public void nullContentNodeIsNotCountedAndNotSegmented() {
        TextExtractor.Result r = TextExtractor.extract(new AnnotatedStructurableText(
                list(new TextContentNode(null), new TextContentNode("hello"),
                        new TextContentNode("world"))));

        assertNotNull(r);
        assertEquals(2, r.textNodeCount);
        assertNotNull(r.segmented);
        assertTrue(r.segmented.startsWith("<s1>hello</s1>"));
    }

    @Test
    public void primitiveTextIsNeverSegmented() {
        TextExtractor.Result r = TextExtractor.extract(
                new PrimitiveStructurableText("hello\nworld"));

        assertNotNull(r);
        assertEquals("hello\nworld", r.text);
        assertNull(r.segmented);
    }
}
