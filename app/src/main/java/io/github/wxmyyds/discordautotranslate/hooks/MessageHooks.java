package io.github.wxmyyds.discordautotranslate.hooks;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import io.github.wxmyyds.discordautotranslate.core.HookConfiguration;
import io.github.wxmyyds.discordautotranslate.core.Reflect;
import io.github.wxmyyds.discordautotranslate.core.StateStore;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 根据活动版本配置安装消息绑定、正文渲染、文本转换和可选回复引用 Hook。
 * 类名、方法签名及参数位置只保存在 {@code hook-config/<versionCode>.json}；
 * 本类负责稳定的翻译与重渲染行为，并返回句柄供热重载清理。
 */
public final class MessageHooks {

    /** Raw visible reply text retained across Discord's bind-driven rerenders. */
    private static final Map<View, ReplyPreviewSnapshot> REPLY_PREVIEW_SNAPSHOTS =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private static final class ReplyPreviewSnapshot {
        final String referencedMessageId;
        final String stateKey;
        final String originalText;

        ReplyPreviewSnapshot(String referencedMessageId, String stateKey, String originalText) {
            this.referencedMessageId = referencedMessageId;
            this.stateKey = stateKey;
            this.originalText = originalText;
        }
    }

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
            installReplyHook(module, cl, config, handles, installedHookIds);
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
                    Object result = chain.proceed();
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

        ReferencedContent(String messageId, Object systemContent, Object messageContent) {
            this.messageId = messageId;
            this.systemContent = systemContent;
            this.messageContent = messageContent;
        }

        Object previewContent() {
            // Matches af/i: systemContent is rendered when present; otherwise Message.getContent().
            return systemContent != null ? systemContent : messageContent;
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
        if (referencedAccessor == null || referencedClass == null || messageField == null
                || idGetter == null || contentField == null || messageContentGetter == null) {
            return null;
        }
        Object referenced = Reflect.call(Reflect.findMethodExact(model.getClass(),
                referencedAccessor, 0), model);
        if (!Reflect.isInstanceOfName(referenced, referencedClass)) {
            return null;
        }
        Object message = Reflect.getField(referenced, messageField);
        if (message == null) {
            return null;
        }
        String messageId = Reflect.callString(message, idGetter);
        Object systemContent = Reflect.getField(referenced, contentField);
        Object messageContent = Reflect.call(Reflect.findMethodExact(message.getClass(),
                messageContentGetter, 0), message);
        return messageId == null ? null
                : new ReferencedContent(messageId, systemContent, messageContent);
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

        if (bindArgs == null) {
            bindArgs = chain.getArgs().toArray();
        }
        // SystemReferencedMessage(c8.i) carries plain String content and Discord draws
        // it directly, bypassing kl.b.B0. Route that source through the same state engine.
        if (bindSystemReferencedPreview(model, row, previewText, bind, bindArgs, spec)) {
            return;
        }

        // RecyclerView may reuse this preview for another referenced message.
        RerenderRegistry.unregister(previewText);
        ReferencedContent referenced = readReferencedContent(model,
                spec.optJSONObject("referencedMessage"));
        if (referenced != null && HookBridge.looksLikeSnowflake(referenced.messageId)) {
            // The preview belongs to the referenced message even when Discord has not
            // supplied its text yet. Never bind it to the containing/replying message.
            Object previewContent = referenced.previewContent();
            if (previewContent != null) {
                REPLY_PREVIEW_SNAPSHOTS.remove(previewText);
                HookBridge.get().bindView(previewText, referenced.messageId);
                RerenderRegistry.register(referenced.messageId, previewText, row,
                        bind, bindArgs);
                HookBridge.get().rememberContent(referenced.messageId, previewContent);
                // Start/reuse work only through the canonical message state pipeline.
                HookBridge.get().onMessageBound(previewText, referenced.messageId, previewContent);
                HookDiagnostics.infoOnce("message.bind.replyTarget.registeredWithContent",
                        "message.bind reply preview target registered; content available");
            } else if (!captureRenderedReplyPreview(previewText, row, bind, bindArgs,
                    referenced.messageId)) {
                // Keep its identity, but don't fabricate text or attribute it to the reply author.
                HookBridge.get().bindView(previewText, referenced.messageId);
                RerenderRegistry.register(referenced.messageId, previewText, row, bind, bindArgs);
                HookDiagnostics.infoOnce("message.bind.replyTarget.registeredWithoutText",
                        "message.bind reply preview target registered; no source text available");
            }
            return;
        }

        // Unknown reference identity is not permission to associate the preview with its parent row.
        HookBridge.get().unbindView(previewText);
    }

