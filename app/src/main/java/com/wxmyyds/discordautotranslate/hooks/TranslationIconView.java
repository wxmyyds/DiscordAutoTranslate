package com.wxmyyds.discordautotranslate.hooks;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.wxmyyds.discordautotranslate.core.ModuleResources;
import com.wxmyyds.discordautotranslate.core.StateStore;

/**
 * 消息右下角的翻译状态图标。
 *
 * <p>使用 {@link ImageView} + 模块自带矢量图 {@code R.drawable.translate_24}，
 * 不再自绘（用户要求：图标要用 ImageView 与 {@code @drawable/translate_24}）。</p>
 *
 * <p>状态表现（需求 §4 交互状态定义）：
 * <ul>
 *   <li>翻译中：图标本体 + 持续旋转动画，不显示任何文字。</li>
 *   <li>翻译成功：静态图标，主题蓝。</li>
 *   <li>显示原文：静态图标，灰色。</li>
 *   <li>失败：图标染红。</li>
 *   <li>未翻译：不显示（需求 §4 待翻译状态，用户已确认选“不显示”）。</li>
 * </ul>
 *
 * <p>着色用 {@code setColorFilter}，因此矢量图本身的 {@code fillColor}
 * 必须是纯白占位色，实际颜色由这里决定。</p>
 */
public final class TranslationIconView extends ImageView {

    private static final int SIZE_DP = 16;
    private static final int MARGIN_DP = 6;

    private static final int COLOR_TRANSLATING = 0xFFB9BBBE;
    private static final int COLOR_TRANSLATED = 0xFF5865F2;
    private static final int COLOR_ORIGINAL = 0xFF72767D;
    private static final int COLOR_FAILED = 0xFFED4245;

    /** 翻译图标（已翻译 / 显示原文）。模块资源加载失败时为 null。 */
    @Nullable
    private static volatile Drawable sTranslateDrawable;
    /** 翻译中图标（旋转）。模块资源加载失败时为 null。 */
    @Nullable
    private static volatile Drawable sProgressDrawable;
    /** 失败图标。模块资源加载失败时为 null。 */
    @Nullable
    private static volatile Drawable sErrorDrawable;

    @Nullable
    private String messageId;
    private StateStore.Status status = StateStore.Status.IDLE;
    private boolean showingOriginal;
    private boolean isSpinning;

    /** 供 {@link ModuleEntry} 预加载模块矢量图；也可不预加载，构造时会自取。 */
    static void setIconDrawables(@Nullable Drawable translate, @Nullable Drawable progress,
                                 @Nullable Drawable error) {
        if (translate != null) {
            sTranslateDrawable = translate;
        }
        if (progress != null) {
            sProgressDrawable = progress;
        }
        if (error != null) {
            sErrorDrawable = error;
        }
    }

    /** 清空缓存的图标（热重载时调用，避免旧代次持有旧 Drawable）。 */
    static void clearIconDrawables() {
        sTranslateDrawable = null;
        sProgressDrawable = null;
        sErrorDrawable = null;
    }

    public TranslationIconView(Context context) {
        super(context);
        setScaleType(ScaleType.FIT_CENTER);
        setClickable(true);
        setFocusable(false);
        setAlpha(0.85f);
        // 图标是叠加在消息行上的装饰，不参与无障碍焦点。
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (sTranslateDrawable == null || sProgressDrawable == null || sErrorDrawable == null) {
            // 预加载未成功（如启动早期无 Application）：此处自取。
            // ModuleResources 内部会缓存结果，不会每条消息都重复解析。
            setIconDrawables(ModuleResources.translateIcon(context),
                    ModuleResources.progressIcon(context),
                    ModuleResources.errorIcon(context));
        }
        applyStateInternal();
    }

    /** 按当前状态选择图片与颜色。 */
    private void applyStateInternal() {
        // 三种状态各有专用图标：翻译中 progress、失败 error、其余 translate。
        Drawable want;
        switch (status) {
            case TRANSLATING:
                want = sProgressDrawable;
                break;
            case FAILED:
                want = sErrorDrawable;
                break;
            case TRANSLATED:
            default:
                want = sTranslateDrawable;
                break;
        }
        if (want == null) {
            // 资源未就绪时退回到任意可用图标，至少保持可点击不至于完全不可见。
            want = sTranslateDrawable != null ? sTranslateDrawable : sErrorDrawable;
        }
        if (want != null && getDrawable() != want) {
            setImageDrawable(want);
        }
        applyTint();
    }

