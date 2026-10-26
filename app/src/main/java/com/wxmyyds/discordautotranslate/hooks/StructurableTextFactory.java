package com.wxmyyds.discordautotranslate.hooks;

import androidx.annotation.Nullable;

import com.wxmyyds.discordautotranslate.core.Reflect;

import java.lang.reflect.Constructor;
import java.util.List;

/**
 * 构造 Discord 的 {@code StructurableText} 实例（需求 §7 阶段 3）。
 *
 * <p>依据 {@code TextUtilsKt.toSpannable} 的分支（已核对 smali）：
 * <ul>
 *   <li>{@code AnnotatedStructurableText} → 渲染内容节点列表；</li>
 *   <li>{@code PrimitiveStructurableText} → 整体当纯文本；</li>
 *   <li>其他类型 → 抛 {@code RuntimeException}。</li>
 * </ul>
 *
 * <p>因此替换时必须产出上述两种类型之一。为保留 Markdown，优先产出
 * {@code AnnotatedStructurableText}（见 {@link ContentNodes}）；
 * 只在原文也是纯文本时产出 {@code PrimitiveStructurableText}。
 * 富文本重建失败时保持原文，避免降级丢失 emoji、提及和代码等结构。</p>
 */
public final class StructurableTextFactory {

    private static volatile Class<?> sPrimitiveClass;
    private static volatile Class<?> sAnnotatedClass;
    private static volatile Constructor<?> sPrimitiveCtor;
    private static volatile Constructor<?> sAnnotatedCtor;

    private StructurableTextFactory() {
    }

    /** 由模块入口在拿到目标 ClassLoader 后初始化。 */
    public static void init(@Nullable ClassLoader cl) {
        sPrimitiveClass = Reflect.loadClass(cl,
                "com.discord.chat.bridge.structurabletext.PrimitiveStructurableText");
        sAnnotatedClass = Reflect.loadClass(cl,
                "com.discord.chat.bridge.structurabletext.AnnotatedStructurableText");

        sPrimitiveCtor = ctorOf(sPrimitiveClass, String.class);
        sAnnotatedCtor = ctorOf(sAnnotatedClass, List.class);

    }

    @Nullable
    private static Constructor<?> ctorOf(@Nullable Class<?> cls, Class<?>... params) {
        if (cls == null) {
            return null;
        }
        try {
            Constructor<?> c = cls.getDeclaredConstructor(params);
            c.setAccessible(true);
            return c;
        } catch (Throwable t) {
            return null;
        }
    }

    public static void reset() {
        sPrimitiveClass = null;
        sAnnotatedClass = null;
        sPrimitiveCtor = null;
        sAnnotatedCtor = null;
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
            return null;
        }
    }

    /** 构造 {@code AnnotatedStructurableText(List<ContentNode>)}；不可用时返回 null。 */
    @Nullable
    public static Object annotated(@Nullable List<?> nodes) {
        Constructor<?> ctor = sAnnotatedCtor;
        if (ctor == null || nodes == null) {
            return null;
        }
        try {
            return ctor.newInstance(nodes);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 为译文构造替换用的 StructurableText。
     *
     * <p>策略：优先保留原类型与 Markdown 结构。</p>
     *
     * @param original      原始 StructurableText（用于判断类型与取节点树）
     * @param translated    译文
     * @return 替换对象；无法构造时返回 null（调用方应保持原文）
     */
    @Nullable
    public static Object forTranslation(@Nullable Object original, String translated) {
        if (original == null || translated == null) {
            return null;
        }

        // 1) 原始是 Annotated：尝试保留 Markdown 结构。
        if (ContentNodes.isAnnotated(original)) {
            List<?> nodes = ContentNodes.listOf(original);
            List<Object> replaced = ContentNodes.replaceText(nodes, translated);
            if (replaced != null) {
                Object out = annotated(replaced);
                if (out != null) {
                    return out;
                }
            }
            // 不接受部分重建，也不退化为纯文本（会丢失 emoji/提及/代码）。
            // 返回 null，让所有调用路径继续使用未修改的原始结构。
            return null;
        }

        // 2) 原始是 Primitive：直接给纯文本。
        if (ContentNodes.isPrimitive(original)) {
            return primitive(translated);
        }

        // 3) 未知类型：交给渲染器会抛异常，因此坚持不替换。
        return null;
    }
}
