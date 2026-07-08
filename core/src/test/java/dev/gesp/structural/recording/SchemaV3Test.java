package dev.gesp.structural.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.api.CollapseReason;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.persistence.StructureConverter;
import dev.gesp.structural.persistence.StructureData;
import dev.gesp.structural.recording.ReplayEngine.ReplayResult;
import dev.gesp.structural.solver.CascadeEngine;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Schema-v3 core types: {@link MarkerEvent}, {@link StressDelta}, and the
 * {@link StressDeltaCollector} that turns the solver's load-ratio stream into a
 * bucket-crossing snapshot.
 */
@DisplayName("Recording schema v3 core types")
class SchemaV3Test {

    @Test
    @DisplayName("schema version is bumped to 3")
    void schemaVersionIsThree() {
        assertEquals(3, RecordingSession.SCHEMA_VERSION);
    }

    @Test
    @DisplayName("MarkerEvent reports MARKER type and defends its fields")
    void markerEventShape() {
        MarkerEvent e = new MarkerEvent(100L, 7L, "round start", Map.of("round", "1"));
        assertEquals(StruxEvent.EventType.MARKER, e.type());
        assertEquals(100L, e.timestampMs());
        assertEquals(7L, e.sequenceId());
        assertEquals("round start", e.name());
        assertEquals("1", e.meta().get("round"));

        // null name/meta are normalised, not stored as null.
        MarkerEvent empty = new MarkerEvent(0L, 0L, null, null);
        assertEquals("", empty.name());
        assertTrue(empty.meta().isEmpty());
    }

    @Test
    @DisplayName("StressDelta.bucketOf maps ratios to the right bucket")
    void bucketBoundaries() {
        assertEquals(0, StressDelta.bucketOf(0.0));
        assertEquals(0, StressDelta.bucketOf(0.24));
        assertEquals(1, StressDelta.bucketOf(0.25));
        assertEquals(1, StressDelta.bucketOf(0.49));
        assertEquals(2, StressDelta.bucketOf(0.5));
        assertEquals(3, StressDelta.bucketOf(0.75));
        assertEquals(4, StressDelta.bucketOf(0.9));
        assertEquals(5, StressDelta.bucketOf(1.0));
        assertEquals(5, StressDelta.bucketOf(2.3));
    }

    @Test
    @DisplayName("collector keeps only blocks that crossed a bucket boundary")
    void collectorKeepsCrossings() {
        NodePos crosser = new NodePos(0, 1, 0);
        NodePos steady = new NodePos(1, 1, 0);

        StressDeltaCollector c = new StressDeltaCollector();
        // First snapshot = baseline: crosser comfortable, steady mid.
        Map<NodePos, Double> first = new LinkedHashMap<>();
        first.put(crosser, 0.10); // bucket 0
        first.put(steady, 0.60); // bucket 2
        c.accept(first);
        // Later snapshot: crosser is now overloaded (bucket 5), steady barely moved (still bucket 2).
        Map<NodePos, Double> second = new LinkedHashMap<>();
        second.put(crosser, 1.40);
        second.put(steady, 0.62);
        c.accept(second);

        StressDelta delta = c.build();
        assertEquals(1, delta.loadRatios().size(), "only the crosser should be recorded");
        assertEquals(1.40, delta.loadRatios().get(crosser), 1e-9);
        assertNull(delta.loadRatios().get(steady));
    }

    @Test
    @DisplayName("collector returns null when nothing crossed a boundary")
    void collectorNullWhenNoCrossing() {
        NodePos steady = new NodePos(0, 1, 0);
        StressDeltaCollector c = new StressDeltaCollector();
        c.accept(Map.of(steady, 0.60));
        c.accept(Map.of(steady, 0.65)); // same bucket
        assertNull(c.build(), "no bucket crossing → no payload");
    }

