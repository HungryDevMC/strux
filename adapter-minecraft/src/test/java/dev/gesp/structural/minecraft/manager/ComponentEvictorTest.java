package dev.gesp.structural.minecraft.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.persistence.StructureData;
import dev.gesp.structural.solver.CascadeEngine;
import dev.gesp.structural.solver.CascadeResult;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The heart of the eviction proof: an evict → re-materialize round-trip through
 * {@link ComponentEvictor} is bit-identical to never having evicted, including a collapse
 * produced after restore. Plus the thermal-softening guard and the memory-bounding effect.
 */
@DisplayName("ComponentEvictor round-trips a component bit-identically")
final class ComponentEvictorTest {

    private static final MaterialSpec STONE = new MaterialSpec(2.0, 30.0);

    private static StructureGraph tower() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= 5; y++) {
            g.addBlock(new NodePos(0, y, 0), STONE, false);
        }
        g.getNode(new NodePos(0, 3, 0)).addDamage(0.3);
        g.getNode(new NodePos(0, 4, 0)).setReinforcement(1.8);
        return g;
    }

    private static Set<NodePos> component(StructureGraph g) {
        return g.componentOf(new NodePos(0, 0, 0));
    }

    @Test
    void evict_removesEveryNodeFromLiveGraph() {
        StructureGraph g = tower();
        ComponentEvictor evictor = new ComponentEvictor();
        int before = g.size();

        StructureData sidecar = evictor.evict(g, component(g), "world");

        assertEquals(0, g.size(), "live graph emptied");
        assertEquals(before, sidecar.blockCount(), "sidecar holds every node");
        assertEquals(1, evictor.evictions());
        assertEquals(before, evictor.nodesEvicted());
    }

    @Test
    void evictThenRematerialize_restoresPerNodeStateExactly() {
        StructureGraph pristine = tower();
        StructureGraph live = tower();
        ComponentEvictor evictor = new ComponentEvictor();

        StructureData sidecar = evictor.evict(live, component(live), "world");
        evictor.rematerialize(live, sidecar);

        assertEquals(pristine.getAllPositions(), live.getAllPositions());
        for (NodePos pos : pristine.getAllPositions()) {
            assertEquals(pristine.getNode(pos).isGrounded(), live.getNode(pos).isGrounded(), "grounded @" + pos);
            assertEquals(pristine.getNode(pos).damage(), live.getNode(pos).damage(), 1e-12, "damage @" + pos);
            assertEquals(
                    pristine.getNode(pos).reinforcement(),
                    live.getNode(pos).reinforcement(),
                    1e-12,
                    "reinforcement @" + pos);
            assertEquals(
                    new HashSet<>(pristine.getNeighbors(pos)), new HashSet<>(live.getNeighbors(pos)), "edges @" + pos);
        }
        assertEquals(1, evictor.rematerializations());
    }

    @Test
    void collapseAfterRestore_matchesNeverEvictedTwin() {
        StructureGraph twin = tower(); // never evicted
        StructureGraph restored = tower();
        ComponentEvictor evictor = new ComponentEvictor();
        evictor.rematerialize(restored, evictor.evict(restored, component(restored), "world"));

        // Break the base of each and settle; the fallen sets must be identical.
        CascadeResult twinResult = new CascadeEngine().cascade(twin, new NodePos(0, 1, 0));
        CascadeResult restoredResult = new CascadeEngine().cascade(restored, new NodePos(0, 1, 0));

        assertEquals(
                new HashSet<>(twinResult.collapsed()),
                new HashSet<>(restoredResult.collapsed()),
                "post-restore collapse equals never-evicted collapse");
    }

    @Test
    void isThermallyResident_refusesSoftenedComponent() {
        StructureGraph g = tower();
        assertTrue(ComponentEvictor.isThermallyResident(g, component(g)), "cold tower is resident");

        g.getNode(new NodePos(0, 2, 0)).setTemperatureCapacityFactor(0.5); // thermally softened
        assertFalse(
                ComponentEvictor.isThermallyResident(g, component(g)),
                "a softened node must refuse the lossy round-trip");
    }
}
