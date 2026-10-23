package com.wxmyyds.discordautotranslate.hooks;

import android.text.Layout;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.wxmyyds.discordautotranslate.core.Reflect;
import com.wxmyyds.discordautotranslate.core.XLog;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 【临时诊断代码，定位完成后必须删除】
 *
 * <p>行内图标点击「未命中、退化成本消息点击」的命中探针。</p>
 *
 * <h3>要区分的两种失败</h3>
 * <ol>
 *   <li><b>根本没命中 span</b>：宿主 {@code handleTouch} 在 {@code ACTION_DOWN} 用
 *       {@code getOffsetForHorizontal} 算出字符位置后 {@code getSpans} 为空，于是 UP 时
 *       {@code spanBeingTouched == null}，落到 {@code onClickListener}（消息点击）。</li>
 *   <li><b>命中了但没生效</b>：{@code spanBeingTouched != null}，UP 时应当调用
 *       {@code onClick}。若仍无反应，则问题在点击回调内部（例如消息 ID 反查失败或状态不符）。</li>
 * </ol>
 *
 * <h3>为何要 Hook 宿主的 tracker 而不是自己算</h3>
 * <p>命中判定发生在宿主的私有方法里，包含「行内左右边界外视为空命中区」这一分支。
 * 只有取到宿主真实使用的 {@code line / offset / span 数组}，才能确定是坐标换算偏移、
 * 边界差一，还是 span 范围本身在渲染后被改写过。</p>
 *
 * <h3>隐私</h3>
 * <p>只输出数值（坐标、字符偏移、span 起止、文本长度）与消息 ID，<b>不输出任何正文</b>，
 * 且仅当用户已打开「详细日志」时输出（{@link XLog#d}）。</p>
 */
public final class IconHitProbe {

    private static final String CLS_TRACKER =
            "com.discord.react_gesture_handler.nested_touch.NestedScrollOnTouchTracker";

    /** 每帧渲染注入的图标字符范围：{start, end, 注入时的文本长度}。 */
    private static final Map<View, int[]> ICON_RANGE = new WeakHashMap<>();
    private static final Object LOCK = new Object();

    /**
     * 安装时解析好的 {@code NestedClickableSpan} 接口。
     *
     * <p>不能用 {@code view.getContext().getClassLoader()} 现查：那个 loader 不一定能
     * 加载 Discord 自己的类，会导致命中统计恒为「接口不可用」。</p>
     */
    @Nullable
    private static volatile Class<?> sClickableIface;

    private IconHitProbe() {
    }

    /** 记录某视图当前图标的字符范围（由 {@link InlineIconInjector} 注入后调用）。 */
    static void noteIconRange(@Nullable View view, int start, int end, int textLength) {
        if (view == null) {
            return;
        }
        synchronized (LOCK) {
            ICON_RANGE.put(view, new int[]{start, end, textLength});
        }
    }

    /**
     * 安装探针。找不到宿主方法时静默跳过——诊断缺失不应影响模块功能。
     *
     * @param out 与其它 Hook 一同登记，便于热重载时统一回收
     */
    static void install(XposedModule module, @Nullable ClassLoader cl,
                        List<XposedInterface.HookHandle> out) {
        try {
            Class<?> tracker = Reflect.loadClass(cl, CLS_TRACKER);
            if (tracker == null) {
                XLog.w("hit probe: NestedScrollOnTouchTracker not found");
                return;
            }
            sClickableIface = Reflect.loadClass(cl,
                    "com.discord.react_gesture_handler.nested_touch.NestedClickableSpan");
            Method handleTouch = null;
            for (Method m : tracker.getDeclaredMethods()) {
                // 混淆后缀随版本变化，只按前缀 + 参数个数匹配。
                if (m.getName().startsWith("handleTouch") && m.getParameterCount() == 3) {
                    handleTouch = m;
                    break;
                }
            }
            if (handleTouch == null) {
                XLog.w("hit probe: handleTouch not found");
                return;
            }
            handleTouch.setAccessible(true);
            final String hookName = handleTouch.getName();

            out.add(module.hook(handleTouch)
                    .setId("dat.iconHitProbe")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        try {
                            probe(chain);
                        } catch (Throwable t) {
                            XLog.d("hit probe failed: " + t.getClass().getSimpleName());
                        }
                        return chain.proceed();
                    }));
            XLog.i("hit probe armed on " + hookName);
        } catch (Throwable t) {
            XLog.w("hit probe installation failed", t);
        }
    }

    /** 在宿主处理触摸之前采点。 */
    private static void probe(XposedInterface.Chain chain) {
        Object tracker = chain.getThisObject();
        Object viewArg = chain.getArg(0);
        Object eventArg = chain.getArg(1);
        if (!(viewArg instanceof View) || !(eventArg instanceof MotionEvent)) {
            return;
        }
        int action = ((MotionEvent) eventArg).getActionMasked();
        // 只关心按下与抬起；移动/取消由宿主自己处理（滚动路径）。
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP) {
            return;
        }
        View view = (View) viewArg;
        int[] range;
        synchronized (LOCK) {
            range = ICON_RANGE.get(view);
        }
        String messageId = HookBridge.get().messageIdOf(view);
        if (range == null) {
            // 盲区修正：以前这里直接静默返回，于是「失败但没日志」的情况抓不到。
            // 现在只在 DOWN 且该视图的文本里确实有图标 span 时才输出，
            // 用来区分「触点 View 不是我们登记的 View」与「图标 span 已不存在」。
            if (action == MotionEvent.ACTION_DOWN) {
                logNoRange(view, (MotionEvent) eventArg, messageId);
            }
            return;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            logDown(view, (MotionEvent) eventArg, range, messageId);
        } else {
            logUp(tracker, view, range, messageId);
        }
    }

    /**
     * 触点所在 View 没有登记过图标区间时的诊断。
     *
     * <p>只有该 View 的文本里真的含有图标 span 才输出：说明注入与触摸发生在
     * 不同 View 实例上（列表复用 / 嵌套结构），或者登记被后续渲染覆盖。</p>
     */
    private static void logNoRange(View view, MotionEvent ev, @Nullable String messageId) {
        TextView tv = view instanceof TextView ? (TextView) view : null;
        if (tv == null) {
            return;
        }
        CharSequence text = tv.getText();
        if (!(text instanceof android.text.Spanned)) {
            return;
        }
        Object[] icons = ((android.text.Spanned) text)
                .getSpans(0, text.length(), InlineIconSpan.class);
        if (icons == null || icons.length == 0) {
            return; // 这个 View 本来就没图标：确实是无关触摸，不输出
        }
        Layout layout = tv.getLayout();
        int line = layout == null ? -1 : layout.getLineForVertical(
                (int) ev.getY() - tv.getTotalPaddingTop() + tv.getScrollY());
        StringBuilder sb = new StringBuilder();
        for (Object icon : icons) {
            sb.append('[').append(((android.text.Spanned) text).getSpanStart(icon))
                    .append(',').append(((android.text.Spanned) text).getSpanEnd(icon))
                    .append(')');
        }
        XLog.d("[hit] down msg=" + messageId + " NO-REGISTERED-RANGE but icons=" + sb
                + " textLen=" + text.length() + " line=" + line
                + " view=" + view.getClass().getSimpleName()
                + "@" + Integer.toHexString(System.identityHashCode(view)));
    }

    private static void logDown(View view, MotionEvent ev, int[] range,
                                @Nullable String messageId) {
        TextView tv = view instanceof TextView ? (TextView) view : null;
        Layout layout = tv == null ? null : tv.getLayout();
        if (layout == null) {
            XLog.d("[hit] down msg=" + messageId + " no-layout");
            return;
        }
        // 与宿主完全相同的坐标换算：先去掉内边距，再加滚动量。
        int x = (int) ev.getX() - tv.getTotalPaddingLeft() + tv.getScrollX();
        int y = (int) ev.getY() - tv.getTotalPaddingTop() + tv.getScrollY();

        int line = layout.getLineForVertical(y);
        float lineLeft = layout.getLineLeft(line);
        float lineRight = layout.getLineRight(line);
        // 宿主在「行内左右边界之外」或「x 为负」时把命中区视为空。
        boolean outsideLine = (x > lineRight) || (x < 0) || (x < lineLeft);

        int off = layout.getOffsetForHorizontal(line, x);
        int iconStart = range[0];
        int iconEnd = range[1];
        boolean offInRange = off >= iconStart && off < iconEnd;
        String spans = describeClickableSpans(tv, off);

        int iconLine = layout.getLineForOffset(iconStart);
        int textLen = tv.getText() == null ? -1 : tv.getText().length();

        // 决定性数据：当前文本里图标 span 的**真实**位置，以及该格子实际占的
        // 水平像素区间。注入时记录的 [start,end) 可能与它不一致（span 被改写），
        // 也可能一致但触点 x 落在了相邻格子里（字符命中是整格判定）。
        String live = describeLiveIcon(tv, layout, line, x);

        XLog.d("[hit] down msg=" + messageId
                + " x=" + x + " y=" + y
                + " line=" + line + " lineL=" + (int) lineLeft + " lineR=" + (int) lineRight
                + " outsideLine=" + outsideLine
                + " off=" + off
                + " icon=[" + iconStart + "," + iconEnd + ") offInRange=" + offInRange
                + " spans=" + spans
                + " iconLine=" + iconLine + (iconLine == line ? "(same)" : "(diff)")
                + " textLen=" + textLen + " injectLen=" + range[2]
                + (textLen == range[2] ? "" : " text-grew-by=" + (textLen - range[2]))
                + " live=" + live);
    }

    /**
     * 输出图标 span 在当前文本中的真实区间，以及该区间在当前行上的像素范围。
     *
     * <p>这两项是区分两种失败原因的关键：</p>
     * <ul>
     *   <li>真实区间 ≠ 注入区间：span 被渲染后的文本改写（如截断/追加）弄错了位置；</li>
     *   <li>真实区间 == 注入区间，但触点 x 不在该区间的像素范围内：
     *       {@code getOffsetForHorizontal} 返回的是<b>整格</b>归属，触点落在
     *       图标左侧那个字符的格子里，于是算出的 offset 是图标前一个字符。</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private static String describeLiveIcon(TextView tv, Layout layout, int line, int x) {
        try {
            CharSequence text = tv.getText();
            if (!(text instanceof android.text.Spanned)) {
                return "no-spanned";
            }
            android.text.Spanned sp = (android.text.Spanned) text;
            Object[] icons = sp.getSpans(0, sp.length(), InlineIconSpan.class);
            if (icons == null || icons.length == 0) {
                return "gone";
            }
            StringBuilder sb = new StringBuilder();
            for (Object icon : icons) {
                int s = sp.getSpanStart(icon);
                int e = sp.getSpanEnd(icon);
                int iconLine = layout.getLineForOffset(s);
                sb.append('[').append(s).append(',').append(e).append(')');
                if (iconLine != line) {
                    sb.append("@line").append(iconLine).append("!=").append(line);
                    continue;
                }
                // 该字符格子在当前行上的左右像素边界。
                float l = layout.getPrimaryHorizontal(s);
                float r = s + 1 <= sp.length()
                        ? layout.getPrimaryHorizontal(Math.min(s + 1, sp.length()))
                        : l;
                if (r < l) {
                    float t = l;
                    l = r;
                    r = t;
                }
                sb.append(" px=[").append((int) l).append(',').append((int) r).append(')')
                        .append(x >= l && x <= r ? " xInside" : " xOUTSIDE");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "err:" + t.getClass().getSimpleName();
        }
    }

    private static void logUp(Object tracker, View view, int[] range, @Nullable String messageId) {
        Object beingTouched = Reflect.getField(tracker, "spanBeingTouched");
        Object activeClick = Reflect.getField(tracker, "activeClick");
        Object consideringDouble = Reflect.getField(tracker, "consideringDoubleClick");
        Object runningDoubleJob = Reflect.getField(tracker, "runningDoubleClickJob");
        XLog.d("[hit] up msg=" + messageId
                + " spanBeingTouched=" + (beingTouched != null)
                + " activeClick=" + activeClick
                + " consideringDouble=" + consideringDouble
                + " runningDoubleJob=" + runningDoubleJob
                + " icon=[" + range[0] + "," + range[1] + ")"
                + " textLen=" + (view instanceof TextView && ((TextView) view).getText() != null
                        ? ((TextView) view).getText().length() : -1));
    }

    /**
     * 列出该 offset 上所有可点击 span 的身份与范围，并标出宿主会选择哪一个。
     *
     * <p>宿主的选择规则（已从 smali 核对）：在候选数组里取
     * {@code getTouchPriority()} 最高者；平手时保留数组中<b>靠前</b>的那个，
     * 而 {@code getSpans} 按 span 插入顺序返回。我们自己注入的 span 总是排在
     * Discord 正文 span 之后，因此平手时我们输。这段日志用于证实或推翻该推断。</p>
     */
    @SuppressWarnings("unchecked")
    private static String describeClickableSpans(TextView tv, int offset) {
        try {
            CharSequence text = tv.getText();
            if (!(text instanceof android.text.Spanned)) {
                return "no-spanned";
            }
            Class<?> iface = sClickableIface;
            if (iface == null) {
                return "no-iface";
            }
            int len = text.length();
            if (len == 0) {
                return "empty-text";
            }
            int at = Math.max(0, Math.min(offset, len - 1));
            android.text.Spanned sp = (android.text.Spanned) text;
            Object[] spans = sp.getSpans(at, at, (Class<Object>) iface);
            if (spans == null || spans.length == 0) {
                return "0";
            }
            // 复算宿主的挑选结果：优先级最高，平手取靠前。
            int winner = 0;
            for (int i = 1; i < spans.length; i++) {
                if (priorityOf(spans[i]) > priorityOf(spans[winner])) {
                    winner = i;
                }
            }
            StringBuilder sb = new StringBuilder();
            sb.append(spans.length).append('{');
            for (int i = 0; i < spans.length; i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                Object s = spans[i];
                boolean ours = isOurProxy(s);
                sb.append(ours ? "OURS" : s.getClass().getSimpleName())
                        .append('[').append(sp.getSpanStart(s))
                        .append(',').append(sp.getSpanEnd(s)).append(')')
                        .append(":p").append(priorityOf(s));
                if (i == winner) {
                    sb.append("<=HOST-PICK");
                }
            }
            return sb.append('}').toString();
        } catch (Throwable t) {
            return "err:" + t.getClass().getSimpleName();
        }
    }

    /** 取 span 的触摸优先级；接口不可用或调用失败时按 0 计。 */
    private static int priorityOf(@Nullable Object span) {
        if (span == null) {
            return 0;
        }
        try {
            Object p = span.getClass()
                    .getMethod("getTouchPriority")
                    .invoke(span);
            return p instanceof Enum ? ((Enum<?>) p).ordinal() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 是否为本模块注入的点击代理（类名是 {@code $Proxy}）。 */
    private static boolean isOurProxy(@Nullable Object span) {
        return span != null && span.getClass().getName().contains("$Proxy");
    }

    /** 供 {@link IconClickSpanFactory} 记录回调确实到达。 */
    static void noteClickDelivered(@Nullable String messageId) {
        XLog.d("[hit] click delivered msg=" + messageId);
    }
}
