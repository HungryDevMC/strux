package dev.gesp.structural.minecraft.listener;

/**
 * TPS-adaptive throttle for the collapse world-apply rate — pure, no Bukkit, unit-testable.
 *
 * <p>A big collapse applies its blocks across many ticks. When the server's measured TPS
 * dips below a floor, we shrink the per-tick block budget so the collapse can never drag
 * the server down further — it just takes more ticks. A collapse always makes progress
 * (never below {@code minCap}), so it can't stall forever. The visible result is identical:
 * the same blocks come down, only the pacing adapts to load.
 */
final class CollapseThrottle {

    private CollapseThrottle() {}

    /**
     * How many blocks to apply this tick given the recent TPS.
     *
     * @param recentTps measured recent TPS (0..20); NaN means "not yet measured" → full cap
     * @param tpsFloor below this TPS the budget shrinks; {@code <= 0} disables the throttle
     * @param baseCap configured max blocks/tick at healthy TPS
     * @param minCap floor on blocks/tick so a collapse always progresses (clamped to [1, baseCap])
     * @return blocks to apply this tick, in {@code [min(1,..), baseCap]}
     */
    static int effectiveCap(double recentTps, double tpsFloor, int baseCap, int minCap) {
        int min = Math.max(1, Math.min(minCap, baseCap));
        if (Double.isNaN(recentTps) || tpsFloor <= 0.0 || recentTps >= tpsFloor) {
            return baseCap;
        }
        int scaled = (int) Math.round(baseCap * (recentTps / tpsFloor));
        if (scaled < min) {
            return min;
        }
        if (scaled > baseCap) {
            return baseCap;
        }
        return scaled;
    }
}