    /** Handle the c8.i SystemReferencedMessage(String) branch that bypasses kl.b.B0. */
    private static boolean bindSystemReferencedPreview(Object model, View row, View previewText,
                                                       Method bind, Object[] bindArgs,
                                                       JSONObject spec) {
        JSONObject systemSpec = spec.optJSONObject("systemReferencedMessage");
        JSONObject referenceSpec = spec.optJSONObject("referencedMessage");
        String accessor = referenceSpec == null ? null : referenceSpec.optString("accessor", null);
        String systemClass = systemSpec == null ? null : systemSpec.optString("class", null);
        String contentField = systemSpec == null ? null : systemSpec.optString("contentField", null);
        if (accessor == null || systemClass == null || contentField == null) {
            return false;
        }
        Object referenced = Reflect.call(Reflect.findMethodExact(model.getClass(), accessor, 0), model);
        if (!Reflect.isInstanceOfName(referenced, systemClass)) {
            return false;
        }

        RerenderRegistry.unregister(previewText);
        REPLY_PREVIEW_SNAPSHOTS.remove(previewText);
        Object rawValue = Reflect.getField(referenced, contentField);
        String rawText = rawValue instanceof String ? (String) rawValue : null;
        String rowId = Reflect.callString(model, spec.optString("messageIdGetter", null));
        String stateKey = rowId == null || !HookBridge.looksLikeSnowflake(rowId)
                ? null : HookBridge.get().embedTextStateKey(rowId, rawText);
        Object wrapped = rawText == null ? null : StructurableTextFactory.primitive(rawText);
        if (stateKey == null || wrapped == null || !(previewText instanceof TextView)) {
            HookBridge.get().unbindView(previewText);
            HookDiagnostics.infoOnce("message.bind.systemReplyPreview.unavailable",
                    "system reply preview source or render target unavailable");
            return true;
        }

        HookBridge.get().bindView(previewText, stateKey);
        RerenderRegistry.register(stateKey, previewText, row, bind, bindArgs);
        HookBridge.get().rememberContent(stateKey, wrapped);
        HookBridge.get().onMessageBound(previewText, stateKey, wrapped);
        applyReplyPreviewTranslation((TextView) previewText, stateKey, true);
        HookDiagnostics.infoOnce("message.bind.systemReplyPreview.registered",
                "system reply preview uses shared translation state");
        return true;
    }

    /** Use Discord's bound TextView as the authoritative visible source when the model omits it. */
    private static boolean captureRenderedReplyPreview(View preview, View row, Method bind,
                                                       Object[] bindArgs, String referencedId) {
        if (!(preview instanceof TextView)) {
            return false;
        }
        TextView textView = (TextView) preview;
        CharSequence displayed = textView.getText();
        String visibleText = displayed == null ? null : displayed.toString();
        ReplyPreviewSnapshot previous = REPLY_PREVIEW_SNAPSHOTS.get(preview);
        if (previous != null && !previous.referencedMessageId.equals(referencedId)) {
            previous = null;
            REPLY_PREVIEW_SNAPSHOTS.remove(preview);
        }
        if (previous != null) {
            StateStore.Entry previousEntry = StateStore.get().peek(previous.stateKey);
            boolean isOurTranslation = previousEntry != null
                    && previousEntry.translatedText != null
                    && previousEntry.translatedText.equals(visibleText);
            if (isOurTranslation || visibleText == null || visibleText.trim().isEmpty()) {
                visibleText = previous.originalText;
            }
        }
        if (visibleText == null || visibleText.trim().isEmpty()) {
            HookDiagnostics.infoOnce("message.bind.replyVisibleText.absent",
                    "reply preview model omitted content and bound view has no visible text");
            return false;
        }

        Object wrapped = StructurableTextFactory.primitive(visibleText);
        if (wrapped == null) {
            return false;
        }
        String stateKey = HookBridge.get().embedTextStateKey(referencedId, visibleText);
        if (stateKey == null) {
            return false;
        }
        REPLY_PREVIEW_SNAPSHOTS.put(preview,
                new ReplyPreviewSnapshot(referencedId, stateKey, visibleText));
        HookBridge.get().bindView(preview, stateKey);
        RerenderRegistry.register(stateKey, preview, row, bind, bindArgs);
        HookBridge.get().rememberContent(stateKey, wrapped);
        HookBridge.get().onMessageBound(preview, stateKey, wrapped);
        applyReplyPreviewTranslation(textView, stateKey, false);
        HookDiagnostics.infoOnce("message.bind.replyVisibleText.captured",
                "reply preview translated from bound visible text via shared state pipeline");
        return true;
    }

