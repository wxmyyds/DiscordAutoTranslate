package io.github.wxmyyds.discordautotranslate.hooks;

import androidx.annotation.Nullable;

import io.github.wxmyyds.discordautotranslate.core.HookConfiguration;
import io.github.wxmyyds.discordautotranslate.core.Reflect;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Profile-selected Embed input and native structured segments; no rendering or requests. */
final class EmbedBinding {
    private static final int MAX_FIELDS = 25;

    final String messageId;
    final List<Object> texts;

    private EmbedBinding(String messageId, List<Object> texts) {
        this.messageId = messageId;
        this.texts = texts;
    }

    @Nullable
    static EmbedBinding read(List<?> args, JSONObject spec) throws org.json.JSONException {
        Object embed;
        Object id;
        String source = spec.optString("inputSource", "");
        if ("arguments".equals(source)) {
            embed = argument(args, spec.getInt("modelArgument"));
            id = argument(args, spec.getInt("messageIdArgument"));
        } else if ("accessory".equals(source)) {
            Object accessory = argument(args, spec.getInt("accessoryArgument"));
            if (accessory == null) {
                return null;
            }
            id = readMember(accessory, "getMessageId");
            embed = readMember(accessory, "getEmbed");
        } else {
            throw new IllegalArgumentException("Unsupported Embed input source");
        }
        if (!(id instanceof String) || !HookBridge.looksLikeSnowflake((String) id)
                || embed == null) {
            return null;
        }
        List<Object> texts = new ArrayList<>();
        add(texts, readMember(embed, "getDescription"));
        add(texts, readMember(embed, "getTitle"));
        Object fields = readMember(embed, "getFields");
        if (fields != null) {
            if (!(fields instanceof List<?>)) {
                throw new IllegalArgumentException("Invalid Embed fields type");
            }
            List<?> list = (List<?>) fields;
            if (list.size() > MAX_FIELDS) {
                throw new IllegalArgumentException("Embed field count exceeds host limit");
            }
            for (Object field : list) {
                if (field != null) {
                    add(texts, readMember(field, "getName"));
                    add(texts, readMember(field, "getValue"));
                }
            }
        }
        return new EmbedBinding((String) id, texts);
    }

    private static void add(List<Object> texts, Object text) {
        if (text != null) {
            texts.add(text);
        }
    }

    private static Object argument(List<?> args, int index) {
        if (index < 0 || index >= args.size()) {
            throw new IllegalArgumentException("Embed argument outside configured signature");
        }
        return args.get(index);
    }

    /** One configured member only. Missing metadata/read failures are not nullable content. */
    private static Object readMember(Object owner, String member) {
        HookConfiguration config = HookConfiguration.activeConfiguration();
        if (config == null) {
            throw new IllegalStateException("No active Embed profile");
        }
        String type = HookConfiguration.semanticTypeName(owner.getClass());
        String accessor = type == null ? null : config.accessorName(type, member);
        if (accessor == null) {
            accessor = config.string("members", member);
        }
        try {
            if (accessor != null) {
                Method method = Reflect.findMethodExact(owner.getClass(), accessor, 0);
                if (method == null) {
                    throw new IllegalStateException("Configured Embed accessor unavailable");
                }
                return method.invoke(owner);
            }
            String name = type == null ? null : config.fieldName(type, member);
            if (name == null) {
                throw new IllegalStateException("Configured Embed member unavailable");
            }
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Configured Embed member read failed", e);
        }
    }
}
