package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.api.CollapseReason;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import dev.gesp.structural.solver.StressSolver;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pacing contract for the settle budget: a pause changes only WHEN blocks fall,
 * never WHICH blocks fall or in what ORDER. This is the specific hazard from the
 * "budget-pause-mid-batch-changes-outcome" review.
 *
 * <p>{@code CascadeEngine.settleResult} collapses an overloaded batch member by
 * member. If the budget pauses mid-batch, the abandoned remainder must be finished
 * on resume EXACTLY as it stood — never re-derived by re-querying the solver against
 * the now-mutated graph (members removed, arms stranded, debris applied). Because the
 * adapter's pause is a wall-clock deadline, a resume that re-queried could let server
 * load change the physics outcome.
 *
 * <p>Two proofs live here:
 *
 * <ul>
 *   <li><b>Contract proof</b> ({@link #midBatchPauseFinishesTheParkedRemainder}) — a
 *       spy solver makes the resume's re-query return a DIFFERENT set than the
 *       abandoned remainder (empty: "stable"). A correct engine finishes the parked
 *       remainder anyway, so the paused run ends at the same graph as the unpaused
 *       one. Without the parked-remainder replay this is RED (the remainder is
 *       dropped). This is the deterministic stand-in for the wall-clock hazard.
 *   <li><b>Adversarial-pause proofs</b> ({@link #everyPausePositionIsOutcomeIdentical},
 *       {@link #alwaysPauseOnMultiMemberBatchIsOutcomeIdentical}) — on a REAL
 *       multi-member overload batch, pausing at every possible position (and pausing
 *       after every single collapse) reproduces the unbudgeted collapse byte-for-byte,
 *       order included.
 * </ul>
 */
@DisplayName("Budget pause is batch-atomic: pacing only, never a different outcome")
class BudgetPauseBatchAtomicityTest {

    private static PhysicsConfig uncapped() {
        PhysicsConfig c = new PhysicsConfig();
        c.setMaxCascadeSteps(1_000_000);
        // Debris ON: collapse order feeds impact damage, so an order change is an
        // outcome change — the strictest setting for the pacing contract.
        c.setDebrisImpactScale(2.0);
        c.setMinImpactDrop(1);
        return c;
    }

    /**
     * Two symmetric arms off one pillar: both far tips sit at the SAME distance level,
     * so the solver returns them as one multi-member overloaded batch — the shape a
     * mid-batch pause can split. Mirrors {@code SettleBudgetPauseTest.stepCapCutsMidBatch}.
     */
    private static StructureGraph twinArms() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), TestMaterials.HEAVY, false);
        g.addBlock(new NodePos(0, 2, 0), TestMaterials.HEAVY, false);
        for (int x = 1; x <= 8; x++) {
            g.addBlock(new NodePos(x, 2, 0), TestMaterials.LIGHT, false);
            g.addBlock(new NodePos(-x, 2, 0), TestMaterials.LIGHT, false);
        }
        return g;
    }

    private static List<NodePos> order(List<CollapsedNode> collapsed) {
        List<NodePos> o = new ArrayList<>();
        for (CollapsedNode c : collapsed) {
            o.add(c.pos());
        }
        return o;
    }

    // ─────────────────────────── contract proof (spy) ───────────────────────────

    /**
     * A solver that reports the genuine overloaded batch on its FIRST completed scan
     * and "stable" (empty) on every later completed scan. That models the review's
     * hazard deterministically: after the first collapse the resume re-queries the
     * mutated graph and gets a DIFFERENT answer (here, nothing) than the abandoned
     * remainder. A paused scan (cursor not complete) is passed through untouched so
     * the engine's own cross-tick scan resume still works.
     */
    private static final class FirstScanOnlySpy extends StressSolver {
        private int completedScans = 0;

        FirstScanOnlySpy(PhysicsConfig config) {
            super(config);
        }

        @Override
        public List<NodePos> findOverloadedBatch(
                StructureGraph graph,
                Set<NodePos> subgraph,
                Object2IntOpenHashMap<NodePos> precomputedDistances,
                BooleanSupplier pause,
                BatchScanCursor cursor) {
            List<NodePos> real = super.findOverloadedBatch(graph, subgraph, precomputedDistances, pause, cursor);
            if (!cursor.isComplete()) {
                return real; // mid-proof pause, not a verdict — leave it alone
            }
            completedScans++;
            return completedScans == 1 ? real : List.of();
        }
    }

    /** Counts overloaded collapses so a pause can trip right after the first batch member. */
    private static final class OverloadCounter implements SolverCallback {
        int overloaded = 0;

        @Override
        public void onCascadeStep(CollapsedNode node, int stepNumber, CollapseReason reason) {
            if (reason == CollapseReason.OVERLOADED) {
                overloaded++;
            }
        }

        @Override
        public void onStressUpdated(Map<NodePos, Double> stressMap) {}

        @Override
        public void onCascadeComplete(List<CollapsedNode> allCollapsed) {}
    }

    @Test
    @DisplayName("A mid-batch pause finishes the PARKED remainder, not a re-query of the mutated graph")
    void midBatchPauseFinishesTheParkedRemainder() {
        PhysicsConfig config = uncapped();

        // Reference: the same spy, run to completion with NO pause. The spy's first
        // scan yields the real multi-member batch; the engine collapses the whole
        // batch, then the spy reports stable. This is the outcome the budget must not
        // change.
        StructureGraph reference = twinArms();
        List<CollapsedNode> unpaused = new CascadeEngine(new FirstScanOnlySpy(config), config)
                .settleResult(reference, new HashSet<>(reference.getAllPositions()), SolverCallback.NONE)
                .collapsed();
        Set<NodePos> referenceSurvivors = new HashSet<>(reference.getAllPositions());
        assertTrue(unpaused.size() >= 2, "sanity: the first scan really is a multi-member batch");

        // Budgeted: pause right after the FIRST overloaded collapse (mid-batch). The
        // counter is reset before every pass so the pause only trips after a collapse,
        // never during the scan itself.
        StructureGraph budgeted = twinArms();
        CascadeEngine engine = new CascadeEngine(new FirstScanOnlySpy(config), config);
        OverloadCounter counter = new OverloadCounter();
        BooleanSupplier pauseAfterFirstCollapse = () -> counter.overloaded >= 1;

        counter.overloaded = 0;
        CascadeEngine.SettleOutcome out = engine.settleResult(
                budgeted, new HashSet<>(budgeted.getAllPositions()), counter, pauseAfterFirstCollapse);
        assertTrue(out.truncated(), "the mid-batch pause must surface as truncation");
        int passes = 0;
        while (out.truncated()) {
            assertTrue(++passes < 1000, "must converge");
            counter.overloaded = 0; // reset so the scan on the resume runs un-paused
            out = engine.settleResult(budgeted, new HashSet<>(out.remainingScope()), counter, pauseAfterFirstCollapse);
        }

        // The parked remainder (the batch's second member) MUST still have collapsed —
        // even though the spy's resume scan reported "stable". Without the fix the
        // resume re-queries, believes the graph stable, and strands that block.
        assertEquals(
                referenceSurvivors,
                new HashSet<>(budgeted.getAllPositions()),
                "a mid-batch budget pause dropped a batch member: the remainder was re-queried, not replayed");
    }

    // ─────────────────────── adversarial physical pauses ────────────────────────

    /** The unbudgeted collapse order over the whole graph — the ground truth. */
    private static List<NodePos> unbudgetedOrder(StructureGraph g, PhysicsConfig config) {
        return order(new CascadeEngine(config)
                .settleResult(g, new HashSet<>(g.getAllPositions()), SolverCallback.NONE)
                .collapsed());
    }

    /**
     * Drive a settle to a fixpoint under {@code pauseFactory} (a fresh supplier per
     * run), concatenating every collapse in order.
     */
    private static List<NodePos> pausedOrder(StructureGraph g, PhysicsConfig config, BooleanSupplier pause) {
        CascadeEngine engine = new CascadeEngine(config);
        List<NodePos> collapsed = new ArrayList<>();
        CascadeEngine.SettleOutcome out =
                engine.settleResult(g, new HashSet<>(g.getAllPositions()), SolverCallback.NONE, pause);
        collapsed.addAll(order(out.collapsed()));
        int passes = 0;
        while (out.truncated()) {
            assertTrue(++passes < 100_000, "a paused settle must converge");
            out = engine.settleResult(g, new HashSet<>(out.remainingScope()), SolverCallback.NONE, pause);
            collapsed.addAll(order(out.collapsed()));
        }
        return collapsed;
    }

    @Test
    @DisplayName("Pausing at EVERY possible position reproduces the unbudgeted collapse, order included")
    void everyPausePositionIsOutcomeIdentical() {
        PhysicsConfig config = uncapped();
        List<NodePos> reference = unbudgetedOrder(twinArms(), config);
        assertTrue(reference.size() >= 4, "sanity: the twin arm cascades several blocks over multiple batches");

        // Probe pausing at global poll k = 1..80 (comfortably past the total poll count
        // of the whole cascade): the budget trips exactly once, at k, then never again,
        // so exactly one pause lands at each reachable position — including every
        // mid-batch position. Each must reproduce the unbudgeted collapse byte-for-byte.
        for (int k = 1; k <= 80; k++) {
            int trip = k;
            int[] poll = {0};
            BooleanSupplier pauseAtK = () -> ++poll[0] == trip;
            List<NodePos> got = pausedOrder(twinArms(), config, pauseAtK);
            assertEquals(reference, got, "pausing at poll position " + k + " changed the collapse");
        }
    }

    @Test
    @DisplayName("Pausing after EVERY collapse (always-true budget) reproduces the unbudgeted collapse")
    void alwaysPauseOnMultiMemberBatchIsOutcomeIdentical() {
        PhysicsConfig config = uncapped();
        List<NodePos> reference = unbudgetedOrder(twinArms(), config);

        // The maximal-fragmentation case: pause after every single unit of work, so a
        // multi-member batch is split at every member and its remainder is parked and
        // replayed each pass. The final collapse list must still match, order included.
        List<NodePos> got = pausedOrder(twinArms(), config, () -> true);

        assertEquals(reference, got, "always-pausing changed the collapse of a multi-member batch");
    }
}
