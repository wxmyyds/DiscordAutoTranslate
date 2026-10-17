package com.wxmyyds.discordautotranslate.hooks;

import android.view.View;
import android.view.ViewGroup;

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
 * <p>已确认的 Hook 点（基于 Discord 340.13 smali 静态分析）：
 * <ol>
 *   <li><b>消息绑定</b>：
 *       {@code MessageContentViewHolder.bind(MessageContentAccessory, ...)}（17 个参数）。
 *       此处能拿到权威的消息 ID（{@code MessageContentAccessory.getMessageId-3Eiw7ao()}），
 *       用于建立 <b>正文视图 → 消息 ID</b> 的关联。这一步是防串位的关键：
 *       RecyclerView 会把同一个 {@code MessageContentView} 复用到不同消息上。</li>
 *   <li><b>正文渲染漏斗</b>：
 *       {@code MessageContentView.setMessageContent(MessageMargins, StructurableText,
 *       MarkdownTextRenderOptions, MarkdownTextRenderEventHandlers, Float, boolean)}。
 *       已确认这是所有路径（消息列表、回复引用、TextDisplay 组件、四个 Embed 视图）
 *       最终汇入的<b>唯一</b> 6 参重载：4 参的 {@code MarkdownTextRenderOptions} 版本会把
 *       参数展开后转发给它。因此只 Hook 这一个方法即可覆盖全部正文渲染，
 *       且无需引用 Kotlin 默认参数合成方法（其方法名带哈希后缀，会随版本变化）。</li>
 * </ol>
 *
 * <p>图标挂在 {@code DecoratedMessageView}（FrameLayout，消息行最外层原生容器）上，
 * 与正文视图分离，避免干扰 Discord 原有布局。</p>
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
    private static final String CLS_DECORATED_MESSAGE_VIEW =
            "com.discord.chat.presentation.message.DecoratedMessageView";
    private static final String CLS_REPLY_PREVIEW =
            "com.discord.chat.presentation.message.MessageViewReplyPreview";

    /** bind 的参数个数（340.13 已确认）。 */
    private static final int BIND_PARAM_COUNT = 17;

    private MessageHooks() {
    }

    /** 安装全部 Hook，返回句柄供热重载清理。 */
    public static List<XposedInterface.HookHandle> install(XposedModule module, ClassLoader cl) {
        List<XposedInterface.HookHandle> handles = new ArrayList<>(2);
        installBindHook(module, cl, handles);
        installRenderHook(module, cl, handles);
        return handles;
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
            // 退化查找：不限定首参类型，仅按名称与参数个数匹配，增强版本耐受性。
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
                    Object result = chain.proceed();
                    try {
                        bindPost(chain.getThisObject());
                    } catch (Throwable t) {
                        XLog.w("bind post failed", t);
                    }
                    return result;
                }));
        XLog.i("hooked MessageContentViewHolder.bind");
    }

    /** bind 前置：建立 正文视图 → 消息 ID 的关联。此时授权数据最新。 */
    private static void bindPre(XposedInterface.Chain chain) {
        Object accessory = chain.getArg(0);
        if (accessory == null) {
            return;
        }
        String messageId = Reflect.callString(accessory, "getMessageId");
        if (messageId == null || !HookBridge.looksLikeSnowflake(messageId)) {
            return;
        }
        View contentView = asView(Reflect.getField(chain.getThisObject(), "messageContentView"));
        if (contentView == null) {
            XLog.d("bind: messageContentView field unavailable");
            return;
        }
        // 列表会把同一视图复用到不同消息：覆盖映射并清掉该行的旧图标（需求 §风险 2）。
        HookBridge.get().bindView(contentView, messageId);
        IconRegistry.onRowRecycled(findRowContainer(contentView));
        XLog.d("bind " + messageId + " view@"
                + Integer.toHexString(System.identityHashCode(contentView)));
    }

    /** bind 后置：内容已渲染完成，此时挂图标并发起翻译。 */
    private static void bindPost(@Nullable Object holder) {
        View contentView = asView(Reflect.getField(holder, "messageContentView"));
        if (contentView == null) {
            return;
        }
        String messageId = HookBridge.get().messageIdOf(contentView);
        if (messageId == null) {
            return;
        }
        ViewGroup row = findRowContainer(contentView);
        if (row == null) {
            XLog.d("row container not found for " + messageId + "; icon not attached");
            return;
        }
        IconRegistry.attach(messageId, row);

        Object structurable = HookBridge.get().lastStructurable(contentView);
        if (structurable == null) {
            // 尚未经过渲染 Hook（例如 bind 先于首次渲染）：等渲染时再触发。
            XLog.d("no rendered content yet for " + messageId);
            return;
        }
        HookBridge.get().onMessageBound(contentView, messageId, structurable);
    }

    // ------------------------------------------------------------------ Hook 2: render

    private static void installRenderHook(XposedModule module, ClassLoader cl,
                                          List<XposedInterface.HookHandle> out) {
        Class<?> contentViewCls = Reflect.loadClass(cl, CLS_MESSAGE_CONTENT_VIEW);
        if (contentViewCls == null) {
            XLog.w("MessageContentView not found; translation display disabled");
            return;
        }
        Method setContent = Reflect.findMethod(contentViewCls, "setMessageContent", 6);
        if (setContent == null) {
            XLog.w("MessageContentView.setMessageContent/6 not found");
            return;
        }
        final Method target = setContent;

        out.add(module.hook(setContent)
                .setId("dat.setMessageContent")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    View view = asView(chain.getThisObject());
                    if (view == null) {
                        return chain.proceed();
                    }
                    if (HookBridge.get().isRerendering()) {
                        // 本次调用是模块自己发起的重渲染，参数已构造好，
                        // 直接执行，避免再次记录/替换而覆盖已保存的“原文”参数。
                        return chain.proceed();
                    }
                    String messageId = HookBridge.get().messageIdOf(view);
                    if (messageId == null) {
                        // 非消息正文（例如 Embed 或回复引用内的正文），不干预。
                        return chain.proceed();
                    }
                    try {
                        Object structurable = chain.getArg(1);
                        Object[] original = chain.getArgs().toArray();

                        StateStore.Entry entry = StateStore.get().peek(messageId);
                        String translated = (entry != null
                                && entry.status == StateStore.Status.TRANSLATED
                                && !entry.showingOriginal) ? entry.translatedText : null;

                        // 记录原始渲染参数（供异步完成后重渲染），有译文时替换正文。
                        Object[] effective = HookBridge.get().interceptRender(
                                view, original, structurable, translated);

                        // 登记重渲染目标，供翻译完成后回写。
                        RerenderRegistry.register(messageId, view, target);

                        if (effective != original) {
                            return chain.proceed(effective);
                        }
                        return chain.proceed();
                    } catch (Throwable t) {
                        // 任何异常都退回原始行为，保证原文可见（需求 §5.4 优雅降级）。
                        XLog.w("setMessageContent hook failed, falling back to original", t);
                        return chain.proceed();
                    }
                }));
        XLog.i("hooked MessageContentView.setMessageContent");
    }

    // ------------------------------------------------------------------ 工具

    @Nullable
    private static View asView(@Nullable Object o) {
        return o instanceof View ? (View) o : null;
    }

    /**
     * 从正文视图向父链上溯到消息行容器（{@code DecoratedMessageView}）。
     *
     * <p>遇到回复引用容器时返回 null：回复引用内也含一个 {@code MessageContentView}，
     * 但它不属于独立消息行，不应挂图标。被回复消息自身的行仍由它自己的 bind 处理。</p>
     */
    @Nullable
    static ViewGroup findRowContainer(@Nullable View contentView) {
        View current = contentView;
        int depth = 0;
        while (current != null && depth < 12) {
            String name = current.getClass().getName();
            if (CLS_DECORATED_MESSAGE_VIEW.equals(name)) {
                return current instanceof ViewGroup ? (ViewGroup) current : null;
            }
            if (CLS_REPLY_PREVIEW.equals(name)) {
                return null;
            }
            android.view.ViewParent vp = current.getParent();
            current = vp instanceof View ? (View) vp : null;
            depth++;
        }
        return null;
    }
}
