package com.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Discord 内部类的反射存取工具。
 *
 * <p>本模块不编译期依赖 Discord 类（那些类在目标 APK 内且名称会被混淆），
 * 所有访问都通过反射完成，并做防御性判断。</p>
 *
 * <p>重要：Discord 340.13 中部分 Kotlin 值类方法带有名称修饰后缀，
 * 例如 {@code Message.getId-3Eiw7ao()}。因此按“前缀匹配”而不是精确名称查找。</p>
 */
public final class Reflect {

    private Reflect() {
    }

    /** 按类名从指定 ClassLoader 加载类，失败返回 null。 */
    @Nullable
    public static Class<?> loadClass(@Nullable ClassLoader cl, String name) {
        if (cl == null) {
            return null;
        }
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 按“名称前缀 + 参数个数”查找方法。
     *
     * @param requiredFirstParams 需要与形参类型可赋值匹配的前若干个参数类型（从头开始比较）
     */
    @Nullable
    public static Method findMethod(Class<?> c, String namePrefix, int paramCount,
                                    Class<?>... requiredFirstParams) {
        if (c == null) {
            return null;
        }
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().startsWith(namePrefix)) {
                continue;
            }
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != paramCount) {
                continue;
            }
            boolean ok = true;
            for (int i = 0; i < requiredFirstParams.length; i++) {
                Class<?> want = requiredFirstParams[i];
                if (want != null && !ps[i].isAssignableFrom(want) && !want.isAssignableFrom(ps[i])) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    /** 查找无参、返回 String 的方法（名称前缀匹配），用于读取 Kotlin 值类 getter。 */
    @Nullable
    public static Method findStringGetter(Class<?> c, String namePrefix) {
        if (c == null) {
            return null;
        }
        for (Method m : c.getDeclaredMethods()) {
            if (m.getParameterTypes().length == 0
                    && m.getName().startsWith(namePrefix)
                    && m.getReturnType() == String.class) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    /**
     * 按「精确方法名 + 参数个数 + 指定下标参数与模型类型可赋值匹配」查找方法。
     *
     * <p>{@link #findMethod} 的前缀校验只作用于首参；宿主 bind 方法的模型参数
     * 位于任意下标（Embed 在 1、帖子预览在 0、投票在 0），因此提供此通用形式。</p>
     */
    @Nullable
    public static Method findMethodAt(Class<?> c, String name, int paramCount,
                                      int typeIndex, Class<?> type) {
        if (c == null) {
            return null;
        }
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals(name)) {
                continue;
            }
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != paramCount || typeIndex >= ps.length) {
                continue;
            }
            if (!ps[typeIndex].isAssignableFrom(type) && !type.isAssignableFrom(ps[typeIndex])) {
                continue;
            }
            m.setAccessible(true);
            return m;
        }
        return null;
    }

    /** 读取字段（含父类链），失败返回 null。 */
    @Nullable
    public static Object getField(@Nullable Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** 安全调用方法。 */
    @Nullable
    public static Object call(@Nullable Method m, @Nullable Object target, Object... args) {
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 安全读取 String getter。 */
    @Nullable
    public static String callString(@Nullable Object target, String namePrefix) {
        if (target == null) {
            return null;
        }
        Method m = findStringGetter(target.getClass(), namePrefix);
        Object v = call(m, target);
        return v instanceof String ? (String) v : null;
    }

    /** 判断目标是否为指定类名（含父类链）的实例。 */
    public static boolean isInstanceOfName(@Nullable Object o, String className) {
        if (o == null) {
            return false;
        }
        Class<?> c = o.getClass();
        while (c != null) {
            if (className.equals(c.getName())) {
                return true;
            }
            c = c.getSuperclass();
        }
        return false;
    }
}
