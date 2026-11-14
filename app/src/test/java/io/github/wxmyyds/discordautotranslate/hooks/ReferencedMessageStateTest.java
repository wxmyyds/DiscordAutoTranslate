package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.*;

import io.github.wxmyyds.discordautotranslate.core.StateStore;
import io.github.wxmyyds.discordautotranslate.core.TextExtractor;
import io.github.wxmyyds.discordautotranslate.core.TranslationCache;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

/** Reply consumers must read canonical state without creating requests or preview state. */
public class ReferencedMessageStateTest {
    private static final String ID = "123456789012345678";
    private HookBridge bridge;

    @Before
    public void setup() {
        ContentNodeTestProfile.activate(ContentNodesTest.class);
        StateStore.get().onNewGeneration();
        TranslationCache.get().clear();
        bridge = HookBridge.get();
        bridge.reset();
    }

    @After
    public void cleanup() {
        StateStore.get().onNewGeneration();
        TranslationCache.get().clear();
        bridge.reset();
        ContentNodeTestProfile.clear();
    }

    private StateStore.Entry canonical(Object body) {
        String key = bridge.embedStateKey(ID, body);
        String hash = key.substring((ID + "\u0000embed\u0000").length());
        TextExtractor.Result extracted = TextExtractor.extract(body);
        return StateStore.get().obtain(ID, hash, extracted.text);
    }

    @Test
    public void missingStateIsNotCreatedEvenIfContentCacheExists() {
        Object body = new ContentNodesTest.PrimitiveStructurableText("Hello world");
        String key = bridge.embedStateKey(ID, body);
        TranslationCache.get().put(key.substring((ID + "\u0000embed\u0000").length()), "你好");
        assertNull(bridge.existingReferencedMessageState(ID, body));
        assertNull(StateStore.get().peek(ID));
    }

    @Test
    public void omittedModelBodyReadsTheStateByReferencedIdNotDisplayedPreview() {
        Object body = new ContentNodesTest.PrimitiveStructurableText("Full original message");
        StateStore.Entry entry = canonical(body);
        entry.translatedText = "完整译文";
        entry.status = StateStore.Status.TRANSLATED;
        assertSame(entry, bridge.existingReferencedMessageState(ID, null));
        assertEquals("完整译文", entry.translatedText);
        assertNull(bridge.existingReferencedMessageState("987654321098765432", null));
        assertNull(bridge.existingReferencedMessageState(null, body));
    }

    @Test
    public void richTextUsesTheSameSegmentHashAsTheCanonicalBody() {
        Object body = new ContentNodesTest.AnnotatedStructurableText(Arrays.asList(
                new ContentNodesTest.TextContentNode("Hello "),
                new ContentNodesTest.TextContentNode("world")));
        assertEquals(2, TextExtractor.extract(body).segmentCount);
        StateStore.Entry entry = canonical(body);
        assertSame(entry, bridge.existingReferencedMessageState(ID, body));
    }

    @Test
    public void editedBodyCannotConsumeAnOldTranslationOrResetItsState() {
        Object original = new ContentNodesTest.PrimitiveStructurableText("Original message");
        StateStore.Entry entry = canonical(original);
        entry.translatedText = "旧译文";
        entry.status = StateStore.Status.TRANSLATED;
        Object edited = new ContentNodesTest.PrimitiveStructurableText("Edited message");
        assertNull(bridge.existingReferencedMessageState(ID, edited));
        assertSame(entry, StateStore.get().peek(ID));
        assertEquals(StateStore.Status.TRANSLATED, entry.status);
        assertEquals("Original message", entry.originalText);
    }

    @Test
    public void projectedReplyUsesCanonicalContentNotItsDifferentText() {
        Object original = new ContentNodesTest.PrimitiveStructurableText("body\nwith attachment");
        Object projected = new ContentNodesTest.PrimitiveStructurableText("body with attachment");
        StateStore.Entry entry = canonical(original);
        entry.translatedText = "正文\n含附件";
        entry.status = StateStore.Status.TRANSLATED;
        bridge.rememberMessageRevision(ID, null);
        bridge.rememberCanonicalContent(ID, original);
        assertNull(bridge.existingReferencedMessageState(ID, projected));
        HookBridge.ReferencedTranslation reused = bridge.referencedTranslation(ID, null);
        assertNotNull(reused);
        assertSame(entry, reused.state);
        assertSame(original, reused.originalContent);
    }

    @Test
    public void editRevisionRejectsOldAndFutureReplyModels() {
        Object original = new ContentNodesTest.PrimitiveStructurableText("Body text");
        canonical(original);
        bridge.rememberMessageRevision(ID, "revision-1");
        bridge.rememberCanonicalContent(ID, original);
        assertNotNull(bridge.referencedTranslation(ID, "revision-1"));
        assertNull(bridge.referencedTranslation(ID, null));
        assertNull(bridge.referencedTranslation(ID, "revision-2"));
        bridge.rememberMessageRevision(ID, "revision-2");
        // New bind arrived before its body renderer: do not relabel the old body.
        assertNull(bridge.referencedTranslation(ID, "revision-1"));
        assertNull(bridge.referencedTranslation(ID, "revision-2"));
    }

    @Test
    public void missingRevisionAndStaleCanonicalHashDoNotBypassGuards() {
        Object original = new ContentNodesTest.PrimitiveStructurableText("Body text");
        StateStore.Entry entry = canonical(original);
        bridge.rememberCanonicalContent(ID, original);
        assertNull(bridge.referencedTranslation(ID, null));
        bridge.rememberMessageRevision(ID, null);
        bridge.rememberCanonicalContent(ID, original);
        assertNotNull(bridge.referencedTranslation(ID, null));
        entry.contentHash = "edited-hash";
        assertNull(bridge.referencedTranslation(ID, null));
        assertNull(bridge.referencedTranslation(null, null));
    }

    @Test
    public void canonicalProjectionReusesStatusWithoutCreatingOrOverridingIt() {
        Object original = new ContentNodesTest.PrimitiveStructurableText("Body text");
        bridge.rememberMessageRevision(ID, null);
        bridge.rememberCanonicalContent(ID, original);
        assertNull(bridge.referencedTranslation(ID, null));
        assertNull(StateStore.get().peek(ID));
        StateStore.Entry entry = canonical(original);
        for (StateStore.Status status : StateStore.Status.values()) {
            entry.status = status;
            entry.showingOriginal = true;
            HookBridge.ReferencedTranslation reused = bridge.referencedTranslation(ID, null);
            assertNotNull(reused);
            assertSame(entry, reused.state);
            assertSame(original, reused.originalContent);
            assertEquals(status, entry.status);
            assertTrue(entry.showingOriginal);
        }
    }

    @Test
    public void consumerDoesNotOverrideToggleOrFailedAndInFlightStatus() {
        Object body = new ContentNodesTest.PrimitiveStructurableText("Original message");
        StateStore.Entry entry = canonical(body);
        for (StateStore.Status status : StateStore.Status.values()) {
            entry.status = status;
            entry.showingOriginal = true;
            assertSame(entry, bridge.existingReferencedMessageState(ID, body));
            assertEquals(status, entry.status);
            assertTrue(entry.showingOriginal);
        }
    }
}
