package com.wxmyyds.discordautotranslate.hooks;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.view.View;

import androidx.annotation.Nullable;

import com.wxmyyds.discordautotranslate.core.ModuleResources;
import com.wxmyyds.discordautotranslate.core.StateStore;
import com.wxmyyds.discordautotranslate.core.TextExtractor;
import com.wxmyyds.discordautotranslate.core.XLog;

/**
 * 在消息正文渲染结果末尾注入行内翻译图标。
 *
 * <p>注入点选择：{@code MessageHooks} 的 {@code toSpannable} Hook。它是所有正文渲染的
 * 唯一汇聚点，在这里拿到的 {@code DraweeSpanStringBuilder} 就是最终交给
 * {@code MessageContentView} 的对象（继承 {@code SpannableStringBuilder}，可写）。</p>
 *
 * <h3>注入范围（需求：只处理正常消息正文的可翻译文本）</h3>
 * <ol>
 *   <li><b>必须来自正常消息正文</b>：只有 {@code MessageContentView.setMessageContent}
 *       路径会设置 {@link #CURRENT_HOST}（渲染宿主）。回复引用
 *       （{@code MessageViewReplyPreview}）与 Embed 直接调用 {@code toSpannable}，
 *       不会设置宿主，因此天然被排除。</li>
 *   <li><b>必须有可翻译文本</b>：{@code TextExtractor} 的 {@code textNodeCount > 0}。
 *       纯图片、纯表情、纯提及消息的文本节点数为 0，不注入。</li>
 * </ol>
 *
 * <h3>宿主 View 的传递</h3>
 * <p>{@code toSpannable} 是静态方法，拿不到渲染它的 {@code TextView}；而旋转动画必须
 * 有宿主才能驱动重绘。因此 {@code setMessageContent} Hook 在调用链同步范围内把宿主放进
 * {@link #CURRENT_HOST}（ThreadLocal），注入完成后立即清理。同一线程内
 * {@code setMessageContent → toSpannable} 是同步的，因此该传递是可靠的。</p>
 */
public final class InlineIconInjector {

    /**
     * 当前渲染宿主（{@code MessageContentView}）。
     *
     * <p>仅在 {@code setMessageContent} 调用链的同步范围内有效；为 {@code null}
     * 表示这次 {@code toSpannable} 不是正常消息正文渲染（回复引用 / Embed / 其他）。</p>
     */
    private static final ThreadLocal<View> CURRENT_HOST = new ThreadLocal<>();

    /** 占位字符：不换行空格。提供一个真实字符位置供 span 附着，且自身不触发换行。 */
    private static final String PLACEHOLDER = "\u00A0";

    /**
     * 点击前置字符：零宽非断行空格（U+FEFF）。
     *
     * <p>它不占视觉宽度、不触发换行，只用来给点击 span 多一个字符位置：
     * 宿主解析出的 offset 可能落在图标前一格，只有 span 覆盖到那一格才能命中。
     * 由于这一格是我们自己插入的，不会影响任何真实内容的点击。</p>
     */
    private static final String CLICK_LEAD = "\uFEFF";

    private InlineIconInjector() {
    }

    /** 由 {@code setMessageContent} Hook 在调用 {@code super} 前设置，调用后必须清理。 */
    static void enterHost(@Nullable View host) {
        if (host != null) {
            CURRENT_HOST.set(host);
        }
    }

    /** 由 {@code setMessageContent} Hook 在返回前清理，避免宿主泄漏到后续其它渲染。 */
    static void exitHost() {
        CURRENT_HOST.remove();
    }

