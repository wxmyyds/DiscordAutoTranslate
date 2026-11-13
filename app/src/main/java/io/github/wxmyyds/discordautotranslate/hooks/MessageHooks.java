package io.github.wxmyyds.discordautotranslate.hooks;

import android.view.View;

import androidx.annotation.Nullable;

import io.github.wxmyyds.discordautotranslate.core.HookConfiguration;
import io.github.wxmyyds.discordautotranslate.core.Reflect;
import io.github.wxmyyds.discordautotranslate.core.StateStore;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Discord 消息渲染 Hook 的安装与实现。
 *
 * <p>具体类名、方法名、参数签名与参数索引由对应 Discord 版本的
 * {@code hook-config/<versionCode>.json} 决定；本类只实现消息绑定、渲染、文本转换与回复逻辑。
 * 以下链路是 340.13 配置对应的已核对实现：</p>
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

    private MessageHooks() {
    }

    /** Install profile-selected hooks and return their handles for hot reload cleanup. */
    public static List<XposedInterface.HookHandle> install(XposedModule module, ClassLoader cl,
                                                            HookConfiguration config) {
        List<XposedInterface.HookHandle> handles = new ArrayList<>(config.requiredHookCount());
        try {
            installBindHook(module, cl, config, handles);
            installExpandedRenderHook(module, cl, config, handles);
            installToSpannableHook(module, cl, config, handles);
            installReplyHook(module, cl, config, handles);
        } catch (Throwable t) {
            for (XposedInterface.HookHandle handle : handles) {
                try {
                    handle.unhook();
                } catch (Throwable ignored) {
                    // 回滚尽力而为；单个 unhook 失败不应掩盖原始异常。
                }
            }
            handles.clear();
        }
        return handles;
    }

    // ------------------------------------------------------------------ Hook 1: bind

    private static void installBindHook(XposedModule module, ClassLoader cl,
                                        HookConfiguration config,
                                        List<XposedInterface.HookHandle> out) {
        if (!config.isEnabled("message.bind")) {
            return;
        }
        Method bind = config.findMethod("message.bind", cl);
        JSONObject spec = config.hook("message.bind");
        if (bind == null || spec == null) {
            return;
        }
        out.add(module.hook(bind)
                .setId("dat.bind")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    try {
                        bindPre(chain, spec);
                    } catch (Throwable ignored) {
                        // 关联失败只影响本条消息的翻译，不影响宿主。
                    }
                    return chain.proceed();
                }));
    }

    /** Establishes the content identity mapping from the configured model argument. */
    private static void bindPre(XposedInterface.Chain chain, JSONObject spec) {
        int modelArgument = spec.optInt("modelArgument", 0);
        if (modelArgument < 0 || modelArgument >= chain.getArgs().size()) {
            return;
        }
        Object model = chain.getArg(modelArgument);
        if (model == null) {
            return;
        }
        String idGetter = spec.optString("messageIdGetter", "getMessageId");
        String contentGetter = spec.optString("contentGetter", "getContent");
        String messageId = Reflect.callString(model, idGetter);
        if (messageId == null || !HookBridge.looksLikeSnowflake(messageId)) {
            return;
        }
        Object content = Reflect.call(Reflect.findMethod(model.getClass(), contentGetter, 0), model);
        HookBridge.get().rememberContent(messageId, content);

        String viewField = spec.optString("contentViewField", null);
        View contentView = viewField == null ? null
                : asView(Reflect.getField(chain.getThisObject(), viewField));
        if (contentView != null) {
            HookBridge.get().bindView(contentView, messageId);
        }
    }

    // ------------------------------------------------- Hook 2: 展开式 setMessageContent

    /**
     * Hook 配置选出的正文渲染入口。它提供「视图 + 内容」，
     * 是挂图标、登记重渲染与触发翻译的位置。不做替换（替换统一在文本转换 Hook 完成）。
     */
    private static void installExpandedRenderHook(XposedModule module, ClassLoader cl,
                                                  HookConfiguration config,
                                                  List<XposedInterface.HookHandle> out) {
        if (!config.isEnabled("message.render")) {
            return;
        }
        Method render = config.findMethod("message.render", cl);
        JSONObject spec = config.hook("message.render");
        if (render == null || spec == null) {
            return;
        }
        boolean isStatic = java.lang.reflect.Modifier.isStatic(render.getModifiers());
        int viewArgument = spec.optInt("viewArgument", -1);
        int contentArgument = spec.optInt("contentArgument", -1);

        out.add(module.hook(render)
                .setId("dat.setMessageContent")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object viewObject = viewArgument >= 0
                            ? safeArg(chain, viewArgument) : chain.getThisObject();
                    View view = asView(viewObject);
                    Object content = safeArg(chain, contentArgument);
                    InlineIconInjector.enterHost(view);
                    try {
                        if (!HookBridge.get().isRerendering() && view != null) {
                            Object[] renderArgs = chain.getArgs().toArray();
                            HookBridge.get().interceptRender(view, renderArgs, content);

                            String messageId = HookBridge.get().messageIdOf(view);
                            if (messageId == null) {
                                messageId = HookBridge.get().resolveMessageId(content);
                            }
                            if (messageId != null) {
                                HookBridge.get().bindView(view, messageId);
                                RerenderRegistry.register(messageId, view,
                                        isStatic ? null : chain.getThisObject(), render, renderArgs);
                                if (content != null) {
                                    HookBridge.get().onMessageBound(view, messageId, content);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                        // 任何异常都不影响原始渲染。
                    }
                    try {
                        return chain.proceed();
                    } finally {
                        InlineIconInjector.exitHost();
                    }
                }));
    }

    // ------------------------------------------------------- Hook 3: toSpannable

    /**
     * Hook 配置选出的文本转换入口：消息、回复引用和 Embed 文本在此登记与替换，
     * 行内图标注入也在宿主生成 Spannable 后完成。
     */
    private static void installToSpannableHook(XposedModule module, ClassLoader cl,
                                               HookConfiguration config,
                                               List<XposedInterface.HookHandle> out) {
        if (!config.isEnabled("message.textConversion")) {
            return;
        }
        Method toSpannable = config.findMethod("message.textConversion", cl);
        JSONObject spec = config.hook("message.textConversion");
        if (toSpannable == null || spec == null) {
            return;
        }
        int contentArgument = spec.optInt("contentArgument", 0);
        int contextArgument = spec.optInt("contextArgument", 1);

        out.add(module.hook(toSpannable)
                .setId("dat.toSpannable")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object source = safeArg(chain, contentArgument);
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
                                renderArgs[contentArgument] = replacement;
                                } else {
                                    HookBridge.get().onTranslationRenderRejected(
                                            messageId, translated);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        renderArgs[contentArgument] = source;
                    }

                    // 宿主渲染只执行一次；图标注入失败时保留原始渲染结果。
                    Object rendered = chain.proceed(renderArgs);
                    try {
                        return InlineIconInjector.inject(source, rendered,
                                safeArg(chain, contextArgument), cl);
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
                                         HookConfiguration config,
                                         List<XposedInterface.HookHandle> out) {
        if (!config.isEnabled("message.reply")) {
            return;
        }
        Method configure = config.findMethod("message.reply", cl);
        JSONObject spec = config.hook("message.reply");
        if (configure == null || spec == null) {
            return;
        }

        out.add(module.hook(configure)
                .setId("dat.configureReply")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    try {
                        replyPre(chain, spec);
                    } catch (Throwable ignored) {
                        // 关联失败只影响本条消息的翻译，不影响宿主。
                    }
                    Object result = chain.proceed();
                    try {
                        replyPost(chain, configure, spec);
                    } catch (Throwable ignored) {
                        // 后处理失败不影响宿主。
                    }
                    return result;
                }));
    }

    /** 后置：登记回复引用的重渲染目标，使译文完成后引用内也能更新。 */
    private static void replyPost(XposedInterface.Chain chain, Method configureReply,
                                  JSONObject spec) {
        Object preview = chain.getThisObject();
        if (preview == null) {
            return;
        }
        View replyText = replyTextViewOf(preview, spec);
        if (replyText == null) {
            return;
        }
        String messageId = HookBridge.get().messageIdOf(replyText);
        if (messageId == null) {
            return;
        }
        RerenderRegistry.register(messageId, replyText, preview,
                configureReply, chain.getArgs().toArray());
    }

    /** 前置：从配置指定的引用消息参数登记消息 ID 与正文身份映射。 */
    private static void replyPre(XposedInterface.Chain chain, JSONObject spec) {
        int argument = spec.optInt("referencedMessageArgument", 0);
        Object referenced = safeArg(chain, argument);
        if (referenced == null || !Reflect.isInstanceOfName(referenced,
                spec.optString("referencedMessageClass", ""))) {
            return;
        }
        Object message = Reflect.call(Reflect.findMethod(referenced.getClass(),
                spec.optString("referencedMessageGetter", "getMessage"), 0), referenced);
        if (message == null) {
            return;
        }
        String messageId = Reflect.callString(message,
                spec.optString("messageIdGetter", "getId"));
        if (messageId == null || !HookBridge.looksLikeSnowflake(messageId)) {
            return;
        }
        Object content = Reflect.call(Reflect.findMethod(message.getClass(),
                spec.optString("contentGetter", "getContent"), 0), message);
        HookBridge.get().rememberContent(messageId, content);

        View replyText = replyTextViewOf(chain.getThisObject(), spec);
        if (replyText != null) {
            HookBridge.get().bindView(replyText, messageId);
        }
    }

    /** 从回复引用实例取出其内部正文视图。 */
    @Nullable
    private static View replyTextViewOf(@Nullable Object replyPreview, JSONObject spec) {
        Object binding = Reflect.getField(replyPreview,
                spec.optString("bindingField", "binding"));
        Object replyText = Reflect.getField(binding,
                spec.optString("textViewField", "replyText"));
        return asView(replyText);
    }

    @Nullable
    private static Object safeArg(XposedInterface.Chain chain, int index) {
        return index < 0 || index >= chain.getArgs().size() ? null : chain.getArg(index);
    }


    // ------------------------------------------------------------------ 工具

    @Nullable
    private static View asView(@Nullable Object o) {
        return o instanceof View ? (View) o : null;
    }
}
