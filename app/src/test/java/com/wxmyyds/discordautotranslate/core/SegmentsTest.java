package com.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * {@link Segments} 的分段标记协议：包标记、判定、解析、回退。
 *
 * <p>要点：解析必须做到「全有或全无」。模型漏段、重复段、编号越界、闭合标记缺失
 * 时一律返回 null，让上层回退到旧的行切分逻辑，绝不能把部分结果当成完整译文。</p>
 */
public class SegmentsTest {

    @Test
    public void wrapAddsMatchingOpenAndCloseTags() {
        assertEquals("<s1>hi</s1>", Segments.wrap(1, "hi"));
        assertEquals("<s12>你好</s12>", Segments.wrap(12, "你好"));
    }

    @Test
    public void isSegmentedDetectsProtocolTags() {
        assertTrue(Segments.isSegmented("<s1>hi</s1>"));
        assertTrue(Segments.isSegmented("prefix <s2>x</s2>"));
        assertFalse(Segments.isSegmented("plain text"));
        assertFalse(Segments.isSegmented(""));
        assertFalse(Segments.isSegmented(null));
    }

    @Test
    public void parseExtractsEverySegmentInOrder() {
        List<String> parts = Segments.parse("<s1>你好</s1>\n<s2>世界</s2>", 2);
        assertNotNull(parts);
        assertEquals(Arrays.asList("你好", "世界"), parts);
    }

    @Test
    public void parseIgnoresOrderOfSegmentsInResponse() {
        // 模型把段落顺序颠倒时仍按编号归位，不能按出现顺序错位回填。
        List<String> parts = Segments.parse("<s2>第二</s2><s1>第一</s1>", 2);
        assertNotNull(parts);
        assertEquals(Arrays.asList("第一", "第二"), parts);
    }

    @Test
    public void parseKeepsInnerNewlinesAndSpaces() {
        List<String> parts = Segments.parse("<s1>第一行\n第二行</s1>", 1);
        assertNotNull(parts);
        assertEquals("第一行\n第二行", parts.get(0));
    }

    @Test
    public void parseToleratesWhitespaceInsideClosingTag() {
        List<String> parts = Segments.parse("<s1>你好</s1 >", 1);
        assertNotNull(parts);
        assertEquals("你好", parts.get(0));
    }

    @Test
    public void parseReturnsNullWhenASegmentIsMissing() {
        // 模型只译了一段：不能把剩余文本节点留空或错位。
        assertNull(Segments.parse("<s1>你好</s1>", 2));
    }

    @Test
    public void parseReturnsNullWhenASegmentIsRepeated() {
        // 重复编号会让后一段覆盖前一段，静默丢字；必须整体回退。
        assertNull(Segments.parse("<s1>甲</s1><s1>乙</s1>", 1));
        assertNull(Segments.parse("<s1>甲</s1><s1>乙</s1><s2>丙</s2>", 2));
    }

    @Test
    public void parseReturnsNullWhenTagIsRenumberedOutOfRange() {
        assertNull(Segments.parse("<s1>a</s1><s3>b</s3>", 2));
        assertNull(Segments.parse("<s0>a</s0><s1>b</s1>", 2));
    }

    @Test
    public void parseReturnsNullWhenClosingTagIsMissing() {
        assertNull(Segments.parse("<s1>你好", 1));
    }

    @Test
    public void parseReturnsNullForPlainText() {
        assertNull(Segments.parse("你好 你好", 2));
        assertNull(Segments.parse(null, 2));
        assertNull(Segments.parse("", 2));
    }

    @Test
    public void parseAllowsEmptySegmentContent() {
        // 某个片段本身可以为空（例如用户留白），但编号必须齐全。
        List<String> parts = Segments.parse("<s1></s1><s2>世界</s2>", 2);
        assertNotNull(parts);
        assertEquals("", parts.get(0));
        assertEquals("世界", parts.get(1));
    }

    @Test
    public void ruleMentionsTagsAndKeepsOutsideCharacters() {
        // 协议说明必须覆盖「不要合并/删除标记」与「保留标记外的字符」两条关键约束。
        assertTrue(Segments.RULE.contains("<sN>"));
        assertTrue(Segments.RULE.contains("Keep every"));
        assertTrue(Segments.RULE.contains("outside the tags"));
    }
}
