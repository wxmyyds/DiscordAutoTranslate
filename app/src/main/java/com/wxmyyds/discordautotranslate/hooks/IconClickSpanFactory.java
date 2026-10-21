package com.wxmyyds.discordautotranslate.hooks;

import android.view.View;

import androidx.annotation.Nullable;

import com.wxmyyds.discordautotranslate.core.Reflect;
import com.wxmyyds.discordautotranslate.core.XLog;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 行内图标的可点击 span。
 *
 * <p>Discord 的文本点击不由标准 {@code ClickableSpan} 直接分发，而是走
 * {@code NestedScrollOnTouchTracker}：它在 {@code ACTION_DOWN} 用
 * {@code Layout.getOffsetForHorizontal} 把触点换算成字符位置，再取该位置上
 * {@code NestedClickableSpan} 类型的 span，命中后于 {@code ACTION_UP} 调用其
 * {@code onClick}。因此图标要与点击范围对齐，该 span 必须实现宿主接口
 * {@code com.discord.react_gesture_handler.nested_touch.NestedClickableSpan}。</p>
 *
 * <p>该接口位于目标 APK 内、编译期不可见，因此用 {@link Proxy} 按接口名动态实现，
 * 避免编译期依赖 Discord 类。</p>
 *
 * <p><b>长按</b>：仅在「详细诊断日志」开启时消费长按并弹出诊断日志，
 * 否则一律返回 {@code false} 放行给宿主自己的消息长按菜单。这样默认行为与
 * 未安装本模块时完全一致。</p>
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
     * @param hostView  承载该 span 的正文视图，用于取宿主 {@code Context} 弹窗；
     *                  仅在长按诊断时需要，可为 null
     * @return 代理对象；接口不可解析时返回 null（图标仍可见，只是不可点）
     */
    @Nullable
    public static Object create(@Nullable ClassLoader hostCl, @Nullable String messageId,
                                @Nullable View hostView) {
        if (hostCl == null || messageId == null) {
            return null;
        }
        Class<?> iface = Reflect.loadClass(hostCl, CLS_NESTED_CLICKABLE);
        if (iface == null || !iface.isInterface()) {
            XLog.w("NestedClickableSpan interface not found; inline icon not clickable");
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
                        // 只有显式开启详细诊断时才抢长按，否则原样放行给宿主消息菜单。
                        if (!HookBridge.isVerboseDiagnostics()) {
                            return Boolean.FALSE;
                        }
                        DiagnosticsDialog.show(hostView == null ? null : hostView.getContext());
                        return Boolean.TRUE;
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
                        XLog.d("inline icon click span: unexpected method " + method.getName());
                        return null;
                }
            }
        };

        try {
            return Proxy.newProxyInstance(hostCl, new Class<?>[]{iface}, handler);
        } catch (Throwable t) {
            XLog.w("create inline icon click span failed", t);
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
            XLog.w("TouchPriority.HIGH not found; using default priority");
            return null;
        } catch (Throwable t) {
            XLog.w("resolve TouchPriority.HIGH failed", t);
            return null;
        }
    }
}
