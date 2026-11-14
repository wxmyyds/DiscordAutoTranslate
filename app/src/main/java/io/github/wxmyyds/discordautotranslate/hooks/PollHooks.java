package io.github.wxmyyds.discordautotranslate.hooks;

import android.view.View;
import android.widget.TextView;

import io.github.wxmyyds.discordautotranslate.core.HookConfiguration;
import io.github.wxmyyds.discordautotranslate.core.StateStore;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Raw Poll text uses its native setter; state, requests, icons and rerenders remain shared. */
public final class PollHooks {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private static final class Scope implements AutoCloseable {
        final PollBinding binding;
        final Object original;
        final String key;
        final Scope previous;

        Scope(PollBinding binding, Object original, String key) {
            this.binding = binding;
            this.original = original;
            this.key = key;
            previous = CURRENT.get();
            CURRENT.set(this);
        }

        @Override public void close() {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    private PollHooks() {}

    public static List<XposedInterface.HookHandle> install(XposedModule module, ClassLoader cl,
            HookConfiguration config, Set<String> installedHookIds) {
        List<XposedInterface.HookHandle> handles = new ArrayList<>(4);
        try {
            installSetter(module, cl, config, "poll.textSet", false, handles, installedHookIds);
            installSetter(module, cl, config, "poll.accessibilitySet", true, handles, installedHookIds);
            installBind(module, cl, config, "poll.bind", false, handles, installedHookIds);
            installBind(module, cl, config, "poll.answerBind", true, handles, installedHookIds);
            return handles;
        } catch (Throwable failure) {
            for (XposedInterface.HookHandle handle : handles) {
                try { handle.unhook(); } catch (Throwable ignored) { /* Continue rollback. */ }
            }
            throw new IllegalStateException("Poll hook installation failed", failure);
        }
    }

    private static Method target(HookConfiguration config, String id, ClassLoader cl) {
        Method method = config.findMethod(id, cl);
        if (method == null) throw new IllegalStateException("Configured Poll target unavailable");
        return method;
    }

    private static void installBind(XposedModule module, ClassLoader cl, HookConfiguration config,
            String id, boolean answer, List<XposedInterface.HookHandle> handles,
            Set<String> installedHookIds) {
        if (!config.isEnabled(id)) return;
        Method method = target(config, id, cl);
        JSONObject spec = config.hook(id);
        handles.add(module.hook(method).setId("dat." + id)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    PollBinding binding;
                    Object original;
                    String key;
                    HookBridge bridge = HookBridge.get();
                    try {
                        if (!bridge.isRerendering()) {
                            Object receiver = PollBinding.receiver(chain.getThisObject(),
                                    chain.getArgs(), spec, answer);
                            if (!(receiver instanceof View))
                                throw new IllegalStateException("Configured Poll receiver unavailable");
                            RerenderRegistry.unregister((View) receiver);
                            bridge.unbindView((View) receiver);
                            if (receiver instanceof TextView) ((View) receiver).setOnTouchListener(null);
                        }
                        binding = answer ? PollBinding.answer(chain.getThisObject(), chain.getArgs(), spec)
                                : PollBinding.question(chain.getThisObject(), chain.getArgs(), spec);
                        if (!(binding.host instanceof View) || !(binding.receiver instanceof View)) {
                            throw new IllegalStateException("Configured Poll views unavailable");
                        }
                        View view = (View) binding.receiver;
                        original = binding.text == null ? null : StructurableTextFactory.primitive(binding.text);
                        if (binding.text != null && original == null) {
                            throw new IllegalStateException("Poll structured input construction failed");
                        }
                        key = original == null || !HookBridge.looksLikeSnowflake(binding.messageId) ? null
                                : bridge.pollStateKey(binding.messageId, binding.slot, original);
                        if (bridge.isRerendering()) {
                            // An answer's saved adapter position may now name another slot or text.
                            // Never replay it merely because it still belongs to the same poll row.
                            if (key == null || !key.equals(bridge.directMessageIdOf(view))) return null;
                        } else {
                            if (key != null) {
                                bridge.bindView(view, key);
                                RerenderRegistry.register(key, view, chain.getThisObject(), method,
                                        chain.getArgs().toArray());
                                bridge.onMessageBound(view, key, original);
                            }
                        }
                        if (key != null) bridge.rememberContent(key, original);
                    } catch (Throwable failure) {
                        HookDiagnostics.warnOnce("poll.bind.failed." + answer,
                                "poll binding failed error=" + failure.getClass().getSimpleName());
                        // Stale positional replays must not reach the native bind with invalid args.
                        if (bridge.isRerendering()) return null;
                        try (Scope blocked = new Scope(null, null, null)) {
                            return chain.proceed();
                        }
                    }
                    try (Scope scope = new Scope(binding, original, key)) {
                        return chain.proceed();
                    }
                }));
        installedHookIds.add(id);
        HookDiagnostics.infoOnce(id + ".target", id + " target=" + method.toGenericString());
    }

    private static void installSetter(XposedModule module, ClassLoader cl, HookConfiguration config,
            String id, boolean accessibility, List<XposedInterface.HookHandle> handles,
            Set<String> installedHookIds) {
        if (!config.isEnabled(id)) return;
        Method method = target(config, id, cl);
        int contentArgument = config.hook(id).optInt("contentArgument", -1);
        handles.add(module.hook(method).setId("dat." + id)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Scope scope = CURRENT.get();
                    if (scope == null || scope.key == null || contentArgument < 0
                            || contentArgument >= chain.getArgs().size()
                            || !scope.binding.matches(chain.getThisObject(),
                                    chain.getArg(contentArgument), accessibility)) {
                        return chain.proceed();
                    }
                    Object[] args = chain.getArgs().toArray();
                    CharSequence display;
                    try {
                        StateStore.Entry state = HookBridge.get()
                                .existingReferencedMessageState(scope.key, scope.original);
                        display = PollTextProjection.display(scope.binding.text, state);
                        if (!accessibility) {
                            android.text.SpannableStringBuilder text =
                                    new android.text.SpannableStringBuilder(display);
                            try (InlineIconInjector.HostScope iconScope = InlineIconInjector.enterHost(
                                    (View) scope.binding.receiver, scope.key)) {
                                InlineIconInjector.inject(scope.original, text,
                                        ((View) scope.binding.receiver).getContext(), cl);
                            }
                            display = text;
                        }
                        args[contentArgument] = display;
                    } catch (Throwable failure) {
                        HookDiagnostics.warnOnce("poll.text.failed", "poll text projection failed error="
                                + failure.getClass().getSimpleName());
                        return chain.proceed(); // Reject the projection before the one native write.
                    }
                    Object result = chain.proceed(args);
                    if (!accessibility) {
                        try {
                            PollIconTouch.attach((TextView) scope.binding.receiver,
                                    (android.text.Spanned) display);
                        } catch (Throwable failure) {
                            HookDiagnostics.warnOnce("poll.icon.touch.failed",
                                    "poll icon touch installation failed error="
                                            + failure.getClass().getSimpleName());
                        }
                    }
                    return result;
                }));
        installedHookIds.add(id);
    }
}
