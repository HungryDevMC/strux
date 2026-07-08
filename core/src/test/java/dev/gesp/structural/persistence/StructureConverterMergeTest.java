package dev.gesp.structural.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link StructureConverter#mergeInto} restores a snapshot into an existing graph
 * bit-identically — the re-materialization half of component memory eviction. Full
 * per-node persistent state (grounded, damage, reinforcement, material spec) and edges
 * (grid-derived and explicit) must survive a {@code toData → mergeInto} round-trip.
 */
final class StructureConverterMergeTest {

    private static final MaterialSpec STONE = new MaterialSpec(2.0, 30.0);

    @Test
    void mergeInto_restoresGridStructureWithPerNodeState() {
        StructureGraph original = new StructureGraph();
        original.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= 4; y++) {
            original.addBlock(new NodePos(0, y, 0), STONE, false);
        }
        // Per-node persistent state that must survive the round-trip.
        original.getNode(new NodePos(0, 2, 0)).addDamage(0.4);
        original.getNode(new NodePos(0, 3, 0)).setReinforcement(2.5);

        StructureData sidecar = StructureConverter.toData(original, "world");

        StructureGraph restored = new StructureGraph();
        StructureConverter.mergeInto(sidecar, restored);

        assertGraphsEqual(original, restored);
    }

    @Test
    void mergeInto_restoresExplicitTopology() {
        // A severed / non-grid topology must ride the persisted edges, not the grid.
        StructureGraph original = new StructureGraph();
        original.addGroundBlock(new NodePos(0, 0, 0));
        original.addBlock(new NodePos(0, 1, 0), STONE, false);
        original.addBlock(new NodePos(0, 2, 0), STONE, false);
        // Sever the grid edge between the two stacked blocks — a non-grid-derivable topology.
        original.disconnect(new NodePos(0, 1, 0), new NodePos(0, 2, 0));

        StructureData sidecar = StructureConverter.toData(original, "world");
        assertTrue(sidecar.isExplicitTopology(), "severed joint should force explicit topology");

        StructureGraph restored = new StructureGraph();
        StructureConverter.mergeInto(sidecar, restored);

        assertGraphsEqual(original, restored);
    }

    @Test
    void mergeInto_addsIntoAnExistingGraphWithoutDisturbingIt() {
        StructureGraph live = new StructureGraph();
        live.addGroundBlock(new NodePos(100, 0, 0));
        live.addBlock(new NodePos(100, 1, 0), STONE, false);

        StructureGraph component = new StructureGraph();
        component.addGroundBlock(new NodePos(0, 0, 0));
        component.addBlock(new NodePos(0, 1, 0), STONE, false);
        StructureData sidecar = StructureConverter.toData(component, "world");

        StructureConverter.mergeInto(sidecar, live);

        assertEquals(4, live.size());
        assertTrue(live.hasBlock(new NodePos(100, 1, 0)));
        assertTrue(live.hasBlock(new NodePos(0, 1, 0)));
    }

    private static void assertGraphsEqual(StructureGraph a, StructureGraph b) {
        assertEquals(a.getAllPositions(), b.getAllPositions(), "positions");
        for (NodePos pos : a.getAllPositions()) {
            Node na = a.getNode(pos);
            Node nb = b.getNode(pos);
            assertEquals(na.isGrounded(), nb.isGrounded(), "grounded @" + pos);
            assertEquals(na.damage(), nb.damage(), 1e-12, "damage @" + pos);
            assertEquals(na.reinforcement(), nb.reinforcement(), 1e-12, "reinforcement @" + pos);
            assertEquals(na.mass(), nb.mass(), 1e-12, "mass @" + pos);
            assertEquals(na.maxLoad(), nb.maxLoad(), 1e-12, "maxLoad @" + pos);
            assertEquals(neighbors(a, pos), neighbors(b, pos), "edges @" + pos);
        }
    }

    private static Set<NodePos> neighbors(StructureGraph g, NodePos pos) {
        return new HashSet<>(g.getNeighbors(pos));
    }
}
