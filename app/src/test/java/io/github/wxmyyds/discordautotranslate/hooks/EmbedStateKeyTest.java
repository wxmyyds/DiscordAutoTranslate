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
 * <p>富 Embed 绑定按每段 StructurableText 生成状态键，键由行消息 ID 与
 * 文本内容共同决定，保证内容相同的段使用一致的哈希口径。</p>
 */
public class EmbedStateKeyTest {

    @org.junit.Before
    public void activateFixtureProfile() { ContentNodeTestProfile.activate(ContentNodesTest.class); }

    @org.junit.After
    public void clearFixtureProfile() { ContentNodeTestProfile.clear(); }

    private static final String ROW = "123456789012345678";

    private static String keyFor(String row, String text) {
        return HookBridge.get().embedStateKey(row,
                new ContentNodesTest.PrimitiveStructurableText(text));
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
        assertNull(keyFor(ROW, "  "));
    }

    @Test
    public void embedKeyIsRecognizableAndCarriesRowId() {
        String key = keyFor(ROW, "Hello");
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
        String title = keyFor(ROW, "title text");
        String description = keyFor(ROW, "description text");
        String otherRow = keyFor("987654321098765432", "title text");
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
        String key = keyFor(ROW, "icon marker text");
        // 未标记的 Embed 键不允许注入（每张卡片只挂一个图标）。
        assertFalse(bridge.isEmbedIconKey(key));
        bridge.markEmbedIconKey(key);
        assertTrue(bridge.isEmbedIconKey(key));
        assertFalse(bridge.isEmbedIconKey(null));
    }
}
