package io.github.wxmyyds.discordautotranslate.hooks;

import android.view.View;

import androidx.annotation.Nullable;

import io.github.wxmyyds.discordautotranslate.core.HookConfiguration;
import io.github.wxmyyds.discordautotranslate.core.Reflect;

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
        });
        if (!posted) {
            PENDING_ROW_REFRESH.remove(key);
            // 视图未附着：滚动重新绑定时仍会走正常渲染，无需强制刷新。
        }
    }

    /** 已安排但尚未执行的行刷新；避免同一帧内重复 notifyItemChanged。 */
    private static final java.util.Set<String> PENDING_ROW_REFRESH =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /** A content view belongs to the attached item whose configured itemView contains it. */
    private static boolean ownsContentView(Object holder, View view) {
        String itemViewField = configuredField("RecyclerViewViewHolder", "itemView");
        Object itemView = itemViewField == null ? null : Reflect.getField(holder, itemViewField);
        return itemView instanceof View && isDescendantOrSelf(view, (View) itemView);
    }

    @Nullable
    private static Method findRecyclerApiMethod(Class<?> type, String name,
                                               Class<?>... parameterTypes) {
        try {
            return type.getMethod(name, parameterTypes);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Locate the attached item that owns the content view and read its holder from LayoutParams. */
    @Nullable
    private static Object findContainingViewHolder(android.view.ViewParent recycler, View view) {
        Method childCountMethod = recyclerAccessor(recycler, "getChildCount");
        Method childAtMethod = recyclerAccessor(recycler, "getChildAt", int.class);
        Object countValue = Reflect.call(childCountMethod, recycler);
        if (!(countValue instanceof Integer) || childAtMethod == null) {
            reportRowRefreshFailure("contentChildren");
            return null;
        }
        int count = (Integer) countValue;
        for (int i = 0; i < count; i++) {
            Object child = Reflect.call(childAtMethod, recycler, i);
            if (!(child instanceof View) || !isDescendantOrSelf(view, (View) child)) {
                continue;
            }
            return holderFromItemView((View) child);
        }
        reportRowRefreshFailure("contentItemView");
        return null;
    }

    private static boolean isDescendantOrSelf(View candidate, View root) {
        for (View current = candidate; current != null; ) {
            if (current == root) {
                return true;
            }
            android.view.ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private static void reportRowRefreshFailure(String stage) {
        HookDiagnostics.infoOnce("rowRefresh.failure." + stage,
                "row adapter refresh lookup failed at " + stage);
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
            reportRowRefreshFailure("boundMessageMismatch");
            return false;
        }
        try {
            android.view.ViewParent contentRecycler = findRecyclerView(view);
            if (contentRecycler == null) {
                reportRowRefreshFailure("contentRecycler");
                return false;
            }
            Object contentHolder = findContainingViewHolder(contentRecycler, view);
            if (contentHolder == null) {
                reportRowRefreshFailure("contentHolder");
                return false;
            }
            if (!ownsContentView(contentHolder, view)) {
                reportRowRefreshFailure("contentOwnership");
                return false;
            }
            String getAdapterName = configuredAccessor("RecyclerView", "getAdapter");
            String holderPositionName = configuredAccessor(
                    "RecyclerViewViewHolder", "getBindingAdapterPosition");
            String absolutePositionName = configuredAccessor(
                    "RecyclerViewViewHolder", "getAbsoluteAdapterPosition");
            String rowRecyclerField = configuredField("RecyclerViewViewHolder", "recyclerView");
            String bindingAdapterField = configuredField("RecyclerViewViewHolder", "bindingAdapter");
            Object contentAdapter = getAdapterName == null ? null : Reflect.call(
                    findInheritedMethod(contentRecycler.getClass(), getAdapterName, 0), contentRecycler);
            View messageRow = findAncestorView(view, configuredType("MessageView"));
            Object rowHolder = messageRow == null ? null : holderFromItemView(messageRow);
            Object rowRecyclerObject = rowRecyclerField == null ? null
                    : Reflect.getField(rowHolder, rowRecyclerField);
            if (contentAdapter == null || rowHolder == null
                    || !(rowRecyclerObject instanceof android.view.ViewParent)) {
                reportRowRefreshFailure(contentAdapter == null ? "contentAdapter"
                        : rowHolder == null ? "messageRowHolder" : "rowRecycler");
                return false;
            }
            android.view.ViewParent rowRecycler = (android.view.ViewParent) rowRecyclerObject;
            Object rowAdapter = getAdapterName == null ? null : Reflect.call(
                    findInheritedMethod(rowRecycler.getClass(), getAdapterName, 0), rowRecycler);
            String itemViewField = configuredField("RecyclerViewViewHolder", "itemView");
            Object itemView = itemViewField == null ? null : Reflect.getField(rowHolder, itemViewField);
            if (rowAdapter == null) {
                reportRowRefreshFailure("rowAdapter");
                return false;
            }
            if (itemView != messageRow) {
                reportRowRefreshFailure("messageViewItem");
                return false;
            }
            Object bindingPositionValue = holderPositionName == null ? null : Reflect.call(
                    findInheritedMethod(rowHolder.getClass(), holderPositionName, 0), rowHolder);
            Object absolutePositionValue = absolutePositionName == null ? null : Reflect.call(
                    findInheritedMethod(rowHolder.getClass(), absolutePositionName, 0), rowHolder);
            Object bindingAdapter = bindingAdapterField == null ? null
                    : Reflect.getField(rowHolder, bindingAdapterField);
            int bindingPosition = bindingPositionValue instanceof Integer
                    ? (Integer) bindingPositionValue : -1;
            int absolutePosition = absolutePositionValue instanceof Integer
                    ? (Integer) absolutePositionValue : -1;
            int notifyPosition = absolutePosition >= 0 ? absolutePosition
                    : (bindingAdapter == rowAdapter ? bindingPosition : -1);
            if (notifyPosition < 0) {
                reportRowRefreshFailure("invalidRowPosition");
                return false;
            }
            boolean bindCacheCleared = invalidateMessageContentBindCache(contentHolder);
            HookDiagnostics.info("row refresh content bind cache cleared=" + bindCacheCleared
                    + " holderClass=" + contentHolder.getClass().getName());
            // Notify both the nested content adapter and the outer row adapter. In the
            // minified host, adapter notifications are exposed through the adapter's
            // observable rather than a reflectable notifyItemChanged method.
            int contentPosition = -1;
            Object contentPosValue = holderPositionName == null ? null : Reflect.call(
                    findInheritedMethod(contentHolder.getClass(), holderPositionName, 0), contentHolder);
            if (contentPosValue instanceof Integer) {
                contentPosition = (Integer) contentPosValue;
            }
            HookDiagnostics.info("row refresh started contentHeight=" + view.getHeight()
                    + " contentMeasured=" + view.getMeasuredHeight()
                    + " attached=" + view.isAttachedToWindow());
            HookDiagnostics.info("row refresh recycler state before notify content="
                    + recyclerState(contentRecycler) + " outer=" + recyclerState(rowRecycler));
            boolean contentNotified = contentPosition >= 0
                    && notifyAdapterItemChanged(contentAdapter, contentPosition);
            if (contentPosition >= 0 && !contentNotified) {
                reportRowRefreshFailure("notifyContentItemChanged");
            }
            boolean rowNotified = notifyAdapterItemChanged(rowAdapter, notifyPosition);
            if (!rowNotified) {
                reportRowRefreshFailure("notifyRowItemChanged");
                return false;
            }
            HookDiagnostics.info("row refresh adapters notified content=" + contentNotified
                    + " contentPosition=" + contentPosition
                    + " rowPosition=" + notifyPosition);
            boolean contentUpdatesConsumed = contentNotified
                    && consumeRecyclerPendingUpdates(contentRecycler);
            boolean rowUpdatesConsumed = consumeRecyclerPendingUpdates(rowRecycler);
            HookDiagnostics.info("row refresh RecyclerView updates consumed content="
                    + contentUpdatesConsumed + " outer=" + rowUpdatesConsumed
                    + " content=" + recyclerState(contentRecycler)
                    + " outerState=" + recyclerState(rowRecycler));
            postRecyclerState(contentRecycler, "content", view, messageRow);
            postRecyclerState(rowRecycler, "outer", view, messageRow);
            messageRow.post(() -> HookDiagnostics.info("row refresh after layout"
                    + " content=" + sizeOf(view)
                    + " accessories=" + sizeOf(findAncestorView(view, configuredType("MessageAccessoriesView")))
                    + " row=" + sizeOf(messageRow)
                    + " recycler=" + sizeOfViewParent(rowRecycler)));
            HookDiagnostics.infoOnce("rowRefresh.outerAdapterNotified",
                    "outer chat adapter notified; recyclerClass="
                            + rowRecycler.getClass().getName()
                            + " bindingPosition=" + bindingPosition
                            + " absolutePosition=" + absolutePosition
                            + " notifyPosition=" + notifyPosition);
            requestLayoutChain(messageRow);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void postRecyclerState(android.view.ViewParent recycler, String label,
                                          View contentView, View messageRow) {
        if (!(recycler instanceof View)) {
            return;
        }
        View recyclerView = (View) recycler;
        recyclerView.postOnAnimation(() -> HookDiagnostics.info(
                "row refresh recycler state next frame " + label + "="
                        + recyclerState(recycler)));
        android.view.ViewTreeObserver observer = recyclerView.getViewTreeObserver();
        if (!observer.isAlive()) {
            return;
        }
        android.view.ViewTreeObserver.OnPreDrawListener listener =
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        if (observer.isAlive()) {
                            observer.removeOnPreDrawListener(this);
                        }
                        HookDiagnostics.info("row refresh recycler state pre-draw " + label + "="
                                + recyclerState(recycler)
                                + " content=" + sizeOf(contentView)
                                + " row=" + sizeOf(messageRow));
                        return true;
                    }
                };
        observer.addOnPreDrawListener(listener);
    }

    private static String recyclerState(android.view.ViewParent recycler) {
        if (!(recycler instanceof View)) {
            return recycler == null ? "missing" : recycler.getClass().getName();
        }
        View view = (View) recycler;
        return view.getClass().getSimpleName()
                + "{attached=" + view.isAttachedToWindow()
                + ",layoutRequested=" + view.isLayoutRequested()
                + ",height=" + view.getHeight()
                + ",measured=" + view.getMeasuredHeight()
                + ",P=" + readRecyclerState(recycler, "stateP")
                + ",Q=" + readRecyclerState(recycler, "stateQ")
                + ",R=" + readRecyclerState(recycler, "stateR")
                + ",S=" + readRecyclerState(recycler, "stateS")
                + ",T=" + readRecyclerState(recycler, "stateT")
                + ",U=" + readRecyclerState(recycler, "stateU") + "}";
    }

    @Nullable
    private static Object readRecyclerState(Object recycler, String key) {
        String field = configuredField("RecyclerView", key);
        return field == null ? null : Reflect.getField(recycler, field);
    }

    private static String sizeOf(@Nullable View view) {
        return view == null ? "missing" : view.getClass().getSimpleName()
                + "{" + view.getHeight() + "/" + view.getMeasuredHeight() + "}";
    }

    private static String sizeOfViewParent(@Nullable android.view.ViewParent parent) {
        return parent instanceof View ? sizeOf((View) parent)
                : parent == null ? "missing" : parent.getClass().getSimpleName();
    }

    private static boolean notifyAdapterItemChanged(Object adapter, int position) {
        String observableField = configuredField("RecyclerViewAdapter", "observable");
        String notifyName = configuredAccessor("RecyclerViewAdapterObservable", "notifyItemChanged");
        Object observable = observableField == null ? null : Reflect.getField(adapter, observableField);
        if (observable == null || notifyName == null) {
            return false;
        }
        Method notifyRangeChanged = findInheritedMethod(
                observable.getClass(), notifyName, 3,
                int.class, int.class, Object.class);
        if (notifyRangeChanged == null) {
            return false;
        }
        try {
            notifyRangeChanged.invoke(observable, position, 1, null);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Nullable
    private static Object holderFromItemView(@Nullable View itemView) {
        if (itemView == null) {
            return null;
        }
        String holderField = configuredField("RecyclerViewLayoutParams", "viewHolder");
        String itemViewField = configuredField("RecyclerViewViewHolder", "itemView");
        Object holder = holderField == null ? null
                : Reflect.getField(itemView.getLayoutParams(), holderField);
        return holder != null && itemViewField != null
                && Reflect.getField(holder, itemViewField) == itemView
                ? holder : null;
    }

    private static boolean invalidateMessageContentBindCache(Object holder) {
        HookConfiguration config = HookConfiguration.activeConfiguration();
        if (config == null || !"MessageContentViewHolder".equals(
                HookConfiguration.semanticTypeName(holder.getClass()))) {
            return false;
        }
        String fieldName = config.fieldName("MessageContentViewHolder", "bindCache");
        if (fieldName == null || fieldName.isEmpty()) {
            return false;
        }
        for (Class<?> cls = holder.getClass(); cls != null; cls = cls.getSuperclass()) {
            try {
                java.lang.reflect.Field field = cls.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(holder, null);
                return true;
            } catch (NoSuchFieldException ignored) {
                // 继续沿继承链查找字段声明。
            } catch (Throwable t) {
                return false;
            }
        }
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
            Method m = Reflect.findMethodExact(c, namePrefix, paramCount, firstParams);
            if (m != null) {
                return m;
            }
        }
        return null;
    }

    @Nullable
    private static HookConfiguration configuration() {
        return HookConfiguration.activeConfiguration();
    }

    @Nullable
    private static String configuredType(String semanticType) {
        HookConfiguration config = configuration();
        return config == null ? null : config.typeClass(semanticType);
    }

    @Nullable
    private static String configuredField(String semanticType, String member) {
        HookConfiguration config = configuration();
        return config == null ? null : config.fieldName(semanticType, member);
    }

    @Nullable
    private static String configuredAccessor(String semanticType, String member) {
        HookConfiguration config = configuration();
        return config == null ? null : config.accessorName(semanticType, member);
    }

    @Nullable
    private static Method recyclerAccessor(android.view.ViewParent recycler, String member,
                                          Class<?>... parameterTypes) {
        String name = configuredAccessor("RecyclerView", member);
        return name == null ? null : findRecyclerApiMethod(
                recycler.getClass(), name, parameterTypes);
    }

    /** 沿父链找到最近的 RecyclerView。 */
    @Nullable
    private static android.view.ViewParent findRecyclerView(View view) {
        String recyclerClass = configuredType("RecyclerView");
        if (recyclerClass == null) return null;
        android.view.ViewParent parent = view.getParent();
        for (int depth = 0; depth < RELAYOUT_MAX_DEPTH && parent != null; depth++) {
            if (Reflect.isInstanceOfName(parent, recyclerClass)) {
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
     * <p>先调 {@code forceLayout()} 的原因：仅调 {@code requestLayout()} 时，
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
    @Nullable
    private static View findAncestorView(@Nullable View view, String className) {
        if (view == null || className == null || className.isEmpty()) return null;
        View current = view;
        for (int depth = 0; depth < RELAYOUT_MAX_DEPTH && current != null; depth++) {
            if (Reflect.isInstanceOfName(current, className)) {
                return current;
            }
            android.view.ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }
    private static boolean consumeRecyclerPendingUpdates(
            @Nullable android.view.ViewParent recycler) {
        String recyclerClass = configuredType("RecyclerView");
        if (recycler == null || recyclerClass == null
                || !Reflect.isInstanceOfName(recycler, recyclerClass)) {
            return false;
        }
        try {
            String suppressedField = configuredField("RecyclerView", "layoutSuppressed");
            String interceptField = configuredField("RecyclerView", "interceptRequestLayoutDepth");
            Object suppressed = suppressedField == null ? null : Reflect.getField(recycler, suppressedField);
            Object interceptDepth = interceptField == null ? null : Reflect.getField(recycler, interceptField);
            Method computingMethod = recyclerAccessor(recycler, "isComputingLayout");
            Object computing = computingMethod == null ? null : Reflect.call(computingMethod, recycler);
            if (!Boolean.FALSE.equals(suppressed)
                    || !(interceptDepth instanceof Integer)
                    || ((Integer) interceptDepth) != 0
                    || !Boolean.FALSE.equals(computing)) {
                HookDiagnostics.info("RecyclerView pending updates deferred"
                        + " suppressed=" + suppressed + " interceptDepth=" + interceptDepth
                        + " computing=" + computing);
                return false;
            }
            Method consumeUpdates = recyclerAccessor(recycler, "consumePendingUpdates");
            if (consumeUpdates == null) {
                HookDiagnostics.info("RecyclerView consume pending updates target missing");
                return false;
            }
            consumeUpdates.invoke(recycler);
            return true;
        } catch (Throwable t) {
            HookDiagnostics.info("RecyclerView consume pending updates failed error="
                    + t.getClass().getSimpleName());
            return false;
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
        }
    }

    /**
     * 父链传播的最大深度。
     *
     * <p>实测消息正文视图到外层 ChatListView 的层级较深，原先 12 层的
     * 上限可能在到达 ChatListView 之前就截断，导致 requestLayout 没有传到
     * 真正负责布局的那一层，表现为“高度不更新，滚动一次才恢复”。
     * 这里放宽到 32（仍为有界值，避免异常情况下的无界传播）。</p>
     */
    private static final int RELAYOUT_MAX_DEPTH = 32;
}
