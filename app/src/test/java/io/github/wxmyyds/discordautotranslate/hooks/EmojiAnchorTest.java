package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.github.wxmyyds.discordautotranslate.core.TextExtractor;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * emoji 作为请求正文里的定位标记。
 *
 * <p>背景：emoji 在宿主里是独立节点（{@code UnicodeEmojiContentNode} /
 * {@code CustomEmojiContentNode}），旧实现只在请求里输出一个空格，模型看不到
 * emoji 的位置，译文行结构与原文对不上（实测 emoji 被挤到行尾/消息末尾）。
 * 现在把 emoji 字符（或 {@code :name:}）写进请求，回填时剔除并据此切分间隔，
 * 显示仍由原节点完成。</p>
 */
public class EmojiAnchorTest {

    /** 文本 emoji 节点：与宿主同名的 getContent()。 */
    public static final class UnicodeEmojiContentNode {
        private final String content;
        private final String surrogate;
        private final boolean jumboable;

        public UnicodeEmojiContentNode(String content, String surrogate, boolean jumboable) {
            this.content = content;
            this.surrogate = surrogate;
            this.jumboable = jumboable;
        }

        public String getContent() {
            return content;
        }

        public String getSurrogate() {
            return surrogate;
        }

        public boolean getJumboable() {
            return jumboable;
        }
    }

    /** 自定义 emoji 节点：没有可读字符，只有 alt 名称。 */
    public static final class CustomEmojiContentNode {
        private final long id;
        private final String alt;

        public CustomEmojiContentNode(long id, String alt) {
            this.id = id;
            this.alt = alt;
        }

        public long getId() {
            return id;
        }

        public String getAlt() {
            return alt;
        }
    }

    public static final class TextContentNode {
        private final String content;

        public TextContentNode(String content) {
            this.content = content;
        }

