package dev.gesp.structural.minecraft.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link TaskTimings} registry: it lazily creates one
 * {@link PerfTracker} per canonical task name, records wall-clock + work per
 * pass, and snapshots only the tasks that actually ran (sorted by name).
 */
@DisplayName("TaskTimings registry: lazy trackers, averages, omission of never-ran tasks")
class TaskTimingsTest {

    @Test
    @DisplayName("tracker(name) is lazy and stable — same instance returned for the same name")
    void trackerIsLazyAndStable() {
        TaskTimings timings = new TaskTimings();
        // Nothing recorded yet → the snapshot is empty (no tracker created).
        assertTrue(timings.snapshot().isEmpty(), "a fresh registry holds no trackers until something records");

        PerfTracker first = timings.tracker(TaskTimings.DAMAGE_VISUALIZER);
        PerfTracker again = timings.tracker(TaskTimings.DAMAGE_VISUALIZER);
        assertSame(first, again, "the same name must map to the same tracker instance");
    }

    @Test
    @DisplayName("record stores nanos as ms average and the work count as average work")
    void recordStoresMillisAndWork() {
        TaskTimings timings = new TaskTimings();
        // 2 ms and 5 ms over two passes → 3.5 ms average; work 10 then 20 → 15 average.
        timings.record(TaskTimings.STRESS_VISUALIZER, 2_000_000L, 10);
        timings.record(TaskTimings.STRESS_VISUALIZER, 5_000_000L, 20);

        PerfTracker tracker = timings.tracker(TaskTimings.STRESS_VISUALIZER);
        assertEquals(2, tracker.sampleCount(), "both passes must be sampled");
        assertEquals(3.5, tracker.averageMillis(), 1e-9, "avg ms is the mean of the recorded nanos");
        assertEquals(5.0, tracker.worstMillis(), 1e-9, "worst ms is the slowest recorded pass");
        assertEquals(15.0, tracker.averageWork(), 1e-9, "avg work is the mean of the recorded work counts");
    }

    @Test
    @DisplayName("averageWork of an untouched tracker is exactly zero (no NaN, no divide-by-zero)")
    void averageWorkOfEmptyTrackerIsZero() {
        assertEquals(0.0, new PerfTracker().averageWork(), 0.0, "an unsampled tracker reports zero average work");
    }

    @Test
    @DisplayName("snapshot is sorted by name and omits tasks that never ran")
    void snapshotSortedAndOmitsNeverRan() {
        TaskTimings timings = new TaskTimings();
        // Record out of alphabetical order; only these two ran.
        timings.record(TaskTimings.WEATHER_LOAD, 1_000_000L, 7);
        timings.record(TaskTimings.BLAST_QUEUE, 1_000_000L, 3);

        Map<String, PerfTracker> snap = timings.snapshot();
        assertEquals(
                List.of(TaskTimings.BLAST_QUEUE, TaskTimings.WEATHER_LOAD),
                List.copyOf(snap.keySet()),
                "snapshot keys must be exactly the tasks that ran, sorted by name");
        assertFalse(
                snap.containsKey(TaskTimings.DAMAGE_VISUALIZER), "a task that never ran is absent from the snapshot");
    }

    @Test
    @DisplayName("the canonical task-name constants are the exact strings the brief pins")
    void canonicalNamesAreExact() {
        // These exact strings are the public contract (readout + probe ranking),
        // so pin them: a typo'd rename would silently break the perf table.
        assertEquals("stress-visualizer", TaskTimings.STRESS_VISUALIZER);
        assertEquals("damage-visualizer", TaskTimings.DAMAGE_VISUALIZER);
        assertEquals("entity-weight", TaskTimings.ENTITY_WEIGHT);
        assertEquals("fire-scorch", TaskTimings.FIRE_SCORCH);
        assertEquals("weather-load", TaskTimings.WEATHER_LOAD);
        assertEquals("impact-queue", TaskTimings.IMPACT_QUEUE);
        assertEquals("blast-queue", TaskTimings.BLAST_QUEUE);
        assertEquals("delayed-collapse", TaskTimings.DELAYED_COLLAPSE);
        assertEquals("cascade-resume", TaskTimings.CASCADE_RESUME);
    }
}
