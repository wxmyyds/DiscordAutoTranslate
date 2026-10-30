package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 重渲染的复用校验。
 *
 * <p>背景：视图→消息 ID 映射表是容量上限 512 的 LRU。长列表滚动后旧条目会被淘汰，
 * 此时查询结果为 null。若把“未知”当成“已复用”而丢弃登记目标，该消息今后
 * 就不会再因译文完成而刷新，只有滚动重新 bind 才恢复。</p>
 */
public class RerenderRegistryTest {

    @Test
    public void unknownBindingIsNotTreatedAsReuse() {
        assertTrue(RerenderRegistry.stillBound("123456789012345678", null));
    }

    @Test
    public void sameBindingIsStillValid() {
        assertTrue(RerenderRegistry.stillBound("123456789012345678", "123456789012345678"));
    }

    @Test
    public void reboundToAnotherMessageIsRejected() {
        assertFalse(RerenderRegistry.stillBound("123456789012345678", "987654321098765432"));
    }

    @Test
    public void missingExpectationSkipsValidation() {
        assertTrue(RerenderRegistry.stillBound(null, "987654321098765432"));
    }
}