        public String getContent() {
            return content;
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

    private static List<Object> list(Object... nodes) {
        return new ArrayList<>(Arrays.asList(nodes));
    }

    // ------------------------------------------------------------ nodeEmojiText

    @Test
    public void unicodeEmojiYieldsItsCharacter() {
        assertEquals("\uD83D\uDCE2", TextExtractor.nodeEmojiText(
                new UnicodeEmojiContentNode("\uD83D\uDCE2", "x", false)));
    }

    @Test
    public void unicodeEmojiFallsBackToSurrogateField() {
        // 部分构造路径把字符放在 surrogate 字段。
        assertEquals("\uD83D\uDCE2", TextExtractor.nodeEmojiText(
                new UnicodeEmojiContentNode("", "\uD83D\uDCE2", false)));
    }

    @Test
    public void customEmojiYieldsOriginalAlt() {
        assertEquals(":blob:",
                TextExtractor.nodeEmojiText(new CustomEmojiContentNode(7L, ":blob:")));
    }

    @Test
    public void nonEmojiNodesYieldNull() {
        assertEquals(null, TextExtractor.nodeEmojiText(new TextContentNode("hi")));
        assertEquals(null, TextExtractor.nodeEmojiText(
                new UnicodeEmojiContentNode("", "", false)));
        assertEquals(null, TextExtractor.nodeEmojiText(
                new CustomEmojiContentNode(1L, "")));
    }

    // ------------------------------------------------------------- 请求正文

    @Test
    public void emojiCharacterIsSentInTheRequestText() {
        TextExtractor.Result r = TextExtractor.extract(new AnnotatedStructurableText(
                list(new TextContentNode("Lower Rates"),
                        new UnicodeEmojiContentNode("\uD83D\uDCE2", "x", false),
                        new TextContentNode("Are Back"))));

        assertNotNull(r);
        // 旧行为只有一个空格，模型无从得知 emoji 的位置。
        assertTrue("emoji 字符必须出现在请求正文里",
                r.text.contains("\uD83D\uDCE2"));
        assertTrue(r.text.contains("Lower Rates"));
        assertTrue(r.text.contains("Are Back"));
    }

    @Test
    public void customEmojiIsSentAsColonName() {
        TextExtractor.Result r = TextExtractor.extract(new AnnotatedStructurableText(
                list(new TextContentNode("hello"),
                        new CustomEmojiContentNode(3L, ":party:"),
                        new TextContentNode("world"))));

        assertNotNull(r);
        assertTrue(r.text.contains(":party:"));
    }

    @Test
    public void emojiDoesNotCountAsTranslatableSegment() {
        TextExtractor.Result r = TextExtractor.extract(new AnnotatedStructurableText(
                list(new TextContentNode("one"),
                        new UnicodeEmojiContentNode("\uD83D\uDCE2", "x", false),
                        new TextContentNode("two"))));

        assertNotNull(r);
        // emoji 由原节点渲染，不消费译文片段。
        assertEquals(2, r.segmentCount);
    }

    // ------------------------------------------------------------- 回填对齐

    @Test
    public void translationKeepsEmojiPositionAndTextLandsOnBothSides() {
        TextContentNode first = new TextContentNode("Lower Rates");
        UnicodeEmojiContentNode emoji =
                new UnicodeEmojiContentNode("\uD83D\uDCE2", "x", false);
        TextContentNode second = new TextContentNode("Are Back");
        List<Object> nodes = list(first, emoji, second);

        // 模型按提示词保留了 emoji 的位置。
        List<Object> out = ContentNodes.replaceText(nodes,
                "费率下调 \uD83D\uDCE2 回归了");

        assertNotNull("emoji 位置正确时必须对齐成功", out);
        assertSame("emoji 节点原样保留（显示由宿主完成）", emoji, out.get(1));
        String left = ((TextContentNode) out.get(0)).getContent();
        String right = ((TextContentNode) out.get(2)).getContent();
        assertTrue("emoji 左侧必须有译文，实际=" + left, left.contains("费率下调"));
        assertTrue("emoji 右侧必须有译文，实际=" + right, right.contains("回归了"));
    }

    /** 锚点丢失且译文只剩一行，缺少精确边界时整条拒绝。 */
    @Test
    public void droppedEmojiWithMergedSingleLineKeepsOriginalNodes() {
        TextContentNode first = new TextContentNode("Lower Rates");
        UnicodeEmojiContentNode emoji =
                new UnicodeEmojiContentNode("\uD83D\uDCE2", "x", false);
        TextContentNode second = new TextContentNode("Are Back");
        List<Object> nodes = list(first, emoji, second);

        // 两个片段只拿到一行译文：行结构对不上，整条保留原文。
        assertEquals(null, ContentNodes.replaceText(nodes, "费率下调 回归了"));
        assertEquals("Lower Rates", first.getContent());
        assertEquals("Are Back", second.getContent());
        assertSame(emoji, nodes.get(1));
    }

    /** 锚点丢失时严格拒绝，不按行结构猜测回填。 */
    @Test
    public void droppedEmojiWithLinePerSegmentKeepsOriginalNodes() {
        TextContentNode first = new TextContentNode("Lower Rates");
        UnicodeEmojiContentNode emoji =
                new UnicodeEmojiContentNode("\uD83D\uDCE2", "x", false);
        TextContentNode second = new TextContentNode("Are Back");
        List<Object> nodes = list(first, emoji, second);

        assertNull("缺少 emoji 锚点时不能按行猜测位置",
                ContentNodes.replaceText(nodes, "费率下调\n回归了"));
        assertEquals("Lower Rates", first.getContent());
        assertEquals("Are Back", second.getContent());
        assertSame(emoji, nodes.get(1));
    }

    /** 重复回显的锚点被剥离后照常回填，不得在文本里留下重复 emoji。 */
    @Test
    public void duplicateEmojiAnchorIsRejected() {
        TextContentNode text = new TextContentNode("hello");
        UnicodeEmojiContentNode emoji =
                new UnicodeEmojiContentNode("\uD83D\uDCE2", "x", false);
        List<Object> nodes = list(text, emoji);

        assertNull("锚点数量不符时整条拒绝",
                ContentNodes.replaceText(nodes, "你好 \uD83D\uDCE2 \uD83D\uDCE2"));
        assertEquals("hello", text.getContent());
        assertSame(emoji, nodes.get(1));
    }

    @Test
    public void emojiTextIsNotDuplicatedIntoRenderedText() {
        TextContentNode first = new TextContentNode("hello");
        UnicodeEmojiContentNode emoji =
                new UnicodeEmojiContentNode("\uD83D\uDCE2", "x", false);
        List<Object> nodes = list(first, emoji);

        List<Object> out = ContentNodes.replaceText(nodes, "你好 \uD83D\uDCE2");

        assertNotNull(out);
        assertSame(emoji, out.get(1));
        String rendered = ((TextContentNode) out.get(0)).getContent();
        assertTrue("emoji 由原节点绘制，不能同时出现在文本里，实际=" + rendered,
                !rendered.contains("\uD83D\uDCE2"));
    }
}
