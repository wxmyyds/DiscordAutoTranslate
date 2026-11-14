package io.github.wxmyyds.discordautotranslate.hooks;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.util.List;

/** Exact profile paths into native Poll inputs; never writes a host model. */
final class PollBinding {
    final String messageId;
    final String slot;
    final String text;
    final Object host;
    final Object receiver;
    final boolean accessibility;

    private PollBinding(String messageId, String slot, String text, Object host,
                        Object receiver, boolean accessibility) {
        this.messageId = messageId;
        this.slot = slot;
        this.text = text;
        this.host = host;
        this.receiver = receiver;
        this.accessibility = accessibility;
    }

    static Object receiver(Object target, List<?> args, JSONObject spec, boolean answer) throws Exception {
        if (!answer) return path(target, spec.getJSONArray("textViewPath"));
        Object holder = argument(args, spec.getInt("holderArgument"));
        Object binding = path(holder, spec.getJSONArray("holderBindingPath"));
        return path(binding, answerViewPath(binding, spec));
    }

    private static JSONArray answerViewPath(Object binding, JSONObject spec) throws Exception {
        if (binding != null && binding.getClass().getName().equals(spec.getString("textBindingClass")))
            return spec.getJSONArray("textViewPath");
        if (binding != null && binding.getClass().getName().equals(spec.getString("imageBindingClass")))
            return spec.getJSONArray("imageViewPath");
        throw new IllegalStateException("Unknown configured Poll answer binding type");
    }

    static PollBinding question(Object holder, List<?> args, JSONObject spec) throws Exception {
        Object accessory = argument(args, spec.getInt("accessoryArgument"));
        String id = string(path(accessory, spec.getJSONArray("messageIdPath")));
        Object text = path(accessory, spec.getJSONArray("textPath"));
        Object host = path(holder, spec.getJSONArray("hostPath"));
        Object receiver = path(holder, spec.getJSONArray("textViewPath"));
        return new PollBinding(id, "question", string(text), host, receiver, false);
    }

    static PollBinding answer(Object adapter, List<?> args, JSONObject spec) throws Exception {
        Object items = path(adapter, spec.getJSONArray("itemsPath"));
        if (!(items instanceof List<?>)) {
            throw new IllegalStateException("Configured Poll answers list unavailable");
        }
        Object position = argument(args, spec.getInt("positionArgument"));
        if (!(position instanceof Integer) || (Integer) position < 0
                || (Integer) position >= ((List<?>) items).size()) {
            throw new IllegalStateException("Poll answer position outside current list");
        }
        Object item = ((List<?>) items).get((Integer) position);
        String id = string(path(item, spec.getJSONArray("messageIdPath")));
        String answerId = string(path(item, spec.getJSONArray("answerIdPath")));
        if (answerId == null || answerId.isEmpty() || answerId.indexOf('\u0000') >= 0) {
            throw new IllegalStateException("Invalid Poll answer identity");
        }
        String text = string(path(item, spec.getJSONArray("textPath")));
        Object holder = argument(args, spec.getInt("holderArgument"));
        Object binding = path(holder, spec.getJSONArray("holderBindingPath"));
        boolean accessibility;
        JSONArray viewPath = answerViewPath(binding, spec);
        if (binding != null && binding.getClass().getName().equals(spec.getString("textBindingClass"))) {
            accessibility = false;
        } else if (binding != null && binding.getClass().getName().equals(spec.getString("imageBindingClass"))) {
            accessibility = true;
        } else {
            throw new IllegalStateException("Unknown configured Poll answer binding type");
        }
        Object receiver = path(binding, viewPath);
        return new PollBinding(id, "answer:" + answerId, text, receiver, receiver, accessibility);
    }

    boolean sameInput(PollBinding other) {
        return other != null && receiver == other.receiver && accessibility == other.accessibility
                && java.util.Objects.equals(messageId, other.messageId)
                && java.util.Objects.equals(slot, other.slot)
                && java.util.Objects.equals(text, other.text);
    }

    /** Native setter must match both its exact receiver and captured raw input. */
    boolean matches(Object view, Object input, boolean accessibilitySetter) {
        return view == receiver && accessibility == accessibilitySetter
                && (input == null || input instanceof String)
                && java.util.Objects.equals(text, input);
    }

    static Object path(Object object, JSONArray fields) throws Exception {
        Object value = object;
        for (int i = 0; i < fields.length(); i++) {
            if (value == null) {
                return null;
            }
            String name = fields.getString(i);
            Class<?> type = value.getClass();
            Field selected = null;
            while (type != null) {
                try {
                    selected = type.getDeclaredField(name);
                    break;
                } catch (NoSuchFieldException absent) {
                    type = type.getSuperclass();
                }
            }
            if (selected == null) {
                throw new IllegalStateException("Configured Poll field unavailable");
            }
            selected.setAccessible(true);
            value = selected.get(value);
        }
        return value;
    }

    private static Object argument(List<?> args, int index) {
        if (index < 0 || index >= args.size()) {
            throw new IllegalStateException("Poll argument outside configured signature");
        }
        return args.get(index);
    }

    private static String string(Object value) {
        if (value == null || value instanceof String) {
            return (String) value;
        }
        throw new IllegalStateException("Configured Poll text/identity is not a String");
    }
}
