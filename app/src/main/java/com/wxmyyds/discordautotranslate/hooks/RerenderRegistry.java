package com.wxmyyds.discordautotranslate.hooks;

import android.view.View;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 视图重渲染登记表：把 messageId 映射到<b>一组</b>可重渲染目标。
 *
 * <p>翻译是异步完成的，完成时需要通过消息 ID 找回对应视图重新渲染。</p>
 *
 * <h3>为何需要“一组”而非单个视图</h3>
 * <p>同一条消息的正文可能同时出现在多个位置：消息自身所在的行，
 * 以及其他消息中引用它的回复预览（{@code MessageViewReplyPreview.replyText}）。
 * 若只记录一个视图，另一处就不会随译文更新。</p>
 *
 * <h3>为何记录“调用目标 + 方法 + 实参”而非只记录视图</h3>
 * <p>消息正文的重渲染入口是 {@code MessageContentView.setMessageContent}，
 * 而回复引用的重渲染入口是父容器
 * {@code MessageViewReplyPreview.configureReply}（回复引用的正文由它内部渲染）。
 * 两者方法、接收者、实参都不同，因此每个目标都显式保存这三项。</p>
 *
 * <h3>复用防护</h3>
 * <p>列表会复用视图。渲染前逐个校验“视图当前绑定的消息 ID 是否仍是目标消息”，
 * 不是则从登记表移除，避免把译文渲染到错误的消息上（需求 §风险 2）。
 * 视图与调用目标均用弱引用，避免长期持有已回收的列表项。</p>
 */
public final class RerenderRegistry {

    /** 单个重渲染目标。 */
    public static final class Target {
        /** 用于校验“是否仍绑定目标消息”的正文视图。 */
        private final WeakReference<View> contentView;
        /** 实际调用 {@link #method} 的接收者（正文视图本身，或回复预览容器）。 */
        private final WeakReference<Object> invocationTarget;
        private final Method method;
        /** 原始实参快照；重渲染即用同一组实参再跑一次。 */
        private final Object[] args;

        Target(View contentView, Object invocationTarget, Method method, Object[] args) {
            this.contentView = new WeakReference<>(contentView);
            this.invocationTarget = new WeakReference<>(invocationTarget);
            this.method = method;
            this.args = args;
        }
    }

    /** messageId -> 承载它的视图集合。 */
    private static final Map<String, List<Target>> TARGETS = new ConcurrentHashMap<>();

    /** 登记上限，避免快速滚动时无限增长。 */
    private static final int MAX_MESSAGES = 1024;

    private RerenderRegistry() {
    }

    /**
     * 登记一个重渲染目标。同一正文视图重复登记不会产生重复项，
     * 但会刷新保存的实参（消息可能被重新渲染为最新内容）。
     *
     * @param contentView      正文视图，用于复用校验
     * @param invocationTarget 调用 method 的接收者
     * @param method           重渲染方法
     * @param args             原始实参
     */
    public static void register(String messageId, View contentView, Object invocationTarget,
                                Method method, Object[] args) {
        if (messageId == null || contentView == null || invocationTarget == null
                || method == null || args == null) {
            return;
        }
        pruneIfNeeded();
        List<Target> list = TARGETS.computeIfAbsent(messageId, k -> new ArrayList<>(2));
        synchronized (list) {
            for (int i = 0; i < list.size(); i++) {
                Target existing = list.get(i);
                if (existing.contentView.get() == contentView) {
                    // 同一视图再次渲染：替换为新实参，避免重渲染回退到旧内容。
                    list.set(i, new Target(contentView, invocationTarget, method, args.clone()));
                    return;
                }
            }
            list.add(new Target(contentView, invocationTarget, method, args.clone()));
        }
    }

    public static void clear() {
        TARGETS.clear();
    }

    /**
     * 视图是否仍可承载该消息的渲染。
     *
     * <p>绑定未知（{@code null}）不等于已复用：映射表是容量上限 512 的 LRU，
     * 长列表滚动后旧条目会被淘汰。只有确认绑定了其他消息时才必须跳过。</p>
     */
    static boolean stillBound(@Nullable String expected, @Nullable String actual) {
        return expected == null || actual == null || expected.equals(actual);
    }

    /**
     * 用最新状态重新渲染该消息的全部视图（消息行 + 所有引用它的回复预览）。
     *
     * <p>视图已回收或确认已被复用到其他消息时会跳过并清理。注意：
     * “绑定未知”不等于“已复用”——{@code viewToMessageId} 是容量上限 512 的 LRU，
     * 长列表滚动后旧条目会被淘汰。若把未知也当成已复用而删除登记项，
     * 该消息就再也不会因译文完成而刷新，只有重新 bind（滚动）才恢复。</p>
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
            for (Iterator<Target> it = list.iterator(); it.hasNext(); ) {
                Target target = it.next();
                View contentView = target.contentView.get();
                if (contentView == null) {
                    it.remove();
                    continue;
                }
                String bound = HookBridge.get().messageIdOf(contentView);
                if (!stillBound(messageId, bound)) {
                    // 已确认被复用到别的消息，不能再用它渲染本条消息。
                    it.remove();
                    continue;
                }
                Object invocationTarget = target.invocationTarget.get();
                if (invocationTarget == null) {
                    it.remove();
                    continue;
                }
                HookBridge.get().requestRerender(contentView, messageId,
                        invocationTarget, target.method, target.args);
            }
            if (list.isEmpty()) {
                TARGETS.remove(messageId, list);
            }
        }
    }

    /** 定期清理已回收视图，并把登记表压回上限以内。 */
    private static void pruneIfNeeded() {
        if (TARGETS.size() < MAX_MESSAGES) {
            return;
        }
        for (Iterator<Map.Entry<String, List<Target>>> entries = TARGETS.entrySet().iterator();
                entries.hasNext(); ) {
            List<Target> list = entries.next().getValue();
            synchronized (list) {
                for (Iterator<Target> it = list.iterator(); it.hasNext(); ) {
                    if (it.next().contentView.get() == null) {
                        it.remove();
                    }
                }
                if (list.isEmpty()) {
                    entries.remove();
                    continue;
                }
            }
            if (TARGETS.size() < MAX_MESSAGES) {
                return;
            }
        }
        // 仍然超限：丢弃最早登记的键，避免无界增长。
        Iterator<String> keys = TARGETS.keySet().iterator();
        if (keys.hasNext()) {
            keys.next();
            keys.remove();
        }
    }
}
