package io.github.wxmyyds.discordautotranslate.hooks;

import java.util.function.IntConsumer;

/** Display-only reply projection; never changes canonical content or translation state. */
final class ReplyTextProjection {
    private ReplyTextProjection() {
    }

    /**
     * Replace paragraph separators one UTF-16 unit at a time, without trimming or
     * collapsing horizontal whitespace. The caller edits the same native builder;
     * length and all subsequent character/attachment offsets remain unchanged.
     * Run after rich-text alignment/rendering, before Discord adds its leading margin.
     */
    static int flattenLineBreaks(CharSequence text, IntConsumer replaceWithSpace) {
        int replaced = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029') {
                replaceWithSpace.accept(i);
                replaced++;
            }
        }
        return replaced;
    }
}
