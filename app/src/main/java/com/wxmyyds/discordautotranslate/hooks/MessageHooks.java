package com.wxmyyds.discordautotranslate.hooks;

import android.view.View;

import androidx.annotation.Nullable;

import com.wxmyyds.discordautotranslate.core.Reflect;
import com.wxmyyds.discordautotranslate.core.StateStore;
import com.wxmyyds.discordautotranslate.core.XLog;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Discord 消息渲染 Hook 的安装与实现。
 *
 * <p><b>已核对的真实渲染链（Discord 340.13）</b>：
 * <pre>
 * 消息列表  MessageContentViewHolder.bind
 *             → MessageContentView.setMessageContent-hYrISQc$default
 *             → MessageContentView.setMessageContent-hYrISQc   (展开式，34 参)
 *             → TextUtilsKt.toSpannable                         (真正把结构转成 Spannable)
 * 回复引用  MessageViewReplyPreview → TextUtilsKt.toSpannable   (不经 setMessageContent)
 * </pre>
 *
 * <p>因此本类安装四个渲染 Hook，并额外安装一个可选的测量冻结诊断 Hook：</p>
 * <ol>
 *   <li><b>bind</b>：{@code MessageContentViewHolder.bind} —— 唯一可靠的
 *       「视图 → 消息 ID」来源（{@code MessageContentAccessory.getMessageId}）。</li>
 *   <li><b>渲染入口</b>：{@code MessageContentView} 上的展开式 {@code setMessageContent}
 *       （参数最多、首参为 {@code MessageMargins} 的那个重载）——
 *       提供「视图 + 内容」，用于登记重渲染、触发翻译，并把宿主视图传给行内图标注入器。
 *       注意：<b>不能</b> Hook 6 参的 {@code setMessageContent}，它只被
 *       {@code TextDisplayComponentView} 调用，不在消息路径上。</li>
 *   <li><b>文本漏斗</b>：{@code TextUtilsKt.toSpannable} —— 所有文本渲染的唯一汇聚点
 *       （含回复引用与 Embed）。翻译替换与行内图标注入都在此处完成，因为该方法是静态的、
 *       接收的 {@code StructurableText} 与 bind 阶段是同一实例，可用身份映射反查消息 ID。</li>
 *   <li><b>回复引用</b>：{@code MessageViewReplyPreview.configureReply} ——
 *       把引用内的正文视图关联到被引用消息 ID，使引用内译文也能更新。</li>
 *   <li><b>测量诊断</b>：观察 {@code onMeasureConsiderFrozen} 的 same-key 冻结分支，
 *       只记录父级宽度、子视图尺寸与 key 状态，不改变宿主测量参数。</li>
 * </ol>
 *
 * <p>所有 Hook 均以 {@code ExceptionMode.PROTECTIVE} 安装，保证模块自身异常不会
 * 让 Discord 崩溃（需求 §5.1）。</p>
 */
public final class MessageHooks {

    private static final String CLS_MESSAGE_CONTENT_VIEW =
            "com.discord.chat.presentation.message.view.MessageContentView";
    private static final String CLS_CONTENT_VIEW_HOLDER =
            "com.discord.chat.presentation.message.viewholder.MessageContentViewHolder";
    private static final String CLS_CONTENT_ACCESSORY =
            "com.discord.chat.presentation.message.messagepart.MessageContentAccessory";
    private static final String CLS_MESSAGE_MARGINS =
            "com.discord.chat.presentation.message.messagepart.MessageMargins";

