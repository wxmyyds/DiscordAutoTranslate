package io.github.wxmyyds.discordautotranslate.hooks;

import android.view.View;

import androidx.annotation.Nullable;

import io.github.wxmyyds.discordautotranslate.core.Reflect;
import io.github.wxmyyds.discordautotranslate.core.StateStore;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Discord 消息渲染 Hook 的安装与实现。
 *
 * <p>已核对的真实渲染链（Discord 340.13）：
 * <pre>
 * 消息列表  MessageContentViewHolder.bind
 *             → MessageContentView.setMessageContent-hYrISQc$default
 *             → MessageContentView.setMessageContent-hYrISQc   (展开式，34 参)
 *             → TextUtilsKt.toSpannable                         (真正把结构转成 Spannable)
 * 回复引用  MessageViewReplyPreview → TextUtilsKt.toSpannable   (不经 setMessageContent)
 * </pre>
 *
 * <p>因此本类只安装四个渲染 Hook：</p>
 * <ol>
 *   <li>bind：{@code MessageContentViewHolder.bind}，唯一可靠的
 *       「视图 → 消息 ID」来源（{@code MessageContentAccessory.getMessageId}）。</li>
 *   <li>渲染入口：{@code MessageContentView} 上的展开式 {@code setMessageContent}
 *       （参数最多、首参为 {@code MessageMargins} 的那个重载），
 *       提供「视图 + 内容」，用于登记重渲染、触发翻译，并把宿主视图传给行内图标注入器。
 *       注意：不能 Hook 6 参的 {@code setMessageContent}，它只被
 *       {@code TextDisplayComponentView} 调用，不在消息路径上。</li>
 *   <li>文本漏斗：{@code TextUtilsKt.toSpannable}，所有文本渲染的唯一汇聚点
 *       （含回复引用与 Embed）。翻译替换与行内图标注入都在此处完成，因为该方法是静态的、
 *       接收的 {@code StructurableText} 与 bind 阶段是同一实例，可用身份映射反查消息 ID。</li>
 *   <li>回复引用：{@code MessageViewReplyPreview.configureReply}，
 *       把引用内的正文视图关联到被引用消息 ID，使引用内译文也能更新。</li>
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
        List<XposedInterface.HookHandle> handles = new ArrayList<>(4);
        try {
            installBindHook(module, cl, handles);
            installExpandedRenderHook(module, cl, handles);
            installToSpannableHook(module, cl, handles);
            installReplyHook(module, cl, handles);
        } catch (Throwable t) {
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

        return handles;
    }

    // ------------------------------------------------------------------ Hook 1: bind

    private static void installBindHook(XposedModule module, ClassLoader cl,
                                        List<XposedInterface.HookHandle> out) {
        Class<?> holder = Reflect.loadClass(cl, CLS_CONTENT_VIEW_HOLDER);
        Class<?> accessory = Reflect.loadClass(cl, CLS_CONTENT_ACCESSORY);
        if (holder == null || accessory == null) {
            return;
        }

        Method bind = Reflect.findMethod(holder, "bind", BIND_PARAM_COUNT, accessory);
        if (bind == null) {
            return;
        }

        out.add(module.hook(bind)
                .setId("dat.bind")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    try {
                        bindPre(chain);
                    } catch (Throwable ignored) {
                        // 关联失败只影响本条消息的翻译，不影响宿主。
                    }
                    return chain.proceed();
                }));
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
            return;
        }
        HookBridge.get().bindView(contentView, messageId);
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
            return;
        }

        Method expanded = findExpandedSetMessageContent(contentViewCls, marginsCls, structurableCls);
        if (expanded == null) {
            return;
        }
        final Method target = expanded;

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
                    }
                    try {
                        return chain.proceed();
                    } finally {
                        InlineIconInjector.exitHost();
                    }
                }));
    }

    /**
     * 定位展开式 {@code setMessageContent}。
     *
     * <p>筛选条件：名字以 {@code setMessageContent} 开头、参数最多、且首参为
     * {@code MessageMargins}。最后一条用于排除 Kotlin 生成的
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
            return;
        }
        Class<?> structurableCls = Reflect.loadClass(cl, CLS_STRUCTURABLE);
        Method toSpannable = null;
        for (Method m : textUtils.getDeclaredMethods()) {
            // 必须选中首参为 StructurableText 的重载。
            // 注意 TextUtilsKt 中有两个方法名以 toSpannable 开头的重载：
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
                                } else {
                                    HookBridge.get().onTranslationRenderRejected(
                                            messageId, translated);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        renderArgs[0] = source;
                    }

                    // 宿主渲染只执行一次；图标注入失败时保留原始渲染结果。
                    Object rendered = chain.proceed(renderArgs);
                    try {
                        return InlineIconInjector.inject(source, rendered, chain.getArg(1), cl);
                    } catch (Throwable t) {
                        return rendered;
                    }
                }));
    }

    // ------------------------------------------------------------- Hook 4: 回复引用

    /**
     * Hook {@code MessageViewReplyPreview.configureReply}：把回复引用内的正文视图
     * 关联到被引用消息自己的 ID，使引用内的内容也能被翻译。
     * 行内图标不注入回复引用（{@link InlineIconInjector} 在无宿主时跳过），
     * 但引用内的译文切换仍需要重渲染登记。
     */
    private static void installReplyHook(XposedModule module, ClassLoader cl,
                                         List<XposedInterface.HookHandle> out) {
        Class<?> replyPreview = Reflect.loadClass(cl, CLS_REPLY_PREVIEW);
        if (replyPreview == null) {
            return;
        }
        Method configure = Reflect.findMethod(replyPreview, "configureReply", 3);
        if (configure == null) {
            return;
        }

        out.add(module.hook(configure)
                .setId("dat.configureReply")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    try {
                        replyPre(chain);
                    } catch (Throwable ignored) {
                        // 关联失败只影响本条消息的翻译，不影响宿主。
                    }
                    Object result = chain.proceed();
                    try {
                        replyPost(chain, configure);
                    } catch (Throwable ignored) {
                        // 后处理失败不影响宿主。
                    }
                    return result;
                }));
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
