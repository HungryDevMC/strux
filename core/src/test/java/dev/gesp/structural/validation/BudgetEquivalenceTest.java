package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import dev.gesp.structural.solver.StressSolver;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The budget now binds INSIDE the two heavy settle units (the affected-region closure
 * and the stress level-scan), not just between collapses — yet the collapse it produces
 * is byte-identical to an unbudgeted settle. These are the equivalence + binding proofs
 * (AC #1, #2): an always-true pause fragments the settle into the smallest possible
 * steps, and driving it to a fixpoint must reproduce the exact same collapsed list, in
 * the exact same order, as one unbudgeted call.
 */
@DisplayName("Budget equivalence: an interruptible settle collapses the same blocks, in the same order")
class BudgetEquivalenceTest {

    private static final BooleanSupplier ALWAYS_PAUSE = () -> true;

    /** High cap so the reference single call runs to a true fixpoint (not the step cap). */
    private static PhysicsConfig uncapped() {
        PhysicsConfig c = new PhysicsConfig();
        c.setMaxCascadeSteps(1_000_000);
        return c;
    }

    /**
     * A grounded pillar with a long horizontal cantilever arm — the arm's far tip is the
     * most overloaded, so it fails first and the collapse marches inward over MANY
     * overload batches, exercising the resumable level scan and the between-collapse pause.
     */
    private static StructureGraph cantilever(int armLength) {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= 3; y++) {
            g.addBlock(new NodePos(0, y, 0), TestMaterials.HEAVY, false);
        }
        for (int x = 1; x <= armLength; x++) {
            g.addBlock(new NodePos(x, 3, 0), TestMaterials.LIGHT, false);
        }
        return g;
    }

    /** Collect the collapsed positions of a full unbudgeted settle over the whole graph. */
    private static List<NodePos> unbudgetedCollapseOrder(StructureGraph g) {
        List<CollapsedNode> collapsed = new CascadeEngine(uncapped())
                .settleResult(g, SolverCallback.NONE)
                .collapsed();
        List<NodePos> order = new ArrayList<>();
        for (CollapsedNode n : collapsed) {
            order.add(n.pos());
        }
        return order;
    }

    /** Drive an always-paused settle to a fixpoint, concatenating every collapse in order. */
    private static List<NodePos> budgetedCollapseOrder(CascadeEngine engine, StructureGraph g, Set<NodePos> scope) {
        List<NodePos> order = new ArrayList<>();
        Set<NodePos> next = new HashSet<>(scope);
        int passes = 0;
        while (true) {
            CascadeEngine.SettleOutcome outcome = engine.settleResult(g, next, SolverCallback.NONE, ALWAYS_PAUSE);
            for (CollapsedNode n : outcome.collapsed()) {
                order.add(n.pos());
            }
            assertTrue(++passes < 100_000, "an always-paused settle must converge to a fixpoint");
            if (!outcome.truncated()) {
                break;
            }
            assertFalse(outcome.remainingScope().isEmpty(), "a truncated pass must hand back a non-empty resume scope");
            next = new HashSet<>(outcome.remainingScope());
        }
        return order;
    }

    @Test
    @DisplayName("AC#2: an always-paused, resumed-to-fixpoint cascade equals the unbudgeted collapse, order included")
    void resumedCascadeIsOrderIdenticalToUnbudgeted() {
        StructureGraph budgeted = cantilever(24);
        StructureGraph reference = cantilever(24);

        List<NodePos> expected = unbudgetedCollapseOrder(reference);
        List<NodePos> actual = budgetedCollapseOrder(
                new CascadeEngine(uncapped()), budgeted, new HashSet<>(budgeted.getAllPositions()));

        assertFalse(expected.isEmpty(), "sanity: the cantilever actually cascades");
        assertEquals(expected, actual, "budget pausing must not change WHICH blocks fall, or the ORDER they fall in");
        assertEquals(
                new HashSet<>(reference.getAllPositions()),
                new HashSet<>(budgeted.getAllPositions()),
                "the paused-and-resumed graph must end identical to the unbudgeted one");
    }

    @Test
    @DisplayName(
            "AC#1: on a > CHUNK closure the first budgeted pass truncates before any collapse, with a resume scope")
    void firstPassInterruptsInsideTheClosure() {
        // A structural beam larger than one closure chunk: the affected-region BFS alone
        // exceeds the budget, so the FIRST pass must pause INSIDE the closure — reporting
        // truncated with a non-empty resume scope and having collapsed nothing yet. Before
        // the budget bound the closure, the whole hundreds-of-ms closure ran in this one
        // pass. The scope handed back is the SEED set (re-seeding the partial region would
        // grow it wrongly), so it is exactly what was passed in.
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), TestMaterials.HEAVY, false);
        int length = StructureGraph.CLOSURE_CHUNK + 500;
        for (int x = 1; x <= length; x++) {
            g.addBlock(new NodePos(x, 1, 0), TestMaterials.LIGHT, false);
        }
        Set<NodePos> seed = Set.of(new NodePos(1, 1, 0));

        CascadeEngine.SettleOutcome first =
                new CascadeEngine(uncapped()).settleResult(g, new HashSet<>(seed), SolverCallback.NONE, ALWAYS_PAUSE);

        assertTrue(first.truncated(), "a > CHUNK closure must not finish in one budgeted pass");
        assertTrue(first.collapsed().isEmpty(), "the pass paused inside the closure, before any collapse");
        assertEquals(seed, first.remainingScope(), "the resume scope is the original seed set");
    }

    @Test
    @DisplayName("AC#5 surrogate: every budgeted pass over a large scope does bounded work (never the whole settle)")
    void everyPassDoesBoundedWork() {
        // A long cantilever whose unbudgeted settle collapses dozens of blocks in one call.
        // Under an always-true pause, NO single pass may collapse more than one overload
        // batch worth of blocks — the deterministic, timing-free surrogate for "one pass
        // stays within the wall-clock budget". (A batch is all tips at the farthest level;
        // this 1-wide arm has one tip per level, so the bound is a single collapse per pass.)
        StructureGraph g = cantilever(40);
        CascadeEngine engine = new CascadeEngine(uncapped());
        Set<NodePos> next = new HashSet<>(g.getAllPositions());
        int totalCollapsed = 0;
        int passes = 0;
        while (true) {
            CascadeEngine.SettleOutcome outcome = engine.settleResult(g, next, SolverCallback.NONE, ALWAYS_PAUSE);
            assertTrue(outcome.collapsed().size() <= 1, "an always-paused pass must not collapse a whole cascade");
            totalCollapsed += outcome.collapsed().size();
            assertTrue(++passes < 100_000, "must converge");
            if (!outcome.truncated()) {
                break;
            }
            next = new HashSet<>(outcome.remainingScope());
        }
        assertTrue(totalCollapsed > 5, "sanity: the cascade really did drop many blocks, one bounded pass at a time");
    }

    @Test
    @DisplayName("The level scan pauses AFTER the floating unit when finite levels still remain")
    void batchScanPausesAfterFloatingUnitWithLevelsRemaining() {
        // A subgraph that has BOTH a floating block (distance MAX_VALUE → the floating
        // bucket) and grounded finite levels. Under an always-true pause, one findOverloaded-
        // Batch must finish the floating unit and then pause before the first finite level,
        // parking the scan — the branch that keeps the floating unit atomic yet interruptible.
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), TestMaterials.HEAVY, false);
        g.addBlock(new NodePos(1, 1, 0), TestMaterials.LIGHT, false); // leaner → finite level
        g.addBlock(new NodePos(10, 20, 0), TestMaterials.LIGHT, false); // stranded → floating bucket
        Set<NodePos> scope = new HashSet<>(g.getAllPositions());
        StressSolver solver = new StressSolver(uncapped());

        StressSolver.BatchScanCursor cursor = new StressSolver.BatchScanCursor();
        List<NodePos> first = solver.findOverloadedBatch(g, scope, null, ALWAYS_PAUSE, cursor);

        assertTrue(first.isEmpty(), "the floating block does not overload");
        assertFalse(cursor.isComplete(), "with finite levels still to score, the scan pauses after the floating unit");

        int guard = 0;
        while (!cursor.isComplete()) {
            solver.findOverloadedBatch(g, scope, null, ALWAYS_PAUSE, cursor);
            assertTrue(++guard < 1000, "the resumed scan must converge");
        }
        assertTrue(cursor.isComplete(), "resuming to completion proves the stable graph stable");
    }

    @Test
    @DisplayName("AC#5 legacy: budget 0 runs the whole settle in one call, byte-identical to today")
    void zeroBudgetRunsWholeSettleInOnePass() {
        StructureGraph budgeted = cantilever(24);
        StructureGraph reference = cantilever(24);

        // A never-tripping supplier is the in-core equivalent of settle-budget-ms: 0.
        CascadeEngine.SettleOutcome oneCall =
                new CascadeEngine(uncapped()).settleResult(budgeted, SolverCallback.NONE, () -> false);
        List<NodePos> reference0 = unbudgetedCollapseOrder(reference);

        assertFalse(oneCall.truncated(), "an unbudgeted settle finishes in one call");
        List<NodePos> order = new ArrayList<>();
        for (CollapsedNode n : oneCall.collapsed()) {
            order.add(n.pos());
        }
        assertEquals(reference0, order, "budget 0 is byte-identical to the plain 3-arg settle");
    }
}
