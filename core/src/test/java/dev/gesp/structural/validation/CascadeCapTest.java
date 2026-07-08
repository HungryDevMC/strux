package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code maxCascadeSteps} is a safety valve on SOLVER work, not physics.
 * The pinned contract:
 *
 * <pre>
 *   • Pure floating collapse is EXEMPT — connectivity is cheap, so a cut
 *     column always falls completely, cap or no cap.
 *   • Overload-driven steps (each needs a solver pass) are capped; hitting
 *     the cap deliberately leaves the graph mid-collapse.
 *   • A follow-up settle RESUMES the collapse — truncation is never lost,
 *     so adapters can spread giant cascades across ticks.
 * </pre>
 */
@DisplayName("Cascade cap: bounds solver work, never corrupts the outcome")
class CascadeCapTest {

    /** A 30-block column on ground. */
    private static StructureGraph tallColumn() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= 30; y++) {
            g.addBlock(new NodePos(0, y, 0), TestMaterials.LIGHT, false);
        }
        return g;
    }

    /**
     * A short pillar carrying an absurdly long arm: the arm root is far past
     * its moment capacity, so settling trims the arm in OVERLOADED steps
     * (each one a solver pass) — the work the cap exists to bound.
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
    @DisplayName("Pure floating collapse ignores the cap — a cut column always falls completely")
    void floatingCollapseIsExemptFromCap() {
        PhysicsConfig config = new PhysicsConfig();
        config.setMaxCascadeSteps(5);
        StructureGraph g = tallColumn();

        var result = new CascadeEngine(config).cascade(g, new NodePos(0, 1, 0));

        assertEquals(29, result.collapsedNodes().size(), "all 29 floating blocks fall despite the cap of 5");
        assertTrue(g.getFloatingBlocks().isEmpty(), "nothing is left dangling");
        assertEquals(1, g.size(), "only the ground survives");
    }

    @Test
    @DisplayName("Overload-driven settling truncates at the cap and leaves visible leftovers")
    void overloadSettleTruncatesAtCap() {
        PhysicsConfig config = new PhysicsConfig();
        config.setMaxCascadeSteps(2);
        StructureGraph g = overloadedArm();
        int before = g.size();

        var collapsed = new CascadeEngine(config).settle(g, SolverCallback.NONE);

        assertEquals(2, collapsed.size(), "the cap bounds the solver-driven steps");
        assertTrue(g.size() > before - 9, "most of the arm is still standing, waiting for the next slice");
    }

    @Test
    @DisplayName("Capped slices converge to exactly the uncapped end state")
    void cappedSlicesConvergeToUncapped() {
        // Capped run, resumed slice by slice — a per-tick collapse budget.
        PhysicsConfig capped = new PhysicsConfig();
        capped.setMaxCascadeSteps(2);
        StructureGraph a = overloadedArm();
        CascadeEngine cappedEngine = new CascadeEngine(capped);
        int safety = 30;
        while (!cappedEngine.settle(a, SolverCallback.NONE).isEmpty() && safety-- > 0) {
            // keep slicing until a pass removes nothing
        }

        // One uncapped run.
        StructureGraph b = overloadedArm();
        new CascadeEngine(new PhysicsConfig()).settle(b, SolverCallback.NONE);

        assertEquals(b.getAllPositions(), a.getAllPositions(), "cap + resume must land where one big run lands");
        assertTrue(a.getFloatingBlocks().isEmpty(), "no floating leftovers once the slices finish");
    }
}