    @Test
    @DisplayName("a block first seen mid-event counts from bucket 0")
    void lateBlockCountsFromZero() {
        NodePos late = new NodePos(0, 2, 0);
        StressDeltaCollector c = new StressDeltaCollector();
        c.accept(Map.of(new NodePos(0, 0, 0), 0.1));
        // 'late' appears only now, already heavy — that's a crossing from the implicit baseline 0.
        c.accept(Map.of(new NodePos(0, 0, 0), 0.1, late, 0.95));
        StressDelta delta = c.build();
        assertEquals(0.95, delta.loadRatios().get(late), 1e-9);
    }

    @Test
    @DisplayName("StressDelta normalises a null map to empty")
    void stressDeltaNullMap() {
        assertTrue(new StressDelta(null).isEmpty());
    }

    @Test
    @DisplayName("collector ignores a null snapshot and reports empty until fed")
    void collectorIgnoresNull() {
        StressDeltaCollector c = new StressDeltaCollector();
        assertTrue(c.isEmpty(), "nothing fed yet");
        c.accept(null); // must be a safe no-op, not an NPE
        assertTrue(c.isEmpty(), "a null snapshot feeds nothing");
        assertNull(c.build(), "empty collector builds no payload");
    }

    @Test
    @DisplayName("the default onStressDelta is a harmless no-op")
    void defaultStressDeltaIsNoop() {
        // NONE doesn't override onStressDelta — calling it must do nothing and not throw.
        SolverCallback.NONE.onStressDelta(Map.of(new NodePos(0, 0, 0), 0.5));
        assertFalse(SolverCallback.NONE.wantsStressDeltas(), "the default opts out of stress deltas");
    }

    @Test
    @DisplayName("a settle fires onStressDelta only for a callback that asks, and the collector captures crossings")
    void cascadeFeedsCollector() {
        // A weak arm that overloads and trims over several settle passes — so the
        // progressive loop runs and load migrates through the arm.
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), new MaterialSpec(5.0, 50.0), false);
        for (int x = 1; x <= 5; x++) {
            g.addBlock(new NodePos(x, 1, 0), new MaterialSpec(5.0, 5.0), false);
        }

        StressDeltaCollector collector = new StressDeltaCollector();
        SolverCallback cb = new SolverCallback() {
            @Override
            public boolean wantsStressDeltas() {
                return true;
            }

            @Override
            public void onStressDelta(Map<NodePos, Double> loadRatios) {
                collector.accept(loadRatios);
            }

            @Override
            public void onStressUpdated(Map<NodePos, Double> stressMap) {}

            @Override
            public void onCascadeStep(CollapsedNode collapsed, int stepNumber, CollapseReason reason) {}

            @Override
            public void onCascadeComplete(List<CollapsedNode> allCollapsed) {}
        };

        new CascadeEngine(new PhysicsConfig()).settle(g, cb);

        assertFalse(collector.isEmpty(), "an interested callback receives load-ratio snapshots");
        StressDelta delta = collector.build();
        // The overloaded arm blocks (ratio >= 1.0) cross from their starting bucket, so at
        // least one crossing is captured with a finite, non-negative ratio.
        assertNotNull(delta, "blocks crossed a bucket while the arm overloaded");
        for (double ratio : delta.loadRatios().values()) {
            assertTrue(ratio >= 0.0 && Double.isFinite(ratio), "captured ratios are real");
        }
    }

    @Test
    @DisplayName("a MarkerEvent replays as a no-op (it is a label, not a physics action)")
    void markerReplaysAsNoop() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), new MaterialSpec(1.0, 6.0), false);
        StructureData initial = StructureConverter.toData(g, "w");

        RecordingSession session = new RecordingSession("marker", 0L, "w", initial);
        session.addEvent(new MarkerEvent(1L, 1L, "round start", Map.of("round", "1")));

        ReplayResult result = new ReplayEngine(new PhysicsConfig()).replay(session, ReplayEngine.ReplayListener.NONE);
        assertEquals(1, result.eventsReplayed(), "the marker event is replayed");
        assertTrue(result.divergences().isEmpty(), "a marker changes nothing, so it never diverges");
    }
}
