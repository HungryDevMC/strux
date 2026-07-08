package dev.gesp.structural.minecraft.perf;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A registry of named {@link PerfTracker}s, one per repeating adapter task, so
 * {@code /strux perf} can show where strux actually spends a server's tick.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                          TASK TIMINGS                              │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  The single PerfTracker on StructureManager measures the SOLVER.   │
 *   │  But steady-state lag hides in the repeating tasks — crack visuals, │
 *   │  stress particles, entity weight, fire, weather, the impact/blast  │
 *   │  queues, delayed collapse, cascade resume — each ticking every few │
 *   │  ticks. This registry holds one PerfTracker per task, keyed by a   │
 *   │  canonical name, so each pass records BOTH its wall-clock cost AND  │
 *   │  a work count (nodes scanned, entities checked, items settled).    │
 *   │                                                                     │
 *   │  A reading then separates "slow because big" (high work) from      │
 *   │  "slow per unit" (low work, high ms) — the data the next           │
 *   │  optimization round needs.                                         │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>Thread-safe: the per-pass {@link #record} runs on the main thread; the
 * {@link #snapshot} readout may be taken from a command handler on any thread.
 * Tracker creation goes through {@link ConcurrentHashMap#computeIfAbsent}, which
 * happens once per name (not on the steady per-pass path), so the hot path is
 * just the lookup plus the tracker's own single synchronized record.
 */
public final class TaskTimings {

    /** Canonical task name: the crack/damage overlay refresh. */
    public static final String DAMAGE_VISUALIZER = "damage-visualizer";

    /** Canonical task name: the stress particle/sound pass. */
    public static final String STRESS_VISUALIZER = "stress-visualizer";

    /** Canonical task name: the per-player live stress-summary action-bar pass. */
    public static final String STRESS_SUMMARY = "stress-summary";

    /** Canonical task name: the periodic standing-entity-weight scan. */
    public static final String ENTITY_WEIGHT = "entity-weight";

    /** Canonical task name: the periodic heavy-container weight scan. */
    public static final String CONTAINER_WEIGHT = "container-weight";

    /** Canonical task name: the periodic fire heat-degradation scan. */
    public static final String FIRE_SCORCH = "fire-scorch";

    /** Canonical task name: the periodic weather (rain/thunder/snow) scan. */
    public static final String WEATHER_LOAD = "weather-load";

    /** Canonical task name: the periodic temperature (heat/cold strength) scan. */
    public static final String TEMPERATURE_LOAD = "temperature-load";

    /** Canonical task name: the tick-budgeted projectile-impact queue drain. */
    public static final String IMPACT_QUEUE = "impact-queue";

    /** Canonical task name: the tick-budgeted explosion queue drain. */
    public static final String BLAST_QUEUE = "blast-queue";

    /** Canonical task name: the delayed/progressive block-collapse animator. */
    public static final String DELAYED_COLLAPSE = "delayed-collapse";

    /** Canonical task name: the cap-truncated cascade resume. */
    public static final String CASCADE_RESUME = "cascade-resume";

    /**
     * Canonical task name: the main-thread half of the async settle (snapshot +
     * conflict recheck + graph apply). The off-thread solve itself is not a
     * main-thread cost and is not timed here.
     */
    public static final String ASYNC_SETTLE = "async-settle";

    private final ConcurrentHashMap<String, PerfTracker> trackers = new ConcurrentHashMap<>();

    /**
     * The tracker for {@code name}, created on first use. Lazy creation via
     * {@code computeIfAbsent} runs once per name — never on the steady per-pass
     * path once a task has ticked — so the per-pass cost is just a map get.
     */
    public PerfTracker tracker(String name) {
        return trackers.computeIfAbsent(name, n -> new PerfTracker());
    }

    /**
     * Record one pass of a named task: its wall-clock nanos and a work count
     * meaningful to that task (nodes scanned, entities checked, items settled,
     * blocks applied). Two {@code System.nanoTime()} calls and one synchronized
     * record per pass; no per-pass allocation once the tracker exists.
     */
    public void record(String name, long elapsedNanos, int work) {
        tracker(name).record(elapsedNanos, work);
    }

    /**
     * An immutable snapshot of every named tracker that has recorded at least
     * one pass, sorted by name for a stable {@code /strux perf} readout. Tasks
     * that never ran are absent (their tracker was never created).
     */
    public Map<String, PerfTracker> snapshot() {
        return new TreeMap<>(trackers);
    }
}
