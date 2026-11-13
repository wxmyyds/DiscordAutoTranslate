package io.github.wxmyyds.discordautotranslate.hooks;

import android.text.style.ClickableSpan;

import androidx.annotation.Nullable;

import io.github.wxmyyds.discordautotranslate.core.HookConfiguration;
import io.github.wxmyyds.discordautotranslate.core.Reflect;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** Creates the host-recognized icon click span using the active hook profile protocol. */
public final class IconClickSpanFactory {
    private static final Object INVALID_ARGUMENT = new Object();

    private IconClickSpanFactory() {
    }

    @Nullable
    public static Object create(@Nullable ClassLoader hostCl, @Nullable String messageId) {
        HookConfiguration configuration = HookConfiguration.activeConfiguration();
        JSONObject spec = configuration == null ? null : configuration.iconClickConfiguration();
        if (hostCl == null || messageId == null || spec == null
                || !spec.optBoolean("enabled", false)) {
            return null;
        }
        try {
            Object span;
            switch (spec.optString("protocol", "")) {
                case "constructorProxy":
                    span = createByConstructor(hostCl, messageId, spec);
                    break;
                case "interfaceProxy":
                    span = createByInterface(hostCl, messageId, spec);
                    break;
                default:
                    span = null;
            }
            if (span == null) {
                HookDiagnostics.warnOnce("icon.click.span-unavailable",
                        "icon click span unavailable: configured protocol could not be created");
            }
            return span;
        } catch (Throwable t) {
            HookDiagnostics.warnOnce("icon.click.span-create-failed",
                    "icon click span creation failed: " + t.getClass().getSimpleName());
            return null;
        }
    }

    @Nullable
    private static Object createByConstructor(ClassLoader hostCl, String messageId,
                                               JSONObject spec) throws Exception {
        Class<?> spanClass = configuredClass(hostCl, spec.optString("spanClass", ""));
        Class<?> expectedSuperclass = configuredClass(
                hostCl, spec.optString("requiredSuperclass", ""));
        if (spanClass == null || expectedSuperclass == null
                || !expectedSuperclass.isAssignableFrom(spanClass)
                || !ClickableSpan.class.isAssignableFrom(spanClass)) {
            return null;
        }
        Class<?>[] interfaces = configuredInterfaces(hostCl, spec.optJSONArray("interfaces"));
        if (interfaces == null) {
            return null;
        }
        for (Class<?> iface : interfaces) {
            if (!iface.isInterface() || !iface.isAssignableFrom(spanClass)) {
                return null;
            }
        }

        JSONObject constructorSpec = spec.optJSONObject("constructor");
        JSONArray parameterNames = constructorSpec == null ? null
                : constructorSpec.optJSONArray("parameterTypes");
        JSONArray argumentSpecs = constructorSpec == null ? null
                : constructorSpec.optJSONArray("arguments");
        if (parameterNames == null || argumentSpecs == null
                || parameterNames.length() != argumentSpecs.length()) {
            return null;
        }
        Class<?>[] parameterTypes = new Class<?>[parameterNames.length()];
        Object[] arguments = new Object[argumentSpecs.length()];
        for (int i = 0; i < parameterTypes.length; i++) {
            parameterTypes[i] = resolveType(hostCl, parameterNames.optString(i, ""));
            arguments[i] = resolveArgument(hostCl, messageId, argumentSpecs.optJSONObject(i));
            if (parameterTypes[i] == null || arguments[i] == INVALID_ARGUMENT
                    || !accepts(parameterTypes[i], arguments[i])) {
                return null;
            }
        }
        Constructor<?> constructor = spanClass.getDeclaredConstructor(parameterTypes);
        constructor.setAccessible(true);
        Object result = constructor.newInstance(arguments);
        HookDiagnostics.infoOnce("icon.click.span-ready",
                "icon click span created with configured constructor protocol");
        return result;
    }

    @Nullable
    private static Object createByInterface(ClassLoader hostCl, String messageId,
                                             JSONObject spec) throws Exception {
        Class<?> iface = configuredClass(hostCl, spec.optString("interface", ""));
        JSONObject handlers = spec.optJSONObject("methods");
        if (iface == null || !iface.isInterface() || handlers == null) {
            return null;
        }
        return Proxy.newProxyInstance(hostCl, new Class<?>[]{iface}, (proxy, method, args) -> {
            JSONObject behavior = handlers.optJSONObject(method.getName());
            return behavior == null ? objectMethod(proxy, method, args)
                    : invokeConfiguredBehavior(hostCl, messageId, proxy, method, args, behavior);
        });
    }

