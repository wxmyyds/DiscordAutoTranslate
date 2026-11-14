package io.github.wxmyyds.discordautotranslate.hooks;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.view.View;

import androidx.annotation.Nullable;

import io.github.wxmyyds.discordautotranslate.core.ModuleResources;
import io.github.wxmyyds.discordautotranslate.core.StateStore;
import io.github.wxmyyds.discordautotranslate.core.TextExtractor;

/**
 * 在消息正文渲染结果末尾注入行内翻译图标。
 *
 * <p>注入点由版本配置中的 {@code message.textConversion} 选择；该 Hook 收到宿主生成的
 * 最终 {@code SpannableStringBuilder}，在同一对象末尾追加图标。</p>
 *
 * <h3>注入范围（需求：只处理正常消息正文的可翻译文本）</h3>
 * <ol>
 *   <li>必须有渲染宿主：正文渲染入口与 Embed 绑定（{@code EmbedHooks}）会在调用链内设置
 *       {@link #CURRENT_HOST}；回复引用不设置宿主，因此不注入。</li>
 *   <li>必须有可翻译文本：{@code TextExtractor} 的 {@code textNodeCount > 0}。
 *       纯图片、纯表情、纯提及消息的文本节点数为 0，不注入。</li>
 *   <li>Embed 每卡片只挂一个图标：只有 {@code HookBridge.markEmbedIconKey}
 *       标记的主段（富 Embed 的描述/标题/首字段）允许注入。</li>
 * </ol>
 *
 * <h3>宿主 View 的传递</h3>
 * <p>文本转换方法是静态的，拿不到渲染它的 View；旋转动画需要宿主驱动重绘。
 * 因此正文渲染 Hook 在同步调用文本转换期间将宿主放进 {@link #CURRENT_HOST}，
 * 注入完成后立即清理。</p>
 */
public final class InlineIconInjector {

    /**
     * 当前正文渲染宿主。
     *
     * <p>仅在正文或 Embed 渲染调用链的同步范围内有效；为 {@code null} 表示本次转换来自回复引用
     * 或其他不应注入行内图标的无宿主路径。</p>
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

    /** 由正文渲染 Hook 在调用文本转换前设置，调用后必须清理。 */
    static void enterHost(@Nullable View host) {
        if (host != null) {
            CURRENT_HOST.set(host);
        }
    }

    /** 由正文渲染 Hook 在返回前清理，避免宿主泄漏到后续其它渲染。 */
    static void exitHost() {
        CURRENT_HOST.remove();
    }

    /**
     * 在渲染结果末尾注入行内图标。
     *
     * <p>任何异常都不得影响原渲染，因此内部整体 try/catch，失败时原样返回。</p>
     *
     * @param structurable 本次渲染的 {@code StructurableText}（用于反查消息 ID 与文本量）
     * @param rendered     配置选定的文本转换方法返回值
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
                HookDiagnostics.warnOnce("icon.noHost", "icon injection skipped: no active render host");
                return rendered; // 回复引用等无宿主路径：不注入
            }
            Context context = rawContext instanceof Context ? (Context) rawContext : null;
            if (!(rendered instanceof SpannableStringBuilder) || context == null) {
                HookDiagnostics.warnOnce("icon.invalidResult", "icon injection skipped: invalid result/context"
                        + " renderedClass=" + (rendered == null ? "null" : rendered.getClass().getName())
                        + " contextValid=" + (context != null));
                return rendered;
            }
            String messageId = HookBridge.get().resolveMessageId(structurable);
            boolean iconAllowed = messageId != null
                    && (!HookBridge.isEmbedKey(messageId)
                    || HookBridge.get().isEmbedIconKey(messageId));
            if (!iconAllowed) {
                HookDiagnostics.warnOnce("icon.noMessageIdentity", "icon injection skipped: message identity not resolved");
                return rendered;
            }
            StateStore.Entry entry = StateStore.get().peek(messageId);
            if (entry == null) {
                HookDiagnostics.warnOnce("icon.noState", "icon injection skipped: no translation state exists");
                return rendered; // 尚无状态（还没触发过翻译流程）：不注入
            }
            // 未翻译（IDLE）不显示图标：既符合「待翻译不显示」的要求，
            // 也使非英文消息、纯表情消息自然不出图标。
            if (entry.status == StateStore.Status.IDLE) {
                HookDiagnostics.infoOnce("icon.idle", "icon injection skipped: translation state is IDLE");
                return rendered;
            }
            // 需求：纯图片 / 纯表情等无可翻译正文的消息不注入。
            TextExtractor.Result ex = TextExtractor.extract(structurable);
            if (ex == null || ex.textNodeCount == 0) {
                HookDiagnostics.infoOnce("icon.noTextNodes", "icon injection skipped: no extractable text nodes");
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
                    entry.status);
            if (icon == null) {
                HookDiagnostics.warnOnce("icon.spanCreation", "icon injection skipped: icon span creation failed");
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
                // start 是图标占位符下标；clickRange 再向前覆盖 CLICK_LEAD，不能传 leadStart，
                // 否则会再多退一格，把正文最后一个字符也包进 ClickableSpan 并染成链接紫色。
                int[] click = InlineIconGeometry.clickRange(start, end);
                ssb.setSpan(clickSpan, click[0], click[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            // 启动/续接旋转动画（仅翻译中状态）。
            icon.bindHost(host);
            HookDiagnostics.infoOnce("icon.injected", "translation icon injected successfully");

            return ssb;
        } catch (Throwable t) {
            HookDiagnostics.warnOnce("icon.exception", "icon injection failed with "
                    + t.getClass().getName());
            return rendered;
        }
    }

    /** 状态 → 图标 Drawable。Embed/投票路径共用。 */
    @Nullable
    static Drawable iconFor(Context context, StateStore.Status status) {
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
