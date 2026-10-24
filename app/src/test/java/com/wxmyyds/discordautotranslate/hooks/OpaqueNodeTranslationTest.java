package com.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.wxmyyds.discordautotranslate.core.EnglishDetector;
import com.wxmyyds.discordautotranslate.core.Scheduler;
import com.wxmyyds.discordautotranslate.core.TextExtractor;
import com.wxmyyds.discordautotranslate.hooks.ContentNodesTest.AnnotatedStructurableText;
import com.wxmyyds.discordautotranslate.hooks.ContentNodesTest.BulletListContentNode;
import com.wxmyyds.discordautotranslate.hooks.ContentNodesTest.EmojiContentNode;
import com.wxmyyds.discordautotranslate.hooks.ContentNodesTest.ParagraphContentNode;
import com.wxmyyds.discordautotranslate.hooks.ContentNodesTest.StrongContentNode;
import com.wxmyyds.discordautotranslate.hooks.ContentNodesTest.TextContentNode;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 提及/频道虽有 content 子列表，也不是正文的译文位置。 */
public class OpaqueNodeTranslationTest {

    // 实际宿主的这些类型使用 copy-<suffix>；这里不提供名为 copy 的方法，
    // 模拟旧 findCopyMethod 无法处理的情况。正确实现根本不应尝试复制它们。
    public static final class UserOrRoleMentionContentNode {
        private final List<Object> content;
        public UserOrRoleMentionContentNode(List<Object> content) { this.content = content; }
        public List<Object> getContent() { return content; }
    }

    public static final class StaticChannelMentionContentNode {
        private final List<Object> content;
        public StaticChannelMentionContentNode(List<Object> content) { this.content = content; }
        public List<Object> getContent() { return content; }
    }

    public static final class CommandMentionContentNode {
        private final List<Object> content;
        public CommandMentionContentNode(List<Object> content) { this.content = content; }
        public List<Object> getContent() { return content; }
    }

    // 即使复制能力可用，也不能把译文写进频道名称。
    public static final class ChannelMentionContentNode {
        private final List<Object> content;
        public ChannelMentionContentNode(List<Object> content) { this.content = content; }
        public List<Object> getContent() { return content; }
        public ChannelMentionContentNode copy(List<Object> content) {
            return new ChannelMentionContentNode(content);
        }
    }

    public static final class ChannelNameContentNode {
        private final List<Object> content;
        public ChannelNameContentNode(List<Object> content) { this.content = content; }
        public List<Object> getContent() { return content; }
        public ChannelNameContentNode copy(List<Object> content) {
            return new ChannelNameContentNode(content);
        }
    }

    public static final class FutureOpaqueContentNode {
        private final List<Object> content;
        public FutureOpaqueContentNode(List<Object> content) { this.content = content; }
        public List<Object> getContent() { return content; }
    }

    public static final class TimestampContentNode {
    }

    private static List<Object> list(Object... nodes) {
        return new ArrayList<>(Arrays.asList(nodes));
    }

    private static List<Object> opaqueNodes(List<Object> children) {
        return list(new UserOrRoleMentionContentNode(children),
                new StaticChannelMentionContentNode(children),
                new CommandMentionContentNode(children),
                new ChannelMentionContentNode(children),
                new ChannelNameContentNode(children),
                new FutureOpaqueContentNode(children));
    }

