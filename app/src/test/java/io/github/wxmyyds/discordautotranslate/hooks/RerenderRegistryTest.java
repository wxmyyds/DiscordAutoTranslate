package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * 重渲染的复用校验。
 *
 * <p>背景：视图→消息 ID 映射表是容量上限 512 的 LRU。长列表滚动后旧条目会被淘汰，
 * 此时查询结果为 null。若把“未知”当成“已复用”而丢弃登记目标，该消息今后
 * 就不会再因译文完成而刷新，只有滚动重新 bind 才恢复。</p>
 */
public class RerenderRegistryTest {

    public static void nativeBind() {}

    @SuppressWarnings("unchecked")
    private static Map<String, List<RerenderRegistry.Target>> targets() throws Exception {
        Field field = RerenderRegistry.class.getDeclaredField("TARGETS");
        field.setAccessible(true);
        return (Map<String, List<RerenderRegistry.Target>>) field.get(null);
    }

    private static boolean current(String key, RerenderRegistry.Target target) throws Exception {
        Method method = RerenderRegistry.class.getDeclaredMethod("isCurrentRegistration",
                String.class, RerenderRegistry.Target.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, key, target);
    }

    @Test
    public void embedRebindInvalidatesEveryOldSegmentEvenForSameRow() throws Exception {
        RerenderRegistry.clear();
        try {
            View view = new View(null); // Android stub only; no framework methods used.
            Method method = getClass().getMethod("nativeBind");
            String title = "123456789012345678\u0000embed\u0000title";
            String description = "123456789012345678\u0000embed\u0000description";
            RerenderRegistry.register(title, view, null, method, new Object[0]);
            RerenderRegistry.register(description, view, null, method, new Object[0]);
            RerenderRegistry.Target oldTitle = targets().get(title).get(0);
            RerenderRegistry.Target oldDescription = targets().get(description).get(0);
            assertTrue(current(title, oldTitle));
            assertTrue(current(description, oldDescription));
            RerenderRegistry.unregister(view);
            // A card editing/reordering within the same message can keep a text key.
            RerenderRegistry.register(title, view, null, method, new Object[0]);
            assertFalse(current(title, oldTitle));
            assertFalse(current(description, oldDescription));
            assertEquals(1, targets().size());
            assertTrue(current(title, targets().get(title).get(0)));
            // The next image-only card must leave no queued text callbacks.
            RerenderRegistry.unregister(view);
            assertTrue(targets().isEmpty());
        } finally {
            RerenderRegistry.clear();
        }
    }

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
