package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import dev.gesp.structural.solver.CascadeResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cascade cap is a per-event work budget; when it fires it leaves the graph
 * mid-collapse. {@link CascadeResult#truncated()} (and its sibling
 * {@link CascadeEngine.SettleOutcome#truncated()}) make the cap stop OBSERVABLE,
 * so an adapter knows to resume the collapse on a later tick instead of
 * stranding floating leftovers.
 *
 * <pre>
 *   truncated() == true  → the cap stopped solver work while overloads remained.
 *   truncated() == false → the cascade ran to a stable end on its own.
 * </pre>
 */
@DisplayName("Cascade truncation: the step cap is observable so adapters can resume")
class CascadeTruncationTest {

    /**
     * A short pillar carrying an absurdly long arm: settling trims the arm in
     * OVERLOADED steps (each a solver pass), the work the cap exists to bound.
     * Mirrors {@code CascadeCapTest.overloadedArm} so the two pin the same shape.
     */
    private static StructureGraph overloadedArm() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), TestMaterials.HEAVY, false);
        g.addBlock(new NodePos(0, 2, 0), TestMaterials.HEAVY, false);
        for (int x = 1; x <= 8; x++) {
            g.addBlock(new NodePos(x, 2, 0), TestMaterials.LIGHT, false);
        }
        return g;
    }

    @Test
    @DisplayName("A settle that hits the cap reports truncated() == true and leaves work behind")
    void overCapSettleReportsTruncated() {
        PhysicsConfig config = new PhysicsConfig();
        config.setMaxCascadeSteps(2);
        StructureGraph g = overloadedArm();

        CascadeEngine.SettleOutcome outcome = new CascadeEngine(config).settleResult(g, SolverCallback.NONE);

        assertTrue(outcome.truncated(), "the cap stopped solver work while overloads remained — it must say so");
        assertEquals(2, outcome.collapsed().size(), "the cap still bounds the per-pass work");
    }

    @Test
    @DisplayName("A settle that finishes under the cap reports truncated() == false")
    void underCapSettleReportsNotTruncated() {
        PhysicsConfig config = new PhysicsConfig();
        config.setMaxCascadeSteps(1_000);
        StructureGraph g = overloadedArm();

        CascadeEngine.SettleOutcome outcome = new CascadeEngine(config).settleResult(g, SolverCallback.NONE);

        assertFalse(outcome.truncated(), "a settle that finished on its own must not claim truncation");
        assertTrue(g.getFloatingBlocks().isEmpty(), "and nothing is left dangling");
    }

    /**
     * A long horizontal span held at BOTH ends by short pillars: as a two-pier
     * bridge it is stable. Knock out one pier ({@code (8,1,0)}) and the span
     * becomes a cantilever off the surviving pier — overloaded but still
     * grounded, so it trims in OVERLOAD steps (cap-bound), never pure floaters.
     */
    private static StructureGraph twoPierBridge() {
        StructureGraph g = new StructureGraph();
        for (int pier : new int[] {0, 8}) {
            g.addGroundBlock(new NodePos(pier, 0, 0));
            g.addBlock(new NodePos(pier, 1, 0), TestMaterials.HEAVY, false);
            g.addBlock(new NodePos(pier, 2, 0), TestMaterials.HEAVY, false);
        }
        for (int x = 1; x <= 7; x++) {
            g.addBlock(new NodePos(x, 2, 0), TestMaterials.LIGHT, false);
        }
        return g;
    }

    @Test
    @DisplayName("cascade() surfaces truncation when the trigger's collapse exceeds the cap")
    void cascadeSurfacesTruncation() {
        PhysicsConfig config = new PhysicsConfig();
        config.setMaxCascadeSteps(2);
        StructureGraph g = twoPierBridge();

        // Knock out one pier: the span becomes an overloaded cantilever that the
        // cap leaves half-trimmed.
        CascadeResult result = new CascadeEngine(config).cascade(g, new NodePos(8, 1, 0));

        assertTrue(result.truncated(), "a capped break-path cascade must report it was cut short");
    }

    @Test
    @DisplayName("An uncapped cascade reports truncated() == false")
    void uncappedCascadeNotTruncated() {
        PhysicsConfig config = new PhysicsConfig();
        config.setMaxCascadeSteps(1_000);
        StructureGraph g = twoPierBridge();

        CascadeResult result = new CascadeEngine(config).cascade(g, new NodePos(8, 1, 0));

        assertFalse(result.truncated(), "a cascade that settled on its own must not claim truncation");
    }

    @Test
    @DisplayName("A no-op break (nothing depends on the trigger) is not truncated")
    void trivialBreakIsNotTruncated() {
        PhysicsConfig config = new PhysicsConfig();
        config.setMaxCascadeSteps(1);
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), TestMaterials.LIGHT, false);

        // Breaking the top block: nothing depends on it, fast path, no cascade.
        CascadeResult result = new CascadeEngine(config).cascade(g, new NodePos(0, 1, 0));

        assertFalse(result.truncated(), "an empty cascade can never be truncated");
        assertFalse(result.hadCascade());
    }
}
