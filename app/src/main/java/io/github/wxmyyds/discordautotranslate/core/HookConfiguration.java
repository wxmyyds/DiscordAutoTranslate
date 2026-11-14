package io.github.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Iterator;
import java.util.Set;

/** Parsed, version-specific Discord hook metadata. */
public final class HookConfiguration {
    private static volatile HookConfiguration active;

    private final int versionCode;
    private final String rawJson;
    private final JSONObject root;

    private HookConfiguration(int versionCode, String rawJson, JSONObject root) {
        this.versionCode = versionCode;
        this.rawJson = rawJson;
        this.root = root;
    }

    public static HookConfiguration parse(String json, int expectedVersionCode)
            throws JSONException {
        JSONObject root = new JSONObject(json);
        if (root.optInt("schemaVersion", -1) != 1) {
            throw new JSONException("unsupported hook config schemaVersion");
        }
        int versionCode = root.optInt("versionCode", -1);
        if (versionCode != expectedVersionCode) {
            throw new JSONException("hook config versionCode mismatch: " + versionCode);
        }
        JSONObject hooks = root.optJSONObject("hooks");
        if (hooks == null) {
            throw new JSONException("hook config is missing hooks");
        }
        validateHookSelectors(hooks);
        validateEmbedInput(hooks);
        validateNodeConstructors(root);
        return new HookConfiguration(versionCode, json, root);
    }

    private static void validateHookSelectors(JSONObject hooks) throws JSONException {
        Iterator<String> ids = hooks.keys();
        while (ids.hasNext()) {
            String id = ids.next();
            JSONObject hook = hooks.optJSONObject(id);
            if (hook == null) {
                throw new JSONException("hook entry is not an object: " + id);
            }
            if (!hook.optBoolean("enabled", true)) {
                continue;
            }
            JSONObject target = hook.optJSONObject("target");
            if (target == null || target.optString("class", "").isEmpty()
                    || (!target.has("name") && !target.has("namePrefix"))
                    || (!target.has("parameterCount") && !target.has("minParameterCount"))
                    || !target.has("static")) {
                throw new JSONException("enabled hook has an incomplete target: " + id);
            }
        }
    }

    private static void validateEmbedInput(JSONObject hooks) throws JSONException {
        JSONObject embed = hooks.optJSONObject("embed.bind");
        if (embed == null || !embed.optBoolean("enabled", true)) {
            return;
        }
        int count = embed.getJSONObject("target").getInt("parameterCount");
        String source = embed.getString("inputSource");
        if ("arguments".equals(source)) {
            int model = embed.getInt("modelArgument");
            int id = embed.getInt("messageIdArgument");
            if (model < 0 || model >= count || id < 0 || id >= count || model == id
                    || embed.has("accessoryArgument")) {
                throw new JSONException("invalid Embed direct input arguments");
            }
        } else if ("accessory".equals(source)) {
            int accessory = embed.getInt("accessoryArgument");
            if (accessory < 0 || accessory >= count || embed.has("modelArgument")
                    || embed.has("messageIdArgument")) {
                throw new JSONException("invalid Embed accessory input argument");
            }
        } else {
            throw new JSONException("unsupported Embed input source");
        }
        JSONObject host = embed.getJSONObject("host");
        String hostSource = host.getString("source");
        if ("field".equals(hostSource)) {
            requireMetadataString(host.get("field"), "Embed host field");
        } else if (!"this".equals(hostSource) || host.has("field")) {
            throw new JSONException("unsupported Embed host source");
        }
        if (embed.getJSONObject("target").getBoolean("static")) {
            throw new JSONException("Embed binding needs an instance host");
        }
    }

