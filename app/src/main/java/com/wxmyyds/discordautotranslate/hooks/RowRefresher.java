package com.wxmyyds.discordautotranslate.hooks;

import android.view.View;

import androidx.annotation.Nullable;

import com.wxmyyds.discordautotranslate.core.Reflect;
import com.wxmyyds.discordautotranslate.core.XLog;

import java.lang.reflect.Method;

/**
 * 行刷新与强制重新布局：译文替换后，宿主 RecyclerView 的行高不会自动更新，
 * 本类集中负责把「内外两层列表都重新测量」所需的全部视图树反射操作。
 *
 * <p>从 {@link HookBridge} 拆出的职责：HookBridge 只负责投递重渲染
 * （{@code requestRerender}），本类负责重渲染之后的行失效与布局传播。
 * 两者的衔接点是 {@link #postInvalidateRow}。</p>
 *
 * <p>本类不依赖 HookBridge 的任何状态；“视图当前绑定的消息 ID”
 * 通过 {@link BoundIdLookup} 由调用方提供，避免与视图映射表产生双向耦合。</p>
 */
final class RowRefresher {

    /** 查询某正文视图当前绑定的消息 ID；由 HookBridge 提供（复用校验用）。 */
    interface BoundIdLookup {
        @Nullable String boundIdOf(@Nullable View view);
    }

    private RowRefresher() {
    }