    /** Apply a ready preview translation after Discord finishes its own bind. */
    private static void applyReplyPreviewTranslation(TextView view, String stateKey,
                                                     boolean boldSystemText) {
        StateStore.Entry entry = StateStore.get().peek(stateKey);
        if (entry == null || entry.status != StateStore.Status.TRANSLATED
                || entry.showingOriginal || entry.translatedText == null) {
            return;
        }
        SpannableStringBuilder styled = new SpannableStringBuilder(entry.translatedText);
        if (boldSystemText && styled.length() > 0) {
            styled.setSpan(new StyleSpan(2), 0, styled.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        view.setText(styled, TextView.BufferType.SPANNABLE);
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
                    InlineIconInjector.enterHost(view);
                    try {
                        if (!HookBridge.get().isRerendering() && view != null) {
                            Object[] renderArgs = chain.getArgs().toArray();
                            HookBridge.get().interceptRender(view, renderArgs, content);

                            String directMessageId = HookBridge.get().directMessageIdOf(view);
                            String contentMessageId = HookBridge.get().resolveMessageId(content);
                            Object configuredId = safeArg(chain, messageIdArgument);
                            String configuredMessageId = configuredId instanceof String
                                    && HookBridge.looksLikeSnowflake((String) configuredId)
                                    ? (String) configuredId : null;
                            String messageId = configuredMessageId != null ? configuredMessageId
                                    : directMessageId != null ? directMessageId
                                    : HookBridge.get().messageIdOf(view);
                            if (messageId == null) {
                                messageId = contentMessageId;
                            }
                            String idSource = configuredMessageId != null ? "argument"
                                    : directMessageId != null ? "view"
                                    : contentMessageId != null ? "content" : "ancestor";
                            HookDiagnostics.infoOnce("message.render", "message.render invoked host="
                                    + (view != null) + " idResolved=" + (messageId != null)
                                    + " idSource=" + idSource
                                    + " contentClass="
                                    + (content == null ? "null" : content.getClass().getName()));
                            if (messageId != null) {
                                // Do not pin a child to an inherited ID unless the configured
                                // render call itself provides the authoritative message ID.
                                if (configuredMessageId != null || directMessageId != null
                                        || contentMessageId != null) {
                                    HookBridge.get().bindView(view, messageId);
                                }
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
                        InlineIconInjector.exitHost();
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

        out.add(module.hook(conversionMethod)
                .setId("dat.textConversion")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object source = safeArg(chain, contentArgument);
                    Object[] renderArgs = chain.getArgs().toArray();
                    String resolvedMessageId = HookBridge.get().resolveMessageId(source);
                    HookDiagnostics.infoOnce("message.textConversion.idResolved." + (resolvedMessageId != null),
                            "message.textConversion identity resolved=" + (resolvedMessageId != null)
                                    + " sourceClass="
                                    + (source == null ? "null" : source.getClass().getName()));
                    HookDiagnostics.infoOnce("message.textConversion.callers",
                            "message.textConversion Discord callers="
                                    + HookDiagnostics.discordCallers());
                    try {
                        String messageId = resolvedMessageId;
                        if (messageId != null) {
                            // 内容身份映射由显式绑定/渲染入口建立；此处只读取并应用已有状态。
                            HookBridge.get().rememberContent(messageId, source);

                            StateStore.Entry e = StateStore.get().peek(messageId);
                            String translated = (e != null
                                    && e.status == StateStore.Status.TRANSLATED
                                    && !e.showingOriginal) ? e.translatedText : null;
                            if (translated != null) {
                                Object replacement = StructurableTextFactory
                                        .forTranslation(source, translated);
                                if (replacement != null) {
                                    HookDiagnostics.info("message translation replacement applied"
                                            + " sourceClass=" + source.getClass().getName()
                                            + " replacementClass=" + replacement.getClass().getName());
                                    renderArgs[contentArgument] = replacement;
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
        installedHookIds.add("message.textConversion");
    }

    // ------------------------------------------------------------- Hook 4: 回复引用

    /**
     * 配置启用时，登记引用消息 ID 与正文身份，供翻译和重渲染使用。
     * 行内图标不注入回复引用（{@link InlineIconInjector} 在无宿主时跳过），
     * 但引用内的译文切换仍需要重渲染登记。
     */
    private static void installReplyHook(XposedModule module, ClassLoader cl,
                                         HookConfiguration config,
                                         List<XposedInterface.HookHandle> out,
                                         Set<String> installedHookIds) {
        if (!config.isEnabled("message.reply")) {
            return;
        }
        Method replyBind = config.findMethod("message.reply", cl);
        JSONObject spec = config.hook("message.reply");
        if (replyBind == null || spec == null) {
            return;
        }

        out.add(module.hook(replyBind)
                .setId("dat.messageReply")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    try {
                        replyPre(chain, spec);
                    } catch (Throwable ignored) {
                        // 关联失败只影响本条消息的翻译，不影响宿主。
                    }
                    Object result = chain.proceed();
                    try {
                        replyPost(chain, replyBind, spec);
                    } catch (Throwable ignored) {
                        // 后处理失败不影响宿主。
                    }
                    return result;
                }));
        installedHookIds.add("message.reply");
    }

    /** 后置：登记回复引用的重渲染目标，使译文完成后引用内也能更新。 */
    private static void replyPost(XposedInterface.Chain chain, Method replyBind,
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
                replyBind, chain.getArgs().toArray());
    }

    /** 前置：从配置指定的引用消息参数登记消息 ID 与正文身份映射并触发统一翻译流程。 */
    private static void replyPre(XposedInterface.Chain chain, JSONObject spec) {
        int argument = spec.optInt("referencedMessageArgument", -1);
        Object referenced = safeArg(chain, argument);
        String referencedClass = spec.optString("referencedMessageClass", null);
        String referencedGetter = spec.optString("referencedMessageGetter", null);
        String messageIdGetter = spec.optString("messageIdGetter", null);
        String contentGetter = spec.optString("contentGetter", null);
        if (referenced == null || referencedClass == null || referencedGetter == null
                || messageIdGetter == null || contentGetter == null
                || !Reflect.isInstanceOfName(referenced, referencedClass)) {
            return;
        }
        Object message = Reflect.call(Reflect.findMethodExact(referenced.getClass(),
                referencedGetter, 0), referenced);
        if (message == null) {
            return;
        }
        String messageId = Reflect.callString(message, messageIdGetter);
        if (messageId == null || !HookBridge.looksLikeSnowflake(messageId)) {
            return;
        }
        Object content = Reflect.call(Reflect.findMethodExact(message.getClass(),
                contentGetter, 0), message);
        HookBridge.get().rememberContent(messageId, content);
        HookBridge.get().onMessageBound(null, messageId, content);

        View replyText = replyTextViewOf(chain.getThisObject(), spec);
        if (replyText != null) {
            HookBridge.get().bindView(replyText, messageId);
        }
    }

    /** 从回复引用实例取出其内部正文视图。 */
    @Nullable
    private static View replyTextViewOf(@Nullable Object replyPreview, JSONObject spec) {
        Object binding = Reflect.getField(replyPreview,
                spec.optString("bindingField", null));
        Object replyText = Reflect.getField(binding,
                spec.optString("textViewField", null));
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