    private static void validateNodeConstructors(JSONObject root) throws JSONException {
        if (!root.has("nodeConstructors")) {
            return;
        }
        JSONObject constructors = root.getJSONObject("nodeConstructors");
        JSONObject types = root.optJSONObject("types");
        Iterator<String> names = constructors.keys();
        while (names.hasNext()) {
            String semanticType = requireMetadataString(names.next(),
                    "node constructor semantic type");
            Object mappedType = types == null ? null : types.opt(semanticType);
            boolean mapped = mappedType instanceof String && !((String) mappedType).trim().isEmpty()
                    && mappedType.equals(((String) mappedType).trim());
            JSONObject aliases = root.optJSONObject("typeAliases");
            if (!mapped && aliases != null) {
                Iterator<String> classNames = aliases.keys();
                while (classNames.hasNext()) {
                    String className = classNames.next();
                    if (!className.trim().isEmpty() && className.equals(className.trim())
                            && semanticType.equals(aliases.opt(className))) {
                        mapped = true;
                        break;
                    }
                }
            }
            if (!mapped) {
                throw new JSONException("node constructor has no mapped type: " + semanticType);
            }
            JSONObject specification = constructors.getJSONObject(semanticType);
            JSONArray parameterTypes = specification.getJSONArray("parameterTypes");
            JSONArray arguments = specification.getJSONArray("arguments");
            if (parameterTypes.length() != arguments.length()) {
                throw new JSONException("node constructor argument count mismatch: " + semanticType);
            }
            int children = 0;
            for (int i = 0; i < parameterTypes.length(); i++) {
                String parameterType = requireMetadataString(parameterTypes.get(i),
                        "node constructor parameter type: " + semanticType);
                if (!parameterType.equals(parameterType.trim())
                        || parameterType.indexOf('*') >= 0 || "void".equals(parameterType)) {
                    throw new JSONException("node constructor needs an exact parameter type: "
                            + semanticType);
                }
                JSONObject argument = arguments.getJSONObject(i);
                String kind = requireMetadataString(argument.get("kind"),
                        "node constructor argument kind: " + semanticType);
                switch (kind) {
                    case "children":
                        children++;
                        if (!"java.util.List".equals(parameterType)) {
                            throw new JSONException("node constructor children must be java.util.List: "
                                    + semanticType);
                        }
                        break;
                    case "field":
                        requireMetadataString(argument.get("name"),
                                "node constructor field name: " + semanticType);
                        break;
                    case "integer":
                        Object value = argument.get("value");
                        if (!"int".equals(parameterType)
                                || (!(value instanceof Integer) && !(value instanceof Long))
                                || ((Number) value).longValue() < Integer.MIN_VALUE
                                || ((Number) value).longValue() > Integer.MAX_VALUE) {
                            throw new JSONException("node constructor integer must be an int: "
                                    + semanticType);
                        }
                        break;
                    default:
                        throw new JSONException("unknown node constructor argument kind: " + kind);
                }
            }
            if (children != 1) {
                throw new JSONException("node constructor needs exactly one children argument: "
                        + semanticType);
            }
        }
    }

    private static String requireMetadataString(Object value, String description)
            throws JSONException {
        if (!(value instanceof String) || ((String) value).trim().isEmpty()
                || !value.equals(((String) value).trim())) {
            throw new JSONException("missing or invalid " + description);
        }
        return (String) value;
    }

    public static void activate(HookConfiguration configuration) {
        active = configuration;
    }

    @Nullable
    public static HookConfiguration activeConfiguration() {
        return active;
    }

    public static String member(String key) {
        HookConfiguration configuration = active;
        return configuration == null ? null : configuration.string("members", key);
    }

    /** Returns a semantic type only when the active profile explicitly maps the runtime class. */
    @Nullable
    public static String semanticTypeName(Class<?> runtimeType) {
        HookConfiguration configuration = active;
        JSONObject types = configuration == null ? null : configuration.root.optJSONObject("types");
        if (types != null) {
            Iterator<String> keys = types.keys();
            while (keys.hasNext()) {
                String semanticName = keys.next();
                String configuredClass = types.optString(semanticName, "");
                if (runtimeType.getName().equals(configuredClass)) {
                    return semanticName;
                }
            }
        }
        JSONObject aliases = configuration == null ? null
                : configuration.root.optJSONObject("typeAliases");
        return aliases == null ? null : aliases.optString(runtimeType.getName(), null);
    }

    @Nullable
    public String fieldName(String semanticType, String memberName) {
        JSONObject members = root.optJSONObject("fields");
        JSONObject typeMembers = members == null ? null : members.optJSONObject(semanticType);
        return typeMembers == null ? null : typeMembers.optString(memberName, null);
    }

    @Nullable
    public String accessorName(String semanticType, String accessor) {
        JSONObject accessors = root.optJSONObject("accessors");
        JSONObject typeAccessors = accessors == null ? null : accessors.optJSONObject(semanticType);
        return typeAccessors == null ? null : typeAccessors.optString(accessor, null);
    }

    /** Exact constructor metadata for a semantic node type, or null for the copy strategy. */
    @Nullable
    public JSONObject nodeConstructor(String semanticType) {
        JSONObject constructors = root.optJSONObject("nodeConstructors");
        return constructors == null ? null : constructors.optJSONObject(semanticType);
    }

    @Nullable
    public String typeClass(String semanticName) {
        JSONObject types = root.optJSONObject("types");
        if (types == null) {
            return null;
        }
        String className = types.optString(semanticName, null);
        return className == null || className.isEmpty() ? null : className;
    }

    public int versionCode() {
        return versionCode;
    }

    public String rawJson() {
        return rawJson;
    }

    public boolean isEnabled(String hookId) {
        JSONObject hook = hook(hookId);
        return hook != null && hook.optBoolean("enabled", true);
    }

    public int requiredHookCount() {
        JSONArray required = root.optJSONArray("requiredHooks");
        return required == null ? 0 : required.length();
    }

