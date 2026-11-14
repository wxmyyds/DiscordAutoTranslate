package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.*;

import org.junit.Test;

public class TextWhitespaceStatsTest {
    @Test
    public void countsWhitespaceWithoutChangingOrPrintingContent() {
        String original = "  secret\t\ntext\u00a0\u00a0 ";
        TextWhitespaceStats stats = TextWhitespaceStats.of(original);
        assertEquals(original.length(), stats.length);
        assertEquals(6, stats.horizontal);
        assertEquals(1, stats.lineBreaks);
        assertEquals(2, stats.leading);
        assertEquals(3, stats.trailing);
        assertEquals(3, stats.longestHorizontalRun);
        assertFalse(stats.toString().contains("secret"));
        assertFalse(stats.toString().contains("text"));
        assertEquals("  secret\t\ntext\u00a0\u00a0 ", original);
    }

    @Test
    public void distinguishesSpaceOnlyDifferencesFromChangedNonWhitespaceText() {
        assertTrue(TextWhitespaceStats.equalWithoutWhitespace("first\nsecond", "first second"));
        assertTrue(TextWhitespaceStats.equalWithoutWhitespace(" a\u00a0b ", "ab"));
        assertFalse(TextWhitespaceStats.equalWithoutWhitespace("first second", "first third"));
        assertFalse(TextWhitespaceStats.equalWithoutWhitespace("abc", "abcd"));
        assertFalse(TextWhitespaceStats.equalWithoutWhitespace(null, ""));
    }

    @Test
    public void handlesNullAndAllWhitespaceWithoutDisclosingText() {
        assertEquals(-1, TextWhitespaceStats.of(null).length);
        TextWhitespaceStats stats = TextWhitespaceStats.of(" \n\u2028\u2029");
        assertEquals(3, stats.lineBreaks);
        assertEquals(4, stats.leading);
        assertEquals(4, stats.trailing);
        assertTrue(TextWhitespaceStats.equalWithoutWhitespace(" \n", ""));
    }
}