    /** 按当前状态给图标着色。 */
    private void applyTint() {
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
        setColorFilter(color);
    }

    /**
     * 拦截触摸，保证图标可点击。
     *
     * <p>必要性：消息行 {@code MessageView} 覆盖了 {@code onInterceptTouchEvent}，
     * 返回 {@code !allowChildGestures}。当 {@code allowChildGestures} 为 false 时
     * 它会直接接管整个手势（已核对 smali），子视图将收不到点击。
     * 在 ACTION_DOWN 时请求父级不要拦截即可。</p>
     */
    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        switch (event.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                android.view.ViewParent parent = getParent();
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(true);
                }
                setAlpha(1f);
                return true;
            case android.view.MotionEvent.ACTION_UP:
                setAlpha(0.85f);
                performClick();
                return true;
            case android.view.MotionEvent.ACTION_CANCEL:
                setAlpha(0.85f);
                android.view.ViewParent p2 = getParent();
                if (p2 != null) {
                    p2.requestDisallowInterceptTouchEvent(false);
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        // 满足无障碍规范；实际逻辑由 OnClickListener 处理。
        return super.performClick();
    }

    public void setMessageId(@Nullable String id) {
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
            startSpin();
        } else if (status == StateStore.Status.IDLE) {
            stopSpin();
            // 未翻译时不显示图标（用户已确认）。
            setVisibility(GONE);
        } else {
            stopSpin();
            setVisibility(VISIBLE);
        }
        applyStateInternal();
    }

    private void startSpin() {
        if (isSpinning) {
            return;
        }
        isSpinning = true;
        animate().cancel();
        setRotation(0f);
        animate()
                .rotation(360f)
                .setDuration(900L)
                .setInterpolator(new LinearInterpolator())
                .setRepeatCount(android.view.animation.Animation.INFINITE)
                .start();
    }

    private void stopSpin() {
        if (!isSpinning) {
            return;
        }
        isSpinning = false;
        animate().cancel();
        setRotation(0f);
    }

    @Override
    protected void onDetachedFromWindow() {
        stopSpin();
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------ 创建/挂载

    /**
     * 在消息行容器上挂载或复用图标。
     *
     * <p>容器类型：聊天列表的行控件是 {@code MessageView}
     * （{@code extends ChatListRelativeLayout extends RelativeLayout}），
     * RN 组件场景是 {@code DecoratedMessageView}（FrameLayout）。
     * 两者均需按对应类型设置 LayoutParams 并停靠右下角。</p>
     */
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

        created.setLayoutParams(buildLayoutParams(container, size, margin));
        container.addView(created);
        return created;
    }

    /** 按容器类型生成右下角停靠的 LayoutParams。 */
    private static ViewGroup.LayoutParams buildLayoutParams(ViewGroup container, int size, int margin) {
        if (container instanceof android.widget.RelativeLayout) {
            // MessageView / ChatListRelativeLayout 走这一支。
            android.widget.RelativeLayout.LayoutParams rlp =
                    new android.widget.RelativeLayout.LayoutParams(size, size);
            rlp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM);
            rlp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_RIGHT);
            rlp.bottomMargin = margin;
            rlp.rightMargin = margin;
            return rlp;
        }
        if (container instanceof android.widget.FrameLayout) {
            android.widget.FrameLayout.LayoutParams flp =
                    new android.widget.FrameLayout.LayoutParams(size, size);
            flp.gravity = android.view.Gravity.END | android.view.Gravity.BOTTOM;
            flp.rightMargin = margin;
            flp.bottomMargin = margin;
            return flp;
        }
        if (container instanceof android.widget.LinearLayout) {
            android.widget.LinearLayout.LayoutParams llp =
                    new android.widget.LinearLayout.LayoutParams(size, size);
            llp.gravity = android.view.Gravity.END | android.view.Gravity.BOTTOM;
            llp.rightMargin = margin;
            llp.bottomMargin = margin;
            return llp;
        }
        return new ViewGroup.LayoutParams(size, size);
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
