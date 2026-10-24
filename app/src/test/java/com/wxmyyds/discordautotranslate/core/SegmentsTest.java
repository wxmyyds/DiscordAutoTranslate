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
    public void parseReturnsNullWhenSegmentsAreOutOfOrder() {
        // 错序意味着模型重排/改写了段落，编号与原文位置无法对齐；
        // 按编号硬归位会掩盖错位本身，必须整体拒绝、保留原文。
        assertNull(Segments.parse("<s2>第二</s2><s1>第一</s1>", 2));
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
    public void stripRemovesTagsAndKeepsContentInOrder() {
        assertEquals("你好\n世界", Segments.strip("<s1>你好</s1>\n<s2>世界</s2>"));
        // 只译了一段：剩下的标记被剥离，内容仍保留，不能把标签当文字显示。
        assertEquals("你好", Segments.strip("<s1>你好</s1>"));
    }

    @Test
    public void stripKeepsTextOutsideTags() {
        assertEquals("前 x", Segments.strip("前 <s1>x</s1>"));
        assertEquals("a你好b", Segments.strip("a<s1>你好</s1>b"));
    }

    @Test
    public void stripHandlesMissingCloseTagGracefully() {
        // 闭合标记缺失：只丢掉开标记，其后文字不得丢失。
        assertEquals("你好", Segments.strip("<s1>你好"));
    }

    @Test
    public void stripReturnsEmptyForNullOrEmpty() {
        assertEquals("", Segments.strip(null));
        assertEquals("", Segments.strip(""));
    }

    @Test
    public void parseReturnsNullWhenSegmentContentIsBlank() {
        // 被标记的段落在提取侧永不为空（空段落不占标记）；空段 = 模型丢内容。
        assertNull(Segments.parse("<s1></s1><s2>世界</s2>", 2));
        assertNull(Segments.parse("<s1>  </s1><s2>世界</s2>", 2));
    }

    @Test
    public void parseReturnsNullWhenStrayMarkerLeaksIntoContent() {
        // 实测：模型把改写后的编号（如 <s22>）夹在段内容里，回填后会作为
        // 字面量显示给用户。必须整体拒绝。
        assertNull(Segments.parse("<s1>你好 <s22>泄漏</s22> 些</s1>", 1));
        assertNull(Segments.parse("<s1>你好</s1><s2>世界 </s2>尾部</s2>", 2));
    }

    @Test
    public void parseReturnsNullWhenContentHasTemplatePlaceholder() {
        // 实测：小模型在长重复文本上幻觉出 SENSENOVA_..._XXXXXX 这类占位符。
        assertNull(Segments.parse(
                "<s1>SENSENOVA_6_8_FLASH_LITE_YYYMMDD_HHMMSS_XXXXXX</s1>", 1));
        assertNull(Segments.parse("<s1>占位 XXXXXX 符</s1>", 1));
        // 三个连续 X 不是占位符形态，不得误伤。
        List<String> ok = Segments.parse("<s1>版本 XXX 正常</s1>", 1);
        assertNotNull(ok);
        assertEquals("版本 XXX 正常", ok.get(0));
    }

    @Test
    public void parseReturnsNullWhenExtraTagAppearsAfterAllParts() {
        // 模型多输出了一段：额外的 <s22> 说明它自行改写了段落结构。
        assertNull(Segments.parse("<s1>a</s1><s2>b</s2><s22>多</s22>", 2));
    }

    @Test
    public void ruleMentionsTagsAndKeepsOutsideCharacters() {
        // 协议说明必须覆盖「不要合并/删除标记」与「保留标记外的字符」两条关键约束。
        assertTrue(Segments.RULE.contains("<sN>"));
        assertTrue(Segments.RULE.contains("Keep every"));
        assertTrue(Segments.RULE.contains("outside the tags"));
    }
}
