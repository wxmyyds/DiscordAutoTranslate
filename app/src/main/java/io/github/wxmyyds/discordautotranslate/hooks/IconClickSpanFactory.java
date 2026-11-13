package io.github.wxmyyds.discordautotranslate.hooks;

import androidx.annotation.Nullable;

import io.github.wxmyyds.discordautotranslate.core.Reflect;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 行内图标的可点击 span。
 *
 * <p>Discord 352017 的消息触摸分发器只查询宿主 {@code zf/b} marker 接口，随后把命中的
 * span 强转为 {@code lh/d}。该类继承 Android {@code ClickableSpan} 并实现 marker，且其
 * 构造器接收 Kotlin {@code Function1} 点击回调。由于类名随宿主版本混淆，运行时反射创建，
 * 不引入编译期 Discord 依赖。</p>
 */
public final class IconClickSpanFactory {

    private static final String CLS_HOST_CLICKABLE_SPAN = "lh.d";
    private static final String CLS_HOST_CLICKABLE_MARKER = "zf.b";
    private static final String CLS_KOTLIN_FUNCTION1 = "kotlin.jvm.functions.Function1";

    private static final String CLS_LEGACY_NESTED_CLICKABLE =
            "com.discord.react_gesture_handler.nested_touch.NestedClickableSpan";
    private static final String CLS_LEGACY_TOUCH_PRIORITY =
            "com.discord.react_gesture_handler.nested_touch.NestedClickableSpan$TouchPriority";

    private IconClickSpanFactory() {
    }

    /**
     * 创建目标 Discord 版本可识别的点击 span。
     *
     * @param hostCl 宿主 ClassLoader
     * @param messageId 所属消息 ID，点击时用于查回该消息的状态
     * @return 可点击 span；宿主类型解析失败时返回 null
     */
    @Nullable
    public static Object create(@Nullable ClassLoader hostCl, @Nullable String messageId) {
        if (hostCl == null || messageId == null) {
            return null;
        }

        Object hostSpan = createHostClickableSpan(hostCl, messageId);
        if (hostSpan != null) {
            return hostSpan;
        }
        return createLegacyNestedClickable(hostCl, messageId);
    }

    /** Discord 352017 的 zf/f 只接受实现 zf/b 且实际为 lh/d 的 span。 */
    @Nullable
    private static Object createHostClickableSpan(ClassLoader hostCl, String messageId) {
        Class<?> spanClass = Reflect.loadClass(hostCl, CLS_HOST_CLICKABLE_SPAN);
        Class<?> markerClass = Reflect.loadClass(hostCl, CLS_HOST_CLICKABLE_MARKER);
        Class<?> function1Class = Reflect.loadClass(hostCl, CLS_KOTLIN_FUNCTION1);
        if (spanClass == null || markerClass == null || function1Class == null
                || !android.text.style.ClickableSpan.class.isAssignableFrom(spanClass)
                || !markerClass.isInterface() || !markerClass.isAssignableFrom(spanClass)
                || !function1Class.isInterface()) {
            return null;
        }

        try {
            Object clickCallback = Proxy.newProxyInstance(hostCl,
                    new Class<?>[]{function1Class}, clickCallback(messageId));
            java.lang.reflect.Constructor<?> constructor = spanClass.getDeclaredConstructor(
                    Integer.class, Integer.class, Integer.class, Boolean.class,
                    function1Class, function1Class, int.class);
            constructor.setAccessible(true);
            // 保留宿主默认样式/长按处理，将 Function1 的第二个参数设为点击回调。
            Object span = constructor.newInstance(null, null, null, null, null, clickCallback, 0xff);
            HookDiagnostics.infoOnce("icon.click.host-span-ready",
                    "icon click span created using host class " + CLS_HOST_CLICKABLE_SPAN);
            return span;
        } catch (Throwable t) {
            HookDiagnostics.warnOnce("icon.click.host-span-create-failed",
                    "icon click span creation failed for host class " + CLS_HOST_CLICKABLE_SPAN
                            + ": " + t.getClass().getSimpleName());
            return null;
        }
    }

    /** 兼容仍使用公开 NestedClickableSpan 接口的旧 Discord 版本。 */
    @Nullable
    private static Object createLegacyNestedClickable(ClassLoader hostCl, String messageId) {
        Class<?> iface = Reflect.loadClass(hostCl, CLS_LEGACY_NESTED_CLICKABLE);
        if (iface == null || !iface.isInterface()) {
            HookDiagnostics.warnOnce("icon.click.span-unavailable",
                    "icon click span unavailable: neither host clickable span nor legacy interface resolved");
            return null;
        }
        Object highPriority = resolveHighPriority(hostCl);
        try {
            return Proxy.newProxyInstance(hostCl, new Class<?>[]{iface},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            switch (method.getName()) {
                                case "onClick":
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
                                    return "InlineIconClickSpan";
                                default:
                                    return null;
                            }
                        }
                    });
        } catch (Throwable t) {
            HookDiagnostics.warnOnce("icon.click.legacy-span-create-failed",
                    "legacy icon click span creation failed: " + t.getClass().getSimpleName());
            return null;
        }
    }

    private static InvocationHandler clickCallback(String messageId) {
        return new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                switch (method.getName()) {
                    case "invoke":
                        HookDiagnostics.infoOnce("icon.click.callback-reached",
                                "icon click callback reached host span Function1");
                        HookBridge.get().onIconClicked(messageId);
                        return null;
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return args != null && args.length == 1 && proxy == args[0];
                    case "toString":
                        return "InlineIconClickCallback";
                    default:
                        return null;
                }
            }
        };
    }

    /** 取旧接口的 {@code TouchPriority.HIGH}；解析失败返回 null。 */
    @Nullable
    private static Object resolveHighPriority(ClassLoader hostCl) {
        Class<?> priorityCls = Reflect.loadClass(hostCl, CLS_LEGACY_TOUCH_PRIORITY);
        if (priorityCls == null || !priorityCls.isEnum()) {
            return null;
        }
        try {
            Object[] constants = priorityCls.getEnumConstants();
            if (constants != null) {
                for (Object constant : constants) {
                    if (constant instanceof Enum && "HIGH".equals(((Enum<?>) constant).name())) {
                        return constant;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Default priority remains usable for the legacy interface.
        }
        return null;
    }
}
