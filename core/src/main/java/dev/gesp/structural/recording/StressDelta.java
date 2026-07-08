package dev.gesp.structural.recording;

import dev.gesp.structural.model.NodePos;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An OPTIONAL stress snapshot attached to a recorded event.
 *
 * <p>While a break or a blast settles, the engine recomputes how much load each
 * block carries (its <em>load ratio</em>: 0.0 = empty, 1.0 = at its limit, &gt;1.0 =
 * overloaded and about to fail). Most of those numbers wiggle a tiny bit and are
 * boring. What matters for a debug view is when a block crosses a meaningful
 * threshold — when it goes from "comfortable" to "getting heavy" to "about to
 * snap". Those thresholds are the {@link #BUCKETS buckets}.
 *
 * <p>This payload records, for the blocks that crossed a bucket boundary during a
 * single event, their final load ratio. Watching these across the timeline shows
 * stress migrate through the structure toward the failure point — which is what
 * turns the replay into a debug tool.
 *
 * <p>It is captured only when {@code recording.captureStress} is on, so it costs
 * nothing when off. A recorded event with no capture simply has a {@code null}
 * stress payload.
 *
 * @param loadRatios block position → its load ratio at the end of the event, for
 *                   every block that crossed a {@link #BUCKETS bucket} during it
 */
public record StressDelta(Map<NodePos, Double> loadRatios) {

    /**
     * Load-ratio thresholds. A block "crossed a bucket" during an event when its
     * load ratio moved across one of these values (in either direction) between the
     * start and end of the event. Ascending.
     */
    public static final double[] BUCKETS = {0.25, 0.5, 0.75, 0.9, 1.0};

    public StressDelta {
        loadRatios = loadRatios == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(loadRatios));
    }

    /** True when no block crossed a bucket — nothing worth keeping. */
    public boolean isEmpty() {
        return loadRatios.isEmpty();
    }

    /**
     * Which bucket index a load ratio falls in: 0 for {@code [0,0.25)}, 1 for
     * {@code [0.25,0.5)}, … up to {@code BUCKETS.length} for {@code >=1.0}. Used to
     * decide whether two ratios sit in different buckets (i.e. a boundary was
     * crossed).
     */
    public static int bucketOf(double loadRatio) {
        int bucket = 0;
        for (double threshold : BUCKETS) {
            if (loadRatio >= threshold) {
                bucket++;
            } else {
                break;
            }
        }
        return bucket;
    }
}
