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
            requestLayoutChain(view);
        });
        if (!posted) {
            PENDING_ROW_REFRESH.remove(key);
            // 视图未附着：滚动重新绑定时仍会走正常渲染，无需强制刷新。
        }
    }

    /** 已安排但尚未执行的行刷新；避免同一帧内重复 notifyItemChanged。 */
    private static final java.util.Set<String> PENDING_ROW_REFRESH =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /** Optional direct content-view fields on holders; 340.13 is validated by itemView ancestry. */
    private static final String[] CONTENT_VIEW_FIELDS = {
            "messageContentView", "embedView", "postPreviewEmbedView", "pollView"};

    /** holder 是否以 {@code view} 作为其内容承载视图；据此过滤无关视图的误刷新。 */
    private static boolean ownsContentView(Object holder, View view) {
        for (String fieldName : CONTENT_VIEW_FIELDS) {
            if (Reflect.getField(holder, fieldName) == view) {
                return true;
            }
        }
        // Discord 340.13 keeps the content child under the ViewHolder's itemView rather
        // than exposing it as a holder field. findContainingViewHolder already identifies
        // the owning holder; verify containment through the actual view hierarchy instead.
        Object itemView = Reflect.getField(holder, "itemView");
        if (itemView instanceof View && isDescendantOrSelf(view, (View) itemView)) {
            HookDiagnostics.infoOnce("rowRefresh.ownerViaItemView",
                    "content holder matched through itemView descendant hierarchy");
            return true;
        }
        return false;
    }

    @Nullable
    private static Method findRecyclerApiMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            // AndroidX RecyclerView/ViewGroup expose these as public inherited API methods.
            // Class.getDeclaredMethods() may omit framework methods on Android; getMethod()
            // searches public methods through superclasses and interfaces.
            return type.getMethod(name, parameterTypes);
        } catch (Throwable ignored) {
            return findInheritedMethod(type, name, parameterTypes.length, parameterTypes);
        }
    }

    /** Resolve a RecyclerView holder, including when its framework containment lookup misses. */
    @Nullable
    private static Object findContainingViewHolder(android.view.ViewParent recycler, View view) {
        Method findContaining = findRecyclerApiMethod(
                recycler.getClass(), "findContainingViewHolder", View.class);
        Object holder = Reflect.call(findContaining, recycler, view);
        if (holder != null) {
            return holder;
        }
        if (findContaining == null) {
            HookDiagnostics.infoOnce("rowRefresh.containmentScanFallback",
                    "RecyclerView containment API unavailable; scanning attached item views");
        }

        Method childCountMethod = findRecyclerApiMethod(recycler.getClass(), "getChildCount");
        Method childAtMethod = findRecyclerApiMethod(recycler.getClass(), "getChildAt", int.class);
        Object countValue = Reflect.call(childCountMethod, recycler);
        if (!(countValue instanceof Integer) || childAtMethod == null) {
            reportRowRefreshFailure("attachedChildScanMethods");
            return null;
        }
        int count = (Integer) countValue;
        for (int i = 0; i < count; i++) {
            Object childObject = Reflect.call(childAtMethod, recycler, i);
            if (!(childObject instanceof View)) {
                continue;
            }
            View itemView = (View) childObject;
            if (!isDescendantOrSelf(view, itemView)) {
                continue;
            }
            Object layoutParams = itemView.getLayoutParams();
            Object childHolder = Reflect.getField(layoutParams, "viewHolder");
            if (childHolder != null
                    && Reflect.getField(childHolder, "itemView") == itemView) {
                HookDiagnostics.infoOnce("rowRefresh.layoutParamsHolderMatched",
                        "RecyclerView item holder resolved from item layout params");
                return childHolder;
            }
        }
        reportRowRefreshFailure("attachedChildScanNoMatch");
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
            Object contentAdapter = Reflect.call(
                    findInheritedMethod(contentRecycler.getClass(), "getAdapter", 0), contentRecycler);
            View messageRow = findAncestorView(view, MESSAGE_VIEW_CLASS);
            Object rowHolder = messageRow == null ? null : holderFromItemView(messageRow);
            Object rowRecyclerObject = Reflect.getField(rowHolder, "recyclerView");
            if (contentAdapter == null || rowHolder == null
                    || !(rowRecyclerObject instanceof android.view.ViewParent)) {
                reportRowRefreshFailure(contentAdapter == null ? "contentAdapter"
                        : rowHolder == null ? "messageRowHolder" : "rowRecycler");
                return false;
            }
            android.view.ViewParent rowRecycler = (android.view.ViewParent) rowRecyclerObject;
            Object rowAdapter = Reflect.call(
                    findInheritedMethod(rowRecycler.getClass(), "getAdapter", 0), rowRecycler);
            Object itemView = Reflect.getField(rowHolder, "itemView");
            if (rowAdapter == null) {
                reportRowRefreshFailure("rowAdapter");
                return false;
            }
            if (itemView != messageRow) {
                reportRowRefreshFailure("messageViewItem");
                return false;
            }
            Object bindingPositionValue = Reflect.call(
                    findInheritedMethod(rowHolder.getClass(), "getBindingAdapterPosition", 0),
                    rowHolder);
            Object absolutePositionValue = Reflect.call(
                    findInheritedMethod(rowHolder.getClass(), "getAbsoluteAdapterPosition", 0),
                    rowHolder);
            Object bindingAdapter = Reflect.getField(rowHolder, "bindingAdapter");
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
            Object contentPosValue = Reflect.call(
                    findInheritedMethod(contentHolder.getClass(), "getBindingAdapterPosition", 0),
                    contentHolder);
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
            postRecyclerState(contentRecycler, "content", view, messageRow);
            postRecyclerState(rowRecycler, "outer", view, messageRow);
            messageRow.post(() -> HookDiagnostics.info("row refresh after layout"
                    + " content=" + sizeOf(view)
                    + " accessories=" + sizeOf(findAncestorView(view, MESSAGE_ACCESSORIES_VIEW_CLASS))
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
                        // The 352017 RecyclerView observer can set the layout-request flag
                        // while its current traversal is already pending. If the outer list
                        // still reports a request at pre-draw, queue one fresh request after
                        // this traversal so the ChatList LayoutManager consumes the changed
                        // row measurement without requiring a touch/scroll event.
                        if ("outer".equals(label) && recyclerView.isLayoutRequested()) {
                            messageRow.postOnAnimation(() -> {
                                if (!messageRow.isAttachedToWindow()
                                        || !recyclerView.isAttachedToWindow()) {
                                    return;
                                }
                                requestLayoutChain(messageRow);
                                HookDiagnostics.info("row refresh post-draw layout retry requested"
                                        + " outer=" + recyclerState(recycler)
                                        + " row=" + sizeOf(messageRow));
                                logNextPreDraw(recyclerView, recycler, label,
                                        contentView, messageRow);
                            });
                        }
                        return true;
                    }
                };
        observer.addOnPreDrawListener(listener);
    }

    private static void logNextPreDraw(View recyclerView,
                                       android.view.ViewParent recycler, String label,
                                       View contentView, View messageRow) {
        android.view.ViewTreeObserver observer = recyclerView.getViewTreeObserver();
        if (!observer.isAlive()) {
            return;
        }
        observer.addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (observer.isAlive()) {
                    observer.removeOnPreDrawListener(this);
                }
                HookDiagnostics.info("row refresh recycler state retry pre-draw " + label + "="
                        + recyclerState(recycler)
                        + " content=" + sizeOf(contentView)
                        + " row=" + sizeOf(messageRow));
                return true;
            }
        });
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
                + ",P=" + Reflect.getField(recycler, "P")
                + ",Q=" + Reflect.getField(recycler, "Q")
                + ",R=" + Reflect.getField(recycler, "R")
                + ",S=" + Reflect.getField(recycler, "S")
                + ",T=" + Reflect.getField(recycler, "T")
                + ",U=" + Reflect.getField(recycler, "U") + "}";
    }

    private static String sizeOf(@Nullable View view) {
        return view == null ? "missing" : view.getClass().getSimpleName()
                + "{" + view.getHeight() + "/" + view.getMeasuredHeight() + "}";
    }

    private static String sizeOfViewParent(@Nullable android.view.ViewParent parent) {
        return parent instanceof View ? sizeOf((View) parent)
                : parent == null ? "missing" : parent.getClass().getSimpleName();
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
                        relayoutContentSelf(view);
                        relayoutNamedAncestor(view, MESSAGE_ACCESSORIES_VIEW_CLASS);
                        relayoutNamedAncestor(view, MESSAGE_VIEW_CLASS);
                        view.invalidate();
                        dispatchRecyclerLayout(findRecyclerView(view));
                        View messageRow = findAncestorView(view, MESSAGE_VIEW_CLASS);
                        dispatchRecyclerLayout(messageRow == null
                                ? null : findRecyclerView(messageRow));
                    } catch (Throwable ignored) {
                        // 布局派发失败可忽略。
                    }
                }
            });
        } catch (Throwable ignored) {
            // 投递重渲染失败不影响宿主。
        }
    }

    private static boolean notifyAdapterItemChanged(Object adapter, int position) {
        Object observable = Reflect.getField(adapter, "observable");
        if (observable == null) {
            return false;
        }
        Method notifyRangeChanged = findInheritedMethod(
                observable.getClass(), "notifyItemChanged", 3,
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
        Object holder = Reflect.getField(itemView.getLayoutParams(), "viewHolder");
        return holder != null && Reflect.getField(holder, "itemView") == itemView
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
        } catch (Throwable ignored) {
            // 布局请求失败可忽略。
        }
    }

    /** Re-measure a known message container after its content child has shrunk. */
    private static void relayoutNamedAncestor(@Nullable View view, String className) {
        View target = findAncestorView(view, className);
        if (target == null || !target.isAttachedToWindow()) {
            return;
        }
        try {
            int width = target.getWidth();
            if (width <= 0) {
                width = target.getMeasuredWidth();
            }
            if (width <= 0) {
                return;
            }
            int oldHeight = target.getHeight();
            target.forceLayout();
            target.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int newHeight = target.getMeasuredHeight();
            if (newHeight > 0 && newHeight != oldHeight) {
                target.layout(target.getLeft(), target.getTop(),
                        target.getLeft() + width, target.getTop() + newHeight);
            }
            HookDiagnostics.infoOnce("rowRefresh.containerRelayout." + className,
                    "message container remeasured class=" + className
                            + " oldHeight=" + oldHeight + " newHeight=" + newHeight);
        } catch (Throwable t) {
            HookDiagnostics.infoOnce("rowRefresh.containerRelayoutFailed." + className,
                    "message container remeasure failed class=" + className
                            + " error=" + t.getClass().getSimpleName());
        }
    }

    @Nullable
    private static View findAncestorView(@Nullable View view, String className) {
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
    private static void dispatchRecyclerLayout(@Nullable android.view.ViewParent recycler) {
        if (recycler == null) {
            return;
        }
        try {
            Object suppressed = Reflect.getField(recycler, "mLayoutSuppressed");
            Object interceptDepth = Reflect.getField(recycler, "mInterceptRequestLayoutDepth");
            Object computing = Reflect.call(findRecyclerApiMethod(
                    recycler.getClass(), "isComputingLayout"), recycler);

            if (!Boolean.FALSE.equals(suppressed)
                    || !(interceptDepth instanceof Integer)
                    || ((Integer) interceptDepth) != 0
                    || Boolean.TRUE.equals(computing)) {
                return;
            }
            Method dispatchLayout = findRecyclerApiMethod(
                    recycler.getClass(), "dispatchLayout");

            if (dispatchLayout != null) {
                dispatchLayout.invoke(recycler);
            }
        } catch (Throwable ignored) {
            // 派发布局失败可忽略。
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

    private static final String MESSAGE_ACCESSORIES_VIEW_CLASS =
            "com.discord.chat.presentation.message.MessageAccessoriesView";
    private static final String MESSAGE_VIEW_CLASS =
            "com.discord.chat.presentation.message.MessageView";

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
