package com.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 诊断日志格式化：设置页展示依赖它把"批次头 + 行前缀"转成可读文本。
 *
 * <p>这些用例锁定三件事：批次头的时间/条数解析、行前缀剥离、
 * 以及超长日志只保留尾部（诊断越新越有价值）。</p>
 */
public class DiagnosticsLogTest {

    @Test
    public void formatStripsLinePrefixesAndKeepsContent() {
        String raw = "T|ts=1730000000000 n=2 dropped=0 host=/data/user/0/com.discord at=12s\n"
                + "I|layout-diag: msg=123 height=549\n"
                + "D|row-refresh-after-layout(before=549,after=549)\n";
        String formatted = DiagnosticsLog.format(raw, 0);

        assertTrue("batch header should be rendered", formatted.contains("推送 2 条"));
        assertTrue(formatted.contains("layout-diag: msg=123 height=549"));
        assertTrue(formatted.contains("row-refresh-after-layout(before=549,after=549)"));
        // 行前缀是通道标记，不应出现在展示文本里。
        assertFalse(formatted.contains("I|"));
        assertFalse(formatted.contains("D|"));
    }

    @Test
    public void formatReportsDroppedCountOnlyWhenPositive() {
        String withDrops = "T|ts=1730000000000 n=1 dropped=7 host=? at=1s\nI|x\n";
        assertTrue(DiagnosticsLog.format(withDrops, 0).contains("队列溢出丢弃 7 条"));

        String noDrops = "T|ts=1730000000000 n=1 dropped=0 host=? at=1s\nI|x\n";
        assertFalse(DiagnosticsLog.format(noDrops, 0).contains("队列溢出丢弃"));
    }

    @Test
    public void formatKeepsNewestLinesWhenTruncating() {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            raw.append("I|line-").append(i).append('\n');
        }
        String formatted = DiagnosticsLog.format(raw.toString(), 5);
        assertTrue("truncation should be announced", formatted.contains("仅显示最近 5 行"));
        assertFalse("oldest lines should be dropped", formatted.contains("line-14"));
        assertTrue("newest lines should remain", formatted.contains("line-19"));
    }

    @Test
    public void formatToleratesLegacyAndUnknownLines() {
        String raw = "msg=1562029352026247171 view=null outcome=target(h=786,attached=true)\n";
        String formatted = DiagnosticsLog.format(raw, 0);
        // 没有批次头与行前缀的旧格式行原样保留，不丢信息。
        assertTrue(formatted.contains("outcome=target"));
    }

    @Test
    public void formatReturnsEmptyForMissingOrBlankInput() {
        assertEquals("", DiagnosticsLog.format(null, 0));
        assertEquals("", DiagnosticsLog.format("", 0));
        assertEquals("", DiagnosticsLog.format("\n\n", 0));
    }

    @Test
    public void formatSurvivesMalformedBatchHeader() {
        String raw = "T|ts=abc n=zz dropped= q\nI|still-here\n";
        String formatted = DiagnosticsLog.format(raw, 0);
        assertTrue("unparsable time falls back to a label", formatted.contains("时间未知"));
        assertTrue(formatted.contains("still-here"));
    }
}
