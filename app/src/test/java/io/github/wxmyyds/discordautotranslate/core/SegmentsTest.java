package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link Segments} 的共用标记拒绝谓词与纯文本防损坏校验。 */
public class SegmentsTest {

    @Test
    public void hasSegmentMarkersDetectsOpeningClosingAndIncompleteTags() {
        for (String text : new String[]{"<s1>你好</s1>", "译文里残留</s2>字面量",
                "<s1>你好", "<s 12 >文本", "</s 12 >", "前言 <s3", "</s4",
                "<s999999999999999999999999>文本"}) {
            assertTrue(text, Segments.hasSegmentMarkers(text));
            assertNotNull(text, Segments.plainTextViolation(text, "hello world"));
        }
    }

    @Test
    public void hasSegmentMarkersLeavesPlainTextUntouched() {
        assertFalse(Segments.hasSegmentMarkers(null));
        assertFalse(Segments.hasSegmentMarkers(""));
        assertFalse(Segments.hasSegmentMarkers("你好世界"));
        assertFalse(Segments.hasSegmentMarkers("<strong>hello</strong>"));
    }

    @Test
    public void plainTextViolationLengthBoundariesAreInclusive() {
        String source = repeat('a', 40);
        assertNull(Segments.plainTextViolation(repeat('b', 10), source));
        assertNotNull(Segments.plainTextViolation(repeat('b', 9), source));
        assertNull(Segments.plainTextViolation(repeat('b', 120), source));
        assertNotNull(Segments.plainTextViolation(repeat('b', 121), source));
        assertNull(Segments.plainTextViolation(repeat('b', 121), repeat('a', 39)));
    }

    @Test
    public void plainTextViolationPlaceholderBoundaryAndNullAreRejected() {
        assertNull(Segments.plainTextViolation("XXX", "hello"));
        assertNotNull(Segments.plainTextViolation("XXXX", "hello"));
        assertNotNull(Segments.plainTextViolation(null, "hello"));
    }

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            out.append(value);
        }
        return out.toString();
    }

    @Test
    public void plainTextViolationGuardsForSinglePath() {
        // 单链路引擎在返回译文前调用该校验：任何一项不过都判失败，
        // 由用户点图标重试，绝不把损坏内容送进节点树。
        assertNotNull("残留标记必须拦截",
                Segments.plainTextViolation("译文里残留</s2>字面量", "hello world"));
        assertNotNull("模板占位符必须拦截",
                Segments.plainTextViolation("SENSENOVA_6_8_FLASH_LITE_XXXXXX", "hello world"));
        assertNotNull("空译文必须拦截",
                Segments.plainTextViolation("  ", "hello world"));

        // 长度比例校验只对 ≥40 字符的原文生效。
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            source.append("paragraph ").append(i).append(' ');
        }
        String longSource = source.toString();
        assertTrue(longSource.length() >= 40);
        StringBuilder flood = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            flood.append(longSource); // 10 倍长度：复读刷屏
        }
        assertNotNull("复读（长度暴涨）必须拦截",
                Segments.plainTextViolation(flood.toString(), longSource));
        assertNotNull("内容大面积丢失必须拦截",
                Segments.plainTextViolation("丢了", longSource));

        assertNull("正常译文必须放行",
                Segments.plainTextViolation("你好世界", "hello world"));
        assertNull("短原文不做比例校验",
                Segments.plainTextViolation("很长很长的译文比原文还长好几倍", "hi"));
    }
}
