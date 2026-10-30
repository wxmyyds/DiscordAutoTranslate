package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link Segments} 的译文净化（strip）与防损坏校验（plainTextViolation）。
 *
 * <p>分段标记协议的请求侧机器已随单链路重构删除；这里覆盖的是仍在
 * 生产链路上的两个入口：引擎返回前的防损坏校验，以及渲染侧剥离
 * 模型幻觉标记的最后防线。</p>
 */
public class SegmentsTest {

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
