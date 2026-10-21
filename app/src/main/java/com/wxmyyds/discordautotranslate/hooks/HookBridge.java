package com.wxmyyds.discordautotranslate.hooks;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.wxmyyds.discordautotranslate.core.Config;
import com.wxmyyds.discordautotranslate.core.Diagnostics;
import com.wxmyyds.discordautotranslate.core.Reflect;
import com.wxmyyds.discordautotranslate.core.EnglishDetector;
import com.wxmyyds.discordautotranslate.core.Scheduler;
import com.wxmyyds.discordautotranslate.core.StateStore;
import com.wxmyyds.discordautotranslate.core.TextExtractor;
import com.wxmyyds.discordautotranslate.core.TranslationCache;
import com.wxmyyds.discordautotranslate.core.TranslationException;
import com.wxmyyds.discordautotranslate.core.XLog;
import com.wxmyyds.discordautotranslate.translate.JsonApiEngine;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 渲染层与翻译状态之间的桥。
 *
 * <p>职责：
 * <ol>
 *   <li>维护 <b>正文视图 → 消息 ID</b> 与 <b>内容对象 → 消息 ID</b> 两张映射。
 *       必须如此：RecyclerView 会复用 {@code MessageContentView} 实例，
 *       译文状态绝不能存在 View 上（需求 §风险 2）。</li>
 *   <li>保存每条消息最近一次的原始渲染参数，用于翻译完成后重新渲染。</li>
 *   <li>提供“原文/译文”切换与失败重试入口，供图标点击调用。</li>
 * </ol>
 */
public final class HookBridge {

    /** Discord 消息 ID 是雪花 ID：纯数字，长度约 17–20。 */
    private static final int SNOWFLAKE_MIN = 15;
    private static final int SNOWFLAKE_MAX = 21;

    /** 重渲染期间的标记：避免重渲染调用再次被 Hook 拦截并覆盖已保存的“原文”。 */
    private static final ThreadLocal<Boolean> RERENDERING = new ThreadLocal<>();

    private static final HookBridge INSTANCE = new HookBridge();

    public static HookBridge get() {
        return INSTANCE;
    }

    /** 正文视图 → 消息 ID（有界，避免列表回收后无限增长）。 */
    private final Map<View, String> viewToMessageId = boundedMap(512);

    /** 消息 ID → 原始 StructurableText（有界，供失败重试使用）。 */
    private final Map<String, Object> rawContent = boundedMap(512);

    /** 消息 ID → 最近承载它的视图（弱引用，避免消息状态反向固定 View）。 */
    private final Map<String, java.lang.ref.WeakReference<View>> lastView = boundedMap(512);

    /** 按引用身份反查消息 ID；Discord StructurableText 的 equals() 是按内容比较。 */
    private final IdentityMessageMap contentIdentityToMessageId = new IdentityMessageMap();

    private volatile Config.Snapshot config = Config.read(null);
    private volatile Scheduler.Engine engine = new JsonApiEngine(config);

    private HookBridge() {
    }

    // ---------------------------------------------------------------- 配置与引擎

    /**
     * 更新配置。会影响翻译结果的配置变化（URL/模型/目标语言/模板/提取路径/凭据）
     * 发生后，递增状态代次使旧译文与在途任务失效（需求 §5.6、§5.7）。
     */
    public void updateConfig(Config.Snapshot c) {
        if (c == null) {
            return;
        }
        Config.Snapshot old = this.config;
        this.config = c;
        XLog.setVerbose(c.verboseLog);
        // 开关关闭时，Diagnostics 不再尝试跨进程回传（诊断仍写 logcat/框架日志）。
        Diagnostics.setForwardEnabled(c.diagnosticsForward);
        // 布局诊断始终开启：设备上既没有 logcat，也不保证用户勾选了详细日志，
        // 若与 verboseLog 绑定，排查时就永远拿不到数据。
        setLayoutDiagnostic(true);

        // 并发上限即时生效（需求 §5.7 普通配置变更尽量即时生效）。
        Scheduler.get().setMaxConcurrency(c.maxConcurrency);

        // version() 已包含密钥/额外请求头指纹：凭据变更同样会使缓存与在途结果失效。
        if (old == null || !old.version().equals(c.version())) {
            XLog.i("translation config changed, invalidating cached state");
            StateStore.get().onNewGeneration();
            TranslationCache.get().clear();
        }
        // 引擎随配置重建，保证下一次请求使用最新配置。
        this.engine = new JsonApiEngine(c);
    }

