package io.github.wxmyyds.discordautotranslate.hooks;

import android.text.SpannableStringBuilder;
import android.view.View;
import android.view.ViewGroup;
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
 * 根据活动版本配置安装消息绑定、正文渲染、文本转换和可选回复引用 Hook。
 * 类名、方法签名及参数位置只保存在 {@code hook-config/<versionCode>.json}；
 * 本类负责稳定的翻译与重渲染行为，并返回句柄供热重载清理。
 */
public final class MessageHooks {

    private static final AtomicLong REPLY_PREVIEW_TRACE_SEQUENCE = new AtomicLong();
    private static final Map<View, Long> REPLY_PREVIEW_TRACE_IDS =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

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
        ReferencedContent content = new ReferencedContent(messageId, systemContent, messageContent);
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
        // SystemReferencedMessage(c8.i) carries plain String content and Discord draws
        // it directly, bypassing kl.b.B0. Route that source through the same state engine.
        if (bindSystemReferencedPreview(model, row, previewText, bind, bindArgs, spec,
                replyTrace)) {
            return;
        }

        // RecyclerView may reuse this preview for another referenced message.
        RerenderRegistry.unregister(previewText);
        ReferencedContent referenced = replySource;
        if (referenced != null && HookBridge.looksLikeSnowflake(referenced.messageId)) {
            // The preview belongs to the referenced message even when Discord has not
            // supplied its text yet. Never bind it to the containing/replying message.
            Object previewContent = referenced.previewContent();
            if (previewContent != null) {
                String previewSourceText = extractedText(previewContent);
                String status = registerReplyPreviewConsumer(previewText, row, bind, bindArgs,
                        referenced.messageId, previewSourceText, replyTrace, spec,
                        "model-structured");
                HookDiagnostics.info("replyPreview.source trace=" + replyTrace
                        + " source=model-structured state=" + status + " requestSubmitted=false");
                HookDiagnostics.infoOnce("message.bind.replyTarget.registeredWithContent",
                        "message.bind reply preview target registered; content available");
            } else if (!captureRenderedReplyPreview(previewText, row, bind, bindArgs,
                    referenced.messageId, spec, replyTrace)) {
                String status = registerReplyPreviewConsumer(previewText, row, bind, bindArgs,
                        referenced.messageId, null, replyTrace, spec, "awaiting-source");
                HookDiagnostics.info("replyPreview.source trace=" + replyTrace
                        + " outcome=waiting-canonical-bind state=" + status
                        + " requestSubmitted=false");
                HookDiagnostics.infoOnce("message.bind.replyTarget.registeredWithoutText",
                        "message.bind reply preview target registered; no source text available");
            }
            return;
        }

