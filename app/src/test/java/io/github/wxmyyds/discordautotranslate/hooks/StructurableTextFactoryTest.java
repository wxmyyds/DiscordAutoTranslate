package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.github.wxmyyds.discordautotranslate.core.Config;
import io.github.wxmyyds.discordautotranslate.core.JsonPath;
import io.github.wxmyyds.discordautotranslate.core.Protocols;
import io.github.wxmyyds.discordautotranslate.core.TextExtractor;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.AnnotatedStructurableText;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.EmojiContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.NoCopyContainer;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.PrimitiveStructurableText;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.TextContentNode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 工厂只构造原始节点类型；替换失败时调用方保留原始对象。 */
public class StructurableTextFactoryTest {

    @Before
    public void installFakeHostConstructors() throws Exception {
        ContentNodeTestProfile.activate(ContentNodesTest.class, OpaqueNodeTranslationTest.class);
        StructurableTextFactory.reset();
        setConstructor("sAnnotatedCtor", AnnotatedStructurableText.class.getConstructor(List.class));
        setConstructor("sPrimitiveCtor", PrimitiveStructurableText.class.getConstructor(String.class));
    }

    @After
    public void clearFakeHostConstructors() {
        StructurableTextFactory.reset();
        ContentNodeTestProfile.clear();
    }

