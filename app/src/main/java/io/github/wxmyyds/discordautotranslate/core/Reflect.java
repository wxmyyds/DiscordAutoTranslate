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
 * <p>所有宿主成员名称由活动版本配置提供并精确匹配，包括 Kotlin 值类后缀。
 * 不使用前缀匹配，避免将 getContent$annotations 等元数据方法误当作 getter。</p>
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
     * Find a method by its exact name mapped through the active profile.
     *
     * @param requiredFirstParams types that must be assignable for the leading parameters
     */
    @Nullable
    public static Method findMethod(Class<?> c, String semanticAccessor, int paramCount,
                                    Class<?>... requiredFirstParams) {
        if (c == null) {
            return null;
        }
        String configuredName = accessorName(c, semanticAccessor);
        return findMethodExact(c, configuredName, paramCount, requiredFirstParams);
    }

    /** Find a method by an exact name supplied explicitly by profile data. */
    @Nullable
    public static Method findMethodExact(Class<?> c, @Nullable String name, int paramCount,
                                         Class<?>... requiredFirstParams) {
        if (c == null || name == null || name.isEmpty()) {
            return null;
        }
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals(name)) {
                continue;
            }
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != paramCount || requiredFirstParams.length > ps.length) {
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

    /** Find a no-argument String getter by its exact configured name. */
    @Nullable
    private static Method findStringGetterExact(@Nullable Class<?> c, @Nullable String name) {
        if (c == null || name == null || name.isEmpty()) {
            return null;
        }
        for (Method m : c.getDeclaredMethods()) {
            if (m.getParameterTypes().length == 0
                    && m.getName().equals(name)
                    && m.getReturnType() == String.class) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

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

    /** Invoke the profile-selected accessor or read the profile-selected field; never try both. */
    @Nullable
    public static Object callMember(@Nullable Object target, String memberName) {
        if (target == null) {
            return null;
        }
        HookConfiguration configuration = HookConfiguration.activeConfiguration();
        if (configuration == null) {
            return null;
        }
        String semanticType = HookConfiguration.semanticTypeName(target.getClass());
        String accessor = configuredAccessor(configuration, semanticType, memberName);
        if (accessor != null) {
            return call(findMethodExact(target.getClass(), accessor, 0), target);
        }
        String field = semanticType == null ? null
                : configuration.fieldName(semanticType, memberName);
        return field == null ? null : getField(target, field);
    }

    /** Read the profile-selected String accessor or field without runtime path switching. */
    @Nullable
    public static String callString(@Nullable Object target, String memberName) {
        if (target == null) {
            return null;
        }
        HookConfiguration configuration = HookConfiguration.activeConfiguration();
        if (configuration == null) {
            return null;
        }
        String semanticType = HookConfiguration.semanticTypeName(target.getClass());
        String accessor = configuredAccessor(configuration, semanticType, memberName);
        if (accessor != null) {
            Object value = call(findStringGetterExact(target.getClass(), accessor), target);
            return value instanceof String ? (String) value : null;
        }
        String field = semanticType == null ? null
                : configuration.fieldName(semanticType, memberName);
        Object value = field == null ? null : getField(target, field);
        return value instanceof String ? (String) value : null;
    }

    @Nullable
    private static String accessorName(Class<?> type, String accessor) {
        HookConfiguration configuration = HookConfiguration.activeConfiguration();
        return configuration == null ? null
                : configuredAccessor(configuration,
                        HookConfiguration.semanticTypeName(type), accessor);
    }

    @Nullable
    private static String configuredAccessor(HookConfiguration configuration,
                                            String semanticType, String accessor) {
        String mapped = semanticType == null ? null
                : configuration.accessorName(semanticType, accessor);
        if (mapped == null || mapped.isEmpty()) {
            mapped = configuration.string("members", accessor);
        }
        return mapped == null || mapped.isEmpty() ? null : mapped;
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
