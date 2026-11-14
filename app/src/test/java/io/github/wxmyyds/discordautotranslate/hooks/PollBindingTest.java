package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Profile-selected fake native fields only; no Android views or host setters. */
public class PollBindingTest {
    private static final String ROW = "123456789012345678";
    private static final String OTHER_ROW = "987654321098765432";

    private static final class Label {
        private Object text;
        Label(Object text) { this.text = text; }
    }

    private static final class Message {
        private Object id;
        Message(Object id) { this.id = id; }
    }

    private static final class Poll {
        private final Label question;
        private final int totalVotes = 42;
        private final boolean multipleChoice = true;
        Poll(Object text) { question = new Label(text); }
    }

    private static final class Accessory {
        private final Message message;
        private final Poll poll;
        Accessory(Object id, Object text) {
            message = new Message(id);
            poll = new Poll(text);
        }
    }

    private static final class Sink {
        private final String nativeText;
        private final String contentDescription;
        Sink(String text) { nativeText = text; contentDescription = text; }
    }

    private static final class QuestionViews {
        private final Object root = new Object();
        private final Sink question = new Sink("native question");
        private final Sink voteCount = new Sink("42 votes");
        private final Sink prompt = new Sink("Select an answer");
    }

    private static class BaseQuestionHolder {
        private final QuestionViews binding = new QuestionViews();
    }

    private static final class QuestionHolder extends BaseQuestionHolder {}

    private static final class Answer {
        private Object id;
        private final Label label;
        private final int votes = 17;
        private final boolean selected = true;
        private final Object image = new Object();
        Answer(Object id, Object text) { this.id = id; label = new Label(text); }
    }

    private static final class AnswerItem {
        private final Message message;
        private final Answer answer;
        AnswerItem(Object row, Object id, Object text) {
            message = new Message(row);
            answer = new Answer(id, text);
        }
    }

    private static final class AdapterData {
        private Object items;
        AdapterData(Object items) { this.items = items; }
    }

    private static final class Adapter {
        private final AdapterData data;
        Adapter(Object items) { data = new AdapterData(items); }
    }

    private static final class TextAnswerBinding {
        private final Sink label = new Sink("native answer");
        private final Sink voteCount = new Sink("17 votes");
        private final Sink prompt = new Sink("Select an answer");
    }

    private static final class ImageAnswerBinding {
        private final Sink image = new Sink("native image description");
        private final Sink voteCount = new Sink("17 votes");
        private final Sink prompt = new Sink("Select an answer");
    }

    /** Has a readable label but is deliberately absent from the configured class map. */
    private static final class UnknownAnswerBinding {
        private final Sink label = new Sink("must not be guessed");
    }

    private static final class AnswerHolder {
        private final Object binding;
        AnswerHolder(Object binding) { this.binding = binding; }
    }

    private static JSONArray fields(String... names) {
        return new JSONArray(Arrays.asList(names));
    }

    private static JSONObject questionSpec() throws Exception {
        return new JSONObject().put("accessoryArgument", 1)
                .put("messageIdPath", fields("message", "id"))
                .put("textPath", fields("poll", "question", "text"))
                .put("hostPath", fields("binding", "root"))
                .put("textViewPath", fields("binding", "question"));
    }

    private static JSONObject answerSpec() throws Exception {
        return new JSONObject().put("itemsPath", fields("data", "items"))
                .put("positionArgument", 3).put("holderArgument", 1)
                .put("messageIdPath", fields("message", "id"))
                .put("answerIdPath", fields("answer", "id"))
                .put("textPath", fields("answer", "label", "text"))
                .put("holderBindingPath", fields("binding"))
                .put("textBindingClass", TextAnswerBinding.class.getName())
                .put("imageBindingClass", ImageAnswerBinding.class.getName())
                .put("textViewPath", fields("label"))
                .put("imageViewPath", fields("image"));
    }

    private static List<Object> questionArgs(Accessory accessory) {
        return Arrays.asList(new Accessory(OTHER_ROW, "decoy question"), accessory);
    }

    private static List<Object> answerArgs(AnswerHolder holder, Object position) {
        return Arrays.asList(null, holder, 999L, position);
    }

    private static PollBinding question(QuestionHolder holder, Accessory accessory) throws Exception {
        return PollBinding.question(holder, questionArgs(accessory), questionSpec());
    }