    /**
     * 回复引用容器。行内图标不注入它（见 {@link InlineIconInjector}），
     * 但需要 Hook 它的 {@code configureReply} 登记重渲染目标。
     */
    private static final String CLS_REPLY_PREVIEW =
            "com.discord.chat.presentation.message.MessageViewReplyPreview";
    private static final String CLS_TEXT_UTILS =
            "com.discord.chat.presentation.textutils.TextUtilsKt";
    private static final String CLS_CHAT_LIST_CHILD_VIEW =
            "com.discord.chat.presentation.list.ChatListChildView";
    private static final String CLS_CHAT_LIST_CHILD_VIEW_DEFAULT_IMPLS =
            "com.discord.chat.presentation.list.ChatListChildView$DefaultImpls";
    private static final String CLS_CHAT_LIST_VIEW =
            "com.discord.chat.presentation.list.ChatListView";
    private static final String CLS_STRUCTURABLE =
            "com.discord.chat.bridge.structurabletext.StructurableText";

    /** bind 的参数个数（340.13 已确认）。 */
    private static final int BIND_PARAM_COUNT = 17;

    /** 展开式 setMessageContent 的参数个数下限（实测 34，留出余量以耐受版本差异）。 */
    private static final int EXPANDED_PARAM_COUNT_MIN = 20;

    /** 低于该数量视为安装不完整：模块不会生效，调用方应回滚并等待重启。 */
    public static final int MIN_REQUIRED_HOOKS = 4;

    private MessageHooks() {
    }

    /** 安装全部 Hook，返回句柄供热重载清理。
     *
     * <p>任一步骤抛出异常时回滚已安装的 Hook，避免留下“部分安装”的进程状态；
     * 调用方应据返回的句柄数量判断模块是否可用。</p> */
    public static List<XposedInterface.HookHandle> install(XposedModule module, ClassLoader cl) {
        List<XposedInterface.HookHandle> handles = new ArrayList<>(6);
        try {
            installBindHook(module, cl, handles);
            installExpandedRenderHook(module, cl, handles);
            installToSpannableHook(module, cl, handles);
            installReplyHook(module, cl, handles);
            installMeasureFreezeDiagnosticHook(module, cl, handles);
        } catch (Throwable t) {
            XLog.e("hook installation aborted, rolling back " + handles.size() + " hooks", t);
            for (XposedInterface.HookHandle handle : handles) {
                try {
                    handle.unhook();
                } catch (Throwable ignored) {
                    // 回滚尽力而为；单个 unhook 失败不应掩盖原始异常。
                }
            }
            handles.clear();
            return handles;
        }

        // Four renderer hooks are required; the freeze-measure observer is optional.
        XLog.i("hook pipeline armed: " + handles.size() + " hook handles (4 required)");
        if (handles.size() < MIN_REQUIRED_HOOKS) {
            XLog.w("too few hooks installed; module will not function on this Discord version");
        }
        return handles;
    }

