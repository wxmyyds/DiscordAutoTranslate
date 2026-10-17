package com.wxmyyds.discordautotranslate.hooks;

import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.wxmyyds.discordautotranslate.core.StateStore;
import com.wxmyyds.discordautotranslate.core.XLog;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 翻译图标登记表：messageId → 图标视图，并负责把状态刷新到图标。
 *
 * <p>图标挂在 {@code DecoratedMessageView}（FrameLayout，消息行最外层原生容器）上，
 * 与消息正文 {@code MessageContentView} 分离，避免干扰 Discord 原有布局（需求 §5.2）。</p>
 *
 * <p>由于 RecyclerView 会复用整行视图，图标实例也会被复用；因此每次挂载都必须显式
 * 更新图标当前代表的消息 ID，并解除旧消息的登记，否则会出现译文/状态串位
 * （需求 §风险 2）。</p>
 */
public final class IconRegistry {

    private static final Map<String, WeakReference<TranslationIconView>> ICONS =
            new ConcurrentHashMap<>();

    private IconRegistry() {
    }

    /**
     * 确保消息行上有图标，并把它绑定到指定消息。
     *
     * @return 图标视图；行容器不可用时返回 null
     */
    @Nullable
    public static TranslationIconView attach(String messageId, @Nullable ViewGroup rowContainer) {
        if (messageId == null || rowContainer == null) {
            return null;
        }
        TranslationIconView icon = TranslationIconView.attachTo(rowContainer);
        if (icon == null) {
            return null;
        }

        // 行被复用：解除该图标原来代表的消息，避免旧消息状态残留。
        String previous = icon.getMessageId();
        if (previous != null && !previous.equals(messageId)) {
            WeakReference<TranslationIconView> old = ICONS.get(previous);
            if (old != null && old.get() == icon) {
                ICONS.remove(previous);
            }
        }

        icon.setMessageId(messageId);
        icon.setOnClickListener(v -> onClick(icon));
        ICONS.put(messageId, new WeakReference<>(icon));
        refresh(messageId);
        return icon;
    }

    /** 图标点击行为（需求 §2.4 / §2.6）。 */
    private static void onClick(TranslationIconView icon) {
        String id = icon.getMessageId();
        if (id == null) {
            return;
        }
        StateStore.Entry e = StateStore.get().peek(id);
        if (e == null) {
            return;
        }
        switch (e.status) {
            case TRANSLATED:
                // 切换原文/译文，不再发起新请求。
                HookBridge.get().toggleShowingOriginal(id);
                break;
            case FAILED:
                HookBridge.get().retry(id);
                break;
            default:
                // 翻译中或未开始：忽略，避免重复触发。
                break;
        }
    }

    /** 刷新某条消息的图标状态。 */
    public static void refresh(String messageId) {
        if (messageId == null) {
            return;
        }
        WeakReference<TranslationIconView> ref = ICONS.get(messageId);
        if (ref == null) {
            return;
        }
        TranslationIconView icon = ref.get();
        if (icon == null) {
            ICONS.remove(messageId);
            return;
        }
        // 图标可能已被复用到别的消息：此时不能用它显示本条消息的状态。
        if (!messageId.equals(icon.getMessageId())) {
            ICONS.remove(messageId);
            return;
        }
        icon.applyState(StateStore.get().peek(messageId));
    }

    /** 行被回收/复用时调用：解除图标与消息的关联并隐藏。 */
    public static void onRowRecycled(@Nullable ViewGroup rowContainer) {
        TranslationIconView icon = TranslationIconView.findByTag(rowContainer);
        if (icon == null) {
            return;
        }
        String id = icon.getMessageId();
        if (id != null) {
            WeakReference<TranslationIconView> ref = ICONS.get(id);
            if (ref != null && ref.get() == icon) {
                ICONS.remove(id);
            }
        }
        icon.setMessageId(null);
        icon.applyState(null);
        XLog.d("icon recycled" + (id == null ? "" : " (was " + id + ")"));
    }

    public static void clear() {
        ICONS.clear();
    }
}