    // ---------------------------------------------------------------- 视图映射

    /** 记录正文视图当前绑定的消息 ID。列表复用时会被覆盖，始终反映最新绑定。 */
    public void bindView(View contentView, String messageId) {
        if (contentView == null || messageId == null) {
            return;
        }
        String prev = viewToMessageId.put(contentView, messageId);
        if (prev != null && !prev.equals(messageId)) {
            java.lang.ref.WeakReference<View> last = lastView.get(prev);
            if (last != null && last.get() == contentView) {
                lastView.remove(prev);
            }
        }
        lastView.put(messageId, new java.lang.ref.WeakReference<>(contentView));
    }

    @Nullable
    public String messageIdOf(@Nullable View contentView) {
        return contentView == null ? null : viewToMessageId.get(contentView);
    }

    /** 清空全部映射。热重载与配置重置时调用。 */
    public void reset() {
        viewToMessageId.clear();
        rawContent.clear();
        lastView.clear();
        contentIdentityToMessageId.clear();
    }

    private static <K, V> Map<K, V> boundedMap(int maxEntries) {
        return Collections.synchronizedMap(new LinkedHashMap<K, V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxEntries;
            }
        });
    }

    // ------------------------------------------------------- 内容 → 消息 ID

    /**
     * 记录“内容对象 → 消息 ID”。只能在能拿到权威消息 ID 的地方调用
     * （目前是 {@code MessageContentViewHolder.bind} 与 {@code configureReply}）。
     */
    public void rememberContent(String messageId, @Nullable Object structurableText) {
        if (messageId == null || structurableText == null) {
            return;
        }
        contentIdentityToMessageId.put(structurableText, messageId);
    }

    /**
     * 反查某个 {@code StructurableText} 属于哪条消息。
     *
     * <p>只按对象身份映射反查。不得按正文哈希兜底：不同消息完全可能有相同正文，
     * 将哈希映射到单个 ID 会把译文错误地套用到另一条消息。</p>
     */
    @Nullable
    public String resolveMessageId(@Nullable Object structurableText) {
        if (structurableText == null) {
            return null;
        }
        return contentIdentityToMessageId.get(structurableText);
    }

    // ---------------------------------------------------------------- 渲染拦截

    /** 当前是否处于“重渲染”调用中。 */
    public boolean isRerendering() {
        return Boolean.TRUE.equals(RERENDERING.get());
    }

    /**
     * 记录“内容对象 → 消息 ID”身份映射。
     *
     * <p>阶段 3 修正：正文替换统一在渲染漏斗 {@code TextUtilsKt.toSpannable} 完成。
     * 重渲染使用 {@link #requestRerender} 的实参，不再在此保存。
     *
     * @return 始终返回传入的同一数组
     */
    public Object[] interceptRender(View contentView, Object[] args,
                                    @Nullable Object structurableText) {
        if (contentView != null && args != null && structurableText != null) {
            String id = viewToMessageId.get(contentView);
            if (id != null) {
                rememberContent(id, structurableText);
            }
        }
        return args;
    }

    /**
     * 投递重渲染：必须离开当前宿主布局过程再执行。
     *
     * <p>调用方（{@code onMessageBound}、点击回调）可能正处于 RecyclerView 的
     * measure/layout 中，此时直接重渲染会让新的布局请求被本轮遍历丢掉，
     * 表现为“文字变了但行高/空白行要等滚动才更新”。投递到视图消息队列后，
     * 下一次遍历再测量，结果立即生效。</p>
     *
     * <p>视图是否已被复用的判定见 {@link RerenderRegistry}；投递执行时由
     * {@code toSpannable} 按当前状态重新决定替换与否，因此不会把译文写错消息。</p>
     *
     * <p>真正的文本替换发生在 {@code TextUtilsKt.toSpannable}，
     * 它会按当前状态自行决定是否替换。</p>
     *
     * @param invocationTarget 调用 {@code method} 的接收者（正文视图或回复预览容器）
     */
    public void requestRerender(@Nullable Object invocationTarget, @Nullable Method method,
                                @Nullable Object[] args) {
        requestRerender(null, null, invocationTarget, method, args);
    }

    /**
     * 带身份校验的重渲染投递。
     *
     * <p>排队期间视图可能已被 RecyclerView 复用到其他消息，因此执行前必须
     * 再校验一次：绑定已变成其他消息则跳过，不能把旧译文渲染到新消息上。
     * 绑定未知（{@code null}，通常是映射表 LRU 淘汰）不视为复用。</p>
     *
     * @param contentView 用于校验复用的正文视图，可为 null（不校验）
     * @param messageId   期望绑定的消息 ID，可为 null（不校验）
     */
    public void requestRerender(@Nullable View contentView, @Nullable String messageId,
                                @Nullable Object invocationTarget, @Nullable Method method,
                                @Nullable Object[] args) {
        if (invocationTarget == null || method == null || args == null) {
            return;
        }
        String bound = messageIdOf(contentView);
        if (contentView != null && messageId != null && !RerenderRegistry.stillBound(messageId, bound)) {
            XLog.d("rerender skipped: view rebound to another message");
            logRerender(messageId, contentView, bound, "rebound");
            return;
        }
        logRerender(messageId, null, bound, "dispatch");
        Runnable task = () -> {
            String currentBound = messageIdOf(contentView);
            if (contentView != null && messageId != null
                    && !RerenderRegistry.stillBound(messageId, currentBound)) {
                logRerender(messageId, contentView, currentBound, "rebound-before-invoke");
                return;
            }
            invokeRerender(invocationTarget, contentView, messageId, method, args);
        };
        if (invocationTarget instanceof View) {
            View host = (View) invocationTarget;
            // View.post 把执行移出当前布局遍历；视图未附着时排队到下一次 attach。
            if (!host.post(task)) {
                logRerender(messageId, host, bound, "post-rejected");
                task.run();
            }
            return;
        }
        // 非视图目标（理论上不出现）：没有可用的布局时机，保持原同步行为。
        task.run();
    }

    private void invokeRerender(Object invocationTarget, @Nullable View contentView,
                                @Nullable String messageId, Method method, Object[] args) {
        View view = contentView != null
                ? contentView
                : (invocationTarget instanceof View ? (View) invocationTarget : null);
        String metricsBefore = textLayoutMetrics(view);
        int heightBefore = view == null ? -1 : view.getHeight();
        RERENDERING.set(Boolean.TRUE);
        try {
            method.invoke(invocationTarget, args);
        } catch (Throwable t) {
            XLog.w("rerender failed: " + t.getClass().getSimpleName(), t);
            // 这里以前只写 XLog，导致“有 target 但没有 invoked”无从判断。
            logRerender(messageId, null, null,
                    "invoke-failed:" + t.getClass().getSimpleName());
            return;
        } finally {
            RERENDERING.remove();
        }
        // 回复引用的调用目标是容器，真正需要重新绑定的是内部正文视图。
        postInvalidateRow(messageId, view, heightBefore);
        logRerender(messageId, view, messageIdOf(view),
                "invoked(before=" + heightBefore + ",afterInvoke="
                        + (view == null ? -1 : view.getHeight())
                        + ",metricsBefore=" + metricsBefore
                        + ",metricsAfterInvoke=" + textLayoutMetrics(view) + ")");
    }

    private static String textLayoutMetrics(@Nullable View view) {
        if (!(view instanceof TextView)) {
            return "main=unavailable";
        }
        try {
            String main = oneTextViewMetrics((TextView) view);
            Object shadow = Reflect.call(
                    Reflect.findMethod(view.getClass(), "getShadowView", 0), view);
            return "main{" + main + "} shadow{"
                    + (shadow instanceof TextView
                    ? oneTextViewMetrics((TextView) shadow) : "unavailable") + "}";
        } catch (Throwable t) {
            return "metrics-error=" + t.getClass().getSimpleName();
        }
    }

    private static String oneTextViewMetrics(TextView view) {
        CharSequence text = view.getText();
        android.text.Layout layout = view.getLayout();
        return "len=" + (text == null ? -1 : text.length())
                + ",lines=" + view.getLineCount()
                + ",layoutLines=" + (layout == null ? -1 : layout.getLineCount())
                + ",layoutH=" + (layout == null ? -1 : layout.getHeight());
    }

    /**
     * 把行刷新投递到下一轮消息队列，并按消息去重。
     *
     * <p>{@code notifyItemChanged} 会触发 RecyclerView 重新布局。渲染漏斗
     * （{@code toSpannable}）本身就在宿主布局过程中被调用，此时直接通知会重入
     * 布局并抛出 “Cannot call this method while RecyclerView is computing a layout”。
     * 高度在下一次 pre-draw 读取，避免把通知刚发出时的旧高度误当成布局结果。</p>
     */
    private static void postInvalidateRow(@Nullable String messageId, @Nullable View view,
                                          int heightBefore) {
        if (view == null) {
            return;
        }
        final String key = messageId != null
                ? messageId
                : Integer.toHexString(System.identityHashCode(view));
        if (!PENDING_ROW_REFRESH.add(key)) {
            return; // 同一帧内已经安排过，避免重复通知造成多次重绑。
        }
        boolean posted = view.post(() -> {
            String currentBound = HookBridge.get().messageIdOf(view);
            if (messageId != null
                    && !RerenderRegistry.stillBound(messageId, currentBound)) {
                PENDING_ROW_REFRESH.remove(key);
                logRerender(messageId, view, currentBound, "row-refresh-skipped-rebound");
                return;
            }
            PENDING_ROW_REFRESH.remove(key);
            boolean notified = invalidateRow(messageId, view);
            final android.view.ViewTreeObserver observer = view.getViewTreeObserver();
            if (!observer.isAlive()) {
                PENDING_HOST_REBINDS.remove(view);
                logRerender(messageId, view, currentBound,
                        "row-refresh-no-predraw(notified=" + notified + ",before="
                                + heightBefore + ",current=" + view.getHeight() + ")");
                return;
            }
            observer.addOnPreDrawListener(
                    new android.view.ViewTreeObserver.OnPreDrawListener() {
                        @Override
                        public boolean onPreDraw() {
                            if (observer.isAlive()) {
                                observer.removeOnPreDrawListener(this);
                            }
                            String drawnBound = HookBridge.get().messageIdOf(view);
                            if (messageId != null
                                    && !RerenderRegistry.stillBound(messageId, drawnBound)) {
                                logRerender(messageId, view, drawnBound,
                                        "row-refresh-skipped-rebound-before-draw");
                                return true;
                            }
                            boolean hostBindObserved = !PENDING_HOST_REBINDS.remove(view);
                            logRerender(messageId, view, drawnBound,
                                    "row-refresh-after-layout(notified=" + notified
                                            + ",hostBind=" + hostBindObserved
                                            + ",before=" + heightBefore
                                            + ",after=" + view.getHeight()
                                            + ",metrics=" + textLayoutMetrics(view) + ")");
                            return true;
                        }
                    });
            view.invalidate();
        });
        if (!posted) {
            PENDING_ROW_REFRESH.remove(key);
            // 视图未附着：滚动重新绑定时仍会走正常渲染，无需强制刷新。
            XLog.d("row refresh skipped: view not attached");
        }
    }

    /** 已安排但尚未执行的行刷新；避免同一帧内重复 notifyItemChanged。 */
    private static final java.util.Set<String> PENDING_ROW_REFRESH =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private static final java.util.Set<View> PENDING_HOST_REBINDS =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    static boolean consumePendingHostRebind(View view) {
        return view != null && PENDING_HOST_REBINDS.remove(view);
    }

    void logHostBindObserved(String messageId, View view) {
        logRerender(messageId, view, messageIdOf(view), "host-bind-enter-after-row-refresh");
    }

    /**
     * Refresh the outer chat row containing a content item in the nested accessories list.
     * The nested adapter can rebind the text holder, but only the outer ChatListView row
     * rebind refreshes MessageView's height in the chat list.
     */
    private static boolean invalidateRow(@Nullable String messageId, @Nullable View view) {
        if (view == null) {
            return false;
        }
        String bound = HookBridge.get().messageIdOf(view);
        if (messageId != null && !RerenderRegistry.stillBound(messageId, bound)) {
            logRerender(messageId, view, bound, "row-refresh-skipped-rebound-before-notify");
            return false;
        }
        try {
            android.view.ViewParent contentRecycler = findRecyclerView(view);
            if (contentRecycler == null) {
                logRerender(messageId, view, bound, "row-refresh-no-content-list");
                return false;
            }
            Object contentHolder = Reflect.call(
                    findInheritedMethod(contentRecycler.getClass(), "findContainingViewHolder",
                            1, View.class), contentRecycler, view);
            if (contentHolder == null
                    || Reflect.getField(contentHolder, "messageContentView") != view) {
                logRerender(messageId, view, bound, "row-refresh-content-holder-mismatch");
                return false;
            }
            Object contentAdapter = Reflect.call(
                    findInheritedMethod(contentRecycler.getClass(), "getAdapter", 0), contentRecycler);
            android.view.ViewParent rowRecycler = findAncestorByClassName(view, CHAT_LIST_VIEW_CLASS);
            if (contentAdapter == null || rowRecycler == null) {
                logRerender(messageId, view, bound, "row-refresh-no-chat-list");
                return false;
            }
            Object rowAdapter = Reflect.call(
                    findInheritedMethod(rowRecycler.getClass(), "getAdapter", 0), rowRecycler);
            Object rowHolder = Reflect.call(
                    findInheritedMethod(rowRecycler.getClass(), "findContainingViewHolder",
                            1, View.class), rowRecycler, view);
            if (rowAdapter == null || rowHolder == null) {
                logRerender(messageId, view, bound, "row-refresh-no-message-row-holder");
                return false;
            }
            Object itemView = Reflect.getField(rowHolder, "itemView");
            if (!Reflect.isInstanceOfName(itemView, MESSAGE_VIEW_CLASS)) {
                logRerender(messageId, view, bound,
                        "row-refresh-unexpected-item-view=" + (itemView == null
                                ? "null" : itemView.getClass().getName()));
                return false;
            }
            Object bindingPositionValue = Reflect.call(
                    findInheritedMethod(rowHolder.getClass(), "getBindingAdapterPosition", 0),
                    rowHolder);
            Object absolutePositionValue = Reflect.call(
                    findInheritedMethod(rowHolder.getClass(), "getAbsoluteAdapterPosition", 0),
                    rowHolder);
            Object bindingAdapter = Reflect.call(
                    findInheritedMethod(rowHolder.getClass(), "getBindingAdapter", 0), rowHolder);
            int bindingPosition = bindingPositionValue instanceof Integer
                    ? (Integer) bindingPositionValue : -1;
            int absolutePosition = absolutePositionValue instanceof Integer
                    ? (Integer) absolutePositionValue : -1;
            int notifyPosition = absolutePosition >= 0 ? absolutePosition
                    : (bindingAdapter == rowAdapter ? bindingPosition : -1);
            if (notifyPosition < 0) {
                logRerender(messageId, view, bound,
                        "row-refresh-no-outer-position(binding=" + bindingPosition
                                + ",absolute=" + absolutePosition + ")");
                return false;
            }
            boolean cacheInvalidated = invalidateMessageContentBindCache(contentHolder);
            logRerender(messageId, view, bound,
                    "row-refresh-bind-cache-invalidated=" + cacheInvalidated);
            Method notifyItemChanged = findInheritedMethod(
                    rowAdapter.getClass(), "notifyItemChanged", 1, int.class);
            logRerender(messageId, view, bound,
                    "row-refresh-target(outerAdapter=" + rowAdapter.getClass().getName()
                            + ",contentAdapter=" + contentAdapter.getClass().getName()
                            + ",bindingAdapter=" + (bindingAdapter == null
                            ? "unavailable" : bindingAdapter.getClass().getName())
                            + ",bindingPosition=" + bindingPosition
                            + ",absolutePosition=" + absolutePosition
                            + ",notifyMethod=" + (notifyItemChanged != null)
                            + ",notifyPosition=" + notifyPosition + ")");
            if (notifyItemChanged == null) {
                return false;
            }
            PENDING_HOST_REBINDS.add(view);
            try {
                notifyItemChanged.invoke(rowAdapter, notifyPosition);
                return true;
            } catch (Throwable t) {
                PENDING_HOST_REBINDS.remove(view);
                Throwable cause = t instanceof java.lang.reflect.InvocationTargetException
                        && t.getCause() != null ? t.getCause() : t;
                XLog.d("notify outer row failed: " + cause.getClass().getSimpleName());
                return false;
            }
        } catch (Throwable t) {
            XLog.d("notifyItemChanged failed: " + t.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean invalidateMessageContentBindCache(Object holder) {
        for (Class<?> cls = holder.getClass(); cls != null; cls = cls.getSuperclass()) {
            try {
                java.lang.reflect.Field field = cls.getDeclaredField("lastMessageContent");
                field.setAccessible(true);
                field.set(holder, null);
                return true;
            } catch (NoSuchFieldException ignored) {
                // Keep searching the holder's superclass chain.
            } catch (Throwable t) {
                XLog.w("cannot invalidate message bind cache: "
                        + t.getClass().getSimpleName(), t);
                return false;
            }
        }
        XLog.w("lastMessageContent field not found; row rebind may be skipped");
        return false;
    }

    /**
     * 沿继承链查找方法：{@code getAdapter}/{@code notifyItemChanged} 等声明在
     * {@code RecyclerView} 或其 {@code Adapter} 基类上，子类的
     * {@code getDeclaredMethods()} 看不到它们。
     */
    @Nullable
    private static Method findInheritedMethod(@Nullable Class<?> cls, String namePrefix,
                                              int paramCount, Class<?>... firstParams) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            Method m = Reflect.findMethod(c, namePrefix, paramCount, firstParams);
            if (m != null) {
                return m;
            }
        }
        return null;
    }

    /**
     * 沿父链找到最近的 RecyclerView（按类名判断，避免编译期依赖）。
     */
    @Nullable
    private static android.view.ViewParent findRecyclerView(View view) {
        android.view.ViewParent parent = view.getParent();
        for (int depth = 0; depth < RELAYOUT_MAX_DEPTH && parent != null; depth++) {
            if (Reflect.isInstanceOfName(parent, RECYCLER_VIEW_CLASS)) {
                return parent;
            }
            parent = parent.getParent();
        }
        return null;
    }

    /** Finds an ancestor RecyclerView by its concrete host class name. */
    @Nullable
    private static android.view.ViewParent findAncestorByClassName(View view, String className) {
        android.view.ViewParent parent = view.getParent();
        for (int depth = 0; depth < RELAYOUT_MAX_DEPTH && parent != null; depth++) {
            if (Reflect.isInstanceOfName(parent, className)) {
                return parent;
            }
            parent = parent.getParent();
        }
        return null;
    }

    private static final String RECYCLER_VIEW_CLASS = "androidx.recyclerview.widget.RecyclerView";
    private static final String CHAT_LIST_VIEW_CLASS =
            "com.discord.chat.presentation.list.ChatListView";
    private static final String MESSAGE_VIEW_CLASS =
            "com.discord.chat.presentation.message.MessageView";

    /** 向上查找列表时的最大层数。 */
    private static final int RELAYOUT_MAX_DEPTH = 12;

    /** 诊断开关：只有排查“布局未刷新”时才打开。 */
    private static volatile boolean sLayoutDiagnostic = false;

    /** 由模块入口在解析配置后设置，与“详细诊断日志”共用同一个开关。 */
    public static void setLayoutDiagnostic(boolean enabled) {
        sLayoutDiagnostic = enabled;
    }

    /**
     * 诊断日志：记录重渲染是否真的执行、视图处于什么状态。
     *
     * <p>不记录消息正文与密钥；只含消息 ID、视图类型、attached/可见性、
     * 重渲染时视图高度、当前绑定关系与结论标签。</p>
     */
    private static void logRerender(@Nullable String messageId, @Nullable View view,
                                    @Nullable String bound, String outcome) {
        String line;
        if (view == null) {
            line = "msg=" + messageId + " view=null outcome=" + outcome;
        } else {
            line = "msg=" + messageId
                    + " view=" + view.getClass().getSimpleName()
                    + " attached=" + view.isAttachedToWindow()
                    + " visible=" + (view.getVisibility() == View.VISIBLE)
                    + " height=" + view.getHeight()
                    + " bound=" + (bound == null ? "null" : bound)
                    + " outcome=" + outcome;
        }
        Diagnostics.record("layout-diag: " + line);
    }

    /** 记录模块进入 Discord 目标进程的事实，不依赖远程配置写入。 */
    public static void logModuleReady(String detail) {
        XLog.i("module-ready " + detail);
    }

    /**
     * 把宿主 {@link View} 的 Context 交给诊断通道。
     *
     * <p>只在首次调用时真正获取 {@link android.content.ContentResolver}，
     * 后续调用为一次 null 检查；渲染漏斗每次重绑都会走这条路径，必须足够廉价。</p>
     */
    private static void captureHostContext(@Nullable View view) {
        if (view == null) {
            return;
        }
        try {
            Diagnostics.captureHostContext(view.getContext());
        } catch (Throwable ignored) {
            // 取 Context 失败不影响翻译主流程。
        }
    }

    /**
     * 记录一条布局事实（供 {@link RerenderRegistry} 等外部调用）。
     *
     * <p>与 {@link #logRerender} 共用同一套日志输出通道。</p>
     */
    public void logLayoutFact(@Nullable String messageId, String what) {
        logRerender(messageId, null, null, what);
    }

    // ---------------------------------------------------------------- 翻译流程

    /**
     * 消息绑定后的处理入口：识别英文、查缓存、按需发起翻译。
     */
    public void onMessageBound(@Nullable View contentView, String messageId,
                               @Nullable Object structurableText) {
        if (messageId == null || structurableText == null) {
            return;
        }
        // 诊断回传需要宿主的 ContentResolver；模块自身在 Discord 进程里没有 Context，
        // 只能从宿主视图取。缓存成功后不会重复执行。
        captureHostContext(contentView);
        TextExtractor.Result ex = TextExtractor.extract(structurableText);
        if (ex == null || ex.text.trim().isEmpty()) {
            return;
        }

        Config.Snapshot cfg = config;
        String hash = Scheduler.contentHash(ex.text, cfg.version());
        StateStore.Entry entry = StateStore.get().obtain(messageId, hash, ex.text);
        rawContent.put(messageId, structurableText);
        if (contentView != null) {
            lastView.put(messageId, new java.lang.ref.WeakReference<>(contentView));
        }

        if (!cfg.enabled) {
            // 总开关关闭：不发起新翻译，保留已有译文（需求 §2.7）。
            XLog.d("auto-translate disabled, skip " + messageId);
            return;
        }
        if (entry.status == StateStore.Status.TRANSLATING
                || entry.status == StateStore.Status.TRANSLATED) {
            return; // 已有在途任务或已有译文，不重复翻译（需求 §5.4 去重）。
        }
        if (entry.status == StateStore.Status.FAILED) {
            // 失败后不自动重试：渲染漏斗会被频繁调用（每次重绘都会经过），
            // 若无条件重试会造成请求风暴。失败后统一由用户点图标重试（需求 §2.6）。
            return;
        }

        // 缓存命中：直接出结果，不发请求（需求 §5.6）。
        String cached = TranslationCache.get().get(hash);
        if (cached != null) {
            entry.translatedText = cached;
            entry.status = StateStore.Status.TRANSLATED;
            entry.showingOriginal = false;
            XLog.d("cache hit for " + messageId);
            RerenderRegistry.rerender(messageId);
            return;
        }

        if (!cfg.isUsable()) {
            entry.status = StateStore.Status.FAILED;
            entry.lastError = "API 未配置";
            XLog.d("api not configured, skip " + messageId);
            RerenderRegistry.rerender(messageId);
            return;
        }

        EnglishDetector.Verdict v = EnglishDetector.evaluate(
                ex.text, ex.textNodeCount, ex.opaqueNodeCount, ex.codeHeavy);
        if (!v.shouldTranslate) {
            XLog.d("skip " + messageId + " reason=" + v.reason);
            return;
        }

        entry.status = StateStore.Status.TRANSLATING;
        XLog.i("translate start " + messageId + " " + v.reason
                + " text=" + XLog.text(ex.text));
        // 立即重渲染，让「翻译中」旋转图标显示出来。
        RerenderRegistry.rerender(messageId);

        Scheduler.get().submit(messageId, hash, ex.text, engine,
                (generationSnapshot, contentHash) -> {
                    if (generationSnapshot != StateStore.get().generation()) {
                        return false; // 模块已热重载/配置已重置：丢弃（需求 §风险 6）
                    }
                    if (!config.enabled) {
                        return false; // 开关已关闭：丢弃在途结果（需求 §2.7 注）
                    }
                    StateStore.Entry cur = StateStore.get().peek(messageId);
                    if (cur == null || !contentHash.equals(cur.contentHash)) {
                        return false; // 消息已被编辑：丢弃（需求 §风险 3）
                    }
                    return true;
                },
                (id, accepted, translated, error) ->
                        onTranslateResult(id, accepted, translated, error));
    }

    /** 翻译结果回写（主线程）。 */
    private void onTranslateResult(String id, boolean accepted,
                                   @Nullable String translated, @Nullable Throwable error) {
        StateStore.Entry cur = StateStore.get().peek(id);
        if (cur == null) {
            return;
        }
        if (!accepted) {
            // 被丢弃通常是过期或开关关闭：回到可重试状态，但不显示“失败”。
            if (cur.status == StateStore.Status.TRANSLATING) {
                cur.status = cur.translatedText != null
                        ? StateStore.Status.TRANSLATED
                        : StateStore.Status.IDLE;
            }
            RerenderRegistry.rerender(id);
            return;
        }
        if (error != null || translated == null || translated.isEmpty()) {
            cur.status = StateStore.Status.FAILED;
            cur.lastError = describe(error);
            XLog.w("translate failed " + id + ": " + cur.lastError);
        } else {
            cur.translatedText = translated;
            cur.status = StateStore.Status.TRANSLATED;
            cur.showingOriginal = false;
            // 写入缓存，供同内容的其它消息复用（需求 §5.6）。
            TranslationCache.get().put(cur.contentHash, translated);
            XLog.i("translate done " + id + " -> " + XLog.text(translated));
        }
        RerenderRegistry.rerender(id);
    }

    /** 把异常转换为可读且不泄露密钥的描述。 */
    private static String describe(@Nullable Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        if (t instanceof TranslationException) {
            return ((TranslationException) t).userMessage();
        }
        return t.getClass().getSimpleName()
                + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }

    /** 原文/译文切换：由图标点击触发，不触发新请求（需求 §2.4）。 */
    public void toggleShowingOriginal(String messageId) {
        StateStore.Entry e = StateStore.get().peek(messageId);
        if (e == null || e.status != StateStore.Status.TRANSLATED || e.translatedText == null) {
            return;
        }
        e.showingOriginal = !e.showingOriginal;
        XLog.d("toggle " + messageId + " showingOriginal=" + e.showingOriginal);
        RerenderRegistry.rerender(messageId);
    }

    /**
     * 行内图标点击的统一入口（需求：成功时切换原文/译文，失败时重试）。
     *
     * <p>由 {@code IconClickSpanFactory} 创建的 span 回调触发。
     * 状态可能因列表复用而变化，因此每次都重新读取当前状态。</p>
     */
    public void onIconClicked(String messageId) {
        if (messageId == null) {
            return;
        }
        StateStore.Entry e = StateStore.get().peek(messageId);
        if (e == null) {
            return;
        }
        switch (e.status) {
            case TRANSLATED:
                if (e.translatedText != null) {
                    toggleShowingOriginal(messageId);
                }
                break;
            case FAILED:
                retry(messageId);
                break;
            default:
                // 翻译中或未开始：忽略，避免重复触发。
                break;
        }
    }

    /** 失败后重试：由图标点击触发（需求 §2.6）。 */
    public void retry(String messageId) {
        StateStore.Entry e = StateStore.get().peek(messageId);
        if (e == null) {
            return;
        }
        Object structurable = rawContent.get(messageId);
        if (structurable == null) {
            XLog.d("retry skipped: no raw content for " + messageId);
            return;
        }

        e.status = StateStore.Status.IDLE;
        e.lastError = null;
        RerenderRegistry.rerender(messageId);

        java.lang.ref.WeakReference<View> viewRef = lastView.get(messageId);
        View last = viewRef == null ? null : viewRef.get();
        onMessageBound(last, messageId, structurable);
    }

    // ---------------------------------------------------------------- 工具

    /** 判断字符串是否符合 Discord 雪花 ID 形态。 */
    public static boolean looksLikeSnowflake(@Nullable String s) {
        if (s == null) {
            return false;
        }
        int n = s.length();
        if (n < SNOWFLAKE_MIN || n > SNOWFLAKE_MAX) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
