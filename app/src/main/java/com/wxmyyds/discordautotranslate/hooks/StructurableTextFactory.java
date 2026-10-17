package com.wxmyyds.discordautotranslate.hooks;

import androidx.annotation.Nullable;

import com.wxmyyds.discordautotranslate.core.Reflect;
import com.wxmyyds.discordautotranslate.core.XLog;

import java.lang.reflect.Constructor;

/**
 * 构造 Discord 的 {@code StructurableText} 实例。
 *
 * <p>Discord 340.13 的模型中，只有 {@code PrimitiveStructurableText(String)} 可以由
 * 单纯的译文构造。带 Markdown/提及/表情的消息使用 {@code AnnotatedStructurableText(List)}，
 * 需要重建整个 {@code ContentNode} 树才能保留原有富文本；这属于阶段 3 的工作
 * （见 docs/design-stage1.md）。</p>
 */
public final class StructurableTextFactory {

    private static volatile Class<?> sPrimitiveClass;
    private static volatile Constructor<?> sPrimitiveCtor;
    private static volatile boolean sInitTried;

    private StructurableTextFactory() {
    }

    /** 由模块入口在拿到目标 ClassLoader 后初始化。 */
    public static void init(@Nullable ClassLoader cl) {
        sInitTried = true;
        sPrimitiveClass = Reflect.loadClass(cl,
                "com.discord.chat.bridge.structurabletext.PrimitiveStructurableText");
        if (sPrimitiveClass != null) {
            try {
                sPrimitiveCtor = sPrimitiveClass.getDeclaredConstructor(String.class);
                sPrimitiveCtor.setAccessible(true);
                XLog.i("StructurableTextFactory ready");
            } catch (Throwable t) {
                sPrimitiveCtor = null;
                XLog.w("PrimitiveStructurableText(String) not found", t);
            }
        } else {
            XLog.w("PrimitiveStructurableText not found in target classloader");
        }
    }

    public static void reset() {
        sPrimitiveClass = null;
        sPrimitiveCtor = null;
        sInitTried = false;
    }

    public static boolean isReady() {
        return sPrimitiveCtor != null;
    }

    public static boolean isInitTried() {
        return sInitTried;
    }

    /** 构造 {@code PrimitiveStructurableText(text)}；不可用时返回 null。 */
    @Nullable
    public static Object primitive(@Nullable String text) {
        Constructor<?> ctor = sPrimitiveCtor;
        if (ctor == null || text == null) {
            return null;
        }
        try {
            return ctor.newInstance(text);
        } catch (Throwable t) {
            XLog.w("construct PrimitiveStructurableText failed", t);
            return null;
        }
    }

    /** 判断给定对象是否为 PrimitiveStructurableText。 */
    public static boolean isPrimitive(@Nullable Object o) {
        return o != null && sPrimitiveClass != null && sPrimitiveClass.isInstance(o);
    }
}
