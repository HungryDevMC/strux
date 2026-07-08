package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cooperative settle budget: a {@code pause} supplier consulted between
 * units of work. When it trips, the settle stops where it is and reports
 * {@code truncated()} with the live scope as {@code remainingScope()} — the
 * adapters' anti-freeze hook (a per-tick deadline goes in; a too-big cascade
 * becomes a multi-tick delayed collapse instead of a frozen server tick).
 *
 * <p>Two properties are load-bearing and pinned here:
 *
 * <pre>
 *   PROGRESS — even a pause that is ALWAYS true advances the cascade every
 *              call (≥ 1 collapse, or a completed stability proof), so a
 *              tight deadline can slow a collapse but never starve it into
 *              an infinite resume loop.
 *   CONVERGENCE — resuming a budget-paused settle until it finishes reaches
 *              the SAME final stable graph as one unbudgeted settle.
 * </pre>
 */
@DisplayName("Settle budget: pausing yields delayed collapse, never a stall or a different outcome")
class SettleBudgetPauseTest {

    private static final BooleanSupplier ALWAYS_PAUSE = () -> true;

    /** Mirrors {@code CascadeTruncationTest.overloadedArm}: trims in OVERLOADED steps. */
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

    /** A stranded vertical chain: pure FLOATING collapse work. */
    private static StructureGraph floatingChain() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= 6; y++) {
            g.addBlock(new NodePos(0, y, 0), TestMaterials.LIGHT, false);
        }
        g.removeBlock(new NodePos(0, 1, 0)); // strand y=2..6
        return g;
    }

    /** Drive a budget-paused settle to completion, asserting it converges. */
    private static CascadeEngine.SettleOutcome resumeUntilDone(
            CascadeEngine engine, StructureGraph g, CascadeEngine.SettleOutcome first) {
        CascadeEngine.SettleOutcome outcome = first;
        int passes = 0;
        while (outcome.truncated()) {
            assertFalse(
                    outcome.remainingScope().isEmpty(),
                    "a budget-paused settle must hand back the live scope to resume from");
            assertTrue(++passes < 200, "a paused settle must converge, not resume forever");
            outcome =
                    engine.settleResult(g, new HashSet<>(outcome.remainingScope()), SolverCallback.NONE, ALWAYS_PAUSE);
        }
        return outcome;
    }

    @Test
    @DisplayName("PROGRESS: an always-true pause still collapses at least one block per pass")
    void alwaysPausedSettleStillMakesProgress() {
        StructureGraph g = floatingChain();

        CascadeEngine.SettleOutcome outcome =
                new CascadeEngine(new PhysicsConfig()).settleResult(g, SolverCallback.NONE, ALWAYS_PAUSE);

        assertTrue(outcome.truncated(), "pausing mid-collapse must be observable so the adapter resumes");
        assertEquals(1, outcome.collapsed().size(), "the pause is only consulted AFTER real progress");
        assertFalse(outcome.remainingScope().isEmpty(), "the live scope is the resume seed");
    }

    @Test
    @DisplayName("CONVERGENCE: budget-paused floating collapse ends at the same graph as unbudgeted")
    void pausedFloatingCollapseConverges() {
        StructureGraph budgeted = floatingChain();
        StructureGraph unbudgeted = floatingChain();
        CascadeEngine engine = new CascadeEngine(new PhysicsConfig());

        CascadeEngine.SettleOutcome first = engine.settleResult(budgeted, SolverCallback.NONE, ALWAYS_PAUSE);
        resumeUntilDone(engine, budgeted, first);
        engine.settleResult(unbudgeted, SolverCallback.NONE);

        assertEquals(
                new HashSet<>(unbudgeted.getAllPositions()),
                new HashSet<>(budgeted.getAllPositions()),
                "a paused-and-resumed settle must reach the same stable graph");
    }

    @Test
    @DisplayName("CONVERGENCE: budget-paused overload cascade ends at the same graph as unbudgeted")
    void pausedOverloadCascadeConverges() {
        StructureGraph budgeted = overloadedArm();
        StructureGraph unbudgeted = overloadedArm();
        CascadeEngine engine = new CascadeEngine(new PhysicsConfig());

        CascadeEngine.SettleOutcome first = engine.settleResult(budgeted, SolverCallback.NONE, ALWAYS_PAUSE);
        CascadeEngine.SettleOutcome last = resumeUntilDone(engine, budgeted, first);
        CascadeEngine.SettleOutcome reference = engine.settleResult(unbudgeted, SolverCallback.NONE);

        assertFalse(last.truncated());
        assertEquals(
                new HashSet<>(unbudgeted.getAllPositions()),
                new HashSet<>(budgeted.getAllPositions()),
                "a paused-and-resumed settle must reach the same stable graph");
        assertFalse(reference.collapsed().isEmpty(), "sanity: the shape actually cascades");
    }

    @Test
    @DisplayName("A stable structure is proven stable in ONE pass under a realistic (never-tripping) budget")
    void stableGraphIsProvenStableInOnePassUnderRealisticBudget() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), TestMaterials.LIGHT, false);

        // A budget that never trips (the normal case: a tiny scan finishes in microseconds,
        // far inside 8ms) proves stability in a single pass — no needless truncation.
        CascadeEngine.SettleOutcome outcome =
                new CascadeEngine(new PhysicsConfig()).settleResult(g, SolverCallback.NONE, () -> false);

        assertFalse(outcome.truncated(), "a completed stability proof is not truncated");
        assertTrue(outcome.collapsed().isEmpty());
        assertTrue(outcome.remainingScope().isEmpty(), "a clean finish leaves nothing to resume");
    }

    @Test
    @DisplayName("A stable structure under an always-true pause still CONVERGES to a proven-stable finish")
    void stableGraphConvergesToStableUnderAlwaysPause() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), TestMaterials.LIGHT, false);
        CascadeEngine engine = new CascadeEngine(new PhysicsConfig());

        // Now the stability proof itself is interruptible (it spans two distance levels:
        // the block and its ground sink), so an always-true pause may defer it across
        // resumes — but it must never collapse anything and must terminate proven-stable.
        CascadeEngine.SettleOutcome outcome = engine.settleResult(g, SolverCallback.NONE, ALWAYS_PAUSE);
        CascadeEngine.SettleOutcome last = resumeUntilDone(engine, g, outcome);

        assertFalse(last.truncated(), "resuming a stable graph must end with a completed stability proof");
        assertTrue(
                outcome.collapsed().isEmpty() && last.collapsed().isEmpty(),
                "a stable graph collapses nothing, paused or not");
        assertTrue(g.hasBlock(new NodePos(0, 1, 0)), "the stable block is untouched");
    }

    @Test
    @DisplayName("A never-pausing budget behaves exactly like the 3-arg settle")
    void neverPauseMatchesUnbudgeted() {
        StructureGraph withSupplier = overloadedArm();
        StructureGraph withoutSupplier = overloadedArm();
        CascadeEngine engine = new CascadeEngine(new PhysicsConfig());

        CascadeEngine.SettleOutcome a = engine.settleResult(withSupplier, SolverCallback.NONE, () -> false);
        CascadeEngine.SettleOutcome b = engine.settleResult(withoutSupplier, SolverCallback.NONE);

        assertEquals(b.truncated(), a.truncated());
        assertEquals(b.collapsed().size(), a.collapsed().size());
        assertEquals(new HashSet<>(withoutSupplier.getAllPositions()), new HashSet<>(withSupplier.getAllPositions()));
    }

    /**
     * A shape whose settle MUST fire the boundary guard: a weak terrain skin on
     * ground, with a heavy block dropped on one end. The seed scope (just the
     * heavy block) closes at the terrain-stable skin, so the overloaded skin
     * block under the weight touches an out-of-scope neighbor — exactly the
     * approximate-edge-stress case the guard widens the scope for.
     */
    private static StructureGraph weightOnTerrainSkin() {
        StructureGraph g = new StructureGraph();
        MaterialSpec skin = new MaterialSpec(1.0, 1.5);
        MaterialSpec weight = new MaterialSpec(10.0, 100.0);
        for (int x = 0; x <= 6; x++) {
            g.addGroundBlock(new NodePos(x, 0, 0));
            g.addBlock(new NodePos(x, 1, 0), skin, false);
        }
        g.addBlock(new NodePos(0, 2, 0), weight, false);
        return g;
    }

    @Test
    @DisplayName("The boundary guard widens the scope and the settle still finishes clean (never-pause)")
    void boundaryGuardExpansionFinishesCleanWithoutPause() {
        StructureGraph g = weightOnTerrainSkin();

        // Seed ONLY the weight: its closure stops at the terrain-stable skin, so
        // the overloaded skin block under it touches out-of-scope neighbors and
        // the guard must expand before any collapse. A correct never-pause settle
        // runs the expansion and finishes stable — it must NOT report truncation
        // (pausing inside the guard is reserved for a real tripped budget).
        CascadeEngine.SettleOutcome outcome = new CascadeEngine(new PhysicsConfig())
                .settleResult(g, new HashSet<>(Set.of(new NodePos(0, 2, 0))), SolverCallback.NONE, () -> false);

        assertFalse(outcome.truncated(), "a guard expansion under a never-tripping budget must not truncate");
        assertTrue(
                outcome.collapsed().stream().anyMatch(n -> n.pos().equals(new NodePos(0, 1, 0))),
                "the overloaded skin block under the weight collapses after the guard widens the scope");
        assertFalse(g.hasBlock(new NodePos(0, 2, 0)), "the weight loses its support and falls too");
        assertTrue(g.hasBlock(new NodePos(6, 1, 0)), "the far end of the skin is untouched");
    }

    @Test
    @DisplayName("A budget can pause inside a guard expansion, and resuming still converges")
    void boundaryGuardPausesUnderBudgetAndConverges() {
        StructureGraph budgeted = weightOnTerrainSkin();
        StructureGraph reference = weightOnTerrainSkin();
        CascadeEngine engine = new CascadeEngine(new PhysicsConfig());
        Set<NodePos> seed = Set.of(new NodePos(0, 2, 0));

        // Pass 1 must reach the boundary guard (the seed's closure stops at the
        // terrain-stable skin) and the always-true budget pauses right after the
        // expansion round — scope growth counts as progress, so pausing there is
        // legal and must report truncated with the grown scope to resume.
        CascadeEngine.SettleOutcome first =
                engine.settleResult(budgeted, new HashSet<>(seed), SolverCallback.NONE, ALWAYS_PAUSE);
        assertTrue(first.truncated(), "pausing mid-guard-expansion must be observable");

        resumeUntilDone(engine, budgeted, first);
        engine.settleResult(reference, new HashSet<>(seed), SolverCallback.NONE, () -> false);

        assertEquals(
                new HashSet<>(reference.getAllPositions()),
                new HashSet<>(budgeted.getAllPositions()),
                "pausing inside the guard must not change the final stable graph");
    }

    @Test
    @DisplayName("The step cap cuts a multi-block overload batch mid-batch, never one past it")
    void stepCapCutsMidBatch() {
        // Two symmetric arms off one pillar: both tips sit at the SAME distance
        // level, so the solver returns them as one overloaded batch. With a cap
        // of 1 the batch loop must stop after exactly one collapse — an off-by-one
        // there silently collapses one block per pass beyond the configured cap.
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), TestMaterials.HEAVY, false);
        g.addBlock(new NodePos(0, 2, 0), TestMaterials.HEAVY, false);
        for (int x = 1; x <= 8; x++) {
            g.addBlock(new NodePos(x, 2, 0), TestMaterials.LIGHT, false);
            g.addBlock(new NodePos(-x, 2, 0), TestMaterials.LIGHT, false);
        }
        PhysicsConfig config = new PhysicsConfig();
        config.setMaxCascadeSteps(1);

        CascadeEngine.SettleOutcome outcome = new CascadeEngine(config).settleResult(g, SolverCallback.NONE);

        assertEquals(1, outcome.collapsed().size(), "the cap bounds the pass even mid-batch");
        assertTrue(outcome.truncated(), "work clearly remains");
    }

    @Test
    @DisplayName("cascade() with a budget surfaces the pause as truncation + remainingScope")
    void cascadeSurfacesBudgetPause() {
        StructureGraph g = new StructureGraph();
        for (int pier : new int[] {0, 8}) {
            g.addGroundBlock(new NodePos(pier, 0, 0));
            g.addBlock(new NodePos(pier, 1, 0), TestMaterials.HEAVY, false);
            g.addBlock(new NodePos(pier, 2, 0), TestMaterials.HEAVY, false);
        }
        for (int x = 1; x <= 7; x++) {
            g.addBlock(new NodePos(x, 2, 0), TestMaterials.LIGHT, false);
        }

        var result = new CascadeEngine(new PhysicsConfig())
                .cascade(g, new NodePos(8, 1, 0), SolverCallback.NONE, ALWAYS_PAUSE);

        assertTrue(result.truncated(), "a budget-paused break cascade must report it was cut short");
        Set<NodePos> remaining = result.remainingScope();
        assertFalse(remaining.isEmpty(), "and hand back the scope so the resume manager can finish it");
    }
}