    @Nullable
    private static Object invokeConfiguredBehavior(ClassLoader hostCl, String messageId,
            Object proxy, Method method, Object[] args, JSONObject behavior) throws Exception {
        switch (behavior.optString("action", "")) {
            case "toggleTranslation":
                HookDiagnostics.infoOnce("icon.click.callback-reached",
                        "icon click callback reached configured host span handler");
                HookBridge.get().onIconClicked(messageId);
                return null;
            case "returnFalse":
                return false;
            case "returnNull":
                return null;
            case "returnEnum":
                Class<?> enumClass = configuredClass(hostCl, behavior.optString("enumClass", ""));
                if (enumClass == null || !enumClass.isEnum()) {
                    return null;
                }
                String enumValue = behavior.optString("enumValue", "");
                for (Object value : enumClass.getEnumConstants()) {
                    if (value instanceof Enum && enumValue.equals(((Enum<?>) value).name())) {
                        return value;
                    }
                }
                return null;
            default:
                return objectMethod(proxy, method, args);
        }
    }

    @Nullable
    private static Object resolveArgument(ClassLoader hostCl, String messageId,
                                          @Nullable JSONObject argument) throws Exception {
        if (argument == null) {
            return INVALID_ARGUMENT;
        }
        switch (argument.optString("kind", "")) {
            case "null": return null;
            case "integer": return argument.optInt("value");
            case "boolean": return argument.optBoolean("value");
            case "string": return argument.optString("value", "");
            case "callback":
                Class<?> callbackInterface = configuredClass(
                        hostCl, argument.optString("interface", ""));
                String callbackMethod = argument.optString("method", "");
                JSONObject behavior = new JSONObject().put(
                        "action", argument.optString("action", ""));
                if (callbackInterface == null || !callbackInterface.isInterface()
                        || callbackMethod.isEmpty()) {
                    return INVALID_ARGUMENT;
                }
                return Proxy.newProxyInstance(hostCl, new Class<?>[]{callbackInterface},
                        (proxy, method, args) -> callbackMethod.equals(method.getName())
                                ? invokeConfiguredBehavior(hostCl, messageId, proxy, method,
                                        args, behavior)
                                : objectMethod(proxy, method, args));
            default: return INVALID_ARGUMENT;
        }
    }

    private static boolean accepts(Class<?> parameterType, Object value) {
        if (value == null) {
            return !parameterType.isPrimitive();
        }
        if (!parameterType.isPrimitive()) {
            return parameterType.isInstance(value);
        }
        return (parameterType == int.class && value instanceof Integer)
                || (parameterType == boolean.class && value instanceof Boolean)
                || (parameterType == long.class && value instanceof Long)
                || (parameterType == float.class && value instanceof Float)
                || (parameterType == double.class && value instanceof Double)
                || (parameterType == short.class && value instanceof Short)
                || (parameterType == byte.class && value instanceof Byte)
                || (parameterType == char.class && value instanceof Character);
    }

    @Nullable
    private static Class<?>[] configuredInterfaces(ClassLoader hostCl, @Nullable JSONArray names) {
        if (names == null) {
            return new Class<?>[0];
        }
        List<Class<?>> result = new ArrayList<>();
        for (int i = 0; i < names.length(); i++) {
            Class<?> type = configuredClass(hostCl, names.optString(i, ""));
            if (type == null) {
                return null;
            }
            result.add(type);
        }
        return result.toArray(new Class<?>[0]);
    }

    @Nullable
    private static Class<?> resolveType(ClassLoader hostCl, String name) {
        switch (name) {
            case "boolean": return boolean.class;
            case "byte": return byte.class;
            case "char": return char.class;
            case "short": return short.class;
            case "int": return int.class;
            case "long": return long.class;
            case "float": return float.class;
            case "double": return double.class;
            default: return configuredClass(hostCl, name);
        }
    }

    @Nullable
    private static Class<?> configuredClass(ClassLoader hostCl, String name) {
        return name.isEmpty() ? null : Reflect.loadClass(hostCl, name);
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "hashCode": return System.identityHashCode(proxy);
            case "equals": return args != null && args.length == 1 && proxy == args[0];
            case "toString": return "ConfiguredIconClickSpan";
            default: return null;
        }
    }
}
