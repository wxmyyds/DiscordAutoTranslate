package com.wxmyyds.discordautotranslate.hooks;

/**
 * 行内图标的纯几何与动画计算。
 *
 * <p>把与 Android 框架无关的纯计算抽出来，便于在单元测试中直接校验，
 * 无需真机或 Robolectric。真正的绘制与排帧仍由 {@link InlineIconSpan} 调用这些结果完成。</p>
 */
public final class InlineIconGeometry {

    /**
     * 图标左边界相对 span 起点 x 的偏移。
     *
     * @param x           span 起点的绝对横坐标（由 {@code ReplacementSpan.draw} 传入，
     *                    已取整）
     * @param leadingGapPx 图标前间距
     */
    public static int iconLeft(int x, int leadingGapPx) {
        return x + leadingGapPx;
    }

    /**
     * 图标纵向中线：基线上方 (ascent + descent) / 2 处。
     *
     * <p>{@code ascent} 为负、{@code descent} 为正，两者之和的一半即文字视觉中线
     * 相对基线的偏移，因此图标以该中线居中即可与文字对齐。</p>
     *
     * @param baselineY 文字基线 y（由 {@code ReplacementSpan.draw} 传入）
     */
    public static int verticalCenter(int baselineY, int ascent, int descent) {
        return baselineY + (ascent + descent) / 2;
    }

    /**
     * 图标顶边界（正方形，边长 {@code iconPx}）。
     */
    public static int iconTop(int verticalCenterY, int iconPx) {
        return verticalCenterY - iconPx / 2;
    }

    /**
     * span 占用的水平宽度 = 前间距 + 图标边长 + 后间距。
     */
    public static int spanWidth(int leadingGapPx, int iconPx, int trailingGapPx) {
        return leadingGapPx + iconPx + trailingGapPx;
    }

    /** dp → px，四舍五入，至少 1px 以免完全不可见。 */
    public static int dpToPx(int dp, float density) {
        return Math.max(1, Math.round(dp * density));
    }

    /** dp → px，可为 0（用于间距）。 */
    public static int dpToPxAllowZero(int dp, float density) {
        return Math.max(0, Math.round(dp * density));
    }

    /**
     * 计算尾部留白，使 span 总宽（leading + icon + trailing）不小于最小点击宽度。
     *
     * <p>字符命中是水平的，因此 span 宽度就是点击宽度。图标保持视觉尺寸不变，
     * 多出的部分全部放在尾部，避免改变图标与文字之间的视觉间距。</p>
     *
     * @param leadingGapPx 图标前间距
     * @param iconPx       图标边长
     * @param minTapPx     期望的最小点击宽度
     * @param minTrailingPx 尾部最小留白（避免图标紧贴后续内容）
     */
    public static int trailingGapForTapTarget(int leadingGapPx, int iconPx,
                                              int minTapPx, int minTrailingPx) {
        int natural = leadingGapPx + iconPx;
        return Math.max(minTrailingPx, minTapPx - natural);
    }

    /**
     * 由已流逝时间换算旋转角度。
     *
     * <p>视角只与时间有关，因此转速与屏幕刷新率无关；暂停后恢复时传入「累计已流过
     * 的时间」即可从原角度继续，不会跳变。</p>
     *
     * @param elapsedMs      已流逝时间（毫秒），可为 0；负值按 0 处理
     * @param durationMs     转一圈所需时间（毫秒），必须为正
     * @return 角度，范围 [0, 360)
     */
    public static float angleForElapsed(long elapsedMs, long durationMs) {
        if (durationMs <= 0) {
            return 0f;
        }
        long safe = Math.max(0L, elapsedMs);
        return (safe % durationMs) * 360f / durationMs;
    }

    private InlineIconGeometry() {
    }
}
