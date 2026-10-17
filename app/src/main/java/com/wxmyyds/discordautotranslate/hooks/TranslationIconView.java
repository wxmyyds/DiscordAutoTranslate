package com.wxmyyds.discordautotranslate.hooks;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.wxmyyds.discordautotranslate.core.StateStore;

/**
 * 消息右下角的翻译状态图标。
 *
 * <p>状态表现（需求 §4 交互状态定义）：
 * <ul>
 *   <li>翻译中：绘制旋转圆弧动画，不显示任何文字。</li>
 *   <li>成功/显示原文：静态图标（两个箭头表示可切换）。</li>
 *   <li>失败：感叹号。</li>
 *   <li>未翻译：不显示。</li>
 * </ul>
 *
 * <p>全部自绘，不依赖 Discord 资源与图标字体，避免资源耦合。</p>
 */
public final class TranslationIconView extends View {

    private static final int SIZE_DP = 16;
    private static final int MARGIN_DP = 6;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcBounds = new RectF();

    @Nullable
    private String messageId;
    private StateStore.Status status = StateStore.Status.IDLE;
    private boolean showingOriginal;
    private float spinDegrees;

    public TranslationIconView(Context context) {
        super(context);
        float d = getResources().getDisplayMetrics().density;
        paint.setStrokeWidth(1.8f * d);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        setClickable(true);
        setFocusable(false);
        setAlpha(0.75f);
    }

    public void setMessageId(String id) {
        this.messageId = id;
    }

    @Nullable
    public String getMessageId() {
        return messageId;
    }

    /** 同步状态到视图并刷新。 */
    public void applyState(@Nullable StateStore.Entry entry) {
        if (entry == null) {
            status = StateStore.Status.IDLE;
        } else {
            status = entry.status;
            showingOriginal = entry.showingOriginal;
        }
        if (status == StateStore.Status.TRANSLATING) {
            if (getVisibility() != VISIBLE) {
                setVisibility(VISIBLE);
            }
            if (!isSpinning) {
                isSpinning = true;
                postOnAnimation(spinner);
            }
        } else if (status == StateStore.Status.IDLE) {
            stopSpin();
            // 未翻译时隐藏（需求 §4“待翻译”状态：由原型验证决定是否显示图标）。
            // 阶段 1 选择隐藏，避免未翻译消息出现无意义图标。
            setVisibility(GONE);
        } else {
            stopSpin();
            setVisibility(VISIBLE);
        }
        invalidate();
    }

    private boolean isSpinning;

    private final Runnable spinner = new Runnable() {
        @Override
        public void run() {
            if (!isSpinning) {
                return;
            }
            spinDegrees = (spinDegrees + 12f) % 360f;
            invalidate();
            postOnAnimation(this);
        }
    };

    private void stopSpin() {
        if (isSpinning) {
            isSpinning = false;
            removeCallbacks(spinner);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopSpin();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float d = getResources().getDisplayMetrics().density;
        int size = Math.round(SIZE_DP * d);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;
        float pad = paint.getStrokeWidth() / 2f;
        float w = getWidth();
        float h = getHeight();

        switch (status) {
            case TRANSLATING: {
                paint.setColor(Color.parseColor("#B9BBBE"));
                arcBounds.set(pad, pad, w - pad, h - pad);
                canvas.save();
                canvas.rotate(spinDegrees, w / 2f, h / 2f);
                canvas.drawArc(arcBounds, 0f, 270f, false, paint);
                canvas.restore();
                return;
            }
            case FAILED: {
                paint.setColor(Color.parseColor("#ED4245"));
                float cx = w / 2f;
                canvas.drawLine(cx, h * 0.22f, cx, h * 0.62f, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, h * 0.8f, paint.getStrokeWidth() * 0.75f, paint);
                paint.setStyle(Paint.Style.STROKE);
                return;
            }
            case TRANSLATED:
            default: {
                // 成功：静态双向箭头。当前显示译文用主题蓝，显示原文用灰色。
                paint.setColor(Color.parseColor(showingOriginal ? "#72767D" : "#5865F2"));
                float left = w * 0.12f;
                float right = w * 0.88f;
                float top = h * 0.3f;
                float bottom = h * 0.7f;
                float head = (right - left) * 0.28f;
                // 上箭头（向右）
                canvas.drawLine(left, top, right, top, paint);
                canvas.drawLine(right - head, top - head * 0.7f, right, top, paint);
                canvas.drawLine(right - head, top + head * 0.7f, right, top, paint);
                // 下箭头（向左）
                canvas.drawLine(right, bottom, left, bottom, paint);
                canvas.drawLine(left + head, bottom - head * 0.7f, left, bottom, paint);
                canvas.drawLine(left + head, bottom + head * 0.7f, left, bottom, paint);
            }
        }
    }

    // ------------------------------------------------------------------ 创建/挂载

    /** 在消息容器（DecoratedMessageView，FrameLayout）右下角挂载或复用图标。 */
    @Nullable
    public static TranslationIconView attachTo(@Nullable ViewGroup container) {
        if (container == null) {
            return null;
        }
        TranslationIconView icon = findByTag(container);
        if (icon != null) {
            return icon;
        }
        Context ctx = container.getContext();
        if (ctx == null) {
            return null;
        }
        TranslationIconView created = new TranslationIconView(ctx);
        created.setTag(TAG_KEY);
        float d = ctx.getResources().getDisplayMetrics().density;
        int size = Math.round(SIZE_DP * d);
        int margin = Math.round(MARGIN_DP * d);

        ViewGroup.LayoutParams lp;
        if (container instanceof android.widget.FrameLayout) {
            android.widget.FrameLayout.LayoutParams flp =
                    new android.widget.FrameLayout.LayoutParams(size, size);
            flp.gravity = android.view.Gravity.END | android.view.Gravity.BOTTOM;
            flp.rightMargin = margin;
            flp.bottomMargin = margin;
            lp = flp;
        } else {
            lp = new ViewGroup.LayoutParams(size, size);
        }
        created.setLayoutParams(lp);
        container.addView(created);
        return created;
    }

    private static final String TAG_KEY = "dat_translate_icon";

    @Nullable
    public static TranslationIconView findByTag(@Nullable ViewGroup container) {
        if (container == null) {
            return null;
        }
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof TranslationIconView
                    && TAG_KEY.equals(child.getTag())) {
                return (TranslationIconView) child;
            }
        }
        return null;
    }
}
