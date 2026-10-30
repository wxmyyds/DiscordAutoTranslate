package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Embed 状态键的口径一致性。
 *
 * <p>登记侧（{@code EmbedViewHolder.bind}）对 StructurableText 用
 * {@link HookBridge#embedStateKey}；替换侧（{@code setPostPreviewEmbed}）对
 * 裸 String 用 {@link HookBridge#embedTextStateKey}。同一段纯文本两者必须产出
 * 同一个键，否则帖子预览的标题译文永远查不到。本测试锁定该不变量。</p>
 */
public class EmbedStateKeyTest {

    private static final String ROW = "123456789012345678";

    @Test
    public void structurableAndRawTextProduceSameKey() {
        Object text = new ContentNodesTest.PrimitiveStructurableText("Hello, world!");
        assertEquals(
                HookBridge.get().embedStateKey(ROW, text),
                HookBridge.get().embedTextStateKey(ROW, "Hello, world!"));
    }

    @Test
    public void keysAreStableAndDistinctPerContent() {
        Object a1 = new ContentNodesTest.PrimitiveStructurableText("first post");
        Object a2 = new ContentNodesTest.PrimitiveStructurableText("first post");
        Object b = new ContentNodesTest.PrimitiveStructurableText("second post");
        // 不同实例、相同内容 → 同键（译文按内容复用）。
        assertEquals(HookBridge.get().embedStateKey(ROW, a1), HookBridge.get().embedStateKey(ROW, a2));
        // 不同内容 → 不同键（标题/描述/字段互不覆盖）。
        assertNotEquals(HookBridge.get().embedStateKey(ROW, a1), HookBridge.get().embedStateKey(ROW, b));
        // 不同行 → 不同键（不同消息的同名文本互不覆盖）。
        assertNotEquals(HookBridge.get().embedStateKey(ROW, a1),
                HookBridge.get().embedStateKey("987654321098765432", a1));
    }

    @Test
    public void emptyTextYieldsNullKey() {
        assertNull(HookBridge.get().embedStateKey(ROW,
                new ContentNodesTest.PrimitiveStructurableText("")));
        assertNull(HookBridge.get().embedStateKey(ROW, null));
        assertNull(HookBridge.get().embedTextStateKey(ROW, "  "));
    }

    @Test
    public void embedKeyIsRecognizableAndCarriesRowId() {
        String key = HookBridge.get().embedTextStateKey(ROW, "Hello");
        assertTrue(HookBridge.isEmbedKey(key));
        assertFalse(HookBridge.isEmbedKey(ROW));
        assertFalse(HookBridge.isEmbedKey(null));
        // 行 ID 提取：复用校验按行比较，行必须可还原。
        assertEquals(ROW, HookBridge.embedRowOf(key));
        assertEquals(ROW, HookBridge.embedRowOf(ROW));
        assertNull(HookBridge.embedRowOf(null));
    }

    @Test
    public void sameRowEmbedKeysAreStillBoundWhileOtherRowsAreNot() {
        String title = HookBridge.get().embedTextStateKey(ROW, "title text");
        String description = HookBridge.get().embedTextStateKey(ROW, "description text");
        String otherRow = HookBridge.get().embedTextStateKey("987654321098765432", "title text");
        // 同一 Embed 视图按段登记多个键，视图绑定只留最后一个：同行必须互视为有效。
        assertTrue(RerenderRegistry.stillBound(title, description));
        assertTrue(RerenderRegistry.stillBound(description, title));
        // 行变了（视图复用到其他消息）：拒绝。
        assertFalse(RerenderRegistry.stillBound(title, otherRow));
        // Embed 键与普通正文键相邻也不能放行：正文视图与 Embed 视图是不同视图。
        assertFalse(RerenderRegistry.stillBound(title, ROW));
        assertFalse(RerenderRegistry.stillBound(ROW, title));
    }

    @Test
    public void iconKeyMarkingIsExplicitPerCard() {
        HookBridge bridge = HookBridge.get();
        String key = bridge.embedTextStateKey(ROW, "icon marker text");
        // 未标记的 Embed 键不允许注入（每张卡片只挂一个图标）。
        assertFalse(bridge.isEmbedIconKey(key));
        bridge.markEmbedIconKey(key);
        assertTrue(bridge.isEmbedIconKey(key));
        assertFalse(bridge.isEmbedIconKey(null));
    }

    @Test
    public void iconOnlyIdentityIsSeparateFromSubstitutionIdentity() {
        HookBridge bridge = HookBridge.get();
        String key = bridge.embedTextStateKey(ROW, "icon only probe");
        Object footer = new Object();
        assertNull(bridge.resolveIconOnlyKey(footer));
        bridge.rememberIconOnlyContent(key, footer);
        assertEquals(key, bridge.resolveIconOnlyKey(footer));
        // 仅图标映射不得泄漏进替换身份，否则译文会写进 footer。
        assertNull(bridge.resolveMessageId(footer));
        // null 容错。
        bridge.rememberIconOnlyContent(key, null);
        assertNull(bridge.resolveIconOnlyKey(null));
    }
}
