package io.github.wxmyyds.discordautotranslate.hooks;

import android.view.View;

import org.json.JSONObject;

import io.github.wxmyyds.discordautotranslate.core.HookConfiguration;
import io.github.wxmyyds.discordautotranslate.core.Reflect;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Native rich Embed binding; all requests and replacements use the shared message pipeline. */
public final class EmbedHooks {
    private EmbedHooks() {}

    public static List<XposedInterface.HookHandle> install(XposedModule module, ClassLoader cl,
            HookConfiguration config, Set<String> installedHookIds) {
        List<XposedInterface.HookHandle> handles = new ArrayList<>(1);
        if (!config.isEnabled("embed.bind")) {
            return handles;
        }
        Method bind = config.findMethod("embed.bind", cl);
        JSONObject spec = config.hook("embed.bind");
        if (bind == null || spec == null) {
            throw new IllegalStateException("Configured Embed binding target unavailable");
        }
        HookDiagnostics.infoOnce("embed.bind.target", "embed.bind target=" + bind.toGenericString());
        handles.add(module.hook(bind)
                .setId("dat.embedBind")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    View view = hostView(chain.getThisObject(), spec);
                    EmbedBinding binding = null;
                    String primary = null;
                    try {
                        if (!HookBridge.get().isRerendering() && view != null) {
                            // Clear even when the new card has no translatable text or invalid input.
                            // Old queued callbacks must never replay earlier same-row card parameters.
                            RerenderRegistry.unregister(view);
                            HookBridge.get().unbindView(view);
                        }
                        binding = EmbedBinding.read(chain.getArgs(), spec);
                        if (binding != null && view != null) {
                            primary = register(binding, view, chain.getThisObject(), bind,
                                    chain.getArgs().toArray());
                        }
                    } catch (Throwable t) {
                        HookDiagnostics.warnOnce("embed.bind.failed", "embed binding failed error="
                                + t.getClass().getSimpleName());
                    }
                    try (InlineIconInjector.HostScope scope =
                                 InlineIconInjector.enterHost(view, primary)) {
                        return chain.proceed();
                    }
                }));
        installedHookIds.add("embed.bind");
        return handles;
    }

    /** Register all current segments before any cache hit can enqueue a native rebind. */
    private static String register(EmbedBinding binding, View view, Object target,
                                   Method bind, Object[] args) {
        HookBridge bridge = HookBridge.get();
        List<String> keys = new ArrayList<>(binding.texts.size());
        String primary = null;
        for (Object text : binding.texts) {
            String key = bridge.embedStateKey(binding.messageId, text);
            keys.add(key);
            if (key == null) {
                continue;
            }
            if (primary == null) {
                primary = key;
            }
            // Identity mappings also cover rerenders; conversion never creates a request.
            bridge.rememberContent(key, text);
            if (!bridge.isRerendering()) {
                bridge.bindView(view, key);
                RerenderRegistry.register(key, view, target, bind, args);
            }
        }
        if (!bridge.isRerendering()) {
            for (int i = 0; i < keys.size(); i++) {
                if (keys.get(i) != null) {
                    bridge.onMessageBound(view, keys.get(i), binding.texts.get(i));
                }
            }
        }
        return primary;
    }

    private static View hostView(Object target, JSONObject spec) throws org.json.JSONException {
        JSONObject host = spec.getJSONObject("host");
        String source = host.getString("source");
        Object view;
        if ("this".equals(source)) {
            view = target;
        } else if ("field".equals(source)) {
            view = Reflect.getField(target, host.getString("field"));
        } else {
            throw new IllegalArgumentException("Unsupported Embed host source");
        }
        if (!(view instanceof View)) {
            throw new IllegalStateException("Configured Embed host unavailable");
        }
        return (View) view;
    }
}
