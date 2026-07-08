package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StructureGraph#copy()} exists for what-if simulation — so the copy
 * must carry the full persistent node state, not just the topology. A copy
 * that silently heals damage (or forgets reinforcement) makes every
 * simulation on a battle-worn structure lie: the question "would this stand?"
 * gets answered for a pristine twin instead.
 */
@DisplayName("Graph copy carries persistent node state")
class GraphStateCopyTest {

    @Test
    @DisplayName("copy() preserves damage")
    void copyPreservesDamage() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), TestMaterials.LIGHT, false);
        g.getNode(new NodePos(0, 1, 0)).addDamage(0.6);

        Node copied = g.copy().getNode(new NodePos(0, 1, 0));
        assertEquals(0.6, copied.damage(), 1e-9, "a copy that heals damage makes what-if sims lie");
    }

    @Test
    @DisplayName("copy() preserves reinforcement")
    void copyPreservesReinforcement() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), TestMaterials.LIGHT, false);
        g.getNode(new NodePos(0, 1, 0)).setReinforcement(3.0);

        Node copied = g.copy().getNode(new NodePos(0, 1, 0));
        assertEquals(3.0, copied.reinforcement(), 1e-9, "a copy that forgets reinforcement underrates the structure");
    }

    @Test
    @DisplayName("copy() preserves the temperature capacity factor")
    void copyPreservesTemperatureFactor() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), TestMaterials.LIGHT, false);
        g.getNode(new NodePos(0, 1, 0)).setTemperatureCapacityFactor(0.4);

        Node copied = g.copy().getNode(new NodePos(0, 1, 0));
        assertEquals(
                0.4,
                copied.temperatureCapacityFactor(),
                1e-9,
                "a copy that forgets heat-softening previews a fire-heated build at full cold strength");
    }

    @Test
    @DisplayName("copy() preserves effective capacity (damage × reinforcement × heat composed)")
    void copyPreservesEffectiveCapacity() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), TestMaterials.LIGHT, false);
        // Constants chosen so they can't cancel out: 20 × 3.0 × 0.5 × (1 − 0.5) = 15 ≠ 20.
        Node original = g.getNode(new NodePos(0, 1, 0));
        original.setReinforcement(3.0);
        original.setTemperatureCapacityFactor(0.5);
        original.addDamage(0.5);

        Node copied = g.copy().getNode(new NodePos(0, 1, 0));
        assertEquals(
                original.effectiveMaxLoad(),
                copied.effectiveMaxLoad(),
                1e-9,
                "what-if simulation must see the same capacity as the live graph");
    }
}
