package io.github.wxmyyds.discordautotranslate.hooks;

import android.text.Editable;
import android.view.View;

import androidx.annotation.Nullable;

import io.github.wxmyyds.discordautotranslate.core.HookConfiguration;
import io.github.wxmyyds.discordautotranslate.core.Reflect;
import io.github.wxmyyds.discordautotranslate.core.StateStore;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 根据活动版本配置安装消息绑定、正文渲染和文本转换 Hook。
 * 类名、方法签名及参数位置只保存在 {@code hook-config/<versionCode>.json}；
 * 本类负责稳定的翻译与重渲染行为，并返回句柄供热重载清理。
 */
public final class MessageHooks {

    private static final ThreadLocal<ReferencedContent> REPLY_CONVERSION = new ThreadLocal<>();

    private MessageHooks() {
    }

    /** Install profile-selected hooks and return their handles for hot reload cleanup. */
    public static List<XposedInterface.HookHandle> install(XposedModule module, ClassLoader cl,
                                                            HookConfiguration config,
                                                            Set<String> installedHookIds) {
        List<XposedInterface.HookHandle> handles = new ArrayList<>(config.requiredHookCount());
        try {
            installBindHook(module, cl, config, handles, installedHookIds);
            installBodyRenderHook(module, cl, config, handles, installedHookIds);
            installTextConversionHook(module, cl, config, handles, installedHookIds);
        } catch (Throwable t) {
            for (XposedInterface.HookHandle handle : handles) {
                try {
                    handle.unhook();
                } catch (Throwable ignored) {
                    // 回滚尽力而为；单个 unhook 失败不应掩盖原始异常。
                }
            }
            handles.clear();
            installedHookIds.clear();
        }
        return handles;
    }

    // ------------------------------------------------------------------ Hook 1: bind