        if (previewActive || referencePresent) {
            HookDiagnostics.info("replyPreview.route trace=" + replyTrace
                    + " outcome=unbound referenceState=" + referenceRead.outcome
                    + " referenceClass=" + referenceRead.referenceClass
                    + " previewVisibility=" + visibilityName(previewContainer)
                    + " textVisibility=" + visibilityName(previewText));
        }
        // Unknown reference identity is not permission to associate the preview with its parent row.
        HookBridge.get().unbindView(previewText);
    }

    /** Handle the c8.i SystemReferencedMessage(String) branch that bypasses kl.b.B0. */
    private static boolean bindSystemReferencedPreview(Object model, View row, View previewText,
                                                       Method bind, Object[] bindArgs,
                                                       JSONObject spec, long replyTrace) {
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
        HookBridge.get().unbindView(previewText);
        HookDiagnostics.info("replyPreview.source trace=" + replyTrace
                + " source=system-plain outcome=no-canonical-referenced-message"
                + " rawTextPresent=" + (rawText != null)
                + " rawTextLength=" + (rawText == null ? 0 : rawText.length())
                + " requestSubmitted=false");
        return true;
    }

    /** Use Discord's bound TextView as the authoritative visible source when the model omits it. */
    private static boolean captureRenderedReplyPreview(View preview, View row, Method bind,
                                                       Object[] bindArgs, String referencedId,
                                                       JSONObject spec, long replyTrace) {
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
                    && previousEntry.originalText != null
                    && previousEntry.originalText.equals(previous.originalText)
                    && previousEntry.translatedText != null
                    && previousEntry.translatedText.equals(visibleText);
            if (isOurTranslation || visibleText == null || visibleText.trim().isEmpty()) {
                visibleText = previous.originalText;
            }
        }
        if (visibleText == null || visibleText.trim().isEmpty()) {
            HookDiagnostics.info("replyPreview.capture trace=" + replyTrace
                    + " outcome=empty textViewPresent=true textLength=0");
            return false;
        }

        String status = registerReplyPreviewConsumer(preview, row, bind, bindArgs,
                referencedId, visibleText, replyTrace, spec, "visible-text");
        HookDiagnostics.info("replyPreview.capture trace=" + replyTrace
                + " outcome=visible-text textLength=" + visibleText.length()
                + " lineCount=" + textView.getLineCount()
                + " state=" + status + " requestSubmitted=false");
        return true;
    }

    private static String registerReplyPreviewConsumer(View preview, View row, Method bind,
                                                       Object[] bindArgs, String referencedId,
                                                       @Nullable String originalText, long replyTrace,
                                                       JSONObject spec, String source) {
        HookBridge bridge = HookBridge.get();
        RerenderRegistry.unregister(preview);
        bridge.bindView(preview, referencedId);
        RerenderRegistry.register(referencedId, preview, row, bind, bindArgs);
        if (originalText == null) {
            REPLY_PREVIEW_SNAPSHOTS.remove(preview);
        } else {
            REPLY_PREVIEW_SNAPSHOTS.put(preview,
                    new ReplyPreviewSnapshot(referencedId, referencedId, originalText));
        }

        StateStore.Entry entry = originalText == null
                ? null : bridge.existingMessageStateForText(referencedId, originalText);
        String status = entry == null ? "missing-or-different-source" : entry.status.name();
        if (entry != null && entry.status == StateStore.Status.TRANSLATING) {
            bridge.trackReplyPreviewState(referencedId, replyTrace);
        } else {
            bridge.clearReplyPreviewState(referencedId, replyTrace);
        }
        HookDiagnostics.info("replyPreview.consumer trace=" + replyTrace
                + " source=" + source
                + " state=" + status
                + " sourceTextMatches=" + (entry != null)
                + " requestSubmitted=false");
        if (preview instanceof TextView) {
            applyReplyPreviewTranslation((TextView) preview, referencedId, originalText,
                    row, spec);
        }
        return status;
    }

    @Nullable
    private static String extractedText(@Nullable Object content) {
        TextExtractor.Result extracted = TextExtractor.extract(content);
        return extracted == null || extracted.text.trim().isEmpty() ? null : extracted.text;
    }

    private static int textLength(TextView view) {
        CharSequence text = view.getText();
        return text == null ? 0 : text.length();
    }

    /** Apply the cited message's existing translation, never starting a request here. */
    private static void applyReplyPreviewTranslation(TextView view, String stateKey,
                                                     @Nullable String sourceText, View row,
                                                     JSONObject spec) {
        StateStore.Entry entry = StateStore.get().peek(stateKey);
        if (entry == null || entry.status != StateStore.Status.TRANSLATED
                || entry.showingOriginal || entry.translatedText == null
                || sourceText == null || !sourceText.equals(entry.originalText)) {
            HookDiagnostics.info("replyPreview.apply trace="
                    + replyTraceFor(view) + " outcome=translation-unavailable"
                    + " state=" + (entry == null ? "missing" : entry.status.name())
                    + " sourceMatches=" + (entry != null && sourceText != null
                    && sourceText.equals(entry.originalText)));
            return;
        }
        SpannableStringBuilder styled = new SpannableStringBuilder(entry.translatedText);
        view.setText(styled, TextView.BufferType.SPANNABLE);
        refreshReplyPreviewLayout(view, row, spec);
        HookDiagnostics.info("replyPreview.apply trace=" + replyTraceFor(view)
                + " outcome=translation-applied textLength=" + styled.length());
    }

    private static long replyTraceFor(View view) {
        Long trace = REPLY_PREVIEW_TRACE_IDS.get(view);
        return trace == null ? 0L : trace;
    }

    /** Re-run Discord's reply-preview measurement after its text height changes. */
    private static void refreshReplyPreviewLayout(TextView textView, View row, JSONObject spec) {
        Long traceId = REPLY_PREVIEW_TRACE_IDS.get(textView);
        long replyTrace = traceId == null ? 0L : traceId;
        String previewField = spec.optString("replyPreviewField", null);
        String layoutMethodName = spec.optString("replyPreviewLayoutMethod", null);
        Object preview = previewField == null ? null : Reflect.getField(row, previewField);
        if (!(preview instanceof View) || layoutMethodName == null) {
            HookDiagnostics.info("replyPreview.layout trace=" + replyTrace
                    + " skipped=preview-or-method-unavailable"
                    + " previewPresent=" + (preview instanceof View)
                    + " methodConfigured=" + (layoutMethodName != null));
            return;
        }
        View previewView = (View) preview;
        Runnable refresh = () -> {
            Method layoutMethod = Reflect.findMethodExact(previewView.getClass(), layoutMethodName, 0);
            boolean invocationSucceeded = false;
            String invocationError = "none";
            if (layoutMethod == null) {
                invocationError = "method-not-found";
            } else {
                try {
                    layoutMethod.invoke(previewView);
                    invocationSucceeded = true;
                } catch (Throwable t) {
                    Throwable cause = t.getCause() == null ? t : t.getCause();
                    invocationError = cause.getClass().getSimpleName();
                }
            }
            View leadingViews = replyLeadingViews(row, spec);
            View username = replyUsernameView(row, spec);
            HookDiagnostics.info("replyPreview.layout trace=" + replyTrace
                    + " methodFound=" + (layoutMethod != null)
                    + " invocationSucceeded=" + invocationSucceeded
                    + " errorClass=" + invocationError
                    + " textWidth=" + textView.getWidth()
                    + " textLines=" + textView.getLineCount()
                    + " textHeight=" + textView.getHeight()
                    + " textLeft=" + textView.getLeft()
                    + " textTop=" + textView.getTop()
                    + " textRight=" + textView.getRight()
                    + " textBottom=" + textView.getBottom()
                    + " usernameLeft=" + (username == null ? -1 : username.getLeft())
                    + " usernameTop=" + (username == null ? -1 : username.getTop())
                    + " usernameRight=" + (username == null ? -1 : username.getRight())
                    + " usernameBottom=" + (username == null ? -1 : username.getBottom())
                    + " usernameVisibility=" + visibilityName(username)
                    + " previewWidth=" + previewView.getWidth()
                    + " previewHeight=" + previewView.getHeight()
                    + " leadingWidth=" + (leadingViews == null ? -1 : leadingViews.getWidth())
                    + " leadingHeight=" + (leadingViews == null ? -1 : leadingViews.getHeight())
                    + " leadingRight=" + (leadingViews == null ? -1 : leadingViews.getRight())
                    + " leadingHeightParam=" + layoutHeightParam(leadingViews));
            textView.requestLayout();
            previewView.requestLayout();
            row.requestLayout();
            row.invalidate();
            ViewTreeObserver observer = previewView.getViewTreeObserver();
            if (observer.isAlive()) {
                ViewTreeObserver.OnPreDrawListener[] holder =
                        new ViewTreeObserver.OnPreDrawListener[1];
                holder[0] = () -> {
                    ViewTreeObserver current = previewView.getViewTreeObserver();
                    if (current.isAlive()) {
                        current.removeOnPreDrawListener(holder[0]);
                    }
                    logReplyPreviewPostLayout(replyTrace, textView, username,
                            previewView, leadingViews);
                    return true;
                };
                observer.addOnPreDrawListener(holder[0]);
            } else {
                HookDiagnostics.info("replyPreview.layout trace=" + replyTrace
                        + " postLayout=observer-unavailable");
            }
        };
        if (textView.getWidth() > 0) {
            refresh.run();
        } else {
            HookDiagnostics.info("replyPreview.layout trace=" + replyTrace
                    + " invocation=posted textWidth=" + textView.getWidth());
            textView.post(refresh);
        }
    }

    private static void logReplyPreviewPostLayout(long replyTrace, TextView textView,
                                                   @Nullable View username,
                                                   View previewView,
                                                   @Nullable View leadingViews) {
        int[] textLocation = new int[2];
        int[] usernameLocation = new int[2];
        textView.getLocationOnScreen(textLocation);
        if (username != null) {
            username.getLocationOnScreen(usernameLocation);
        }
        boolean rectanglesOverlap = username != null
                && textLocation[0] < usernameLocation[0] + username.getWidth()
                && usernameLocation[0] < textLocation[0] + textView.getWidth()
                && textLocation[1] < usernameLocation[1] + username.getHeight()
                && usernameLocation[1] < textLocation[1] + textView.getHeight();
        HookDiagnostics.info("replyPreview.layout trace=" + replyTrace
                + " phase=post-layout"
                + " textX=" + textLocation[0] + " textY=" + textLocation[1]
                + " textWidth=" + textView.getWidth()
                + " textHeight=" + textView.getHeight()
                + " textLines=" + textView.getLineCount()
                + " usernameX=" + (username == null ? -1 : usernameLocation[0])
                + " usernameY=" + (username == null ? -1 : usernameLocation[1])
                + " usernameWidth=" + (username == null ? -1 : username.getWidth())
                + " usernameHeight=" + (username == null ? -1 : username.getHeight())
                + " usernameVisibility=" + visibilityName(username)
                + " rectanglesOverlap=" + rectanglesOverlap
                + " previewWidth=" + previewView.getWidth()
                + " previewHeight=" + previewView.getHeight()
                + " leadingWidth=" + (leadingViews == null ? -1 : leadingViews.getWidth())
                + " leadingHeight=" + (leadingViews == null ? -1 : leadingViews.getHeight())
                + " leadingHeightParam=" + layoutHeightParam(leadingViews));
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

    private static int layoutHeightParam(@Nullable View view) {
        ViewGroup.LayoutParams params = view == null ? null : view.getLayoutParams();
        return params == null ? -1 : params.height;
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
