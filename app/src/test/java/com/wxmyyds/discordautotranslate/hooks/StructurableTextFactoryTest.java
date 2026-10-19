package com.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.wxmyyds.discordautotranslate.core.Config;
import com.wxmyyds.discordautotranslate.core.JsonPath;
import com.wxmyyds.discordautotranslate.core.JsonTemplate;
import com.wxmyyds.discordautotranslate.core.TextExtractor;
import com.wxmyyds.discordautotranslate.hooks.ContentNodesTest.AnnotatedStructurableText;
import com.wxmyyds.discordautotranslate.hooks.ContentNodesTest.EmojiContentNode;
import com.wxmyyds.discordautotranslate.hooks.ContentNodesTest.NoCopyContainer;
import com.wxmyyds.discordautotranslate.hooks.ContentNodesTest.PrimitiveStructurableText;
import com.wxmyyds.discordautotranslate.hooks.ContentNodesTest.TextContentNode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 工厂回退策略：富文本替换失败时必须让调用方保留原文，而不是丢弃结构。 */
public class StructurableTextFactoryTest {

    @Before
    public void installFakeHostConstructors() throws Exception {
        StructurableTextFactory.reset();
        setConstructor("sAnnotatedCtor", AnnotatedStructurableText.class.getConstructor(List.class));
        setConstructor("sPrimitiveCtor", PrimitiveStructurableText.class.getConstructor(String.class));
    }

    @After
    public void clearFakeHostConstructors() {
        StructurableTextFactory.reset();
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
    public void failedRichTextReplacementKeepsOriginalInsteadOfPrimitiveFallback() {
        TextContentNode text = new TextContentNode("hello");
        EmojiContentNode emoji = new EmojiContentNode("1");
        List<Object> nodes = list(new NoCopyContainer.ParagraphContentNode(
                list(new TextContentNode(""))), text, emoji);
        AnnotatedStructurableText original = new AnnotatedStructurableText(nodes);

        // 返回 null 是 forTranslation 的“不替换”协议，所有 Hook 调用方保留 original。
        assertNull(StructurableTextFactory.forTranslation(original, "你好"));
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
        AnnotatedStructurableText original = new AnnotatedStructurableText(list(shared, emoji, shared));

        Object out = StructurableTextFactory.forTranslation(original, "你好");

        assertTrue(out instanceof AnnotatedStructurableText);
        List<Object> nodes = ((AnnotatedStructurableText) out).getContent();
        assertEquals("你好", ((TextContentNode) nodes.get(0)).getContent());
        assertSame(emoji, nodes.get(1));
        assertEquals("", ((TextContentNode) nodes.get(2)).getContent());
        assertEquals("hello", shared.getContent());
    }

    @Test
    public void mentionBodyAndEmojiProduceTranslatedRichTextInsteadOfFallback() {
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
        JsonTemplate.RenderResult request = JsonTemplate.render(Config.DEF_BODY_TEMPLATE,
                extracted.text, Config.DEF_TARGET_LANG, Config.DEF_MODEL, Config.DEF_SYSTEM_PROMPT);
        assertEquals(" " + body, JsonPath.extractString(request.body, "messages.1.content"));

        // 模拟正常接口译文，仅验证本地回填；不声称测试了真实模型输出。
        String translation = "兄弟，给我推荐一些设计系统技能";
        Object out = StructurableTextFactory.forTranslation(original, translation);

        assertTrue(out instanceof AnnotatedStructurableText);
        List<Object> nodes = ((AnnotatedStructurableText) out).getContent();
        assertEquals(2, nodes.size());
        assertSame(mention, nodes.get(0));
        assertEquals(translation, ((TextContentNode) nodes.get(1)).getContent());
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