    private static void installBindHook(XposedModule module, ClassLoader cl,
                                        HookConfiguration config,
                                        List<XposedInterface.HookHandle> out,
                                        Set<String> installedHookIds) {
        if (!config.isEnabled("message.bind")) {
            return;
        }
        Method bind = config.findMethod("message.bind", cl);
        JSONObject spec = config.hook("message.bind");
        if (bind == null || spec == null) {
            return;
        }
        HookDiagnostics.infoOnce("message.bind.target", "message.bind target="
                + bind.toGenericString());
        out.add(module.hook(bind)
                .setId("dat.bind")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    try {
                        bindPre(chain, spec);
                    } catch (Throwable t) {
                        HookDiagnostics.warnOnce("message.bind.exception", "message.bind processing failed with "
                                + t.getClass().getName());
                    }
                    Object[] bindArgs = chain.getArgs().toArray();
                    ReferencedContent previousReply = REPLY_CONVERSION.get();
                    try {
                        Object model = safeArg(chain, spec.optInt("modelArgument", -1));
                        ReferencedContent referenced = model == null ? null
                                : readReferencedContent(model, spec.optJSONObject("referencedMessage"));
                        if (referenced == null) {
                            REPLY_CONVERSION.remove();
                        } else {
                            REPLY_CONVERSION.set(referenced);
                        }
                    } catch (Throwable t) {
                        REPLY_CONVERSION.remove();
                        HookDiagnostics.warnOnce("message.bind.replyScope",
                                "reply conversion scope unavailable errorClass="
                                        + t.getClass().getSimpleName());
                    }
                    Object result;
                    try {
                        result = chain.proceed();
                    } finally {
                        if (previousReply == null) {
                            REPLY_CONVERSION.remove();
                        } else {
                            REPLY_CONVERSION.set(previousReply);
                        }
                    }
                    try {
                        bindPost(chain, bind, spec, bindArgs);
                    } catch (Throwable t) {
                        HookDiagnostics.warnOnce("message.bind.postException",
                                "message.bind post-processing failed with "
                                        + t.getClass().getName());
                    }
                    return result;
                }));
        installedHookIds.add("message.bind");
    }

    /** Establishes the content identity mapping from the configured model argument. */
    private static void bindPre(XposedInterface.Chain chain, JSONObject spec) {
        int modelArgument = spec.optInt("modelArgument", -1);
        if (modelArgument < 0 || modelArgument >= chain.getArgs().size()) {
            return;
        }
        Object model = chain.getArg(modelArgument);
        if (model == null) {
            return;
        }
        rememberReferencedContent(model, spec.optJSONObject("referencedMessage"));
        String idGetter = spec.optString("messageIdGetter", null);
        String contentGetter = spec.optString("contentGetter", null);
        if (idGetter == null || contentGetter == null) {
            return;
        }
        String messageId = Reflect.callString(model, idGetter);
        Object content = Reflect.call(Reflect.findMethodExact(model.getClass(), contentGetter, 0), model);
        boolean validMessageId = messageId != null && HookBridge.looksLikeSnowflake(messageId);
        HookDiagnostics.infoOnce("message.bind", "message.bind invoked idValid="
                + validMessageId + " contentClass="
                + (content == null ? "null" : content.getClass().getName()));
        if (!validMessageId) {
            return;
        }
        String revisionGetter = spec.optString("editedTimestampGetter", null);
        if (revisionGetter != null) {
            HookBridge.get().rememberMessageRevision(messageId,
                    readEditedTimestamp(model, revisionGetter));
        }
        HookBridge.get().rememberContent(messageId, content);

        // The body TextView is a descendant of MessageView, not necessarily the same
        // object as Message.getContent(). Bind the row so rendering can resolve through
        // the ViewParent chain even when Discord supplies a wrapped content instance.
        View rowView = asView(chain.getThisObject());
        if (rowView != null) {
            HookBridge.get().bindView(rowView, messageId);
        }

        String viewField = spec.optString("contentViewField", null);
        View contentView = viewField == null ? null
                : asView(Reflect.getField(chain.getThisObject(), viewField));
        if (contentView != null) {
            HookBridge.get().bindView(contentView, messageId);
        }
    }

    /** Loaded reply data captured from the exact MessageView.f model. */
    private static final class ReferencedContent {
        final String messageId;
        final Object systemContent;
        final Object messageContent;
        final String editedTimestamp;

        ReferencedContent(String messageId, Object systemContent, Object messageContent,
                          @Nullable String editedTimestamp) {
            this.messageId = messageId;
            this.systemContent = systemContent;
            this.messageContent = messageContent;
            this.editedTimestamp = editedTimestamp;
        }
    }

    /** Establish content identity mappings before MessageView.f invokes Discord's converter. */
    private static void rememberReferencedContent(Object model, @Nullable JSONObject spec) {
        ReferencedContent referenced = readReferencedContent(model, spec);
        if (referenced == null) {
            return;
        }
        boolean validMessageId = HookBridge.looksLikeSnowflake(referenced.messageId);
        if (validMessageId) {
            HookBridge.get().rememberContent(referenced.messageId, referenced.systemContent);
            HookBridge.get().rememberContent(referenced.messageId, referenced.messageContent);
        }
        HookDiagnostics.infoOnce("message.bind.replyMapping." + validMessageId + "."
                        + (referenced.systemContent != null) + "." + (referenced.messageContent != null),
                "message.bind referenced preview identity idValid=" + validMessageId
                        + " systemContentPresent=" + (referenced.systemContent != null)
                        + " messageContentPresent=" + (referenced.messageContent != null));
    }

    @Nullable
    private static ReferencedContent readReferencedContent(Object model, @Nullable JSONObject spec) {
        if (spec == null) {
            return null;
        }
        String referencedAccessor = spec.optString("accessor", null);
        String referencedClass = spec.optString("loadedClass", null);
        String messageField = spec.optString("messageField", null);
        String idGetter = spec.optString("messageIdGetter", null);
        String contentField = spec.optString("contentField", null);
        String messageContentGetter = spec.optString("messageContentGetter", null);
        String revisionGetter = spec.optString("editedTimestampGetter", null);
        if (referencedAccessor == null || referencedClass == null || messageField == null
                || idGetter == null || contentField == null || messageContentGetter == null
                || revisionGetter == null) {
            return null;
        }
        Method accessorMethod = Reflect.findMethodExact(model.getClass(), referencedAccessor, 0);
        if (accessorMethod == null) {
            return null;
        }
        Object referenced;
        try {
            referenced = accessorMethod.invoke(model);
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            HookDiagnostics.warnOnce("message.bind.replyAccessor",
                    "referenced message accessor failed with " + cause.getClass().getName());
            return null;
        }
        if (referenced == null || !Reflect.isInstanceOfName(referenced, referencedClass)) {
            return null;
        }
        Object message = Reflect.getField(referenced, messageField);
        if (message == null) {
            return null;
        }
        String messageId = Reflect.callString(message, idGetter);
        if (messageId == null) {
            return null;
        }
        Object systemContent = Reflect.getField(referenced, contentField);
        Object messageContent = Reflect.call(Reflect.findMethodExact(message.getClass(),
                messageContentGetter, 0), message);
        return new ReferencedContent(messageId, systemContent, messageContent,
                readEditedTimestamp(message, revisionGetter));
    }

    @Nullable
    private static String readEditedTimestamp(Object model, String getter) {
        Method method = Reflect.findMethodExact(model.getClass(), getter, 0);
        if (method == null || method.getReturnType() != String.class) {
            throw new IllegalStateException("configured edit revision getter unavailable");
        }
        try {
            return (String) method.invoke(model);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("edit revision read failed", e);
        }
    }

    /** After Discord has bound the preview, register it on the shared translation/render path. */
    private static void bindPost(XposedInterface.Chain chain, Method bind, JSONObject spec,
                                 Object[] bindArgs) {
        int modelArgument = spec.optInt("modelArgument", -1);
        if (modelArgument < 0 || modelArgument >= chain.getArgs().size()) {
            return;
        }
        Object model = chain.getArg(modelArgument);
        View row = asView(chain.getThisObject());
        if (model == null || row == null) {
            return;
        }
        View previewText = replyPreviewTextView(row, spec);
        if (previewText == null) {
            return;
        }

        ReferencedContent replySource = readReferencedContent(model,
                spec.optJSONObject("referencedMessage"));
        Object previewObject = Reflect.getField(row, spec.optString("replyPreviewField", null));
        View previewContainer = asView(previewObject);
        boolean previewActive = previewContainer != null
                && previewContainer.getVisibility() == View.VISIBLE;
        // The preview is rendered by af.i through the same text converter as the body.
        // Only subscribe to the canonical message here. Never overwrite Discord's
        // Drawee builder: it contains the first-line margin reserved for the username.
        RerenderRegistry.unregister(previewText);
        if (!previewActive || replySource == null
                || !HookBridge.looksLikeSnowflake(replySource.messageId)) {
            HookBridge.get().unbindView(previewText);
            return;
        }
        HookBridge bridge = HookBridge.get();
        bridge.bindView(previewText, replySource.messageId);
        RerenderRegistry.register(replySource.messageId, previewText, row, bind, bindArgs);
    }

    @Nullable
    private static View replyPreviewTextView(View row, JSONObject spec) {
        String previewField = spec.optString("replyPreviewField", null);
        String bindingField = spec.optString("replyPreviewBindingField", null);
        String textViewField = spec.optString("replyPreviewTextField", null);
        if (previewField == null || bindingField == null || textViewField == null) {
            return null;
        }
        Object preview = Reflect.getField(row, previewField);
        Object binding = Reflect.getField(preview, bindingField);
        return asView(Reflect.getField(binding, textViewField));
    }

    // -------------------------------------------------- Hook 2: 正文渲染入口

    /**
     * Hook 配置选出的正文渲染入口。它提供「视图 + 内容」，
     * 是挂图标、登记重渲染与触发翻译的位置。不做替换（替换统一在文本转换 Hook 完成）。
     */
    private static void installBodyRenderHook(XposedModule module, ClassLoader cl,
                                                  HookConfiguration config,
                                                  List<XposedInterface.HookHandle> out,
                                                  Set<String> installedHookIds) {
        if (!config.isEnabled("message.render")) {
            return;
        }
        Method render = config.findMethod("message.render", cl);
        JSONObject spec = config.hook("message.render");
        if (render == null || spec == null) {
            return;
        }
        HookDiagnostics.infoOnce("message.render.target", "message.render target="
                + render.toGenericString());
        boolean isStatic = java.lang.reflect.Modifier.isStatic(render.getModifiers());
        String identitySource = spec.optString("identitySource", "");
        if (!"argument".equals(identitySource) && !"boundView".equals(identitySource)) {
            throw new IllegalArgumentException("Unsupported message render identity source");
        }
        int viewArgument = spec.optInt("viewArgument", -1);
        int contentArgument = spec.optInt("contentArgument", -1);
        int messageIdArgument = spec.optInt("messageIdArgument", -1);

        out.add(module.hook(render)
                .setId("dat.messageRender")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object viewObject = viewArgument >= 0
                            ? safeArg(chain, viewArgument) : chain.getThisObject();
                    View view = asView(viewObject);
                    Object content = safeArg(chain, contentArgument);
                    InlineIconInjector.HostScope hostScope = InlineIconInjector.enterHost(view, null);
                    try {
                        if (!HookBridge.get().isRerendering() && view != null) {
                            Object[] renderArgs = chain.getArgs().toArray();
                            Object configuredId = safeArg(chain, messageIdArgument);
                            String messageId = "argument".equals(identitySource)
                                    ? configuredId instanceof String
                                    && HookBridge.looksLikeSnowflake((String) configuredId)
                                    ? (String) configuredId : null
                                    : HookBridge.get().directMessageIdOf(view);
                            HookDiagnostics.infoOnce("message.render", "message.render invoked host="
                                    + (view != null) + " idResolved=" + (messageId != null)
                                    + " idSource=" + identitySource
                                    + " contentClass="
                                    + (content == null ? "null" : content.getClass().getName()));
                            if (messageId != null) {
                                HookBridge.get().bindView(view, messageId);
                                RerenderRegistry.register(messageId, view,
                                        isStatic ? null : chain.getThisObject(), render, renderArgs);
                                if (content != null) {
                                    HookBridge.get().rememberContent(messageId, content);
                                    HookBridge.get().onMessageBound(view, messageId, content);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        HookDiagnostics.warnOnce("message.render.exception", "message.render processing failed with "
                                + t.getClass().getName());
                    }
                    try {
                        return chain.proceed();
                    } finally {
                        hostScope.close();
                    }
                }));
        installedHookIds.add("message.render");
    }

    // ----------------------------------------------- Hook 3: 文本转换

    /**
     * 配置选定的文本转换入口只负责按内容身份读取已有翻译状态并替换译文；
     * 翻译任务由显式绑定/渲染入口登记，转换阶段不补发翻译请求。
     */
    private static void installTextConversionHook(XposedModule module, ClassLoader cl,
                                               HookConfiguration config,
                                               List<XposedInterface.HookHandle> out,
                                               Set<String> installedHookIds) {
        if (!config.isEnabled("message.textConversion")) {
            return;
        }
        Method conversionMethod = config.findMethod("message.textConversion", cl);
        JSONObject spec = config.hook("message.textConversion");
        if (conversionMethod == null || spec == null) {
            return;
        }
        HookDiagnostics.infoOnce("message.textConversion.target", "message.textConversion target="
                + conversionMethod.toGenericString());
        int contentArgument = spec.optInt("contentArgument", -1);
        int contextArgument = spec.optInt("contextArgument", -1);
        int messageIdArgument = spec.optInt("messageIdArgument", -1);

        out.add(module.hook(conversionMethod)
                .setId("dat.textConversion")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object source = safeArg(chain, contentArgument);
                    Object[] renderArgs = chain.getArgs().toArray();
                    ReferencedContent reply = REPLY_CONVERSION.get();
                    Object configuredId = safeArg(chain, messageIdArgument);
                    boolean replyConversion = reply != null && reply.systemContent == null
                            && source != null && source == reply.messageContent
                            && reply.messageId.equals(configuredId);
                    String resolvedMessageId = replyConversion ? reply.messageId
                            : HookBridge.get().resolveMessageId(source);
                    HookDiagnostics.infoOnce("message.textConversion.idResolved." + (resolvedMessageId != null),
                            "message.textConversion identity resolved=" + (resolvedMessageId != null)
                                    + " sourceClass="
                                    + (source == null ? "null" : source.getClass().getName()));
                    HookDiagnostics.infoOnce("message.textConversion.callers",
                            "message.textConversion Discord callers="
                                    + HookDiagnostics.discordCallers());
                    boolean replyTranslationApplied = false;
                    try {
                        String messageId = resolvedMessageId;
                        if (messageId != null) {
                            // 内容身份映射由显式绑定/渲染入口建立；此处只读取并应用已有状态。
                            HookBridge.get().rememberContent(messageId, source);

                            HookBridge.ReferencedTranslation reused = replyConversion
                                    ? HookBridge.get().referencedTranslation(messageId,
                                    reply.editedTimestamp) : null;
                            StateStore.Entry e = replyConversion
                                    ? reused == null ? null : reused.state
                                    : HookBridge.get().existingReferencedMessageState(messageId, source);
                            Object original = replyConversion && reused != null
                                    ? reused.originalContent : source;
                            String translated = (e != null
                                    && e.status == StateStore.Status.TRANSLATED
                                    && !e.showingOriginal) ? e.translatedText : null;
                            if (translated != null) {
                                Object replacement = StructurableTextFactory
                                        .forTranslation(original, translated);
                                if (replacement != null) {
                                    HookDiagnostics.info("message translation replacement applied"
                                            + " route=" + (replyConversion ? "reply-canonical" : "body")
                                            + " sourceClass=" + original.getClass().getName()
                                            + " replacementClass=" + replacement.getClass().getName());
                                    renderArgs[contentArgument] = replacement;
                                    replyTranslationApplied = replyConversion;
                                } else {
                                    HookDiagnostics.info("message translation replacement rejected"
                                            + " sourceClass=" + source.getClass().getName());
                                    HookBridge.get().onTranslationRenderRejected(
                                            messageId, translated);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        HookDiagnostics.warnOnce("message.textConversion.exception",
                                "message.textConversion processing failed with "
                                        + t.getClass().getName());
                        renderArgs[contentArgument] = source;
                        replyTranslationApplied = false;
                    }

                    // 宿主渲染只执行一次；图标注入失败时保留原始渲染结果。
                    Object rendered = chain.proceed(renderArgs);
                    if (replyTranslationApplied) {
                        // B0's native singleLine mode does not remove embedded LF or
                        // NewLine nodes. Project only after canonical rich-text alignment,
                        // before af.i adds LeadingMarginSpan(width, 0) and attaches it.
                        // Keep oj.c itself and its Drawee attachment registries intact.
                        Editable preview = (Editable) rendered;
                        ReplyTextProjection.flattenLineBreaks(preview,
                                index -> preview.replace(index, index + 1, " "));
                    }
                    return InlineIconInjector.inject(source, rendered,
                            safeArg(chain, contextArgument), cl);
                }));
        installedHookIds.add("message.textConversion");
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