    @Test
    public void extractionAndReplacementBothExcludeOpaqueDisplayNames() {
        TextContentNode displayName = new TextContentNode("AliceDisplayName");
        for (Object opaque : opaqueNodes(list(displayName))) {
            TextContentNode body = new TextContentNode("hello world");
            EmojiContentNode emoji = new EmojiContentNode("1");
            List<Object> nodes = list(opaque, body, emoji);
            TextExtractor.Result extracted = TextExtractor.extract(new AnnotatedStructurableText(nodes));

            assertNotNull(extracted);
            assertEquals(1, extracted.textNodeCount);
            assertEquals(" hello world ", extracted.text);
            assertEquals(2, extracted.opaqueNodeCount);
            assertFalse(extracted.text.contains("AliceDisplayName"));
            List<Object> out = ContentNodes.replaceText(nodes, "你好世界");

            assertNotNull("正文应当翻译，而不是整条回退为英文：" + opaque.getClass().getSimpleName(), out);
            assertSame(opaque, out.get(0));
            assertEquals("你好世界", ((TextContentNode) out.get(1)).getContent());
            assertSame(emoji, out.get(2));
            assertEquals("AliceDisplayName", displayName.getContent());
            assertEquals("hello world", body.getContent());
        }
    }

    @Test
    public void mentionBetweenBodyPartsDoesNotConsumeMultilineTranslation() {
        UserOrRoleMentionContentNode mention = new UserOrRoleMentionContentNode(
                list(new TextContentNode("Alice")));
        List<Object> out = ContentNodes.replaceText(list(new TextContentNode("hello"),
                mention, new TextContentNode("welcome")), "<s1>你好</s1><s2>欢迎</s2>");

        assertNotNull(out);
        assertEquals("你好", ((TextContentNode) out.get(0)).getContent());
        assertSame(mention, out.get(1));
        assertEquals("欢迎", ((TextContentNode) out.get(2)).getContent());
    }

    @Test
    public void nestedStyledBodyTranslatesWithoutTouchingMention() {
        UserOrRoleMentionContentNode mention = new UserOrRoleMentionContentNode(
                list(new TextContentNode("Alice")));
        EmojiContentNode emoji = new EmojiContentNode("1");
        ParagraphContentNode original = new ParagraphContentNode(list(mention,
                new StrongContentNode(list(new TextContentNode("hello"))), emoji));

        List<Object> out = ContentNodes.replaceText(list(original), "你好");

        assertNotNull(out);
        ParagraphContentNode paragraph = (ParagraphContentNode) out.get(0);
        assertSame(mention, paragraph.getContent().get(0));
        StrongContentNode strong = (StrongContentNode) paragraph.getContent().get(1);
        assertEquals("你好", ((TextContentNode) strong.getContent().get(0)).getContent());
        assertSame(emoji, paragraph.getContent().get(2));
    }

    @Test
    public void listItemWithChannelMentionStillTranslates() {
        ChannelMentionContentNode channel = new ChannelMentionContentNode(
                list(new TextContentNode("general")));
        List<List<Object>> items = new ArrayList<>();
        items.add(list(channel, new TextContentNode("read the guide")));
        BulletListContentNode original = new BulletListContentNode(null, false, items);

        List<Object> out = ContentNodes.replaceText(list(original), "阅读指南");

        assertNotNull(out);
        BulletListContentNode bullets = (BulletListContentNode) out.get(0);
        assertSame(channel, bullets.getItems().get(0).get(0));
        assertEquals("阅读指南", ((TextContentNode) bullets.getItems().get(0).get(1)).getContent());
    }

    @Test
    public void mentionOnlyContentHasNoTranslationTarget() {
        for (Object opaque : opaqueNodes(list(new TextContentNode("AliceDisplayName")))) {
            List<Object> nodes = list(opaque, new EmojiContentNode("1"));
            TextExtractor.Result extracted = TextExtractor.extract(new AnnotatedStructurableText(nodes));
            assertNotNull(extracted);
            assertEquals(0, extracted.textNodeCount);
            assertTrue(extracted.text.trim().isEmpty());
            assertFalse(EnglishDetector.evaluate(extracted.text, extracted.textNodeCount,
                    extracted.opaqueNodeCount, extracted.codeHeavy).shouldTranslate);
            assertNull(ContentNodes.replaceText(nodes, "不应写入名称"));
        }
    }

