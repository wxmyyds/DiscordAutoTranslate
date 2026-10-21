package com.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * 诊断环形缓冲：长按图标弹出并复制的全部内容都来自这里，
 * 因此需要锁定「容量上限」「尾部截断」「丢弃提示」三件事。
 */
public class DiagnosticsTest {

    @Before
    public void clearBeforeEach() {
        // 缓冲区是静态的：不清理会让用例互相污染。
        Diagnostics.reset();
    }

    @Test
    public void snapshotIsEmptyBeforeAnythingRecorded() {
        assertEquals("", Diagnostics.snapshot(0));
        assertEquals("", Diagnostics.snapshot(10));
    }

    @Test
    public void snapshotKeepsInsertionOrder() {
        Diagnostics.record("first");
        Diagnostics.record("second");
        String text = Diagnostics.snapshot(0);
        assertTrue(text.indexOf("first") < text.indexOf("second"));
    }

    @Test
    public void snapshotReturnsTailWhenLimited() {
        for (int i = 0; i < 10; i++) {
            Diagnostics.record("line-" + i);
        }
        String text = Diagnostics.snapshot(3);
        assertTrue("should announce truncation", text.contains("仅显示最近 3 条"));
        assertFalse("older lines dropped", text.contains("line-6"));
        assertTrue(text.contains("line-7"));
        assertTrue(text.contains("line-9"));
    }

    @Test
    public void bufferIsBoundedAndReportsDroppedCount() {
        // 超出内部上限后必须丢弃最旧的，并如实告知用户记录不完整。
        for (int i = 0; i < 260; i++) {
            Diagnostics.record("filler-" + i);
        }
        String text = Diagnostics.snapshot(0);
        assertTrue("should report dropped lines", text.contains("已丢弃"));
        assertFalse("oldest entries must be gone", text.contains("filler-0\n"));
        assertTrue("newest entry must remain", text.contains("filler-259"));
    }

    @Test
    public void resetClearsBufferAndDroppedCount() {
        for (int i = 0; i < 260; i++) {
            Diagnostics.record("filler-" + i);
        }
        Diagnostics.reset();
        assertEquals("", Diagnostics.snapshot(0));
        // 清空后不应再残留"已丢弃"提示。
        Diagnostics.record("after-reset");
        assertEquals("after-reset\n", Diagnostics.snapshot(0));
    }

    @Test
    public void recordIgnoresNullOrEmptyLines() {
        Diagnostics.record(null);
        Diagnostics.record("");
        assertEquals("", Diagnostics.snapshot(0));
    }
}
