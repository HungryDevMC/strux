package dev.gesp.structural.minecraft.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.minecraft.config.EffectsConfig;
import org.junit.jupiter.api.Test;

/** The freeze-guard math: a big collapse must never push TPS below the floor, and must always progress. */
class CollapseThrottleTest {

    @Test
    void healthyTpsUsesFullBaseCap() {
        assertEquals(25, CollapseThrottle.effectiveCap(20.0, 18.0, 25, 4));
        assertEquals(25, CollapseThrottle.effectiveCap(18.0, 18.0, 25, 4), "exactly at the floor is still healthy");
    }

    @Test
    void belowFloorScalesDownProportionally() {
        // TPS at half the floor -> roughly half the per-tick budget
        assertEquals(12, CollapseThrottle.effectiveCap(9.0, 18.0, 24, 4));
    }

    @Test
    void neverBelowMinCapSoCollapseAlwaysProgresses() {
        assertEquals(4, CollapseThrottle.effectiveCap(1.0, 18.0, 25, 4));
        assertTrue(CollapseThrottle.effectiveCap(0.1, 18.0, 25, 4) >= 1, "must always apply at least one block");
    }

    @Test
    void neverAboveBaseCap() {
        assertTrue(CollapseThrottle.effectiveCap(5.0, 18.0, 25, 4) <= 25);
    }

    @Test
    void floorDisabledOrUnmeasuredUsesBaseCap() {
        assertEquals(25, CollapseThrottle.effectiveCap(5.0, 0.0, 25, 4), "floor <= 0 disables the throttle");
        assertEquals(25, CollapseThrottle.effectiveCap(Double.NaN, 18.0, 25, 4), "unmeasured TPS assumes healthy");
    }

    @Test
    void minCapClampedIntoBaseCapRange() {
        // minCap bigger than baseCap collapses to baseCap
        assertEquals(3, CollapseThrottle.effectiveCap(0.5, 18.0, 3, 10));
    }

    @Test
    void freezeGuardConfigFieldsRoundTrip() {
        EffectsConfig cfg = new EffectsConfig();
        cfg.setTpsFloor(15.5);
        cfg.setMinCollapsesPerTick(7);
        cfg.setMaxCollapseEffectsPerTick(11);
        assertEquals(15.5, cfg.getTpsFloor());
        assertEquals(7, cfg.getMinCollapsesPerTick());
        assertEquals(11, cfg.getMaxCollapseEffectsPerTick());
    }
}
