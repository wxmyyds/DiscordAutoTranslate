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
        if (range == null) {
            return; // 该视图上没有本模块的图标：不产生日志噪音
        }
        String messageId = HookBridge.get().messageIdOf(view);
        if (action == MotionEvent.ACTION_DOWN) {
            logDown(view, (MotionEvent) eventArg, range, messageId);
        } else {
            logUp(tracker, view, range, messageId);
        }
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
        int spanCount = countClickableSpans(tv, off);

        int iconLine = layout.getLineForOffset(iconStart);
        int textLen = tv.getText() == null ? -1 : tv.getText().length();

        XLog.d("[hit] down msg=" + messageId
                + " x=" + x + " y=" + y
                + " line=" + line + " lineL=" + (int) lineLeft + " lineR=" + (int) lineRight
                + " outsideLine=" + outsideLine
                + " off=" + off
                + " icon=[" + iconStart + "," + iconEnd + ") offInRange=" + offInRange
                + " spans=" + spanCount
                + " iconLine=" + iconLine + (iconLine == line ? "(same)" : "(diff)")
                + " textLen=" + textLen + " injectLen=" + range[2]
                + (textLen == range[2] ? "" : " TEXT-CHANGED-AFTER-INJECT"));
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

    /** 用与宿主相同的查询方式数一遍可点击 span，确认代理对象确实在文本里。 */
    @SuppressWarnings("unchecked")
    private static int countClickableSpans(TextView tv, int offset) {
        try {
            CharSequence text = tv.getText();
            if (!(text instanceof android.text.Spanned)) {
                return -1;
            }
            Class<?> iface = sClickableIface;
            if (iface == null) {
                return -2;
            }
            // offset 可能等于文本长度（光标停在末尾），需要夹到合法区间。
            int len = text.length();
            if (len == 0) {
                return 0;
            }
            int at = Math.max(0, Math.min(offset, len - 1));
            Object[] spans = ((android.text.Spanned) text)
                    .getSpans(at, at, (Class<Object>) iface);
            return spans == null ? -3 : spans.length;
        } catch (Throwable t) {
            return -4;
        }
    }

    /** 供 {@link IconClickSpanFactory} 记录回调确实到达。 */
    static void noteClickDelivered(@Nullable String messageId) {
        XLog.d("[hit] click delivered msg=" + messageId);
    }
}
