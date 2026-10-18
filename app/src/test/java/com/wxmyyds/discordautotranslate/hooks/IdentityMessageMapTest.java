package com.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * {@link IdentityMessageMap} 的身份语义。
 *
 * <p>Discord 的 {@code StructurableText} 是 Kotlin data class，其 {@code equals()} 按内容比较。
 * 两条正文完全相同的消息会产生相等的键；若用普通 {@code HashMap}，后写入的映射会覆盖先写入的，
 * 译文就可能套用到错误的消息上。本测试锁定“按引用身份区分”这一行为。</p>
 */
public class IdentityMessageMapTest {

    /** 内容相等即 equals 相等，模拟 Kotlin data class。 */
    private static final class Content {
        private final String text;

        Content(String text) {
            this.text = text;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Content && text.equals(((Content) other).text);
        }

        @Override
        public int hashCode() {
            return text.hashCode();
        }
    }

    @Test
    public void sameContentDifferentObjectsAreDistinctKeys() {
        IdentityMessageMap map = new IdentityMessageMap();
        Content first = new Content("hello");
        Content second = new Content("hello");

        // 前提：两个对象的内容相等（否则本测试无法覆盖目标场景）。
        assertEquals(first, second);

        map.put(first, "message-1");
        map.put(second, "message-2");

        assertEquals("message-1", map.get(first));
        assertEquals("message-2", map.get(second));
    }

    @Test
    public void sameObjectResolvesToItsOwnMessage() {
        IdentityMessageMap map = new IdentityMessageMap();
        Content content = new Content("hello");
        map.put(content, "message-1");
        assertEquals("message-1", map.get(content));
    }

    @Test
    public void unknownContentResolvesToNull() {
        IdentityMessageMap map = new IdentityMessageMap();
        map.put(new Content("hello"), "message-1");
        assertNull(map.get(new Content("hello")));
        assertNull(map.get(null));
    }

    @Test
    public void clearRemovesAllEntries() {
        IdentityMessageMap map = new IdentityMessageMap();
        Content content = new Content("hello");
        map.put(content, "message-1");
        map.clear();
        assertNull(map.get(content));
    }

    @Test
    public void removeMessageDropsOnlyMatchingEntries() {
        IdentityMessageMap map = new IdentityMessageMap();
        Content first = new Content("hello");
        Content second = new Content("world");
        map.put(first, "message-1");
        map.put(second, "message-2");

        map.removeMessage("message-1");

        assertNull(map.get(first));
        assertEquals("message-2", map.get(second));
    }
}
