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
 * Persistent damage: a separate quantity from gravity stress. It lowers a
 * node's effective capacity, survives re-solves, and accumulates — so a
 * sufficiently damaged block fails under the load it was already carrying.
 */
@DisplayName("Damage: persistent weakening lowers effective capacity")
class DamageTest {

    @Test
    @DisplayName("addDamage lowers effective capacity and clamps to [0,1]")
    void damageLowersCapacity() {
        Node n = new Node(new NodePos(0, 1, 0), new MaterialSpec(2.0, 100.0), false);
        assertEquals(100.0, n.effectiveMaxLoad(), 1e-9);

        n.addDamage(0.25);
        assertEquals(75.0, n.effectiveMaxLoad(), 1e-9);
        assertFalse(n.isDestroyed());

        n.addDamage(5.0); // over-add clamps at 1.0
        assertEquals(1.0, n.damage(), 1e-9);
        assertTrue(n.isDestroyed());
        assertEquals(0.0, n.effectiveMaxLoad(), 1e-9);
    }

    @Test
    @DisplayName("Damage survives a solve (resetStress must not clear it)")
    void damagePersistsAcrossSolve() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        NodePos a = new NodePos(0, 1, 0);
        g.addBlock(a, TestMaterials.MEDIUM, false);

        g.getNode(a).addDamage(0.5);
        new StressSolver().solveAll(g);

        assertEquals(0.5, g.getNode(a).damage(), 1e-9, "damage must persist across solve");
    }

    @Test
    @DisplayName("A damaged block becomes overloaded under its own existing load")
    void damagedBlockBecomesOverloaded() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        NodePos base = new NodePos(0, 1, 0);
        g.addBlock(base, TestMaterials.MEDIUM, false);
        g.addBlock(new NodePos(0, 2, 0), TestMaterials.MEDIUM, false);
        g.addBlock(new NodePos(0, 3, 0), TestMaterials.MEDIUM, false);

        StressSolver solver = new StressSolver();
        solver.solveAll(g);
        assertFalse(g.getNode(base).isOverloaded(), "healthy column is stable");

        // MEDIUM is mass 2 / maxLoad 50; base carries the whole column (≈6).
        // Weaken it so its capacity drops below that load: 50 × (1−0.95) = 2.5 < 6.
        g.getNode(base).addDamage(0.95);
        solver.solveAll(g);
        assertTrue(g.getNode(base).isOverloaded(), "damaged base can no longer hold the column above it");
    }

    @Test
    @DisplayName("MaterialSpec carries blast resistance (default 1.0)")
    void blastResistanceField() {
        assertEquals(1.0, new MaterialSpec(2.0, 50.0).blastResistance(), 1e-9);
        assertEquals(6.0, new MaterialSpec(2.0, 50.0, 6.0).blastResistance(), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> new MaterialSpec(2.0, 50.0, 0.0));
    }

    @Test
    @DisplayName("MaterialSpec carries fire resistance (default 1.0, independent of blast)")
    void fireResistanceField() {
        assertEquals(1.0, new MaterialSpec(2.0, 50.0).fireResistance(), 1e-9);
        assertEquals(1.0, new MaterialSpec(2.0, 50.0, 6.0).fireResistance(), 1e-9, "3-arg leaves fire at default");
        assertEquals(8.0, new MaterialSpec(2.0, 50.0, 6.0, 8.0).fireResistance(), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> new MaterialSpec(2.0, 50.0, 1.0, 0.0));
    }
}