    /**
     * 把行刷新投递到下一轮消息队列，并按消息去重。
     *
     * <p>{@code notifyItemChanged} is posted after leaving the current host layout pass,
     * avoiding a RecyclerView notification while it is computing a layout.</p>
     */
    static void postInvalidateRow(BoundIdLookup lookup,
                                  @Nullable String messageId, @Nullable View view) {
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
            String currentBound = lookup.boundIdOf(view);
            if (messageId != null
                    && !RerenderRegistry.stillBound(messageId, currentBound)) {
                PENDING_ROW_REFRESH.remove(key);
                return;
            }
            PENDING_ROW_REFRESH.remove(key);
            invalidateRow(lookup, messageId, view);
            requestLayoutChain(view);
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

    /**
     * holder 的内容承载视图字段名（smali 已核对，Discord 340.13）：
     * 正文 {@code messageContentView}、富 Embed {@code embedView}、
     * 帖子预览 {@code postPreviewEmbedView}、投票 {@code pollView}。
     */
    private static final String[] CONTENT_VIEW_FIELDS = {
            "messageContentView", "embedView", "postPreviewEmbedView", "pollView"};

    /** holder 是否以 {@code view} 作为其内容承载视图；据此过滤无关视图的误刷新。 */
    private static boolean ownsContentView(Object holder, View view) {
        for (String fieldName : CONTENT_VIEW_FIELDS) {
            if (Reflect.getField(holder, fieldName) == view) {
                return true;
            }
        }
        return false;
    }

    /**
     * Refresh the outer chat row containing a content item in the nested accessories list.
     * The nested adapter can rebind the text holder, but the outer ChatListView row must also
     * be notified so MessageView's height is recalculated in the chat list.
     */
    private static boolean invalidateRow(BoundIdLookup lookup,
                                         @Nullable String messageId, @Nullable View view) {
        if (view == null) {
            return false;
        }
        String bound = lookup.boundIdOf(view);
        if (messageId != null && !RerenderRegistry.stillBound(messageId, bound)) {
            return false;
        }
        try {
            android.view.ViewParent contentRecycler = findRecyclerView(view);
            if (contentRecycler == null) {
                return false;
            }
            Object contentHolder = Reflect.call(
                    findInheritedMethod(contentRecycler.getClass(), "findContainingViewHolder",
                            1, View.class), contentRecycler, view);
            if (contentHolder == null || !ownsContentView(contentHolder, view)) {
                return false;
            }
            Object contentAdapter = Reflect.call(
                    findInheritedMethod(contentRecycler.getClass(), "getAdapter", 0), contentRecycler);
            android.view.ViewParent rowRecycler = findAncestorByClassName(view, CHAT_LIST_VIEW_CLASS);
            if (contentAdapter == null || rowRecycler == null) {
                return false;
            }
            Object rowAdapter = Reflect.call(
                    findInheritedMethod(rowRecycler.getClass(), "getAdapter", 0), rowRecycler);
            Object rowHolder = Reflect.call(
                    findInheritedMethod(rowRecycler.getClass(), "findContainingViewHolder",
                            1, View.class), rowRecycler, view);
            if (rowAdapter == null || rowHolder == null) {
                return false;
            }
            Object itemView = Reflect.getField(rowHolder, "itemView");
            if (!Reflect.isInstanceOfName(itemView, MESSAGE_VIEW_CLASS)) {
                return false;
            }
            Object bindingPositionValue = Reflect.call(
                    findInheritedMethod(rowHolder.getClass(), "getBindingAdapterPosition", 0),
                    rowHolder);
            Object absolutePositionValue = Reflect.call(
                    findInheritedMethod(rowHolder.getClass(), "getAbsoluteAdapterPosition", 0),
                    rowHolder);
            Object bindingAdapter = Reflect.call(
                    findInheritedMethod(rowHolder.getClass(), "getBindingAdapter", 0),
                    rowHolder);
            int bindingPosition = bindingPositionValue instanceof Integer
                    ? (Integer) bindingPositionValue : -1;
            int absolutePosition = absolutePositionValue instanceof Integer
                    ? (Integer) absolutePositionValue : -1;
            int notifyPosition = absolutePosition >= 0 ? absolutePosition
                    : (bindingAdapter == rowAdapter ? bindingPosition : -1);
            if (notifyPosition < 0) {
                return false;
            }
            invalidateMessageContentBindCache(contentHolder);
            // The content view is an item in the nested MessageAccessoriesView RecyclerView.
            // Notify it as well as the outer chat row so both layout levels recalculate.
            int contentPosition = -1;
            Object contentPosValue = Reflect.call(
                    findInheritedMethod(contentHolder.getClass(), "getBindingAdapterPosition", 0),
                    contentHolder);
            if (contentPosValue instanceof Integer) {
                contentPosition = (Integer) contentPosValue;
            }
            if (contentPosition >= 0) {
                Method notifyContent = findInheritedMethod(
                        contentAdapter.getClass(), "notifyItemChanged", 1, int.class);
                if (notifyContent != null) {
                    try {
                        notifyContent.invoke(contentAdapter, contentPosition);
                    } catch (Throwable t) {
                        XLog.d("notify inner content failed: "
                                + t.getClass().getSimpleName());
                    }
                }
            }
            Method notifyItemChanged = findInheritedMethod(
                    rowAdapter.getClass(), "notifyItemChanged", 1, int.class);
            if (notifyItemChanged == null) {
                return false;
            }
            try {
                notifyItemChanged.invoke(rowAdapter, notifyPosition);
                // 不做同步重绑，避免直接重跑宿主绑定管线。
                scheduleRelayoutAfterLayout(view);
                return true;
            } catch (Throwable t) {
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

    /**
     * 把一次强制重新测量推到当前帧布局结束之后。
     *
     * <p>{@code View.post()} defers the request until the current host layout pass ends.</p>
     */
    private static void scheduleRelayoutAfterLayout(@Nullable final View view) {
        if (view == null) {
            return;
        }
        try {
            view.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!view.isAttachedToWindow()) {
                            return;
                        }
                        // 布局已结束，此时 requestLayout 不会被丢弃。
                        for (View v = view; v != null; ) {
                            v.forceLayout();
                            android.view.ViewParent parent = v.getParent();
                            v = parent instanceof View ? (View) parent : null;
                        }
                        requestLayoutChain(view);
                        forceRelayoutAncestor(view, CHAT_LIST_VIEW_CLASS);
                        relayoutContentSelf(view);
                        view.invalidate();
                        dispatchRecyclerLayout(findRecyclerView(view));
                        dispatchRecyclerLayout(findAncestorByClassName(view, CHAT_LIST_VIEW_CLASS));
                    } catch (Throwable t) {
                        XLog.d("posted relayout failed: " + t.getClass().getSimpleName());
                    }
                }
            });
        } catch (Throwable t) {
            XLog.d("schedule relayout failed: " + t.getClass().getSimpleName());
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
                // 继续沿父类查找：字段声明可能在基类上。
            } catch (Throwable t) {
                XLog.w("cannot invalidate message bind cache: "
                        + t.getClass().getSimpleName(), t);
                return false;
            }
        }
        // 仅正文 holder 有该缓存；Embed holder 无 bind 早退缓存，找不到属正常。
        XLog.d("lastMessageContent field not found; holder has no bind cache to invalidate");
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

    /** 沿父链找到最近的 RecyclerView（按类名判断，避免编译期依赖）。 */
    @Nullable
    private static android.view.ViewParent findRecyclerView(View view) {
        android.view.ViewParent parent = view.getParent();
        for (int depth = 0; depth < RELAYOUT_MAX_DEPTH && parent != null; depth++) {
            if (Reflect.isInstanceOfName(parent,
                    "androidx.recyclerview.widget.RecyclerView")) {
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

    /**
     * 对视图及其父链请求重新布局。
     *
     * <p>{@code View.invalidate()} only redraws. A text change rebuilds the text layout,
     * but {@code View.getHeight()} updates during measure/layout.</p>
     *
     * <p><b>为什么先 forceLayout()</b>：仅调 {@code requestLayout()} 时，
     * 若该 View 的 {@code PFLAG_FORCE_LAYOUT} 未置位且测量参数未变，
     * 父级会在 {@code measureChildWithMargins} 中直接用缓存尺寸而不再下探。
     * {@code forceLayout()} 会置位该标记并清掉测量缓存，使下一次 measure
     * 真正跑到这个 View。</p>
     *
     * <p>沿父链向上传播的原因：中文正文高度变化后，承载它的
     * {@code MessageAccessoriesView}（一个 RecyclerView）与最外层
     * {@code MessageView} 的测量结果都需要重新计算，只刷单层不足以让
     * 宿主行高更新。</p>
     *
     * <p>深度上有界（{@link #RELAYOUT_MAX_DEPTH}），避免无界传播。</p>
     */
    /** Requests a relayout on the specified ancestor. */
    private static boolean forceRelayoutAncestor(@Nullable View view, String className) {
        if (view == null) {
            return false;
        }
        try {
            android.view.ViewParent parent = view.getParent();
            for (int depth = 0; depth < RELAYOUT_MAX_DEPTH && parent instanceof View; depth++) {
                View parentView = (View) parent;
                if (Reflect.isInstanceOfName(parentView, className)) {
                    parentView.forceLayout();
                    parentView.requestLayout();
                    return true;
                }
                parent = parent.getParent();
            }
        } catch (Throwable t) {
            XLog.d("force relayout ancestor failed: " + t.getClass().getSimpleName());
        }
        return false;
    }

    /**
     * 让内容视图按自身当前文本重新测量并布局，不依赖父级 RecyclerView。
     *
     * <p>Use the current measured width and an {@link View.MeasureSpec#UNSPECIFIED}
     * height so the new text can determine the content view's required height.</p>
     *
     * <p>只改这个子视图自身的尺寸，不改任何数据或文本内容。</p>
     */
    private static void relayoutContentSelf(@Nullable View view) {
        if (view == null || !view.isAttachedToWindow()) {
            return;
        }
        try {
            int width = view.getWidth();
            if (width <= 0) {
                width = view.getMeasuredWidth();
            }
            if (width <= 0) {
                return;
            }
            int widthSpec = View.MeasureSpec.makeMeasureSpec(
                    width, View.MeasureSpec.EXACTLY);
            int heightSpec = View.MeasureSpec.makeMeasureSpec(
                    0, View.MeasureSpec.UNSPECIFIED);
            view.forceLayout();
            view.measure(widthSpec, heightSpec);
            int newHeight = view.getMeasuredHeight();
            int oldHeight = view.getHeight();
            if (newHeight > 0 && newHeight != oldHeight) {
                view.layout(view.getLeft(), view.getTop(),
                        view.getLeft() + width, view.getTop() + newHeight);
            }
            // 父级（内层 RecyclerView）自身高度也需跟着收缩，否则空白还在。
            android.view.ViewParent parent = view.getParent();
            if (parent instanceof View) {
                View parentView = (View) parent;
                parentView.forceLayout();
                parentView.requestLayout();
            }
        } catch (Throwable t) {
            XLog.d("content self relayout failed: " + t.getClass().getSimpleName());
        }
    }

    private static void dispatchRecyclerLayout(@Nullable android.view.ViewParent recycler) {
        if (recycler == null) {
            return;
        }
        try {
            Object suppressed = Reflect.getField(recycler, "mLayoutSuppressed");
            Object interceptDepth = Reflect.getField(recycler, "mInterceptRequestLayoutDepth");
            Object computing = Reflect.call(findInheritedMethod(
                    recycler.getClass(), "isComputingLayout", 0), recycler);
            if (!Boolean.FALSE.equals(suppressed)
                    || !(interceptDepth instanceof Integer)
                    || ((Integer) interceptDepth) != 0
                    || Boolean.TRUE.equals(computing)) {
                return;
            }
            Method dispatchLayout = findInheritedMethod(
                    recycler.getClass(), "dispatchLayout", 0);
            if (dispatchLayout != null) {
                dispatchLayout.invoke(recycler);
            }
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null ? t.getCause() : t;
            XLog.d("dispatchLayout failed: " + cause.getClass().getSimpleName());
        }
    }

    private static void requestLayoutChain(@Nullable View view) {
        if (view == null) {
            return;
        }
        try {
            view.forceLayout();
            view.requestLayout();
            android.view.ViewParent parent = view.getParent();
            for (int depth = 0;
                 depth < RELAYOUT_MAX_DEPTH && parent instanceof View; depth++) {
                View parentView = (View) parent;
                parentView.forceLayout();
                parentView.requestLayout();
                parent = parent.getParent();
            }
        } catch (Throwable t) {
            // 布局请求失败不影响主流程：下一次滚动仍会正确重绑。
            XLog.d("requestLayoutChain failed: " + t.getClass().getSimpleName());
        }
    }

    private static final String CHAT_LIST_VIEW_CLASS =
            "com.discord.chat.presentation.list.ChatListView";
    private static final String MESSAGE_VIEW_CLASS =
            "com.discord.chat.presentation.message.MessageView";

    /**
     * 父链传播的最大深度。
     *
     * <p>实测消息正文视图到外层 ChatListView 的层级较深，原先 12 层的
     * 上限可能在到达 ChatListView 之前就截断，导致 requestLayout 没有传到
     * 真正负责布局的那一层——表现为“高度不更新，滚动一次才恢复”。
     * 这里放宽到 32（仍为有界值，避免异常情况下的无界传播）。</p>
     */
    private static final int RELAYOUT_MAX_DEPTH = 32;
}
