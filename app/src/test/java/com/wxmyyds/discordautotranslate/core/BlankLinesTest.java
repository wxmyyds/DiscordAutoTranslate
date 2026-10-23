package com.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/**
 * 空行（段落）切分的离线验证。
 *
 * <p>真机日志显示：Discord 的段落分隔常常不是独立节点，而是内容为 {@code "\n\n"}
 * 的 {@code TextContentNode}，甚至整段文本就在一个节点里。因此必须能把空行从
 * 「待翻译正文」中分离出来，否则模型会合并或挪走它。</p>
 */
public class BlankLinesTest {

    @Test
    public void singleNewlineIsNotAParagraphBreak() {
        // 普通换行属于同一段落内部，必须留在块里交给模型按正常换行翻译。
        BlankLines.Split s = BlankLines.split("first\nsecond");
        assertEquals(Arrays.asList("first\nsecond"), s.chunks);
        assertTrue(s.separators.isEmpty());
    }

    @Test
    public void blankLineSplitsIntoTwoChunks() {
        BlankLines.Split s = BlankLines.split("hello\n\n@Pids");
        assertEquals(Arrays.asList("hello", "@Pids"), s.chunks);
        assertEquals(Arrays.asList("\n\n"), s.separators);
    }

    @Test
    public void multipleBlankLinesArePreservedExactly() {
        // 原文是几行空白，重建后就必须还是几行，不能归一化成一行。
        for (String sep : new String[]{"\n\n", "\n\n\n", "\n \n", "\n\t\n"}) {
            BlankLines.Split s = BlankLines.split("a" + sep + "b");
            assertEquals("sep=" + sep, Arrays.asList("a", "b"), s.chunks);
            assertEquals("sep=" + sep, Arrays.asList(sep), s.separators);
        }
    }

    @Test
    public void joinReproducesTheOriginalText() {
        // 切分必须是可逆的：任何文本切完再拼回来都要一模一样。
        String[] samples = {
                "",
                "plain",
                "hello\n\nworld",
                "\n\nleading blank lines",
                "trailing blank lines\n\n",
                "a\n\nb\n\nc",
                "mixed\r\nwindows\n\nline\r\nendings",
                "code block\n```\nx\n```\n\ntail",
        };
        for (String sample : samples) {
            assertEquals("sample=" + sample.replace("\n", "\\n"),
                    sample, BlankLines.split(sample).join());
        }
    }

    @Test
    public void threeParagraphsProduceThreeChunks() {
        BlankLines.Split s = BlankLines.split("p1\n\np2\n\np3");
        assertEquals(Arrays.asList("p1", "p2", "p3"), s.chunks);
        assertEquals(2, s.separators.size());
        assertEquals("p1\n\np2\n\np3", s.join());
    }

    @Test
    public void leadingAndTrailingBlankLinesStayOutsideChunks() {
        // 首尾空行不属于任何段落块，应成为空块 + 分隔符，重建时原样还原。
        BlankLines.Split lead = BlankLines.split("\n\nhello");
        assertEquals("", lead.chunks.get(0));
        assertEquals("hello", lead.chunks.get(1));
        assertEquals("\n\nhello", lead.join());

        BlankLines.Split trail = BlankLines.split("hello\n\n");
        assertEquals("hello", trail.chunks.get(0));
        assertEquals("", trail.chunks.get(1));
        assertEquals("hello\n\n", trail.join());
    }

    @Test
    public void nullAndEmptyAreBlank() {
        assertTrue(BlankLines.isBlank(null));
        assertTrue(BlankLines.isBlank(""));
        assertTrue(BlankLines.isBlank("\n\n"));
        assertTrue(BlankLines.isBlank(" \t\r\n"));
        assertFalse(BlankLines.isBlank("a"));
        assertFalse(BlankLines.isBlank("\n a \n"));
    }

    @Test
    public void nullTextDoesNotThrow() {
        BlankLines.Split s = BlankLines.split(null);
        assertEquals(1, s.chunks.size());
        assertEquals("", s.chunks.get(0));
        assertTrue(s.separators.isEmpty());
    }

    // ------------------------------------------------- 片段计数口径

    @Test
    public void segmentCountIgnoresBlankOnlyNodes() {
        // 只含空白的节点是结构，不是正文：不占译文片段。
        assertEquals(0, TextExtractor.countSegments("\n\n"));
        assertEquals(0, TextExtractor.countSegments(""));
        assertEquals(0, TextExtractor.countSegments(null));
    }

    @Test
    public void segmentCountCountsParagraphsInsideOneNode() {
        // 一条纯文本消息 "你好\n\n世界" 整体是一个节点，但有两个可翻译段落。
        assertEquals(2, TextExtractor.countSegments("你好\n\n世界"));
        assertEquals(3, TextExtractor.countSegments("a\n\nb\n\nc"));
    }

    @Test
    public void segmentCountTreatsSingleNewlineAsOneChunk() {
        // 单个换行不是段落分隔：仍是一个片段。
        assertEquals(1, TextExtractor.countSegments("first\nsecond"));
    }

    @Test
    public void segmentCountIgnoresLeadingAndTrailingBlankLines() {
        assertEquals(1, TextExtractor.countSegments("\n\nhello\n\n"));
    }
}
