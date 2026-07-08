package dev.gesp.structural.minecraft.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The TPS estimate that drives the freeze guard, fed synthetic tick timestamps. */
class TickRateMeterTest {

    private static final long MS = 1_000_000L;

    @Test
    void firstTickIsUnmeasured() {
        assertTrue(Double.isNaN(new TickRateMeter().sample(123 * MS)), "one tick can't measure a gap yet");
    }

    @Test
    void steadyFiftyMsTicksReadAboutTwentyTps() {
        TickRateMeter m = new TickRateMeter();
        long t = 0;
        double tps = Double.NaN;
        for (int i = 0; i < 10; i++) {
            t += 50 * MS; // 50ms per tick = 20 TPS
            tps = m.sample(t);
        }
        assertEquals(20.0, tps, 0.5);
    }

    @Test
    void slowHundredMsTicksDragTpsDown() {
        TickRateMeter m = new TickRateMeter();
        long t = 0;
        double tps = Double.NaN;
        for (int i = 0; i < 25; i++) {
            t += 100 * MS; // 100ms per tick ≈ 10 TPS
            tps = m.sample(t);
        }
        assertTrue(tps > 8.0 && tps < 12.0, "100ms ticks should read ~10 TPS, got " + tps);
    }

    @Test
    void neverExceedsTwentyEvenForVeryFastTicks() {
        TickRateMeter m = new TickRateMeter();
        m.sample(0);
        // 1ms gap would be 1000 TPS uncapped — must clamp to 20.
        assertEquals(20.0, m.sample(MS), 1e-9);
    }

    @Test
    void smoothingMovesTowardTheNewRateGradually() {
        // Warm up at fast 50ms ticks (≈20 TPS), then one very slow tick: the EMA should dip
        // but not instantly collapse to the slow rate (smoothing), so it stays above ~12 TPS.
        TickRateMeter m = new TickRateMeter();
        long t = 0;
        for (int i = 0; i < 10; i++) {
            t += 50 * MS;
            m.sample(t);
        }
        t += 500 * MS; // one 500ms stall (the instant slow rate would be ~2 TPS)
        double tps = m.sample(t);
        assertTrue(tps < 18.0 && tps > 3.0, "one stall should dip but not collapse to the slow rate, got " + tps);
    }
}
