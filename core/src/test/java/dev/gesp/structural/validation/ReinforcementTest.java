package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.StressSolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reinforcement: a persistent multiplier that raises a node's effective load
 * capacity. It composes with damage and survives re-solves, mirroring how
 * {@link DamageTest} treats damage.
 */
@DisplayName("Reinforcement: persistent capacity boost")
class ReinforcementTest {

    @Test
    @DisplayName("setReinforcement raises effective capacity and clamps below 1.0")
    void reinforcementRaisesCapacity() {
        Node n = new Node(new NodePos(0, 1, 0), new MaterialSpec(2.0, 100.0), false);
        assertEquals(1.0, n.reinforcement(), 1e-9);
        assertEquals(100.0, n.effectiveMaxLoad(), 1e-9);

        n.setReinforcement(2.0);
        assertEquals(200.0, n.effectiveMaxLoad(), 1e-9);

        n.setReinforcement(0.5); // reinforcement only strengthens
        assertEquals(1.0, n.reinforcement(), 1e-9);
        assertEquals(100.0, n.effectiveMaxLoad(), 1e-9);
    }

    @Test
    @DisplayName("Reinforcement and damage compose multiplicatively")
    void reinforcementComposesWithDamage() {
        Node n = new Node(new NodePos(0, 1, 0), new MaterialSpec(2.0, 100.0), false);
        n.setReinforcement(2.0);
        n.addDamage(0.25);
        // 100 × 2.0 × (1 − 0.25) = 150
        assertEquals(150.0, n.effectiveMaxLoad(), 1e-9);
    }

    @Test
    @DisplayName("Reinforcement survives a solve (resetStress must not clear it)")
    void reinforcementPersistsAcrossSolve() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        NodePos a = new NodePos(0, 1, 0);
        g.addBlock(a, TestMaterials.MEDIUM, false);

        g.getNode(a).setReinforcement(3.0);
        new StressSolver().solveAll(g);

        assertEquals(3.0, g.getNode(a).reinforcement(), 1e-9, "reinforcement must persist across solve");
    }

    @Test
    @DisplayName("Reinforcing an overloaded base stabilizes the column")
    void reinforcementSavesColumn() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        NodePos base = new NodePos(0, 1, 0);
        g.addBlock(base, TestMaterials.LIGHT, false);
        for (int y = 2; y <= 25; y++) {
            g.addBlock(new NodePos(0, y, 0), TestMaterials.LIGHT, false);
        }

        StressSolver solver = new StressSolver();
        solver.solveAll(g);
        assertTrue(g.getNode(base).isOverloaded(), "tall light column overloads its base");

        g.getNode(base).setReinforcement(4.0);
        solver.solveAll(g);
        assertFalse(g.getNode(base).isOverloaded(), "reinforced base now carries the column");
    }
}
