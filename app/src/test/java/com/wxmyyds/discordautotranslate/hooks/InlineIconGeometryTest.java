package com.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 实验用：行内图标几何计算的单元测试。
 *
 * <p>这些用例锁定「图标尺寸 / 间距 / 垂直对齐 / 不撑高行距」这几条可离线验证的约束，
 * 真机只需确认绘制本身是否发生。</p>
 */
public class InlineIconGeometryTest {

    private static final float DENSITY = 2.0f;

    @Test
    public void spanWidthIsLeadingPlusIconPlusTrailing() {
        // 2x 密度：前 3dp=6px，图标 18dp=36px，后 1dp=2px，合计 44px。
        int w = InlineIconGeometry.spanWidth(
                InlineIconGeometry.dpToPxAllowZero(3, DENSITY),
                InlineIconGeometry.dpToPx(18, DENSITY),
                InlineIconGeometry.dpToPxAllowZero(1, DENSITY));
        assertEquals(44, w);
    }

    @Test
    public void tapTargetIsWidenedToMinimumWithoutChangingIcon() {
        // 2x 密度：前 6px + 图标 36px = 42px；最小点击 32dp=64px，
        // 因此尾部留白应为 64-42 = 22px，使 span 总宽正好 64px。
        int leading = InlineIconGeometry.dpToPxAllowZero(3, DENSITY);
        int icon = InlineIconGeometry.dpToPx(18, DENSITY);
        int trailing = InlineIconGeometry.trailingGapForTapTarget(
                leading, icon,
                InlineIconGeometry.dpToPx(32, DENSITY),
                InlineIconGeometry.dpToPxAllowZero(1, DENSITY));
        assertEquals(22, trailing);
        assertEquals(64, InlineIconGeometry.spanWidth(leading, icon, trailing));
    }

    @Test
    public void tapTargetWidthMatchesRequestedRange() {
        // 需求要求点击区域 32–40dp。span 总宽即为点击宽度，必须落在该区间内。
        for (float density : new float[]{1.0f, 2.0f, 3.0f}) {
            int leading = InlineIconGeometry.dpToPxAllowZero(3, density);
            int icon = InlineIconGeometry.dpToPx(18, density);
            int trailing = InlineIconGeometry.trailingGapForTapTarget(
                    leading, icon,
                    InlineIconGeometry.dpToPx(32, density),
                    InlineIconGeometry.dpToPxAllowZero(1, density));
            int total = InlineIconGeometry.spanWidth(leading, icon, trailing);
            int minPx = Math.round(32 * density);
            int maxPx = Math.round(40 * density);
            assertTrue("density=" + density + " total=" + total,
                    total >= minPx && total <= maxPx);
        }
    }

    @Test
    public void trailingGapKeepsMinimumEvenAtLargeDensity() {
        // 即使自然宽度已超过最小点击宽度，尾部也不得为负。
        int trailing = InlineIconGeometry.trailingGapForTapTarget(500, 500, 100, 2);
        assertEquals(2, trailing);
    }

    @Test
    public void iconWidthStaysInsideRequestedRange() {
        // 需求要求视觉尺寸 18–20dp；换算成 px 后必须落在该区间内（按密度缩放）。
        for (float density : new float[]{1.0f, 1.5f, 2.0f, 2.75f, 3.0f, 4.0f}) {
            int px = InlineIconGeometry.dpToPx(18, density);
            int minPx = Math.round(18 * density);
            int maxPx = Math.round(20 * density);
            assertTrue("density=" + density + " px=" + px,
                    px >= minPx && px <= maxPx);
        }
    }

    @Test
    public void leadingGapIsWithinRequestedRange() {
        // 需求要求与文字间距约 3–5dp。
        for (float density : new float[]{1.0f, 2.0f, 3.0f}) {
            int gapPx = InlineIconGeometry.dpToPxAllowZero(3, density);
            int minPx = Math.round(3 * density);
            int maxPx = Math.round(5 * density);
            assertTrue("density=" + density + " gap=" + gapPx,
                    gapPx >= minPx && gapPx <= maxPx);
        }
    }

    @Test
    public void iconIsVerticallyCenteredOnText() {
        // 上标 -40，下标 10 → 中线在基线上方 15px。
        int center = InlineIconGeometry.verticalCenter(100, -40, 10);
        assertEquals(85, center);
        // 36px 图标：顶部 = 85 - 18 = 67，底部 = 103，跨越基线两侧。
        assertEquals(67, InlineIconGeometry.iconTop(center, 36));
    }

    @Test
    public void iconTopIsSymmetricAroundCenter() {
        int center = 200;
        int iconPx = 40;
        int top = InlineIconGeometry.iconTop(center, iconPx);
        assertEquals(center - iconPx / 2, top);
        assertEquals(center, top + iconPx / 2);
    }

    @Test
    public void iconLeftIsShiftedByLeadingGapOnly() {
        // 图标左边界只受前间距影响，不含任何绝对坐标/负边距。
        assertEquals(103, InlineIconGeometry.iconLeft(100, 3));
        assertEquals(100, InlineIconGeometry.iconLeft(100, 0));
    }

    @Test
    public void smallDensityStillProducesVisibleIcon() {
        // 极低密度下也不允许算出 0 尺寸。
        assertTrue(InlineIconGeometry.dpToPx(18, 0.1f) >= 1);
    }

    @Test
    public void gapsNeverBecomeNegative() {
        assertEquals(0, InlineIconGeometry.dpToPxAllowZero(0, 2.0f));
        assertTrue(InlineIconGeometry.dpToPxAllowZero(3, 0f) >= 0);
    }

    @Test
    public void angleAdvancesWithElapsedTimeNotFrames() {
        // 转速只由时间决定：同样的流逝时间下，60Hz（更多帧）与 120Hz（更少帧）
        // 得到相同角度，因此不会因刷新率不同而卡顿或变速。
        assertEquals(90f, InlineIconGeometry.angleForElapsed(225, 900), 0.001f);
        assertEquals(90f, InlineIconGeometry.angleForElapsed(225, 900), 0.001f);
        assertEquals(180f, InlineIconGeometry.angleForElapsed(450, 900), 0.001f);
    }

    @Test
    public void angleWrapsAtFullTurn() {
        // 满一圈回到 0，不累积成 360 以上（否则 draw 时 canvas.rotate 虽可接受，
        // 但长时间运行会让 float 精度下降）。
        assertEquals(0f, InlineIconGeometry.angleForElapsed(900, 900), 0.001f);
        assertEquals(180f, InlineIconGeometry.angleForElapsed(1350, 900), 0.001f);
    }

    @Test
    public void angleIsSafeForDegenerateInputs() {
        // 暂停后恢复会传入累计流逝时间；负值与非正周期都不得产生 NaN/异常。
        assertEquals(0f, InlineIconGeometry.angleForElapsed(-50, 900), 0.001f);
        assertEquals(0f, InlineIconGeometry.angleForElapsed(100, 0), 0.001f);
        assertEquals(0f, InlineIconGeometry.angleForElapsed(100, -1), 0.001f);
    }

    @Test
    public void angleIsAlwaysWithinOneTurn() {
        for (long t = 0; t <= 9000; t += 137) {
            float a = InlineIconGeometry.angleForElapsed(t, 900);
            assertTrue("t=" + t + " angle=" + a, a >= 0f && a < 360f);
        }
    }
}
