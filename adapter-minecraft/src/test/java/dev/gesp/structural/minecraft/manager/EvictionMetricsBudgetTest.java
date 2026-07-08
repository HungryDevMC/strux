package dev.gesp.structural.minecraft.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.persistence.StructureData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Deterministic work-count budgets for eviction, in the spirit of {@code StruxMetrics}:
 * a fixed scenario must produce fixed counter values, so a change that makes eviction do
 * more work (double-evicting, re-materializing twice) trips the gate.
 */
@DisplayName("ComponentEvictor counters hold their budget")
final class EvictionMetricsBudgetTest {

    private static final MaterialSpec STONE = new MaterialSpec(2.0, 30.0);

    private static StructureGraph tower(int height) {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= height; y++) {
            g.addBlock(new NodePos(0, y, 0), STONE, false);
        }
        return g;
    }

    @Test
    void evictThenRematerialize_countsExactlyOnePassEach() {
        StructureGraph g = tower(5); // 6 nodes: ground + 5
        ComponentEvictor evictor = new ComponentEvictor();

        StructureData sidecar = evictor.evict(g, g.componentOf(new NodePos(0, 0, 0)), "world");
        evictor.rematerialize(g, sidecar);

        // Budget: one evict, one rematerialize, six nodes each way. If any of these grow,
        // the eviction path is doing redundant work and this gate should catch it.
        assertEquals(1, evictor.evictions(), "evictions");
        assertEquals(6, evictor.nodesEvicted(), "nodesEvicted");
        assertEquals(1, evictor.rematerializations(), "rematerializations");
        assertEquals(6, evictor.nodesRematerialized(), "nodesRematerialized");
    }
}
