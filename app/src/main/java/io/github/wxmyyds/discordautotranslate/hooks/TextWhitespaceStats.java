package io.github.wxmyyds.discordautotranslate.hooks;

import androidx.annotation.Nullable;

/** Counts only; never retains or outputs message characters. Does not modify text. */
final class TextWhitespaceStats {
    final int length;
    final int horizontal;
    final int lineBreaks;
    final int leading;
    final int trailing;
    final int longestHorizontalRun;

    private TextWhitespaceStats(int length, int horizontal, int lineBreaks,
                                int leading, int trailing, int longestHorizontalRun) {
        this.length = length;
        this.horizontal = horizontal;
        this.lineBreaks = lineBreaks;
        this.leading = leading;
        this.trailing = trailing;
        this.longestHorizontalRun = longestHorizontalRun;
    }

    static TextWhitespaceStats of(@Nullable CharSequence text) {
        if (text == null) {
            return new TextWhitespaceStats(-1, 0, 0, 0, 0, 0);
        }
        int horizontal = 0;
        int lineBreaks = 0;
        int longest = 0;
        int run = 0;
        int leading = 0;
        int trailing = text.length();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isLineBreak(c)) {
                lineBreaks++;
            } else if (isWhitespace(c)) {
                horizontal++;
            }
            if (isWhitespace(c) && !isLineBreak(c)) {
                longest = Math.max(longest, ++run);
            } else {
                run = 0;
            }
        }
        while (leading < text.length() && isWhitespace(text.charAt(leading))) {
            leading++;
        }
        while (trailing > 0 && isWhitespace(text.charAt(trailing - 1))) {
            trailing--;
        }
        return new TextWhitespaceStats(text.length(), horizontal, lineBreaks,
                leading, text.length() - trailing, longest);
    }

    /** Diagnostic comparison only; never used to accept a translation or transform text. */
    static boolean equalWithoutWhitespace(@Nullable CharSequence a, @Nullable CharSequence b) {
        if (a == null || b == null) {
            return false;
        }
        int i = 0;
        int j = 0;
        while (true) {
            while (i < a.length() && isWhitespace(a.charAt(i))) i++;
            while (j < b.length() && isWhitespace(b.charAt(j))) j++;
            if (i == a.length() || j == b.length()) {
                return i == a.length() && j == b.length();
            }
            if (a.charAt(i++) != b.charAt(j++)) {
                return false;
            }
        }
    }

    private static boolean isWhitespace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    private static boolean isLineBreak(char c) {
        return c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029';
    }

    @Override
    public String toString() {
        return "length=" + length + ",horizontal=" + horizontal + ",breaks=" + lineBreaks
                + ",leading=" + leading + ",trailing=" + trailing
                + ",maxHorizontalRun=" + longestHorizontalRun;
    }
}