    private static void setConstructor(String name, Constructor<?> constructor) throws Exception {
        Field field = StructurableTextFactory.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, constructor);
    }

    private static List<Object> list(Object... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    @Test
    public void replyBuildsFromCanonicalSourceAndLeavesTruncationToDiscord() {
        HookBridge bridge = HookBridge.get();
        io.github.wxmyyds.discordautotranslate.core.StateStore store =
                io.github.wxmyyds.discordautotranslate.core.StateStore.get();
        String id = "123456789012345678";
        bridge.reset();
        store.onNewGeneration();
        try {
            PrimitiveStructurableText original = new PrimitiveStructurableText("line one\nline two");
            AnnotatedStructurableText projected = new AnnotatedStructurableText(
                    list(new TextContentNode("line one line two")));
            String key = bridge.embedStateKey(id, original);
            String hash = key.substring((id + "\u0000embed\u0000").length());
            io.github.wxmyyds.discordautotranslate.core.StateStore.Entry entry =
                    store.obtain(id, hash, TextExtractor.extract(original).text);
            String translated = "译文第一行\n" + "完整译文".repeat(100);
            entry.translatedText = translated;
            entry.status = io.github.wxmyyds.discordautotranslate.core.StateStore.Status.TRANSLATED;
            bridge.rememberMessageRevision(id, null);
            bridge.rememberCanonicalContent(id, original);
            assertNull(bridge.existingReferencedMessageState(id, projected));
            HookBridge.ReferencedTranslation reused = bridge.referencedTranslation(id, null);
            assertNotNull(reused);
            Object replacement = StructurableTextFactory.forTranslation(
                    reused.originalContent, reused.state.translatedText);
            assertTrue(replacement instanceof PrimitiveStructurableText);
            assertEquals(translated, TextExtractor.extract(replacement).text);
            // Full content goes to native B0/TextView; this layer never adds an ellipsis.
            assertTrue(TextExtractor.extract(replacement).text.length() > 250);
            StringBuilder rendered = new StringBuilder(TextExtractor.extract(replacement).text);
            ReplyTextProjection.flattenLineBreaks(rendered,
                    index -> rendered.setCharAt(index, ' '));
            assertEquals(translated.replace('\n', ' '), rendered.toString());
            assertEquals(translated.length(), rendered.length());
            assertEquals(translated, entry.translatedText);
            assertEquals("line one\nline two", TextExtractor.extract(original).text);
            assertEquals("line one line two", TextExtractor.extract(projected).text);
        } finally {
            bridge.reset();
            store.onNewGeneration();
        }
    }

    @Test
    public void replyProjectionRunsAfterRichTextLineAlignmentNotBeforeIt() {
        EmojiContentNode emoji = new EmojiContentNode("1");
        ContentNodesTest.NewLineContentNode newline = new ContentNodesTest.NewLineContentNode();
        AnnotatedStructurableText original = new AnnotatedStructurableText(list(
                new TextContentNode("first"), newline, new TextContentNode("second"), emoji));
        Object replacement = StructurableTextFactory.forTranslation(original, "第一\n第二");
        assertNotNull(replacement);
        List<?> nodes = ((AnnotatedStructurableText) replacement).getContent();
        assertSame(newline, nodes.get(1));
        assertSame(emoji, nodes.get(3));
        assertEquals("第一", ((TextContentNode) nodes.get(0)).getContent());
        assertEquals("第二", ((TextContentNode) nodes.get(2)).getContent());
        StringBuilder rendered = new StringBuilder("第一\n第二\ufffc");
        assertEquals(1, ReplyTextProjection.flattenLineBreaks(rendered,
                index -> rendered.setCharAt(index, ' ')));
        assertEquals("第一 第二\ufffc", rendered.toString());
        assertEquals("first", ((TextContentNode) original.getContent().get(0)).getContent());
    }

    @Test
    public void failedRichTextReplacementLeavesOriginalUntouched() {
        TextContentNode text = new TextContentNode("hello");
        EmojiContentNode emoji = new EmojiContentNode("1");
        List<Object> nodes = list(new NoCopyContainer.ParagraphContentNode(
                list(new TextContentNode("first"))), text, emoji);
        AnnotatedStructurableText original = new AnnotatedStructurableText(nodes);

        // 返回 null 是 forTranslation 的“不替换”协议，所有 Hook 调用方保留 original。
        assertNull(StructurableTextFactory.forTranslation(original, "第一\n你好"));
        assertSame(nodes, original.getContent());
        assertSame(emoji, original.getContent().get(2));
        assertEquals("hello", text.getContent());
    }

    @Test
    public void missingAnnotatedConstructorDoesNotDiscardEmoji() throws Exception {
        setConstructor("sAnnotatedCtor", null);
        EmojiContentNode emoji = new EmojiContentNode("1");
        AnnotatedStructurableText original = new AnnotatedStructurableText(
                list(new TextContentNode("hello"), emoji));

        assertNull(StructurableTextFactory.forTranslation(original, "你好"));
        assertSame(emoji, original.getContent().get(1));
    }

    @Test
    public void emojiOnlyStructureIsNotOverwrittenByUnrelatedTranslation() {
        EmojiContentNode emoji = new EmojiContentNode("1");
        AnnotatedStructurableText original = new AnnotatedStructurableText(list(emoji));

        assertNull(StructurableTextFactory.forTranslation(original, "你好"));
        assertSame(emoji, original.getContent().get(0));
    }

    @Test
    public void successfulRichTextReplacementKeepsTextAndEmoji() {
        TextContentNode shared = new TextContentNode("hello");
        EmojiContentNode emoji = new EmojiContentNode("1");
        AnnotatedStructurableText original = new AnnotatedStructurableText(
                list(shared, emoji, new ContentNodesTest.NewLineContentNode(), shared));

        Object out = StructurableTextFactory.forTranslation(original,
                "你好\n你好");

        assertTrue(out instanceof AnnotatedStructurableText);
        List<Object> nodes = ((AnnotatedStructurableText) out).getContent();
        // 共享实例在两个位置各拿一个片段，emoji 原样保留。
        assertEquals(4, nodes.size());
        assertEquals("你好", ((TextContentNode) nodes.get(0)).getContent());
        assertSame(emoji, nodes.get(1));
        assertEquals("你好", ((TextContentNode) nodes.get(3)).getContent());
        assertEquals("hello", shared.getContent());
    }

    @Test
    public void mentionBodyAndEmojiProduceTranslatedRichText() {
        OpaqueNodeTranslationTest.UserOrRoleMentionContentNode mention =
                new OpaqueNodeTranslationTest.UserOrRoleMentionContentNode(
                        list(new TextContentNode("Alice")));
        EmojiContentNode emoji = new EmojiContentNode("1");
        AnnotatedStructurableText original = new AnnotatedStructurableText(
                list(mention, new TextContentNode("hello world"), emoji));

        Object out = StructurableTextFactory.forTranslation(original, "你好世界");

        assertTrue("有提及时也必须产出译文，不能只靠保留英文通过测试",
                out instanceof AnnotatedStructurableText);
        List<Object> nodes = ((AnnotatedStructurableText) out).getContent();
        assertSame(mention, nodes.get(0));
        assertEquals("你好世界", ((TextContentNode) nodes.get(1)).getContent());
        assertSame(emoji, nodes.get(2));
    }

    @Test
    public void mentionIsNotSentToModelAndIsRetainedExactlyOnce() throws Exception {
        String body = " recommend me some design system skills bro";
        OpaqueNodeTranslationTest.UserOrRoleMentionContentNode mention =
                new OpaqueNodeTranslationTest.UserOrRoleMentionContentNode(
                        list(new TextContentNode("ExampleUser")));
        AnnotatedStructurableText original = new AnnotatedStructurableText(
                list(mention, new TextContentNode(body)));
        TextExtractor.Result extracted = TextExtractor.extract(original);
        assertNotNull(extracted);
        String request = Protocols.requestBody(Config.PROTOCOL_OPENAI_COMPATIBLE,
                extracted.text, Config.DEF_SYSTEM_PROMPT, Config.DEF_MODEL,
                Config.DEF_STREAMING);
        assertEquals(Protocols.WRAPPER_OPEN + " " + body + Protocols.WRAPPER_CLOSE,
                JsonPath.extractString(request, "messages.1.content"));

        // 模拟正常接口译文，仅验证本地回填；不声称测试了真实模型输出。
        String translation = "兄弟，给我推荐一些设计系统技能";
        Object out = StructurableTextFactory.forTranslation(original, translation);

        assertTrue(out instanceof AnnotatedStructurableText);
        List<Object> nodes = ((AnnotatedStructurableText) out).getContent();
        assertEquals(2, nodes.size());
        assertSame(mention, nodes.get(0));
        // body 以空格开头：该前导空白属于原节点结构，留在标记外，重建时从原节点取，
        // 因此译文前仍带一个空格。正文本身不得丢失。
        assertEquals(" " + translation, ((TextContentNode) nodes.get(1)).getContent());
        assertEquals("ExampleUser", ((TextContentNode) mention.getContent().get(0)).getContent());
    }

    @Test
    public void literalMentionWordInTranslationIsNotGloballyRemoved() {
        String translation = "@提及 是这里讨论的字面文本，不是新增提及";
        AnnotatedStructurableText original = new AnnotatedStructurableText(
                list(new TextContentNode("Explain the literal mention marker")));

        Object out = StructurableTextFactory.forTranslation(original, translation);

        assertTrue(out instanceof AnnotatedStructurableText);
        assertEquals(translation, ((TextContentNode) ((AnnotatedStructurableText) out)
                .getContent().get(0)).getContent());
    }

    @Test
    public void primitiveTextStillTranslatesNormally() {
        PrimitiveStructurableText original = new PrimitiveStructurableText("hello");

        Object out = StructurableTextFactory.forTranslation(original, "你好");

        assertNotNull(out);
        assertTrue(out instanceof PrimitiveStructurableText);
        assertEquals("你好", ((PrimitiveStructurableText) out).getContent());
        assertEquals("hello", original.getContent());
    }
}
