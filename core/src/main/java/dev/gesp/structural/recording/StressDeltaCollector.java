package dev.gesp.structural.recording;

import dev.gesp.structural.model.NodePos;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Collects {@link StressDelta} payloads from the stream of load-ratio maps the
 * solver emits during a single event.
 *
 * <p>How it works: the first map it sees is the baseline (each block's load ratio
 * before the event really changes anything). Every later map updates each block's
 * latest ratio. When the event ends, {@link #build()} keeps only the blocks whose
 * load ratio moved into a different {@link StressDelta#BUCKETS bucket} between the
 * baseline and the end — those are the structurally interesting changes.
 *
 * <p>Pure Java, no game types: it is fed by an adapter's {@code SolverCallback} via
 * {@link #accept(Map)} and produces a core {@link StressDelta}. A block first seen
 * mid-event (e.g. it entered the re-solved scope late) is treated as starting from
 * bucket 0, so a block that lights up to overloaded still registers as a crossing.
 */
public final class StressDeltaCollector {

    private final Map<NodePos, Double> baseline = new LinkedHashMap<>();
    private final Map<NodePos, Double> latest = new LinkedHashMap<>();
    private boolean sawFirst;

    /** Feed one solver load-ratio snapshot. Call once per settle pass. */
    public void accept(Map<NodePos, Double> loadRatios) {
        if (loadRatios == null) {
            return;
        }
        // Only the FIRST snapshot of the event defines baselines — it is the pre-settle
        // state. A block that appears only in a later pass had no pre-settle value, so it
        // keeps an implicit baseline of 0 (handled in build()), meaning a block that
        // lights up to heavy mid-event still registers as a crossing.
        if (!sawFirst) {
            baseline.putAll(loadRatios);
            sawFirst = true;
        }
        latest.putAll(loadRatios);
    }

    /** True when no snapshot has been fed yet — there is nothing to build. */
    public boolean isEmpty() {
        return latest.isEmpty();
    }

    /**
     * Build the {@link StressDelta} for the event: every block whose end-of-event
     * bucket differs from its baseline bucket, mapped to its end load ratio. Returns
     * {@code null} when nothing crossed a boundary, so the caller stores no payload.
     */
    public StressDelta build() {
        Map<NodePos, Double> crossed = new LinkedHashMap<>();
        for (Map.Entry<NodePos, Double> entry : latest.entrySet()) {
            NodePos pos = entry.getKey();
            double end = entry.getValue();
            double start = baseline.getOrDefault(pos, 0.0);
            if (StressDelta.bucketOf(start) != StressDelta.bucketOf(end)) {
                crossed.put(pos, end);
            }
        }
        return crossed.isEmpty() ? null : new StressDelta(crossed);
    }
}
