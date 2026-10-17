package com.wxmyyds.discordautotranslate.hooks;

import android.view.View;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 视图重渲染登记表：把 messageId 映射到“当前承载它的视图与渲染方法”。
 *
 * <p>翻译是异步完成的，完成时需要通过消息 ID 找回对应视图重新渲染。
 * 由于列表会复用视图，这里存的永远是“最近一次绑定该消息”的视图，
 * 且渲染前会再次校验视图当前绑定的消息 ID 是否仍是目标消息（需求 §风险 2）。</p>
 */
public final class RerenderRegistry {

    /** 渲染目标。视图用弱引用，避免长期持有已回收的列表项。 */
    public static final class Target {
        public final WeakReference<View> view;
        public final Method setMessageContent;

        Target(View v, Method m) {
            this.view = new WeakReference<>(v);
            this.setMessageContent = m;
        }
    }

    private static final Map<String, Target> TARGETS = new ConcurrentHashMap<>();

    private RerenderRegistry() {
    }

    public static void register(String messageId, View view, Method setMessageContent) {
        if (messageId == null || view == null || setMessageContent == null) {
            return;
        }
        TARGETS.put(messageId, new Target(view, setMessageContent));
    }

    public static void clear() {
        TARGETS.clear();
    }

    /**
     * 用最新状态重新渲染该消息。
     * 视图已回收或已被复用到别的消息时自动跳过，避免译文串位。
     */
    public static void rerender(String messageId) {
        if (messageId == null) {
            return;
        }
        Target t = TARGETS.get(messageId);
        if (t == null) {
            return;
        }
        View v = t.view.get();
        if (v == null) {
            TARGETS.remove(messageId);
            return;
        }
        String bound = HookBridge.get().messageIdOf(v);
        if (!messageId.equals(bound)) {
            // 视图已被复用到别的消息，不能再用它渲染本条消息。
            TARGETS.remove(messageId);
            return;
        }
        HookBridge.get().requestRerender(v, t.setMessageContent);
    }

    @Nullable
    public static View viewOf(String messageId) {
        Target t = TARGETS.get(messageId);
        if (t == null) {
            return null;
        }
        View v = t.view.get();
        if (v == null) {
            TARGETS.remove(messageId);
        }
        return v;
    }
}