    public boolean hasAllRequiredTargets(ClassLoader hostClassLoader) {
        JSONArray required = root.optJSONArray("requiredHooks");
        if (required == null) {
            return false;
        }
        for (int i = 0; i < required.length(); i++) {
            String hookId = required.optString(i, "");
            if (hookId.isEmpty() || !isEnabled(hookId)
                    || findMethod(hookId, hostClassLoader) == null) {
                return false;
            }
        }
        return true;
    }

    /** Recover only from this profile's binding target, never a framework/bootstrap class. */
    @Nullable
    public ClassLoader bindingClassLoader(@Nullable Class<?> declaringClass) {
        JSONObject bind = hook("message.bind");
        JSONObject target = bind == null ? null : bind.optJSONObject("target");
        String expected = target == null ? null : target.optString("class", null);
        if (declaringClass == null || !declaringClass.getName().equals(expected)) {
            return null;
        }
        ClassLoader loader = declaringClass.getClassLoader();
        return loader != null && hasAllRequiredTargets(loader) ? loader : null;
    }

    public boolean hasAllRequiredHooks(Set<String> installedHookIds) {
        JSONArray required = root.optJSONArray("requiredHooks");
        if (required == null || required.length() == 0 || installedHookIds == null) {
            return false;
        }
        for (int i = 0; i < required.length(); i++) {
            String hookId = required.optString(i, "");
            if (hookId.isEmpty() || !isEnabled(hookId) || !installedHookIds.contains(hookId)) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    public JSONObject iconClickConfiguration() {
        return root.optJSONObject("iconClick");
    }

    @Nullable
    public JSONObject hook(String id) {
        JSONObject hooks = root.optJSONObject("hooks");
        return hooks == null ? null : hooks.optJSONObject(id);
    }

    @Nullable
    public String string(String section, String key) {
        JSONObject object = root.optJSONObject(section);
        return object == null ? null : object.optString(key, null);
    }

    /** Finds a declared method using only the selector stored in this version's config. */
    @Nullable
    public Method findMethod(String hookId, ClassLoader hostClassLoader) {
        JSONObject hook = hook(hookId);
        JSONObject target = hook == null ? null : hook.optJSONObject("target");
        if (target == null) {
            return null;
        }
        String className = target.optString("class", "");
        if (className.isEmpty()) {
            return null;
        }
        Class<?> owner = Reflect.loadClass(hostClassLoader, className);
        return owner == null ? null : findMethod(owner, target);
    }

    @Nullable
    public static Method findMethod(Class<?> owner, JSONObject selector) {
        String exactName = selector.optString("name", null);
        String prefix = selector.optString("namePrefix", null);
        boolean requireStatic = selector.optBoolean("static", false);
        int exactCount = selector.optInt("parameterCount", -1);
        int minCount = selector.optInt("minParameterCount", -1);
        String returnType = selector.optString("returnType", null);
        JSONObject parameterTypes = selector.optJSONObject("parameterTypes");
        String selection = selector.optString("selection", "first");

        Method best = null;
        for (Method method : owner.getDeclaredMethods()) {
            if (exactName != null && !exactName.equals(method.getName())) {
                continue;
            }
            if (prefix != null && !method.getName().startsWith(prefix)) {
                continue;
            }
            if ((exactName == null && prefix == null)
                    || Modifier.isStatic(method.getModifiers()) != requireStatic) {
                continue;
            }
            Class<?>[] actualTypes = method.getParameterTypes();
            if (exactCount >= 0 && actualTypes.length != exactCount) {
                continue;
            }
            if (minCount >= 0 && actualTypes.length < minCount) {
                continue;
            }
            if (returnType != null && !returnType.equals(typeName(method.getReturnType()))) {
                continue;
            }
            if (!matchesParameterTypes(actualTypes, parameterTypes)) {
                continue;
            }
            if (best == null || ("mostParameters".equals(selection)
                    && actualTypes.length > best.getParameterCount())) {
                best = method;
            }
        }
        if (best != null) {
            best.setAccessible(true);
        }
        return best;
    }

    private static boolean matchesParameterTypes(Class<?>[] actualTypes,
                                                  @Nullable JSONObject expectedTypes) {
        if (expectedTypes == null) {
            return true;
        }
        Iterator<String> keys = expectedTypes.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            final int index;
            try {
                index = Integer.parseInt(key);
            } catch (NumberFormatException ignored) {
                return false;
            }
            if (index < 0 || index >= actualTypes.length) {
                return false;
            }
            String expected = expectedTypes.optString(key, "*");
            if (!"*".equals(expected) && !expected.equals(typeName(actualTypes[index]))) {
                return false;
            }
        }
        return true;
    }

    private static String typeName(Class<?> type) {
        if (type.isArray()) {
            return typeName(type.getComponentType()) + "[]";
        }
        return type.getName();
    }
}
