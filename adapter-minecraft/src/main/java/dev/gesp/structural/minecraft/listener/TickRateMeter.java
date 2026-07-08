package dev.gesp.structural.minecraft.listener;

/**
 * Estimates the server's recent TPS from the wall-clock gap between consecutive ticks,
 * smoothed with an EMA so a single GC blip doesn't swing it. Pure and unit-testable — the
 * tick loop feeds it {@code System.nanoTime()} once per tick and uses the returned TPS to
 * drive the collapse freeze guard ({@link CollapseThrottle}).
 */
final class TickRateMeter {

    /** Weight of the newest gap sample in the EMA (0..1); higher = more responsive, noisier. */
    private static final double EMA_ALPHA = 0.3;

    /** Minecraft's tick ceiling — gaps shorter than 50ms still read as 20 TPS, never higher. */
    private static final double MAX_TPS = 20.0;

    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private boolean primed = false;
    private long lastNanos = 0L;
    private double emaGapNanos = 0.0;

    /**
     * Record a tick at {@code nowNanos} and return the smoothed recent TPS, or {@code NaN}
     * until a second tick has been seen (unmeasured — callers treat that as healthy). A
     * {@code primed} flag (not a sentinel timestamp) marks the first tick, so a {@code
     * nowNanos} of 0 is handled correctly.
     */
    double sample(long nowNanos) {
        if (primed) {
            long gap = nowNanos - lastNanos;
            emaGapNanos = emaGapNanos == 0.0 ? gap : emaGapNanos * (1.0 - EMA_ALPHA) + gap * EMA_ALPHA;
        }
        primed = true;
        lastNanos = nowNanos;
        if (emaGapNanos <= 0.0) {
            return Double.NaN;
        }
        return Math.min(MAX_TPS, NANOS_PER_SECOND / emaGapNanos);
    }
}