    private static PollBinding answer(Adapter adapter, AnswerHolder holder, Object position)
            throws Exception {
        return PollBinding.answer(adapter, answerArgs(holder, position), answerSpec());
    }

    @Test
    public void questionUsesOnlyConfiguredAccessoryAndInheritedHolderFields() throws Exception {
        String raw = new String("  Which option?\n😀  ");
        Accessory accessory = new Accessory(ROW, raw);
        QuestionHolder holder = new QuestionHolder();
        QuestionViews views = ((BaseQuestionHolder) holder).binding;
        PollBinding binding = question(holder, accessory);
        assertEquals(ROW, binding.messageId);
        assertEquals("question", binding.slot);
        assertSame(raw, binding.text);
        assertSame(views.root, binding.host);
        assertSame(views.question, binding.receiver);
        assertFalse(binding.accessibility);
        assertTrue(binding.matches(views.question, raw, false));
        assertTrue(binding.matches(views.question, new String(raw), false));
        assertFalse(binding.matches(views.question, raw.trim(), false));
        assertFalse(binding.matches(views.question, new StringBuilder(raw), false));
        assertFalse(binding.matches(views.question, raw, true));
        assertFalse(binding.matches(views.question, null, false));
        assertFalse(binding.matches(views.voteCount, raw, false));
        assertFalse(binding.matches(views.prompt, raw, false));
        assertFalse(binding.matches(views.root, raw, false));
        assertFalse(binding.matches(new Sink(raw), raw, false));
        assertFalse(binding.matches(views.question, views.voteCount.nativeText, false));
        assertFalse(binding.matches(views.question, views.prompt.nativeText, false));
        assertSame(ROW, accessory.message.id);
        assertSame(raw, accessory.poll.question.text);
        assertEquals(42, accessory.poll.totalVotes);
        assertTrue(accessory.poll.multipleChoice);
        assertEquals("native question", views.question.nativeText);
        assertEquals("native question", views.question.contentDescription);
    }

    @Test
    public void indexedTextAnswerPreservesModelIdentityVotesSelectionAndImage() throws Exception {
        String raw = new String("  Answer 😀\nfull label  ");
        String stableId = new String("stable-answer-id");
        AnswerItem decoy = new AnswerItem(OTHER_ROW, "decoy-id", "decoy text");
        AnswerItem item = new AnswerItem(ROW, stableId, raw);
        List<AnswerItem> items = new ArrayList<>(Arrays.asList(decoy, item));
        Adapter adapter = new Adapter(items);
        TextAnswerBinding views = new TextAnswerBinding();
        AnswerHolder holder = new AnswerHolder(views);
        Object image = item.answer.image;
        PollBinding binding = answer(adapter, holder, 1);
        assertEquals(ROW, binding.messageId);
        assertEquals("answer:stable-answer-id", binding.slot);
        assertSame(raw, binding.text);
        assertSame(views.label, binding.host);
        assertSame(views.label, binding.receiver);
        assertFalse(binding.accessibility);
        assertTrue(binding.matches(views.label, raw, false));
        assertFalse(binding.matches(views.label, raw, true));
        assertFalse(binding.matches(views.label, new StringBuilder(raw), false));
        assertFalse(binding.matches(views.label, "edited label", false));
        assertFalse(binding.matches(views.label, views.voteCount.nativeText, false));
        assertFalse(binding.matches(views.label, views.prompt.nativeText, false));
        assertFalse(binding.matches(views.voteCount, raw, false));
        assertFalse(binding.matches(views.prompt, raw, false));
        assertSame(items, adapter.data.items);
        assertEquals(Arrays.asList(decoy, item), items);
        assertSame(item, items.get(1));
        assertSame(ROW, item.message.id);
        assertSame(stableId, item.answer.id);
        assertSame(raw, item.answer.label.text);
        assertEquals(17, item.answer.votes);
        assertTrue(item.answer.selected);
        assertSame(image, item.answer.image);
        assertEquals("native answer", views.label.nativeText);
        assertEquals("17 votes", views.voteCount.nativeText);
    }

