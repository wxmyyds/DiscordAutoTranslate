package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class ReplyTextProjectionTest {
    @Test
    public void replacesOnlyParagraphSeparatorsAtTheSameOffsets() {
        String original = "  first\n\nsecond\r\nthird\u2028fourth\u2029end\t ";
        StringBuilder rendered = new StringBuilder(original);
        List<Integer> offsets = new ArrayList<>();
        int count = ReplyTextProjection.flattenLineBreaks(rendered, index -> {
            offsets.add(index);
            rendered.setCharAt(index, ' ');
        });
        assertEquals(6, count);
        assertEquals(original.length(), rendered.length());
        assertEquals("  first  second  third fourth end\t ", rendered.toString());
        for (int i = 0; i < original.length(); i++) {
            if (!offsets.contains(i)) assertEquals(original.charAt(i), rendered.charAt(i));
        }
        assertEquals(0, TextWhitespaceStats.of(rendered).lineBreaks);
    }

    @Test
    public void horizontalWhitespaceAndAttachmentCharactersAreNotTouched() {
        String original = "  name\t\u00a0\ufffc\ufeff\u200b text  ";
        StringBuilder rendered = new StringBuilder(original);
        assertEquals(0, ReplyTextProjection.flattenLineBreaks(rendered, index -> fail()));
        assertEquals(original, rendered.toString());
        assertEquals(0, ReplyTextProjection.flattenLineBreaks("", index -> fail()));
    }

    @Test
    public void attachmentOffsetsAndSurrogatePairsRemainStable() {
        String original = "\ufffc\nemoji 😀\n\ufffc";
        StringBuilder rendered = new StringBuilder(original);
        List<Integer> offsets = new ArrayList<>();
        ReplyTextProjection.flattenLineBreaks(rendered, index -> {
            offsets.add(index);
            rendered.setCharAt(index, ' ');
        });
        assertEquals(Arrays.asList(1, original.lastIndexOf('\n')), offsets);
        assertEquals("\ufffc emoji 😀 \ufffc", rendered.toString());
        assertEquals(original.indexOf('\ufffc'), rendered.indexOf("\ufffc"));
        assertEquals(original.lastIndexOf('\ufffc'), rendered.lastIndexOf("\ufffc"));
        // This test verifies character offsets only, not Android/Drawee span behavior.
    }

    @Test
    public void keepsFullTranslationAndIsIdempotentWithoutAddingEllipsis() {
        String original = "首行\n" + "完整译文\n".repeat(100);
        StringBuilder rendered = new StringBuilder(original);
        assertEquals(101, ReplyTextProjection.flattenLineBreaks(rendered,
                index -> rendered.setCharAt(index, ' ')));
        assertEquals(original.length(), rendered.length());
        assertTrue(rendered.length() > 250);
        assertFalse(rendered.toString().contains("…"));
        assertEquals(0, ReplyTextProjection.flattenLineBreaks(rendered, index -> fail()));
        assertTrue(original.contains("\n"));
    }
}
