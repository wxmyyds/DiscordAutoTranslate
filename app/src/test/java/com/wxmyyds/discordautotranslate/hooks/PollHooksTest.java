package com.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/**
 * {@link PollHooks#copyWithReplaced} 的重建语义。
 *
 * <p>投票替换依赖 Kotlin data class 的 copy/componentN 通用重建；这里用普通 Java
 * 类模拟相同的方法形态（宿主的 copy 名字带修饰后缀，JVM 无法声明，只能覆盖
 * 未修饰路径——修饰路径与前缀匹配逻辑共用同一实现）。</p>
 */
public class PollHooksTest {

    /** 模拟 PollMedia：String 文本 + 附加字段。 */
    public static final class FakeMedia {
        private final String text;
        private final String extra;

        public FakeMedia(String text, String extra) {
            this.text = text;
            this.extra = extra;
        }

        public String getText() {
            return text;
        }

        public String component1() {
            return text;
        }

        public String component2() {
            return extra;
        }

        public FakeMedia copy(String text, String extra) {
            return new FakeMedia(text, extra);
        }
    }

    /** 模拟 PollAnswer：组件超过 10 个，验证 component1 不会误配 component11。 */
    public static final class FakeHolder {
        private final FakeMedia media;
        private final int c2;
        private final int c3;
        private final int c4;
        private final int c5;
        private final int c6;
        private final int c7;
        private final int c8;
        private final int c9;
        private final int c10;
        private final int c11;

        public FakeHolder(FakeMedia media) {
            this.media = media;
            this.c2 = 2;
            this.c3 = 3;
            this.c4 = 4;
            this.c5 = 5;
            this.c6 = 6;
            this.c7 = 7;
            this.c8 = 8;
            this.c9 = 9;
            this.c10 = 10;
            this.c11 = 11;
        }

        public FakeMedia component1() {
            return media;
        }

        public int component2() {
            return c2;
        }

        public int component3() {
            return c3;
        }

        public int component4() {
            return c4;
        }

        public int component5() {
            return c5;
        }

        public int component6() {
            return c6;
        }

        public int component7() {
            return c7;
        }

        public int component8() {
            return c8;
        }

        public int component9() {
            return c9;
        }

        public int component10() {
            return c10;
        }

        public int component11() {
            return c11;
        }

        public FakeHolder copy(FakeMedia media, int c2, int c3, int c4, int c5,
                               int c6, int c7, int c8, int c9, int c10, int c11) {
            return new FakeHolder(media);
        }
    }

    @Test
    public void replacesMatchingComponentByIdentity() {
        FakeMedia original = new FakeMedia("hello", "keep");
        FakeHolder holder = new FakeHolder(original);
        FakeMedia translated = new FakeMedia("你好", "keep");

        FakeHolder rebuilt = (FakeHolder) PollHooks.copyWithReplaced(
                holder, original, translated);

        assertNotSame(holder, rebuilt);
        assertSame(translated, rebuilt.component1());
    }

    @Test
    public void mediaCopyKeepsOtherComponents() {
        FakeMedia original = new FakeMedia("hello", "keep-me");
        FakeMedia rebuilt = (FakeMedia) PollHooks.copyWithReplaced(
                original, "hello", "你好");
        assertEquals("你好", rebuilt.getText());
        assertEquals("keep-me", rebuilt.component2());
    }

    @Test
    public void identityMatchIsRequiredNotEquals() {
        FakeHolder holder = new FakeHolder(new FakeMedia("hello", "a"));
        // 相等但不同实例的旧值不得命中，避免误替换同值的其他组件。
        assertNull(PollHooks.copyWithReplaced(holder, new FakeMedia("hello", "a"),
                new FakeMedia("你好", "a")));
    }

    @Test
    public void missingTargetComponentYieldsNull() {
        FakeMedia original = new FakeMedia("hello", "keep");
        assertNull(PollHooks.copyWithReplaced(original, "not-present", "x"));
    }

    @Test
    public void nullInputsYieldNull() {
        assertNull(PollHooks.copyWithReplaced(null, "a", "b"));
    }
}