    @Test
    public void reorderedEqualLabelsCannotReplayAnotherStableAnswerIdentity() throws Exception {
        AnswerItem first = new AnswerItem(ROW, "a", "same label");
        AnswerItem second = new AnswerItem(ROW, "b", "same label");
        List<AnswerItem> items = new ArrayList<>(Arrays.asList(first, second));
        Adapter adapter = new Adapter(items);
        AnswerHolder holder = new AnswerHolder(new TextAnswerBinding());
        PollBinding captured = answer(adapter, holder, 0);
        assertTrue(captured.sameInput(answer(adapter, holder, 0)));
        Collections.swap(items, 0, 1);
        PollBinding replacement = answer(adapter, holder, 0);
        assertEquals(captured.text, replacement.text);
        assertSame(captured.receiver, replacement.receiver);
        assertNotEquals(captured.slot, replacement.slot);
        assertFalse(captured.sameInput(replacement));
        assertTrue(captured.sameInput(answer(adapter, holder, 1)));
        assertFalse(captured.sameInput(null));
        assertFalse(captured.sameInput(answer(adapter,
                new AnswerHolder(new TextAnswerBinding()), 1)));
        first.answer.label.text = "edited label";
        assertFalse(captured.sameInput(answer(adapter, holder, 1)));
        first.answer.label.text = "same label";
        first.message.id = OTHER_ROW;
        assertFalse(captured.sameInput(answer(adapter, holder, 1)));
    }

    @Test
    public void imageAnswerUsesOnlyConfiguredAccessibilityReceiver() throws Exception {
        String raw = "Image option description";
        AnswerItem item = new AnswerItem(ROW, "image-id", raw);
        Adapter adapter = new Adapter(Collections.singletonList(item));
        ImageAnswerBinding views = new ImageAnswerBinding();
        AnswerHolder holder = new AnswerHolder(views);
        PollBinding binding = answer(adapter, holder, 0);
        assertEquals("answer:image-id", binding.slot);
        assertSame(raw, binding.text);
        assertSame(views.image, binding.host);
        assertSame(views.image, binding.receiver);
        assertTrue(binding.accessibility);
        assertTrue(binding.matches(views.image, raw, true));
        assertTrue(binding.sameInput(answer(adapter, holder, 0)));
        assertFalse(binding.matches(views.image, raw, false));
        assertFalse(binding.matches(views.image, new StringBuilder(raw), true));
        assertFalse(binding.matches(views.image, "another description", true));
        assertFalse(binding.matches(views.voteCount, raw, true));
        assertFalse(binding.matches(views.prompt, raw, true));
        assertFalse(binding.matches(views.image, views.voteCount.nativeText, true));
        assertFalse(binding.sameInput(answer(adapter,
                new AnswerHolder(new TextAnswerBinding()), 0)));
        assertSame(raw, item.answer.label.text);
        assertEquals(17, item.answer.votes);
        assertTrue(item.answer.selected);
        assertEquals("native image description", views.image.contentDescription);
    }

    @Test
    public void nullAndEmptyLabelsAreCapturedVerbatimWithoutPlaceholders() throws Exception {
        for (String raw : Arrays.asList(null, "", "  ")) {
            PollBinding question = question(new QuestionHolder(), new Accessory(ROW, raw));
            assertSame(raw, question.text);
            assertTrue(question.matches(question.receiver, raw, false));
            assertFalse(question.matches(question.receiver, "Question", false));
            for (Object views : Arrays.asList(new TextAnswerBinding(), new ImageAnswerBinding())) {
                AnswerItem item = new AnswerItem(ROW, "a", raw);
                PollBinding answer = answer(new Adapter(Collections.singletonList(item)),
                        new AnswerHolder(views), 0);
                assertSame(raw, answer.text);
                assertTrue(answer.matches(answer.receiver, raw, answer.accessibility));
                assertFalse(answer.matches(answer.receiver, "Answer", answer.accessibility));
                assertFalse(answer.matches(answer.receiver, new StringBuilder(), answer.accessibility));
                assertSame(raw, item.answer.label.text);
                assertEquals("answer:a", answer.slot);
            }
        }
    }

    @Test
    public void receiverIsResolvableBeforeMalformedInputSoOldTargetsCanBeCleared() throws Exception {
        QuestionHolder question = new QuestionHolder();
        Object questionView = ((BaseQuestionHolder) question).binding.question;
        assertSame(questionView, PollBinding.receiver(question, Collections.emptyList(),
                questionSpec(), false));
        TextAnswerBinding views = new TextAnswerBinding();
        AnswerHolder holder = new AnswerHolder(views);
        Adapter malformed = new Adapter("not a list");
        assertSame(views.label, PollBinding.receiver(malformed, answerArgs(holder, -1),
                answerSpec(), true));
        assertThrows(IllegalStateException.class, () -> answer(malformed, holder, -1));
    }

