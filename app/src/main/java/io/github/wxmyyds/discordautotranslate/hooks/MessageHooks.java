package io.github.wxmyyds.discordautotranslate.hooks;

import android.text.Layout;
import android.text.Spanned;
import android.text.style.LeadingMarginSpan;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import androidx.annotation.Nullable;

import io.github.wxmyyds.discordautotranslate.core.HookConfiguration;
import io.github.wxmyyds.discordautotranslate.core.Reflect;
import io.github.wxmyyds.discordautotranslate.core.StateStore;
import io.github.wxmyyds.discordautotranslate.core.TextExtractor;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 根据活动版本配置安装消息绑定、正文渲染和文本转换 Hook。
 * 类名、方法签名及参数位置只保存在 {@code hook-config/<versionCode>.json}；
 * 本类负责稳定的翻译与重渲染行为，并返回句柄供热重载清理。
 */
public final class MessageHooks {

    private static final AtomicLong REPLY_PREVIEW_TRACE_SEQUENCE = new AtomicLong();
    private static final Map<View, Long> REPLY_PREVIEW_TRACE_IDS =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

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
                    long replyTrace = REPLY_PREVIEW_TRACE_SEQUENCE.incrementAndGet();
                    try {
                        bindPre(chain, spec);
                    } catch (Throwable t) {
                        HookDiagnostics.warnOnce("message.bind.exception", "message.bind processing failed with "
                                + t.getClass().getName());
                    }
                    Object[] bindArgs = chain.getArgs().toArray();
                    Object result = chain.proceed();
                    try {
                        bindPost(chain, bind, spec, bindArgs, replyTrace);
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
        final boolean hasAttachmentsOrEmbeds;

        ReferencedContent(String messageId, Object systemContent, Object messageContent,
                          boolean hasAttachmentsOrEmbeds) {
            this.messageId = messageId;
            this.systemContent = systemContent;
            this.messageContent = messageContent;
            this.hasAttachmentsOrEmbeds = hasAttachmentsOrEmbeds;
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

    private static final class ReferencedContentRead {
        final ReferencedContent content;
        final String outcome;
        final String referenceClass;

        ReferencedContentRead(ReferencedContent content, String outcome, String referenceClass) {
            this.content = content;
            this.outcome = outcome;
            this.referenceClass = referenceClass;
        }
    }

    @Nullable
    private static ReferencedContent readReferencedContent(Object model, @Nullable JSONObject spec) {
        return inspectReferencedContent(model, spec).content;
    }

    private static ReferencedContentRead inspectReferencedContent(Object model,
                                                                  @Nullable JSONObject spec) {
        if (spec == null) {
            return new ReferencedContentRead(null, "profile-unavailable", "none");
        }
        String referencedAccessor = spec.optString("accessor", null);
        String referencedClass = spec.optString("loadedClass", null);
        String messageField = spec.optString("messageField", null);
        String idGetter = spec.optString("messageIdGetter", null);
        String contentField = spec.optString("contentField", null);
        String messageContentGetter = spec.optString("messageContentGetter", null);
        if (referencedAccessor == null || referencedClass == null || messageField == null
                || idGetter == null || contentField == null || messageContentGetter == null) {
            return new ReferencedContentRead(null, "profile-incomplete", "none");
        }
        Method accessorMethod = Reflect.findMethodExact(model.getClass(), referencedAccessor, 0);
        if (accessorMethod == null) {
            return new ReferencedContentRead(null, "accessor-not-found", "none");
        }
        Object referenced;
        try {
            referenced = accessorMethod.invoke(model);
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            return new ReferencedContentRead(null,
                    "accessor-error-" + cause.getClass().getSimpleName(), "none");
        }
        if (referenced == null) {
            return new ReferencedContentRead(null, "reference-null", "none");
        }
        String actualReferenceClass = referenced.getClass().getName();
        if (!Reflect.isInstanceOfName(referenced, referencedClass)) {
            return new ReferencedContentRead(null, "reference-type-unmapped", actualReferenceClass);
        }
        Object message = Reflect.getField(referenced, messageField);
        if (message == null) {
            return new ReferencedContentRead(null, "referenced-message-null", actualReferenceClass);
        }
        String messageId = Reflect.callString(message, idGetter);
        if (messageId == null) {
            return new ReferencedContentRead(null, "message-id-unavailable", actualReferenceClass);
        }
        Object systemContent = Reflect.getField(referenced, contentField);
        Object messageContent = Reflect.call(Reflect.findMethodExact(message.getClass(),
                messageContentGetter, 0), message);
        String mediaGetter = spec.optString("hasAttachmentsOrEmbedsGetter", null);
        Object media = mediaGetter == null ? null : Reflect.call(
                Reflect.findMethodExact(message.getClass(), mediaGetter, 0), message);
        ReferencedContent content = new ReferencedContent(messageId, systemContent, messageContent,
                Boolean.TRUE.equals(media));
        return new ReferencedContentRead(content,
                HookBridge.looksLikeSnowflake(messageId) ? "loaded" : "message-id-invalid",
                actualReferenceClass);
    }

    /** After Discord has bound the preview, register it on the shared translation/render path. */
    private static void bindPost(XposedInterface.Chain chain, Method bind, JSONObject spec,
                                 Object[] bindArgs, long replyTrace) {
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

        REPLY_PREVIEW_TRACE_IDS.put(previewText, replyTrace);
        if (bindArgs == null) {
            bindArgs = chain.getArgs().toArray();
        }
        ReferencedContentRead referenceRead = inspectReferencedContent(model,
                spec.optJSONObject("referencedMessage"));
        ReferencedContent replySource = referenceRead.content;
        Object previewObject = Reflect.getField(row, spec.optString("replyPreviewField", null));
        View previewContainer = asView(previewObject);
        boolean previewActive = previewContainer != null
                && previewContainer.getVisibility() == View.VISIBLE;
        boolean referencePresent = !"reference-null".equals(referenceRead.outcome);
        if (previewActive || referencePresent) {
            HookDiagnostics.info("replyPreview.bind trace=" + replyTrace
                    + " referenceState=" + referenceRead.outcome
                    + " referenceClass=" + referenceRead.referenceClass
                    + " sourceClass=" + (replySource == null ? "unavailable"
                    : replySource.systemContent != null ? "system-structured"
                    : replySource.messageContent != null ? "message-structured" : "missing")
                    + " previewVisibility=" + visibilityName(previewContainer)
                    + " textVisibility=" + visibilityName(previewText)
                    + " textShown=" + previewText.isShown()
                    + " textLength=" + (previewText instanceof TextView
                    ? textLength((TextView) previewText) : -1));
        }
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
        StateStore.Entry entry = bridge.existingReferencedMessageState(
                replySource.messageId, replySource.messageContent);
        if (entry != null && entry.status == StateStore.Status.TRANSLATING) {
            bridge.trackReplyPreviewState(replySource.messageId, replyTrace);
        }
        StateStore.Entry canonical = StateStore.get().peek(replySource.messageId);
        TextExtractor.Result modelBody = TextExtractor.extract(replySource.messageContent);
        TextExtractor.Result systemBody = TextExtractor.extract(replySource.systemContent);
        HookDiagnostics.info("replyPreview.consumer trace=" + replyTrace
                + " state=" + (entry == null ? "missing-or-stale" : entry.status.name())
                + " canonicalState=" + (canonical == null ? "missing" : canonical.status.name())
                + " hasAttachmentsOrEmbeds=" + replySource.hasAttachmentsOrEmbeds
                + " canonicalLength=" + (canonical == null || canonical.originalText == null
                ? -1 : canonical.originalText.length())
                + " modelLength=" + (modelBody == null ? -1 : modelBody.text.length())
                + " modelSegments=" + (modelBody == null ? -1 : modelBody.segmentCount)
                + " systemLength=" + (systemBody == null ? -1 : systemBody.text.length())
                + " bodyTextMatches=" + (canonical != null && modelBody != null
                && modelBody.text.equals(canonical.originalText))
                + " route=native-converter requestSubmitted=false");
        if (previewText instanceof TextView) {
            observeReplyPreviewLayout((TextView) previewText, row, spec, replyTrace);
        }
    }

    /** Observe native layout without changing its text, spans or measurement. */
    private static void observeReplyPreviewLayout(TextView textView, View row,
                                                  JSONObject spec, long replyTrace) {
        ViewTreeObserver observer = textView.getViewTreeObserver();
        if (!observer.isAlive()) {
            return;
        }
        observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                ViewTreeObserver current = textView.getViewTreeObserver();
                if (current.isAlive()) {
                    current.removeOnPreDrawListener(this);
                }
                Long currentTrace = REPLY_PREVIEW_TRACE_IDS.get(textView);
                if (currentTrace == null || currentTrace != replyTrace || !textView.isShown()) {
                    return true;
                }
                View username = replyUsernameView(row, spec);
                View leading = replyLeadingViews(row, spec);
                CharSequence text = textView.getText();
                LeadingMarginSpan[] margins = text instanceof Spanned
                        ? ((Spanned) text).getSpans(0, text.length(), LeadingMarginSpan.class)
                        : new LeadingMarginSpan[0];
                int firstMargin = 0;
                for (LeadingMarginSpan margin : margins) {
                    firstMargin += margin.getLeadingMargin(true);
                }
                Layout layout = textView.getLayout();
                int[] location = new int[2];
                int[] nameLocation = new int[2];
                textView.getLocationOnScreen(location);
                if (username != null) {
                    username.getLocationOnScreen(nameLocation);
                }
                HookDiagnostics.info("replyPreview.layout trace=" + replyTrace
                        + " route=native phase=pre-draw"
                        + " textLength=" + (text == null ? 0 : text.length())
                        + " marginSpanCount=" + margins.length
                        + " firstMargin=" + firstMargin
                        + " firstLineLeft=" + (layout == null || layout.getLineCount() == 0
                        ? -1 : layout.getLineLeft(0))
                        + " firstLineRight=" + (layout == null || layout.getLineCount() == 0
                        ? -1 : layout.getLineRight(0))
                        + " textX=" + location[0] + " textY=" + location[1]
                        + " textPaddingLeft=" + textView.getTotalPaddingLeft()
                        + " textWidth=" + textView.getWidth()
                        + " textHeight=" + textView.getHeight()
                        + " textLines=" + textView.getLineCount()
                        + " maxLines=" + textView.getMaxLines()
                        + " ellipsize=" + textView.getEllipsize()
                        + " lastLineEllipsisCount=" + (layout == null || layout.getLineCount() == 0
                        ? -1 : layout.getEllipsisCount(layout.getLineCount() - 1))
                        + " usernameX=" + (username == null ? -1 : nameLocation[0])
                        + " usernameWidth=" + (username == null ? -1 : username.getWidth())
                        + " usernameVisibility=" + visibilityName(username)
                        + " leadingWidth=" + (leading == null ? -1 : leading.getMeasuredWidth()));
                return true;
            }
        });
    }

    @Nullable
    private static View replyLeadingViews(View row, JSONObject spec) {
        String previewField = spec.optString("replyPreviewField", null);
        String bindingField = spec.optString("replyPreviewBindingField", null);
        String leadingField = spec.optString("replyPreviewLeadingViewsField", null);
        Object preview = previewField == null ? null : Reflect.getField(row, previewField);
        Object binding = bindingField == null ? null : Reflect.getField(preview, bindingField);
        return asView(leadingField == null ? null : Reflect.getField(binding, leadingField));
    }

    @Nullable
    private static View replyUsernameView(View row, JSONObject spec) {
        String previewField = spec.optString("replyPreviewField", null);
        String bindingField = spec.optString("replyPreviewBindingField", null);
        String usernameField = spec.optString("replyPreviewUsernameField", null);
        Object preview = previewField == null ? null : Reflect.getField(row, previewField);
        Object binding = bindingField == null ? null : Reflect.getField(preview, bindingField);
        return asView(usernameField == null ? null : Reflect.getField(binding, usernameField));
    }

    private static int textLength(TextView view) {
        CharSequence text = view.getText();
        return text == null ? 0 : text.length();
    }

    private static String visibilityName(@Nullable View view) {
        if (view == null) {
            return "missing";
        }
        switch (view.getVisibility()) {
            case View.VISIBLE:
                return "visible";
            case View.INVISIBLE:
                return "invisible";
            case View.GONE:
                return "gone";
            default:
                return "unknown";
        }
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

                            StateStore.Entry e = HookBridge.get()
                                    .existingReferencedMessageState(messageId, source);
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
