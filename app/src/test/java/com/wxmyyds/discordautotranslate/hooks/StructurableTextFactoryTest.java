package com.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

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
        List<Object> nodes = list(new NoCopyContainer(list(new TextContentNode(""))), text, emoji);
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
    public void primitiveTextStillTranslatesNormally() {
        PrimitiveStructurableText original = new PrimitiveStructurableText("hello");

        Object out = StructurableTextFactory.forTranslation(original, "你好");

        assertNotNull(out);
        assertTrue(out instanceof PrimitiveStructurableText);
        assertEquals("你好", ((PrimitiveStructurableText) out).getContent());
        assertEquals("hello", original.getContent());
    }
}