    @Test
    public void positionsAndArgumentSignaturesRejectOutOfRangeAndWrongTypes() throws Exception {
        Adapter adapter = new Adapter(Collections.singletonList(new AnswerItem(ROW, "a", "label")));
        AnswerHolder holder = new AnswerHolder(new TextAnswerBinding());
        for (Object position : Arrays.asList(-1, 1, Integer.MAX_VALUE, 0L, "0", null)) {
            assertThrows(IllegalStateException.class, () -> answer(adapter, holder, position));
        }
        for (Object items : Arrays.asList(null, "not a list", new AnswerItem[0])) {
            assertThrows(IllegalStateException.class, () -> answer(new Adapter(items), holder, 0));
        }
        assertThrows(IllegalStateException.class, () -> PollBinding.answer(adapter,
                Collections.emptyList(), answerSpec()));
        assertThrows(IllegalStateException.class, () -> PollBinding.question(new QuestionHolder(),
                Collections.emptyList(), questionSpec()));
        JSONObject negativeArgument = answerSpec().put("holderArgument", -1);
        assertThrows(IllegalStateException.class, () -> PollBinding.answer(adapter,
                answerArgs(holder, 0), negativeArgument));
    }

    @Test
    public void configuredTextAndIdentityTypesAreNeverCoerced() throws Exception {
        for (Object invalid : Arrays.asList(123L, new StringBuilder("label"))) {
            assertThrows(IllegalStateException.class,
                    () -> question(new QuestionHolder(), new Accessory(invalid, "label")));
            assertThrows(IllegalStateException.class,
                    () -> question(new QuestionHolder(), new Accessory(ROW, invalid)));
            AnswerHolder holder = new AnswerHolder(new TextAnswerBinding());
            assertThrows(IllegalStateException.class, () -> answer(new Adapter(Collections.singletonList(
                    new AnswerItem(invalid, "a", "label"))), holder, 0));
            assertThrows(IllegalStateException.class, () -> answer(new Adapter(Collections.singletonList(
                    new AnswerItem(ROW, invalid, "label"))), holder, 0));
            assertThrows(IllegalStateException.class, () -> answer(new Adapter(Collections.singletonList(
                    new AnswerItem(ROW, "a", invalid))), holder, 0));
        }
        for (Object invalidId : Arrays.asList(null, "", "a\u0000b")) {
            assertThrows(IllegalStateException.class, () -> answer(new Adapter(Collections.singletonList(
                    new AnswerItem(ROW, invalidId, "label"))),
                    new AnswerHolder(new TextAnswerBinding()), 0));
        }
    }

    @Test
    public void missingConfiguredFieldsAndUnmappedBindingsRejectRatherThanGuess() throws Exception {
        QuestionHolder questionHolder = new QuestionHolder();
        Accessory accessory = new Accessory(ROW, "question");
        for (String configuredPath : Arrays.asList("messageIdPath", "textPath", "hostPath", "textViewPath")) {
            JSONObject spec = questionSpec().put(configuredPath, fields("missing"));
            assertThrows(IllegalStateException.class,
                    () -> PollBinding.question(questionHolder, questionArgs(accessory), spec));
        }
        Adapter adapter = new Adapter(Collections.singletonList(new AnswerItem(ROW, "a", "answer")));
        AnswerHolder textHolder = new AnswerHolder(new TextAnswerBinding());
        for (String configuredPath : Arrays.asList("itemsPath", "messageIdPath", "answerIdPath",
                "textPath", "holderBindingPath", "textViewPath")) {
            JSONObject spec = answerSpec().put(configuredPath, fields("missing"));
            assertThrows(IllegalStateException.class,
                    () -> PollBinding.answer(adapter, answerArgs(textHolder, 0), spec));
        }
        JSONObject imageSpec = answerSpec().put("imageViewPath", fields("missing"));
        assertThrows(IllegalStateException.class, () -> PollBinding.answer(adapter,
                answerArgs(new AnswerHolder(new ImageAnswerBinding()), 0), imageSpec));
        for (Object unknown : Arrays.asList(null, new UnknownAnswerBinding())) {
            assertThrows(IllegalStateException.class,
                    () -> answer(adapter, new AnswerHolder(unknown), 0));
        }
    }
}
