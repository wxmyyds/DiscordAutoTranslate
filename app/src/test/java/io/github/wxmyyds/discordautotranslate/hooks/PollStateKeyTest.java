package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.*;

import io.github.wxmyyds.discordautotranslate.core.StateStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/** Poll slots use shared content hashes but independent per-row state identities. */
public class PollStateKeyTest {
    private static final String ROW = "123456789012345678";
    private static final String OTHER_ROW = "987654321098765432";
    private final HookBridge bridge = HookBridge.get();
    private final StateStore store = StateStore.get();

    @Before
    public void setup() {
        ContentNodeTestProfile.activate(ContentNodesTest.class);
        store.onNewGeneration();
    }

    @After
    public void cleanup() {
        store.onNewGeneration();
        ContentNodeTestProfile.clear();
    }

    private static Object text(String value) {
        return new ContentNodesTest.PrimitiveStructurableText(value);
    }

    private String key(String row, String slot, String value) {
        return bridge.pollStateKey(row, slot, text(value));
    }

    private static String finalHash(String key) {
        assertNotNull(key);
        return key.substring(key.lastIndexOf('\u0000') + 1);
    }

    @Test
    public void rowQuestionAnswerAndEmbedNamespacesAreDistinctWithOneContentHash() {
        String question = key(ROW, "question", "same label");
        String first = key(ROW, "answer:a", "same label");
        String second = key(ROW, "answer:b", "same label");
        String otherRow = key(OTHER_ROW, "answer:a", "same label");
        String embed = bridge.embedStateKey(ROW, text("same label"));
        List<String> keys = Arrays.asList(ROW, question, first, second, otherRow, embed);
        assertEquals(keys.size(), new HashSet<>(keys).size());
        for (String card : Arrays.asList(question, first, second, otherRow, embed)) {
            assertEquals(finalHash(question), finalHash(card));
        }
        assertEquals(question, key(ROW, "question", "same label"));
        assertEquals(first, key(ROW, "answer:a", "same label"));
        assertEquals(second, key(ROW, "answer:b", "same label"));
        assertNull(store.peek(ROW));
        assertNull(store.peek(question));
        assertNull(store.peek(first));
        assertNull(store.peek(second));
    }

    @Test
    public void equalLabelSlotsCannotOverwriteEachOthersOrBodyOrEmbedState() {
        String question = key(ROW, "question", "same label");
        String first = key(ROW, "answer:a", "same label");
        String second = key(ROW, "answer:b", "same label");
        String embed = bridge.embedStateKey(ROW, text("same label"));
        List<String> keys = Arrays.asList(ROW, question, first, second, embed,
                key(OTHER_ROW, "answer:a", "same label"));
        for (int i = 0; i < keys.size(); i++) {
            String id = keys.get(i);
            StateStore.Entry entry = store.obtain(id, finalHash(question), "same label");
            entry.translatedText = "translation-" + i;
            entry.status = StateStore.Status.TRANSLATED;
        }
        store.peek(first).showingOriginal = true;
        for (int i = 0; i < keys.size(); i++) {
            StateStore.Entry entry = store.peek(keys.get(i));
            assertEquals(keys.get(i), entry.messageId);
            assertEquals("translation-" + i, entry.translatedText);
            assertEquals(StateStore.Status.TRANSLATED, entry.status);
            assertEquals(keys.get(i).equals(first), entry.showingOriginal);
            if (i > 0) assertNotSame(store.peek(keys.get(i - 1)), entry);
        }
    }

    @Test
    public void editedTextChangesOnlyThatSlotsKeyAndDoesNotMutateExistingState() {
        String oldQuestion = key(ROW, "question", "original question");
        String oldAnswer = key(ROW, "answer:a", "original answer");
        String newQuestion = key(ROW, "question", "edited question");
        String newAnswer = key(ROW, "answer:a", "edited answer");
        assertNotEquals(oldQuestion, newQuestion);
        assertNotEquals(oldAnswer, newAnswer);
        assertNotEquals(finalHash(oldQuestion), finalHash(newQuestion));
        assertNotEquals(finalHash(oldAnswer), finalHash(newAnswer));
        StateStore.Entry old = store.obtain(oldAnswer, finalHash(oldAnswer), "original answer");
        old.status = StateStore.Status.TRANSLATED;
        old.translatedText = "旧答案译文";
        old.showingOriginal = true;
        old.lastError = "retained";
        assertEquals(newAnswer, key(ROW, "answer:a", "edited answer"));
        assertNull(store.peek(newAnswer));
        assertSame(old, store.peek(oldAnswer));
        assertEquals(finalHash(oldAnswer), old.contentHash);
        assertEquals("original answer", old.originalText);
        assertEquals("旧答案译文", old.translatedText);
        assertEquals(StateStore.Status.TRANSLATED, old.status);
        assertTrue(old.showingOriginal);
        assertEquals("retained", old.lastError);
    }

    @Test
    public void nullEmptyWhitespaceAndUnknownContentDoNotProduceStateKeys() {
        for (String slot : Arrays.asList("question", "answer:a")) {
            assertNull(bridge.pollStateKey(ROW, slot, null));
            assertNull(bridge.pollStateKey(ROW, slot, new Object()));
            assertNull(key(ROW, slot, ""));
            assertNull(key(ROW, slot, " \n\t\r "));
        }
    }

