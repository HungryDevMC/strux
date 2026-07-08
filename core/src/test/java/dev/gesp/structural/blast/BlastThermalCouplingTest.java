package dev.gesp.structural.blast;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A heat-softened block is easier to break: its effective blast resistance scales by its
 * {@code temperatureCapacityFactor}. A blast intensity that a cold block (factor 1.0) shrugs
 * off craters the same block once it's been softened by fire — while a factor of 1.0 leaves
 * the blast outcome byte-identical to before (no regression when nothing is hot).
 */
@DisplayName("Blast × heat: a softened block is easier to crater")
class BlastThermalCouplingTest {

    private final StruxExplosionEngine engine = new StruxExplosionEngine();

    private static BlastContext blast(NodePos center, double power) {
        return BlastContext.builder()
                .center(center)
                .power(power)
                .occlusion(BlastOcclusion.NONE)
                .build();
    }

    /** A block with blast resistance 1.0 and a huge maxLoad (so only the blast can remove it). */
    private static MaterialSpec spec() {
        return new MaterialSpec(1.0, 1_000_000.0, 1.0);
    }

    @Test
    @DisplayName("a cold block survives a sub-threshold blast; the same blast craters it when hot")
    void hotBlockIsEasierToBreak() {
        NodePos pos = new NodePos(0, 1, 0);

        // Cold (factor 1.0): power 1.9 / resistance 1.0 = intensity 1.9 < the 2.0 crater
        // threshold — it survives (only cracked).
        StructureGraph cold = new StructureGraph();
        cold.addGroundBlock(new NodePos(0, 0, 0));
        cold.addBlock(pos, spec(), false);
        engine.process(cold, blast(pos, 1.9));
        assertTrue(cold.hasBlock(pos), "a cold block shrugs off the sub-threshold blast");

        // Hot (factor 0.3): effective resistance 1.0 × 0.3 = 0.3 → intensity 1.9/0.3 ≈ 6.3 > 2.0,
        // so the identical blast now craters it.
        StructureGraph hot = new StructureGraph();
        hot.addGroundBlock(new NodePos(0, 0, 0));
        hot.addBlock(pos, spec(), false);
        hot.getNode(pos).setTemperatureCapacityFactor(0.3);
        engine.process(hot, blast(pos, 1.9));
        assertFalse(hot.hasBlock(pos), "the same blast craters the heat-softened block");
    }
}
