package com.wxmyyds.discordautotranslate.hooks;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.style.ImageSpan;
import android.view.View;

import androidx.annotation.Nullable;

import com.wxmyyds.discordautotranslate.core.StateStore;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 消息正文末尾的行内翻译图标。
 *
 * <p>继承 {@link ImageSpan}（其父类为 {@code android.text.style.ReplacementSpan}），
 * 因此图标是<b>文本流的一部分</b>：它紧跟正文最后一个字符，最后一行剩余空间不足时
 * 由 {@code Layout} 自然换到下一行，不使用任何屏幕绝对坐标或负边距。</p>
 *
 * <h3>为何自绘而不依赖 ImageSpan 默认实现</h3>
 * <p>宿主 {@code MessageContentView} 是 {@code SimpleDraweeSpanTextView}，其
 * {@code DraweeSpanStringBuilder} 自带一套 drawee 自绘 span 体系。自绘可以绕开
 * 「标准 ImageSpan 绘制是否被兼容」这一不确定点。已通过真机实验确认：自定义
 * ReplacementSpan 能被正常绘制、能被点击命中。</p>
 *
 * <h3>为何不写 FontMetricsInt</h3>
 * <p>{@link #getSize} 刻意不修改 {@code fm}，因此图标不会撑高行距，
 * 满足「不得挤压、遮挡或截断正文」的要求。</p>
 *
 * <h3>旋转动画如何可靠重绘</h3>
 * <p>{@code ReplacementSpan} 本身不会触发重绘，必须由外部驱动。行内 span 拿不到
 * 宿主 {@code TextView}，因此注入时通过 {@link #bindHost} 记下宿主 View（弱引用），
 * 翻译中状态用 {@code View.postOnAnimation} 自驱动重绘。以下情况都会停止动画：
 * 宿主已回收、已 detach、状态不再是翻译中、或被同视图上的新 span 取代。</p>
 */
public final class InlineIconSpan extends ImageSpan {

    /** 图标视觉尺寸（dp）。需求要求 18–20dp。 */
    private static final int ICON_DP = 18;
    /** 图标前间距（dp）。需求要求与文字间距约 3–5dp。 */
    private static final int LEADING_GAP_DP = 3;
    /**
     * 点击区域最小宽度（dp）。需求要求 32–40dp。
     *
     * <p>字符命中是水平的（{@code getOffsetForHorizontal}），因此把 span 宽度
     * 作为点击宽度：图标仍为 18dp，多出的部分作为尾部留白，既扩大点击区，
     * 又不改变图标与文字的视觉间距。</p>
     */
    private static final int MIN_TAP_DP = 32;

    private static final int COLOR_TRANSLATING = 0xFFB9BBBE;
    private static final int COLOR_TRANSLATED = 0xFF5865F2;
    private static final int COLOR_ORIGINAL = 0xFF72767D;
    private static final int COLOR_FAILED = 0xFFED4245;

    /** 一圈用时（毫秒）。 */
    private static final long SPIN_DURATION_MS = 900L;

    /** 当前承载动画的 span。键与值都是弱引用，视图回收后自动消失。 */
    private static final Map<View, InlineIconSpan> ANIMATING =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final int iconPx;
    private final int leadingGapPx;
    private final int trailingGapPx;

    /** 图标所属消息，仅用于诊断。 */
    private final String messageId;
    /** 是否处于翻译中（决定要不要转动）。构造后不再变化：状态变化会重建 span。 */
    private final boolean spinning;

    @Nullable
    private WeakReference<View> host;
    private volatile float angle;
    private volatile boolean running;
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            View v = host == null ? null : host.get();
            if (!running || v == null || !v.isAttachedToWindow()) {
                running = false;
                return;
            }
            angle = (angle + 24f) % 360f;
            v.invalidate();
            v.postOnAnimation(this);
        }
    };

    private InlineIconSpan(Drawable drawable, float density, StateStore.Status status,
                           String messageId) {
        super(drawable);
        this.iconPx = InlineIconGeometry.dpToPx(ICON_DP, density);
        this.leadingGapPx = InlineIconGeometry.dpToPxAllowZero(LEADING_GAP_DP, density);
        // 尾部留白 = 让 span 总宽达到最小点击宽度；至少保留 1dp 避免紧贴。
        this.trailingGapPx = InlineIconGeometry.trailingGapForTapTarget(
                leadingGapPx, iconPx,
                InlineIconGeometry.dpToPx(MIN_TAP_DP, density),
                InlineIconGeometry.dpToPxAllowZero(1, density));
        this.messageId = messageId;
        this.spinning = status == StateStore.Status.TRANSLATING;
    }

    /**
     * 创建 span。Drawable 或状态无效时返回 null（调用方跳过注入）。
     *
     * @param icon      已按状态着色、且已独立复制的 Drawable
     * @param density   屏幕密度
     * @param status    当前翻译状态（决定是否转动与配色已在 icon 上体现）
     * @param messageId 所属消息 ID
     */
    @Nullable
    static InlineIconSpan create(@Nullable Drawable icon, float density,
                                StateStore.Status status, String messageId) {
        if (icon == null || status == null) {
            return null;
        }
        return new InlineIconSpan(icon, density, status, messageId);
    }

    /** 按状态给 Drawable 着色，并返回独立副本（模块缓存的 Drawable 会被多处复用）。 */
    @Nullable
    static Drawable tintForState(@Nullable Drawable source, StateStore.Status status,
                                 boolean showingOriginal) {
        if (source == null) {
            return null;
        }
        Drawable copy;
        try {
            Drawable.ConstantState cs = source.getConstantState();
            copy = cs != null ? cs.newDrawable().mutate() : source.mutate();
        } catch (Throwable t) {
            copy = source;
        }
        int color;
        switch (status) {
            case TRANSLATING:
                color = COLOR_TRANSLATING;
                break;
            case FAILED:
                color = COLOR_FAILED;
                break;
            case TRANSLATED:
            default:
                color = showingOriginal ? COLOR_ORIGINAL : COLOR_TRANSLATED;
                break;
        }
        try {
            copy.setTint(color);
        } catch (Throwable ignored) {
            // 个别 Drawable 不支持 tint；不着色也比不显示好。
        }
        return copy;
    }

    /**
     * 绑定宿主 View 并在需要时启动旋转。
     *
     * <p>同一视图上已存在旧的动画 span 时先停掉它，避免旧 Runnable 继续刷新。</p>
     */
    void bindHost(@Nullable View view) {
        if (view == null) {
            return;
        }
        this.host = new WeakReference<>(view);
        if (!spinning) {
            return;
        }
        InlineIconSpan previous = ANIMATING.put(view, this);
        if (previous != null && previous != this) {
            previous.stopSpin();
        }
        startSpin();
    }

    private void startSpin() {
        if (running) {
            return;
        }
        View v = host == null ? null : host.get();
        if (v == null) {
            return;
        }
        running = true;
        v.postOnAnimation(ticker);
    }

    /** 停止动画。可在任意线程调用；Runnable 下一次醒来会自行退出。 */
    void stopSpin() {
        running = false;
        View v = host == null ? null : host.get();
        if (v != null && !spinning) {
            ANIMATING.remove(v, this);
        }
    }

    /** 热重载/配置重置时调用：停止所有在途动画，避免旧代次 Runnable 继续存活。 */
    public static void resetAll() {
        synchronized (ANIMATING) {
            for (InlineIconSpan span : ANIMATING.values()) {
                span.running = false;
            }
            ANIMATING.clear();
        }
    }

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end,
                       Paint.FontMetricsInt fm) {
        // 刻意不写 fm：保持宿主原有行高，避免图标撑大行距。
        return InlineIconGeometry.spanWidth(leadingGapPx, iconPx, trailingGapPx);
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end,
                     float x, int top, int y, int bottom, Paint paint) {
        Drawable d = getDrawable();
        if (d == null) {
            return;
        }
        Paint.FontMetricsInt fm = paint.getFontMetricsInt();
        int centerY = InlineIconGeometry.verticalCenter(y, fm.ascent, fm.descent);
        int left = InlineIconGeometry.iconLeft(Math.round(x), leadingGapPx);
        int iconTop = InlineIconGeometry.iconTop(centerY, iconPx);
        d.setBounds(left, iconTop, left + iconPx, iconTop + iconPx);

        boolean rotated = spinning && angle != 0f;
        if (rotated) {
            canvas.save();
            canvas.rotate(angle, left + iconPx / 2f, iconTop + iconPx / 2f);
        }
        d.draw(canvas);
        if (rotated) {
            canvas.restore();
        }
    }

    /** 该 span 占用的水平宽度（含前后间距）。 */
    int widthPx() {
        return InlineIconGeometry.spanWidth(leadingGapPx, iconPx, trailingGapPx);
    }
}