    @Test
    public void invalidSlotOrMessageIdentityRejectsBeforeContentExtraction() {
        for (String slot : Arrays.asList(null, "", "answer:a\u0000b", "\u0000question")) {
            assertThrows(IllegalArgumentException.class, () -> key(ROW, slot, "label"));
            assertThrows(IllegalArgumentException.class, () -> bridge.pollStateKey(ROW, slot, null));
        }
        for (String row : Arrays.asList(null, "", "123", "not-a-message-id",
                "12345678901234567x", "1234567890123456789012", ROW + "\u0000")) {
            assertThrows(IllegalArgumentException.class, () -> key(row, "question", "label"));
            assertThrows(IllegalArgumentException.class, () -> key(row, "answer:a", "label"));
            assertThrows(IllegalArgumentException.class, () -> bridge.pollStateKey(row, "question", null));
        }
    }

    @Test
    public void cardHelpersRecognizePollAndEmbedAndRecoverTheirContainingRow() {
        for (String card : Arrays.asList(key(ROW, "question", "question"),
                key(ROW, "answer:a", "answer"), bridge.embedStateKey(ROW, text("embed")))) {
            assertTrue(HookBridge.isCardKey(card));
            assertEquals(ROW, HookBridge.cardRowOf(card));
        }
        assertEquals(OTHER_ROW, HookBridge.cardRowOf(key(OTHER_ROW, "answer:a", "answer")));
        assertFalse(HookBridge.isCardKey(ROW));
        assertFalse(HookBridge.isCardKey("ordinary body key"));
        assertFalse(HookBridge.isCardKey(null));
        assertEquals(ROW, HookBridge.cardRowOf(ROW));
        assertNull(HookBridge.cardRowOf(null));
    }

    @Test
    public void stillBoundAllowsSameRowCardSegmentsButNotOtherRowsOrBodyViews() {
        List<String> sameRow = Arrays.asList(key(ROW, "question", "question"),
                key(ROW, "answer:a", "answer"), key(ROW, "answer:b", "same answer label"),
                bridge.embedStateKey(ROW, text("embed")));
        List<String> otherRow = Arrays.asList(key(OTHER_ROW, "question", "question"),
                key(OTHER_ROW, "answer:a", "answer"), bridge.embedStateKey(OTHER_ROW, text("embed")));
        for (String expected : sameRow) {
            for (String actual : sameRow) assertTrue(RerenderRegistry.stillBound(expected, actual));
            for (String actual : otherRow) {
                assertFalse(RerenderRegistry.stillBound(expected, actual));
                assertFalse(RerenderRegistry.stillBound(actual, expected));
            }
            assertFalse(RerenderRegistry.stillBound(expected, ROW));
            assertFalse(RerenderRegistry.stillBound(ROW, expected));
        }
        assertTrue(RerenderRegistry.stillBound(ROW, ROW));
        assertFalse(RerenderRegistry.stillBound(ROW, OTHER_ROW));
    }

    @Test
    public void iconScopeAdmitsOnlyItsCardPrimaryOnceAndRestoresNestedScope() {
        String question = key(ROW, "question", "same label");
        String answer = key(ROW, "answer:a", "same label");
        String otherAnswer = key(ROW, "answer:b", "same label");
        String embed = bridge.embedStateKey(ROW, text("same label"));
        String otherPoll = key(OTHER_ROW, "question", "same label");
        assertNull(InlineIconInjector.currentHost());
        try (InlineIconInjector.HostScope poll = InlineIconInjector.enterHost(null, question)) {
            assertSame(poll, InlineIconInjector.currentHost());
            assertTrue(poll.allows(question));
            for (String other : Arrays.asList(answer, otherAnswer, embed, otherPoll, null)) {
                assertFalse(poll.allows(other));
            }
            try (InlineIconInjector.HostScope nested = InlineIconInjector.enterHost(null, answer)) {
                assertSame(nested, InlineIconInjector.currentHost());
                assertTrue(nested.allows(answer));
                assertFalse(nested.allows(question));
                assertFalse(nested.allows(otherAnswer));
                assertFalse(nested.allows(embed));
                nested.recordInjection(answer);
                assertFalse(nested.allows(answer));
            }
            assertSame(poll, InlineIconInjector.currentHost());
            assertTrue(poll.allows(question));
            poll.recordInjection(question);
            assertFalse(poll.allows(question));
        }
        assertNull(InlineIconInjector.currentHost());
        try (InlineIconInjector.HostScope nextCard = InlineIconInjector.enterHost(null, otherPoll)) {
            assertTrue(nextCard.allows(otherPoll));
            assertFalse(nextCard.allows(question));
            nextCard.recordInjection(otherPoll);
            assertFalse(nextCard.allows(otherPoll));
        }
        // Injection markers are synchronous scope state, not a global per-key blacklist.
        try (InlineIconInjector.HostScope rebound = InlineIconInjector.enterHost(null, question)) {
            assertTrue(rebound.allows(question));
        }
        try (InlineIconInjector.HostScope body = InlineIconInjector.enterHost(null, null)) {
            assertTrue(body.allows(ROW));
            for (String card : Arrays.asList(question, answer, embed, otherPoll)) {
                assertFalse(body.allows(card));
            }
        }
        assertNull(InlineIconInjector.currentHost());
    }
}