    @Test
    public void sharedNodeInsideMentionAndBodyOnlyReplacesBodyOccurrence() {
        TextContentNode shared = new TextContentNode("hello");
        UserOrRoleMentionContentNode mention = new UserOrRoleMentionContentNode(list(shared));

        List<Object> out = ContentNodes.replaceText(list(mention, shared), "你好");

        assertNotNull(out);
        assertSame(mention, out.get(0));
        assertSame(shared, mention.getContent().get(0));
        assertEquals("hello", shared.getContent());
        assertEquals("你好", ((TextContentNode) out.get(1)).getContent());
    }

    @Test
    public void preservedStructuresContributeOnlyWordSeparators() {
        List<Object> structures = opaqueNodes(list(new TextContentNode("DisplayName")));
        structures.add(new EmojiContentNode("1"));
        structures.add(new TimestampContentNode());
        for (Object structure : structures) {
            TextExtractor.Result result = TextExtractor.extract(new AnnotatedStructurableText(
                    list(new TextContentNode("hello"), structure, new TextContentNode("world"))));

            assertNotNull(result);
            assertEquals("hello world", result.text);
            assertEquals(2, result.textNodeCount);
            assertEquals(1, result.opaqueNodeCount);
            assertTrue(EnglishDetector.evaluate(result.text, result.textNodeCount,
                    result.opaqueNodeCount, result.codeHeavy).shouldTranslate);
        }
    }

    @Test
    public void removedMentionPlaceholderChangesCacheKey() {
        String body = " recommend me some design system skills bro";
        UserOrRoleMentionContentNode mention = new UserOrRoleMentionContentNode(
                list(new TextContentNode("ExampleUser")));
        TextExtractor.Result result = TextExtractor.extract(new AnnotatedStructurableText(
                list(mention, new TextContentNode(body))));

        assertNotNull(result);
        assertEquals(" " + body, result.text);
        assertNotEquals("旧的占位词译文不能继续命中缓存",
                Scheduler.contentHash(" @mention " + body, "same-config"),
                Scheduler.contentHash(result.text, "same-config"));
    }

    @Test
    public void realTextContainingMarkerWordsIsNotStripped() {
        String literal = "Explain @mention, #channel, :emoji:, [time] and @提及 literally.";
        TextExtractor.Result result = TextExtractor.extract(new AnnotatedStructurableText(
                list(new TextContentNode(literal))));

        assertNotNull(result);
        assertEquals(literal, result.text);
        assertEquals(1, result.textNodeCount);
        assertEquals(0, result.opaqueNodeCount);
        TextExtractor.Result primitive = TextExtractor.extract(
                new ContentNodesTest.PrimitiveStructurableText(literal));
        assertNotNull(primitive);
        assertEquals(literal, primitive.text);
    }

    @Test
    public void structureMarkersCannotMakeShortNonEnglishTextTranslatable() {
        TextExtractor.Result result = TextExtractor.extract(new AnnotatedStructurableText(
                list(new UserOrRoleMentionContentNode(list(new TextContentNode("Someone"))),
                        new TextContentNode("ok"), new EmojiContentNode("1"))));

        assertNotNull(result);
        assertEquals(" ok ", result.text);
        assertFalse(EnglishDetector.evaluate(result.text, result.textNodeCount,
                result.opaqueNodeCount, result.codeHeavy).shouldTranslate);
    }

    @Test
    public void nullTextContentDoesNotConsumeTranslationPart() {
        TextContentNode absent = new TextContentNode(null);
        List<Object> nodes = list(absent, new TextContentNode("hello"));
        TextExtractor.Result extracted = TextExtractor.extract(new AnnotatedStructurableText(nodes));
        assertNotNull(extracted);
        assertEquals(1, extracted.textNodeCount);

        List<Object> out = ContentNodes.replaceText(nodes, "你好");

        assertNotNull(out);
        assertSame(absent, out.get(0));
        assertEquals("你好", ((TextContentNode) out.get(1)).getContent());
    }
}
