package io.github.wxmyyds.discordautotranslate.hooks;

import io.github.wxmyyds.discordautotranslate.core.StateStore;

/** Plain native Poll labels: state-only display selection, never requests or model mutation. */
final class PollTextProjection {
    private PollTextProjection() {}

    static String display(String original, StateStore.Entry state) {
        return state != null && state.status == StateStore.Status.TRANSLATED
                && !state.showingOriginal && state.translatedText != null
                ? state.translatedText : original;
    }
}