    /** Observe only the host's same-key frozen-measure branch; never alter its arguments. */
    private static void installMeasureFreezeDiagnosticHook(
            XposedModule module, ClassLoader cl, List<XposedInterface.HookHandle> out) {
        Class<?> childInterface = Reflect.loadClass(cl, CLS_CHAT_LIST_CHILD_VIEW);
        Class<?> defaultImpls = Reflect.loadClass(cl, CLS_CHAT_LIST_CHILD_VIEW_DEFAULT_IMPLS);
        Method measure = Reflect.findMethod(defaultImpls, "onMeasureConsiderFrozen", 3,
                childInterface, int.class, int.class);
        Method currentKeyGetter = Reflect.findMethod(childInterface, "getCurrentKey", 0);
        Method lastKeyGetter = Reflect.findMethod(childInterface, "getLastKey", 0);
        if (measure == null || currentKeyGetter == null || lastKeyGetter == null) {
            XLog.w("same-key frozen-measure diagnostic hook unavailable");
            return;
        }
        try {
            out.add(module.hook(measure)
                    .setId("dat.measureFrozen")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object arg = chain.getArg(0);
                        if (!(arg instanceof View)) {
                            return chain.proceed();
                        }
                        View child = (View) arg;
                        int widthSpec = (Integer) chain.getArg(1);
                        android.view.ViewParent parent = child.getParent();
                        boolean frozen = Reflect.isInstanceOfName(parent, CLS_CHAT_LIST_VIEW)
                                && parent instanceof View
                                && View.MeasureSpec.getMode(widthSpec) == View.MeasureSpec.EXACTLY
                                && ((View) parent).getMeasuredWidth() == 0;
                        Object currentKey = Reflect.call(currentKeyGetter, child);
                        Object lastKey = Reflect.call(lastKeyGetter, child);
                        boolean sameKey = currentKey != null
                                && java.util.Objects.equals(currentKey, lastKey);
                        boolean report = frozen && sameKey;
                        int oldWidth = child.getMeasuredWidth();
                        int oldHeight = child.getMeasuredHeight();
                        if (report) {
                            XLog.i("measure-freeze enter key=" + currentKey
                                    + " widthSize=" + View.MeasureSpec.getSize(widthSpec)
                                    + " child=" + oldWidth + "x" + oldHeight);
                        }
                        Object result = chain.proceed();
                        if (report) {
                            XLog.i("measure-freeze exit key=" + currentKey
                                    + " child=" + child.getMeasuredWidth() + "x"
                                    + child.getMeasuredHeight());
                        }
                        return result;
                    }));
            XLog.i("hooked same-key frozen-measure diagnostic");
        } catch (Throwable t) {
            XLog.w("cannot install same-key frozen-measure diagnostic: "
                    + t.getClass().getSimpleName(), t);
        }
    }

    // ------------------------------------------------------------------ Hook 1: bind

    private static void installBindHook(XposedModule module, ClassLoader cl,
                                        List<XposedInterface.HookHandle> out) {
        Class<?> holder = Reflect.loadClass(cl, CLS_CONTENT_VIEW_HOLDER);
        if (holder == null) {
            XLog.w("MessageContentViewHolder not found; cannot map view -> message id");
            return;
        }
        Class<?> accessory = Reflect.loadClass(cl, CLS_CONTENT_ACCESSORY);

        Method bind = Reflect.findMethod(holder, "bind", BIND_PARAM_COUNT, accessory);
        if (bind == null) {
            bind = Reflect.findMethod(holder, "bind", BIND_PARAM_COUNT);
        }
        if (bind == null) {
            XLog.w("MessageContentViewHolder.bind not found (paramCount=" + BIND_PARAM_COUNT + ")");
            return;
        }

        out.add(module.hook(bind)
                .setId("dat.bind")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    try {
                        bindPre(chain);
                    } catch (Throwable t) {
                        XLog.w("bind pre failed", t);
                    }
                    return chain.proceed();
                }));
        XLog.i("hooked MessageContentViewHolder.bind");
    }

    /** bind 前置：建立 正文视图 → 消息 ID 关联，并登记内容身份映射。 */
    private static void bindPre(XposedInterface.Chain chain) {
        Object accessory = chain.getArg(0);
        if (accessory == null) {
            return;
        }
        String messageId = Reflect.callString(accessory, "getMessageId");
        if (messageId == null || !HookBridge.looksLikeSnowflake(messageId)) {
            return;
        }
        // 顺带登记「内容 → 消息 ID」：授权对象里的 StructurableText 与后续渲染是同一实例。
        Object content = Reflect.call(Reflect.findMethod(
                accessory.getClass(), "getContent", 0), accessory);
        HookBridge.get().rememberContent(messageId, content);

        View contentView = asView(Reflect.getField(chain.getThisObject(), "messageContentView"));
        if (contentView == null) {
            XLog.d("bind: messageContentView field unavailable");
            return;
        }
        HookBridge.get().bindView(contentView, messageId);
        XLog.d("bind " + messageId + " -> " + contentView.getClass().getSimpleName()
                + "@" + Integer.toHexString(System.identityHashCode(contentView)));
    }

    // ------------------------------------------------- Hook 2: 展开式 setMessageContent

    /**
     * Hook 展开式 {@code setMessageContent}（34 参）。它提供「视图 + 内容」，
     * 是挂图标、登记重渲染与触发翻译的位置。不做替换（替换统一在 toSpannable 完成）。
     */
    private static void installExpandedRenderHook(XposedModule module, ClassLoader cl,
                                                  List<XposedInterface.HookHandle> out) {
        Class<?> contentViewCls = Reflect.loadClass(cl, CLS_MESSAGE_CONTENT_VIEW);
        Class<?> marginsCls = Reflect.loadClass(cl, CLS_MESSAGE_MARGINS);
        Class<?> structurableCls = Reflect.loadClass(cl, CLS_STRUCTURABLE);
        if (contentViewCls == null || marginsCls == null || structurableCls == null) {
            XLog.w("cannot resolve classes for expanded setMessageContent hook");
            return;
        }

        Method expanded = findExpandedSetMessageContent(contentViewCls, marginsCls, structurableCls);
        if (expanded == null) {
            XLog.w("expanded setMessageContent not found; icon/translation trigger unavailable");
            return;
        }
        final Method target = expanded;
        XLog.i("resolved expanded setMessageContent: " + target.getName()
                + " params=" + target.getParameterCount());

        out.add(module.hook(expanded)
                .setId("dat.setMessageContent")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    View view = asView(chain.getThisObject());
                    // 宿主必须在调用链内传递给 toSpannable（同一线程同步），
                    // 行内图标靠它驱动旋转重绘；重渲染时同样需要，故无条件设置。
                    InlineIconInjector.enterHost(view);
                    try {
                        // 重渲染（本模块自行发起）时无需重复登记与触发翻译；
                        // 文本替换由 toSpannable Hook 完成。
                        if (!HookBridge.get().isRerendering() && view != null) {
                            Object structurable = chain.getArg(1);
                            Object[] renderArgs = chain.getArgs().toArray();
                            // 记录内容身份映射，并登记“视图 + 调用目标 + 实参”供重渲染。
                            HookBridge.get().interceptRender(view, renderArgs, structurable);

                            String messageId = HookBridge.get().messageIdOf(view);
                            if (messageId != null) {
                                RerenderRegistry.register(messageId, view,
                                        chain.getThisObject(), target, renderArgs);
                                if (structurable != null) {
                                    HookBridge.get().onMessageBound(
                                            view, messageId, structurable);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        // 任何异常都不影响原始渲染（需求 §5.4 优雅降级）。
                        XLog.w("expanded render hook failed", t);
                    }
                    try {
                        return chain.proceed();
                    } finally {
                        InlineIconInjector.exitHost();
                    }
                }));
        XLog.i("hooked expanded setMessageContent");
    }

    /**
     * 定位展开式 {@code setMessageContent}。
     *
     * <p>筛选条件：名字以 {@code setMessageContent} 开头、参数最多、且<b>首参为
     * {@code MessageMargins}</b>。最后一条用于排除 Kotlin 生成的
     * {@code setMessageContent$default}（其首参是 {@code MessageContentView}）。
     * 不按方法名哈希后缀匹配，以耐受 Discord 版本更新。</p>
     */
    @Nullable
    private static Method findExpandedSetMessageContent(Class<?> contentViewCls,
                                                        Class<?> marginsCls,
                                                        Class<?> structurableCls) {
        Method best = null;
        for (Method m : contentViewCls.getDeclaredMethods()) {
            if (!m.getName().startsWith("setMessageContent")) {
                continue;
            }
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length < EXPANDED_PARAM_COUNT_MIN) {
                continue;
            }
            if (!ps[0].isAssignableFrom(marginsCls) && !marginsCls.isAssignableFrom(ps[0])) {
                continue;
            }
            if (ps.length < 2
                    || (!ps[1].isAssignableFrom(structurableCls)
                    && !structurableCls.isAssignableFrom(ps[1]))) {
                continue;
            }
            if (best == null || ps.length > best.getParameterCount()) {
                best = m;
            }
        }
        if (best != null) {
            best.setAccessible(true);
        }
        return best;
    }

    // ------------------------------------------------------- Hook 3: toSpannable

    /**
     * Hook {@code TextUtilsKt.toSpannable}：所有文本渲染的唯一汇聚点，
     * 翻译替换与行内图标注入都在此完成（含回复引用与 Embed）。
     */
    private static void installToSpannableHook(XposedModule module, ClassLoader cl,
                                               List<XposedInterface.HookHandle> out) {
        Class<?> textUtils = Reflect.loadClass(cl, CLS_TEXT_UTILS);
        if (textUtils == null) {
            XLog.w("TextUtilsKt not found; translation display disabled");
            return;
        }
        Class<?> structurableCls = Reflect.loadClass(cl, CLS_STRUCTURABLE);
        Method toSpannable = null;
        for (Method m : textUtils.getDeclaredMethods()) {
            // 必须选中首参为 StructurableText 的重载。
            // 注意 TextUtilsKt 中有<b>两个</b>方法名以 toSpannable 开头的重载：
            //   29 参 首参 StructurableText  ← 需要这个
            //    4 参 首参 List             ← 内部递归用，不能选
            // 原实现用 getParameterCount() >= 4 取第一个匹配，顺序不确定。
            boolean nameMatches = m.getName().startsWith("toSpannable");
            if (!nameMatches) {
                continue;
            }
            // 排除 Kotlin 生成的 $default / $lambda 合成方法。
            if (m.getName().contains("$")) {
                continue;
            }
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length >= 4 && structurableCls != null
                    && (ps[0].isAssignableFrom(structurableCls)
                    || structurableCls.isAssignableFrom(ps[0]))) {
                toSpannable = m;
                break;
            }
        }
        if (toSpannable == null) {
            XLog.w("TextUtilsKt.toSpannable(StructurableText, ...) not found");
            return;
        }
        toSpannable.setAccessible(true);

        out.add(module.hook(toSpannable)
                .setId("dat.toSpannable")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object source = chain.getArg(0);
                    Object[] renderArgs = chain.getArgs().toArray();
                    try {
                        String messageId = HookBridge.get().resolveMessageId(source);
                        if (messageId != null) {
                            // 回复引用/Embed 路径不经过 setMessageContent，
                            // 在此登记内容并触发翻译。
                            HookBridge.get().rememberContent(messageId, source);

                            StateStore.Entry e = StateStore.get().peek(messageId);
                            if (e == null) {
                                HookBridge.get().onMessageBound(null, messageId, source);
                                e = StateStore.get().peek(messageId);
                            }
                            String translated = (e != null
                                    && e.status == StateStore.Status.TRANSLATED
                                    && !e.showingOriginal) ? e.translatedText : null;
                            if (translated != null) {
                                Object replacement = StructurableTextFactory
                                        .forTranslation(source, translated);
                                if (replacement != null) {
                                    renderArgs[0] = replacement;
                                    XLog.d("toSpannable substituted for " + messageId);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        XLog.w("toSpannable preparation failed, using original content", t);
                        renderArgs[0] = source;
                    }

                    // 宿主渲染只执行一次；图标注入失败时保留原始渲染结果。
                    Object rendered = chain.proceed(renderArgs);
                    try {
                        return InlineIconInjector.inject(source, rendered, chain.getArg(1), cl);
                    } catch (Throwable t) {
                        XLog.w("inline icon injection failed", t);
                        return rendered;
                    }
                }));
        XLog.i("hooked TextUtilsKt.toSpannable (" + toSpannable.getParameterCount() + " params)");
    }

    // ------------------------------------------------------------- Hook 4: 回复引用

    /**
     * Hook {@code MessageViewReplyPreview.configureReply}：把回复引用内的正文视图
     * 关联到<b>被引用消息自己的 ID</b>，使引用内的内容也能被翻译。
     * 行内图标<b>不</b>注入回复引用（{@link InlineIconInjector} 在无宿主时跳过），
     * 但引用内的译文切换仍需要重渲染登记。
     */
    private static void installReplyHook(XposedModule module, ClassLoader cl,
                                         List<XposedInterface.HookHandle> out) {
        Class<?> replyPreview = Reflect.loadClass(cl, CLS_REPLY_PREVIEW);
        if (replyPreview == null) {
            XLog.d("MessageViewReplyPreview not found; reply content translation disabled");
            return;
        }
        Method configure = Reflect.findMethod(replyPreview, "configureReply", 3);
        if (configure == null) {
            XLog.w("MessageViewReplyPreview.configureReply not found");
            return;
        }

        out.add(module.hook(configure)
                .setId("dat.configureReply")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    try {
                        replyPre(chain);
                    } catch (Throwable t) {
                        XLog.w("configureReply pre failed", t);
                    }
                    Object result = chain.proceed();
                    try {
                        replyPost(chain, configure);
                    } catch (Throwable t) {
                        XLog.w("configureReply post failed", t);
                    }
                    return result;
                }));
        XLog.i("hooked MessageViewReplyPreview.configureReply");
    }

    /** 后置：登记回复引用的重渲染目标，使译文完成后引用内也能更新。 */
    private static void replyPost(XposedInterface.Chain chain, Method configureReply) {
        Object preview = chain.getThisObject();
        if (preview == null) {
            return;
        }
        View replyText = replyTextViewOf(preview);
        if (replyText == null) {
            return;
        }
        String messageId = HookBridge.get().messageIdOf(replyText);
        if (messageId == null) {
            return;
        }
        // 回复引用的渲染入口就是这个 configureReply 自身；用同一组实参重跑即可。
        RerenderRegistry.register(messageId, replyText, preview,
                configureReply, chain.getArgs().toArray());
    }

    /** 前置：从被引用消息取 ID，登记到回复引用内的正文视图与内容映射。 */
    private static void replyPre(XposedInterface.Chain chain) {
        Object referenced = chain.getArg(0);
        if (referenced == null) {
            return;
        }
        // 只有 LoadedReferencedMessage 带真实消息；SystemReferencedMessage 是系统文案。
        if (!Reflect.isInstanceOfName(referenced,
                "com.discord.chat.bridge.referencedmessage.LoadedReferencedMessage")) {
            return;
        }
        Object message = Reflect.call(Reflect.findMethod(
                referenced.getClass(), "getMessage", 0), referenced);
        if (message == null) {
            return;
        }
        String messageId = Reflect.callString(message, "getId");
        if (messageId == null || !HookBridge.looksLikeSnowflake(messageId)) {
            return;
        }
        // 被引用消息的正文结构（用于身份映射，使 toSpannable 能反查到该消息）。
        Object content = Reflect.call(Reflect.findMethod(
                message.getClass(), "getContent", 0), message);
        HookBridge.get().rememberContent(messageId, content);

        View replyText = replyTextViewOf(chain.getThisObject());
        if (replyText != null) {
            HookBridge.get().bindView(replyText, messageId);
        }
        XLog.d("reply bound to referenced message " + messageId);
    }

    /** 从回复引用实例取出其内部正文视图。 */
    @Nullable
    private static View replyTextViewOf(@Nullable Object replyPreview) {
        Object binding = Reflect.getField(replyPreview, "binding");
        if (binding == null) {
            return null;
        }
        return asView(Reflect.getField(binding, "replyText"));
    }

    // ------------------------------------------------------------------ 工具

    @Nullable
    private static View asView(@Nullable Object o) {
        return o instanceof View ? (View) o : null;
    }
}
