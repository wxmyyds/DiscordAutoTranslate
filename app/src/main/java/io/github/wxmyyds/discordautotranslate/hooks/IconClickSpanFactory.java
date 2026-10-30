package io.github.wxmyyds.discordautotranslate.hooks;

import androidx.annotation.Nullable;

import io.github.wxmyyds.discordautotranslate.core.Reflect;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 行内图标的可点击 span。
 *
 * <p>Discord 的文本点击由 {@code NestedScrollOnTouchTracker} 分发，不走标准
 * {@code ClickableSpan}：它在 {@code ACTION_DOWN} 用
 * {@code Layout.getOffsetForHorizontal} 把触点换算成字符位置，再取该位置上
 * {@code NestedClickableSpan} 类型的 span，命中后于 {@code ACTION_UP} 调用其
 * {@code onClick}。因此图标要与点击范围对齐，该 span 必须实现宿主接口
 * {@code com.discord.react_gesture_handler.nested_touch.NestedClickableSpan}。</p>
 *
 * <p>该接口位于目标 APK 内、编译期不可见，因此用 {@link Proxy} 按接口名动态实现，
 * 避免编译期依赖 Discord 类。</p>
 *
 * <p>长按始终返回 {@code false}，把长按交给宿主消息菜单。</p>
 */
public final class IconClickSpanFactory {

    private static final String CLS_NESTED_CLICKABLE =
            "com.discord.react_gesture_handler.nested_touch.NestedClickableSpan";
    private static final String CLS_TOUCH_PRIORITY =
            "com.discord.react_gesture_handler.nested_touch.NestedClickableSpan$TouchPriority";

    private IconClickSpanFactory() {
    }

    /**
     * 创建实现宿主 {@code NestedClickableSpan} 的代理对象。
     *
     * @param hostCl    宿主 ClassLoader（必须能加载该接口）
     * @param messageId 所属消息 ID，点击时用于查回该消息的状态
     * @return 代理对象；接口不可解析时返回 null（图标仍可见，只是不可点）
     */
    @Nullable
    public static Object create(@Nullable ClassLoader hostCl, @Nullable String messageId) {
        if (hostCl == null || messageId == null) {
            return null;
        }
        Class<?> iface = Reflect.loadClass(hostCl, CLS_NESTED_CLICKABLE);
        if (iface == null || !iface.isInterface()) {
            return null;
        }
        Object highPriority = resolveHighPriority(hostCl);

        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                switch (method.getName()) {
                    case "onClick":
                        // 触摸在消息行上处理，回调发生在主线程。
                        HookBridge.get().onIconClicked(messageId);
                        return null;
                    case "onLongClick":
                        return Boolean.FALSE;
                    case "enableHighlight":
                        return null;
                    case "getTouchPriority":
                        return highPriority;
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return args != null && args.length == 1 && proxy == args[0];
                    case "toString":
                        return "InlineIconClickSpan(" + messageId + ")";
                    default:
                        return null;
                }
            }
        };

        try {
            return Proxy.newProxyInstance(hostCl, new Class<?>[]{iface}, handler);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 取 {@code TouchPriority.HIGH}；解析失败返回 null（按默认优先级也能点）。 */
    @Nullable
    private static Object resolveHighPriority(ClassLoader hostCl) {
        Class<?> priorityCls = Reflect.loadClass(hostCl, CLS_TOUCH_PRIORITY);
        if (priorityCls == null || !priorityCls.isEnum()) {
            return null;
        }
        try {
            Object[] constants = priorityCls.getEnumConstants();
            if (constants == null) {
                return null;
            }
            for (Object c : constants) {
                if (c instanceof Enum && "HIGH".equals(((Enum<?>) c).name())) {
                    return c;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }
}
