package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.fire.FireModel;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.StressSolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fire degradation: sustained heat becomes persistent damage at a rate set by
 * the material's fire resistance. Wood chars fast, stone resists, and the same
 * Node.damage means fire stacks with blast/impact (combo play) and feeds the
 * crack visuals for free.
 */
@DisplayName("FireModel: heat over time weakens by material fire resistance")
class FireModelTest {

    // fireResistance: wood 0.5 (burns fast), stone 8.0 (shrugs heat off).
    private static final MaterialSpec WOOD = new MaterialSpec(1.0, 40.0, 1.0, 0.5);
    private static final MaterialSpec STONE = new MaterialSpec(3.0, 100.0, 1.5, 8.0);

    private final FireModel fire = new FireModel(new PhysicsConfig());

    @Test
    @DisplayName("Burn damage scales inversely with fire resistance")
    void burnRateScalesWithResistance() {
        // Default base 0.0006/tick. Wood (res 0.5) burns at 0.0012/tick.
        assertEquals(0.0012 * 100, fire.burnDamage(WOOD, 100), 1e-9);
        // Stone (res 8.0) burns at 0.000075/tick — 16× slower than wood.
        assertEquals(0.0006 / 8.0 * 100, fire.burnDamage(STONE, 100), 1e-9);
        assertTrue(
                fire.burnDamage(WOOD, 100) > 15 * fire.burnDamage(STONE, 100),
                "wood must degrade far faster than stone under the same fire");
    }

    @Test
    @DisplayName("Radiant heat is the configured fraction of the direct-burn rate")
    void radiantIsFractionOfDirect() {
        // Default radiant factor 0.25.
        assertEquals(0.25 * fire.burnDamage(STONE, 200), fire.radiantDamage(STONE, 200), 1e-12);
    }

    @Test
    @DisplayName("Rates are configurable")
    void ratesAreConfigurable() {
        PhysicsConfig fast = new PhysicsConfig();
        fast.setFireDamagePerTick(0.01);
        fast.setFireRadiantFactor(0.5);
        FireModel hot = new FireModel(fast);

        assertEquals(0.01 / 0.5 * 50, hot.burnDamage(WOOD, 50), 1e-12);
        assertEquals(0.5 * hot.burnDamage(WOOD, 50), hot.radiantDamage(WOOD, 50), 1e-12);
    }

    @Test
    @DisplayName("Sustained fire eventually weakens a block enough to fail under its own load")
    void sustainedFireUnderminesAColumn() {
        // A wooden column; the base carries the whole stack above it (load ≈16).
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= 16; y++) {
            g.addBlock(new NodePos(0, y, 0), WOOD, false);
        }
        StressSolver solver = new StressSolver();
        solver.solveAll(g);
        Node base = g.getNode(new NodePos(0, 1, 0));
        assertFalse(base.isOverloaded(), "the healthy column stands");

        // Burn the base for 600 ticks (~30s): 0.0012 × 600 = 0.72 damage, leaving
        // capacity 40 × (1 − 0.72) = 11.2 — below the ≈16 it carries.
        base.addDamage(fire.burnDamage(WOOD, 600));
        solver.solveAll(g);
        assertTrue(base.isOverloaded(), "the charred base can no longer hold the column it carries");
    }

    @Test
    @DisplayName("Fire damage stacks with prior damage on the same node (combo play)")
    void fireStacksWithExistingDamage() {
        Node n = new Node(new NodePos(0, 1, 0), WOOD, false);
        n.addDamage(0.4); // e.g. from earlier fire arrows / impact
        n.addDamage(fire.burnDamage(WOOD, 500)); // 0.0012 × 500 = 0.6
        assertEquals(1.0, n.damage(), 1e-9, "0.4 + 0.6 = destroyed; fire finishes what impact started");
        assertTrue(n.isDestroyed());
    }

    @Test
    @DisplayName("Fire adjacency detection: fire one block away from wall is detected as touching")
    void fireAdjacencyDetection() {
        // Mimics the demo scenario geometry: fire at (0,1,1), wall at Z=0
        StructureGraph g = new StructureGraph();
        // Ground
        g.addGroundBlock(new NodePos(0, -1, 0));
        // Wall: 5 wide at Z=0, 4 tall
        for (int w = -2; w <= 2; w++) {
            for (int h = 0; h <= 3; h++) {
                g.addBlock(new NodePos(w, h, 0), STONE, false);
            }
        }

        // Fire position: (0, 1, 1) - adjacent to wall center at (0, 1, 0)
        NodePos firePos = new NodePos(0, 1, 1);

        // Check that at least one adjacent position is in the graph
        boolean touchesWall = false;
        for (NodePos neighbour : g.getAdjacentPositions(firePos)) {
            if (g.hasBlock(neighbour)) {
                touchesWall = true;
                break;
            }
        }

        assertTrue(touchesWall, "fire at (0,1,1) must detect adjacent wall block at (0,1,0)");

        // Verify the specific expected neighbor is found
        NodePos expectedNeighbor = new NodePos(0, 1, 0);
        assertTrue(
                g.getAdjacentPositions(firePos).contains(expectedNeighbor),
                "getAdjacentPositions should include (0,1,0) as a neighbor of (0,1,1)");
        assertTrue(g.hasBlock(expectedNeighbor), "wall block (0,1,0) should be in the graph");
    }
}