    /**
     * 在渲染结果末尾注入行内图标。
     *
     * <p>任何异常都不得影响原渲染，因此内部整体 try/catch，失败时原样返回。</p>
     *
     * @param structurable 本次渲染的 {@code StructurableText}（用于反查消息 ID 与文本量）
     * @param rendered     {@code TextUtilsKt.toSpannable} 的返回值
     * @param rawContext   渲染上下文（运行时才知类型）
     * @param hostCl       宿主 ClassLoader，用于创建可点击 span 代理
     * @return 传入的 {@code rendered}（就地修改后仍返回同一引用）
     */
    @Nullable
    public static Object inject(@Nullable Object structurable, @Nullable Object rendered,
                                @Nullable Object rawContext, @Nullable ClassLoader hostCl) {
        try {
            View host = CURRENT_HOST.get();
            if (host == null) {
                return rendered; // 非正常消息正文（回复引用 / Embed 等）：不注入
            }
            Context context = rawContext instanceof Context ? (Context) rawContext : null;
            if (!(rendered instanceof SpannableStringBuilder) || context == null) {
                return rendered;
            }
            String messageId = HookBridge.get().resolveMessageId(structurable);
            if (messageId == null) {
                return rendered;
            }
            StateStore.Entry entry = StateStore.get().peek(messageId);
            if (entry == null) {
                return rendered; // 尚无状态（还没触发过翻译流程）：不注入
            }
            // 未翻译（IDLE）不显示图标：既符合「待翻译不显示」的要求，
            // 也使非英文消息、纯表情消息自然不出图标。
            if (entry.status == StateStore.Status.IDLE) {
                return rendered;
            }
            // 需求：纯图片 / 纯表情等无可翻译正文的消息不注入。
            TextExtractor.Result ex = TextExtractor.extract(structurable);
            if (ex == null || ex.textNodeCount == 0) {
                return rendered;
            }

            SpannableStringBuilder ssb = (SpannableStringBuilder) rendered;
            if (ssb.length() == 0 || hasIcon(ssb)) {
                return rendered;
            }

            Drawable source = iconFor(context, entry.status);
            Drawable tinted = InlineIconSpan.tintForState(
                    source, entry.status, entry.showingOriginal);
            InlineIconSpan icon = InlineIconSpan.create(
                    tinted, context.getResources().getDisplayMetrics().density,
                    entry.status, messageId);
            if (icon == null) {
                XLog.w("inline icon: drawable unavailable for " + messageId);
                return rendered;
            }

            // 先放点击前置字符，再放图标占位符：两者各自占一个字符格。
            int leadStart = ssb.length();
            ssb.append(CLICK_LEAD);
            int start = ssb.length();
            ssb.append(PLACEHOLDER);
            int end = start + PLACEHOLDER.length();
            ssb.setSpan(icon, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            Object clickSpan = IconClickSpanFactory.create(hostCl, messageId);
            if (clickSpan != null) {
                // 点击 span 必须比图标往前多覆盖一个字符，否则手指落在图标靠左部分时
                // 宿主算出的 offset 是图标前一格，getSpans 取不到任何 span，
                // 最终退化为消息点击（详见 InlineIconGeometry.clickRange）。
                int[] click = InlineIconGeometry.clickRange(leadStart, end);
                ssb.setSpan(clickSpan, click[0], click[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            // 启动/续接旋转动画（仅翻译中状态）。
            icon.bindHost(host);

            XLog.d("inline icon injected msg=" + messageId + " status=" + entry.status
                    + " showingOriginal=" + entry.showingOriginal
                    + " widthPx=" + icon.widthPx() + " clickable=" + (clickSpan != null));
            return ssb;
        } catch (Throwable t) {
            XLog.w("inline icon injection failed", t);
            return rendered;
        }
    }

    @Nullable
    private static Drawable iconFor(Context context, StateStore.Status status) {
        switch (status) {
            case TRANSLATING:
                return ModuleResources.progressIcon(context);
            case FAILED:
                return ModuleResources.errorIcon(context);
            case TRANSLATED:
            default:
                return ModuleResources.translateIcon(context);
        }
    }

    private static boolean hasIcon(SpannableStringBuilder ssb) {
        return ssb.getSpans(0, ssb.length(), InlineIconSpan.class).length > 0;
    }
}
