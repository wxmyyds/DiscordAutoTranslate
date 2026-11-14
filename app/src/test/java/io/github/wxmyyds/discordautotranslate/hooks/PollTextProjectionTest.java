package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.*;

import io.github.wxmyyds.discordautotranslate.core.Scheduler;
import io.github.wxmyyds.discordautotranslate.core.StateStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Display selection is a read-only projection, not a second translation pipeline. */
public class PollTextProjectionTest {
    private static final String KEY = "123456789012345678\u0000poll\u0000answer:a\u0000hash";
    private static final String ORIGINAL = "  Original label\n😀  ";
    private final StateStore store = StateStore.get();

    @Before
    public void setup() {
        store.onNewGeneration();
        Scheduler.reset();
    }

    @After
    public void cleanup() {
        store.onNewGeneration();
        Scheduler.reset();
    }

    private StateStore.Entry state() {
        return store.obtain(KEY, "retained-content-hash", "stored original differs from native label");
    }

    /** Read-only observation catches even unexpected entries under unrelated IDs. */
    private Map<?, ?> entriesSnapshot() throws Exception {
        Field field = StateStore.class.getDeclaredField("entries");
        field.setAccessible(true);
        return new HashMap<>((Map<?, ?>) field.get(store));
    }

    /** A projection must not even initialize the request scheduler. */
    private static void assertNoScheduler() throws Exception {
        Field field = Scheduler.class.getDeclaredField("sInstance");
        field.setAccessible(true);
        assertNull(field.get(null));
    }

    private static final class EntrySnapshot {
        private final String id;
        private final String hash;
        private final String original;
        private final String translated;
        private final StateStore.Status status;
        private final boolean showingOriginal;
        private final String error;

        EntrySnapshot(StateStore.Entry entry) {
            id = entry.messageId;
            hash = entry.contentHash;
            original = entry.originalText;
            translated = entry.translatedText;
            status = entry.status;
            showingOriginal = entry.showingOriginal;
            error = entry.lastError;
        }

        void assertUnchanged(StateStore.Entry entry) {
            assertSame(id, entry.messageId);
            assertSame(hash, entry.contentHash);
            assertSame(original, entry.originalText);
            assertSame(translated, entry.translatedText);
            assertSame(status, entry.status);
            assertEquals(showingOriginal, entry.showingOriginal);
            assertSame(error, entry.lastError);
        }
    }

    @Test
    public void onlyTranslatedNotShowingOriginalWithNonNullTranslationSelectsIt() throws Exception {
        StateStore.Entry entry = state();
        entry.lastError = "retained diagnostic";
        Map<?, ?> before = entriesSnapshot();
        int generation = store.generation();
        for (StateStore.Status status : StateStore.Status.values()) {
            for (boolean showingOriginal : new boolean[] {false, true}) {
                for (String translated : Arrays.asList(null, "", "  完整译文\n😀  ")) {
                    entry.status = status;
                    entry.showingOriginal = showingOriginal;
                    entry.translatedText = translated;
                    EntrySnapshot snapshot = new EntrySnapshot(entry);
                    String expected = status == StateStore.Status.TRANSLATED
                            && !showingOriginal && translated != null ? translated : ORIGINAL;
                    assertSame(expected, PollTextProjection.display(ORIGINAL, entry));
                    snapshot.assertUnchanged(entry);
                    assertSame(entry, store.peek(KEY));
                    assertEquals(before, entriesSnapshot());
                    assertEquals(generation, store.generation());
                    assertNoScheduler();
                }
            }
        }
    }

    @Test
    public void absentStateReturnsRawLabelsWithoutRequestsOrStateCreation() throws Exception {
        Map<?, ?> before = entriesSnapshot();
        int generation = store.generation();
        for (String original : Arrays.asList(null, "", "  ", ORIGINAL)) {
            for (int i = 0; i < 3; i++) {
                assertSame(original, PollTextProjection.display(original, store.peek(KEY)));
            }
        }
        assertNull(store.peek(KEY));
        assertEquals(before, entriesSnapshot());
        assertEquals(generation, store.generation());
        assertNoScheduler();
    }

    @Test
    public void nativeNullOrEmptyLabelNeverBecomesAPlaceholderOrResetsStoredOriginal() throws Exception {
        StateStore.Entry entry = state();
        entry.lastError = "keep me";
        for (StateStore.Status status : StateStore.Status.values()) {
            entry.status = status;
            for (String original : Arrays.asList(null, "", "  ")) {
                for (boolean showingOriginal : new boolean[] {false, true}) {
                    entry.showingOriginal = showingOriginal;
                    for (String translated : Arrays.asList(null, "", "译文")) {
                        entry.translatedText = translated;
                        EntrySnapshot snapshot = new EntrySnapshot(entry);
                        String expected = status == StateStore.Status.TRANSLATED
                                && !showingOriginal && translated != null ? translated : original;
                        assertSame(expected, PollTextProjection.display(original, entry));
                        snapshot.assertUnchanged(entry);
                    }
                }
            }
        }
        assertSame(entry, store.peek(KEY));
        assertNoScheduler();
    }

    @Test
    public void projectingMissingSlotDoesNotTouchAnExistingOtherSlot() throws Exception {
        StateStore.Entry entry = state();
        entry.status = StateStore.Status.TRANSLATED;
        entry.translatedText = "另一答案译文";
        entry.showingOriginal = true;
        entry.lastError = "retained";
        EntrySnapshot snapshot = new EntrySnapshot(entry);
        Map<?, ?> before = entriesSnapshot();
        String missing = "123456789012345678\u0000poll\u0000question\u0000other-hash";
        assertSame(ORIGINAL, PollTextProjection.display(ORIGINAL, store.peek(missing)));
        assertNull(store.peek(missing));
        assertSame(entry, store.peek(KEY));
        snapshot.assertUnchanged(entry);
        assertEquals(before, entriesSnapshot());
        assertNoScheduler();
    }
}
