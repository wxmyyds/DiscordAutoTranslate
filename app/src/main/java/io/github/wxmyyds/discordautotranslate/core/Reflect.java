package io.github.wxmyyds.discordautotranslate.core;

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
        String configuredPrefix = accessorName(c, namePrefix);
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().startsWith(configuredPrefix)) {
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
        String configuredPrefix = accessorName(c, namePrefix);
        for (Method m : c.getDeclaredMethods()) {
            if (m.getParameterTypes().length == 0
                    && m.getName().startsWith(configuredPrefix)
                    && m.getReturnType() == String.class) {
                m.setAccessible(true);
                return m;
            }
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
            String configuredName = fieldName(c, fieldName);
            try {
                Field f = c.getDeclaredField(configuredName);
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

    private static String fieldName(Class<?> type, String memberName) {
        HookConfiguration configuration = HookConfiguration.activeConfiguration();
        if (configuration == null) {
            return memberName;
        }
        String semanticType = HookConfiguration.semanticTypeName(type);
        String configured = configuration.fieldName(semanticType, memberName);
        return configured == null || configured.isEmpty() ? memberName : configured;
    }

    private static String accessorName(Class<?> type, String accessor) {
        HookConfiguration configuration = HookConfiguration.activeConfiguration();
        if (configuration == null) {
            return accessor;
        }
        String semanticType = HookConfiguration.semanticTypeName(type);
        String configured = configuration.accessorName(semanticType, accessor);
        return configured == null || configured.isEmpty() ? accessor : configured;
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

    /** Invoke a getter or read the configured backing field for version-obfuscated models. */
    @Nullable
    public static Object callMember(@Nullable Object target, String memberName) {
        if (target == null) {
            return null;
        }
        Method getter = findMethod(target.getClass(), memberName, 0);
        if (getter != null) {
            return call(getter, target);
        }
        HookConfiguration configuration = HookConfiguration.activeConfiguration();
        String semanticType = HookConfiguration.semanticTypeName(target.getClass());
        String configuredField = configuration == null
                ? null : configuration.fieldName(semanticType, memberName);
        return configuredField == null ? null : getField(target, configuredField);
    }

    /** 安全读取 String getter；目标版本混淆 getter 时回退到内容字段。 */
    @Nullable
    public static String callString(@Nullable Object target, String namePrefix) {
        if (target == null) {
            return null;
        }
        Method m = findStringGetter(target.getClass(), namePrefix);
        Object value = call(m, target);
        if (!(value instanceof String)) {
            value = callMember(target, namePrefix);
        }
        return value instanceof String ? (String) value : null;
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
