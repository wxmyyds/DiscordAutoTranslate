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
    public void emptyTextNodeStillOccupiesASegmentToKeepNumberingAligned() {
        // 空串文本节点也计入 textNodeCount，因此必须占一个标记，
        // 否则标记数与文本节点数错位，回填时片段会串到相邻节点上。
        TextExtractor.Result r = TextExtractor.extract(new AnnotatedStructurableText(
                list(new TextContentNode("hello"), new TextContentNode(""),
                        new TextContentNode("world"))));

        assertNotNull(r);
        assertEquals(3, r.textNodeCount);
        assertEquals("<s1>hello</s1><s2></s2><s3>world</s3>", r.segmented);
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
