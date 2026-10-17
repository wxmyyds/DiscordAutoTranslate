package com.wxmyyds.discordautotranslate.hooks;

import android.view.View;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 视图重渲染登记表：把 messageId 映射到<b>一组</b>承载它的视图与渲染方法。
 *
 * <p>翻译是异步完成的，完成时需要通过消息 ID 找回对应视图重新渲染。</p>
 *
 * <h3>为何需要“一组”而非单个视图</h3>
 * <p>同一条消息的正文可能同时出现在多个位置：消息自身所在的行，
 * 以及其他消息中引用它的回复预览（{@code MessageViewReplyPreview.replyText}）。
 * 若只记录一个视图，另一处就不会随译文更新。
 * 因此这里按消息 ID 维护视图列表。</p>
 *
 * <h3>复用防护</h3>
 * <p>列表会复用视图。渲染前逐个校验“视图当前绑定的消息 ID 是否仍是目标消息”，
 * 不是则从登记表移除，避免把译文渲染到错误的消息上（需求 §风险 2）。
 * 视图用弱引用，避免长期持有已回收的列表项。</p>
 */
public final class RerenderRegistry {

    /** 单个渲染目标。 */
    public static final class Target {
        public final WeakReference<View> view;
        public final Method setMessageContent;

        Target(View v, Method m) {
            this.view = new WeakReference<>(v);
            this.setMessageContent = m;
        }
    }

    /** messageId -> 承载它的视图集合。 */
    private static final Map<String, List<Target>> TARGETS = new ConcurrentHashMap<>();

    private RerenderRegistry() {
    }

    /** 登记一个渲染目标。同一视图重复登记不会产生重复项。 */
    public static void register(String messageId, View view, Method setMessageContent) {
        if (messageId == null || view == null || setMessageContent == null) {
            return;
        }
        List<Target> list = TARGETS.computeIfAbsent(messageId, k -> new ArrayList<>(2));
        synchronized (list) {
            // 去重：同一视图已登记则不再重复添加。
            for (Target t : list) {
                if (t.view.get() == view) {
                    return;
                }
            }
            list.add(new Target(view, setMessageContent));
        }
    }

    public static void clear() {
        TARGETS.clear();
    }

    /**
     * 用最新状态重新渲染该消息的全部视图（消息行 + 所有引用它的回复预览）。
     * 视图已回收或已被复用到其他消息时会自动跳过并清理。
     */
    public static void rerender(String messageId) {
        if (messageId == null) {
            return;
        }
        List<Target> list = TARGETS.get(messageId);
        if (list == null) {
            return;
        }
        synchronized (list) {
            for (java.util.Iterator<Target> it = list.iterator(); it.hasNext(); ) {
                Target t = it.next();
                View v = t.view.get();
                if (v == null) {
                    it.remove();
                    continue;
                }
                String bound = HookBridge.get().messageIdOf(v);
                if (!messageId.equals(bound)) {
                    // 视图已被复用到别的消息，不能再用它渲染本条消息。
                    it.remove();
                    continue;
                }
                HookBridge.get().requestRerender(v, t.setMessageContent);
            }
            if (list.isEmpty()) {
                TARGETS.remove(messageId, list);
            }
        }
    }

    /** 取任一仍在有效承载该消息的视图（用于重试）。 */
    public static View viewOf(String messageId) {
        List<Target> list = TARGETS.get(messageId);
        if (list == null) {
            return null;
        }
        synchronized (list) {
            for (java.util.Iterator<Target> it = list.iterator(); it.hasNext(); ) {
                Target t = it.next();
                View v = t.view.get();
                if (v == null) {
                    it.remove();
                    continue;
                }
                if (messageId.equals(HookBridge.get().messageIdOf(v))) {
                    return v;
                }
                it.remove();
            }
        }
        return null;
    }
}
