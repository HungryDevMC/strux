package dev.gesp.structural.blast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.metrics.StruxMetrics;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The single guarantee of the tick-chunking slice: a blast solved in ONE atomic
 * {@code process()} call must be byte-for-byte equivalent to the SAME blast solved
 * by resuming a {@link StruxExplosionEngine.BlastSession} a few scan-steps at a
 * time. The chunked path simply pauses the phase-1 sphere scan between ticks; the
 * graph is never mutated mid-scan, so deferred removal + settle run identically no
 * matter how the scan was sliced.
 *
 * <p>"Equivalent" means: identical {@code destroyed}/{@code collapsed} ORDERED
 * lists, identical {@code damaged} map, identical surviving graph (positions +
 * per-node {@code damage()}), and identical {@link StruxMetrics} work-counts.
 */
@DisplayName("Blast tick-chunking: atomic process() == chunked begin()+advance(b)")
class BlastSessionEquivalenceTest {

    /** Budgets to slice the scan at. 1 = one candidate per advance (worst case). */
    private static final int[] BUDGETS = {1, 3, 7, 50};

    // ─────────────────────────────────────────────────────────────────────
    //  (2) Atomic-vs-chunked equivalence — many randomized structures/blasts
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Randomized structures + blasts: chunked result == atomic result for every budget")
    void chunkedEqualsAtomicAcrossRandomStructuresAndBudgets() {
        long seed = 0xB1A57L; // pinned; printed on failure below
        Random rng = new Random(seed);
        for (int trial = 0; trial < 60; trial++) {
            long trialSeed = rng.nextLong();
            try {
                runOneEquivalenceTrial(trialSeed);
            } catch (AssertionError | RuntimeException e) {
                throw new AssertionError(
                        "FAILING SEED (re-run with this): outer=" + seed + " trial=" + trial + " trialSeed="
                                + trialSeed,
                        e);
            }
        }
    }

    private static void runOneEquivalenceTrial(long trialSeed) {
        Random rng = new Random(trialSeed);

        // A fresh blueprint each trial; rebuilt per run so each engine mutates its own copy.
        Blueprint bp = randomBlueprint(rng);
        BlastContext ctx = randomBlast(rng, bp);

        Run atomic = runAtomic(bp, ctx);
        for (int budget : BUDGETS) {
            Run chunked = runChunked(bp, ctx, budget);
            assertEquals(
                    atomic.destroyed, chunked.destroyed, "destroyed list (ORDER included) differs at budget=" + budget);
            assertEquals(
                    atomic.collapsed, chunked.collapsed, "collapsed list (ORDER included) differs at budget=" + budget);
            assertEquals(atomic.damaged, chunked.damaged, "damaged map differs at budget=" + budget);
            assertEquals(
                    atomic.survivors,
                    chunked.survivors,
                    "surviving graph (positions + per-node damage) differs at budget=" + budget);

            // External-overload-query protocol (queries answered from copySubgraph
            // snapshots, as a worker thread would): same physics, byte-identical.
            // Metrics are deliberately NOT compared — the snapshot solver is
            // unmetered, so external runs under-count solver passes.
            Run external = runExternal(bp, ctx, budget);
            assertEquals(atomic.destroyed, external.destroyed, "external destroyed differs at budget=" + budget);
            assertEquals(atomic.collapsed, external.collapsed, "external collapsed differs at budget=" + budget);
            assertEquals(atomic.damaged, external.damaged, "external damaged map differs at budget=" + budget);
            assertEquals(atomic.survivors, external.survivors, "external surviving graph differs at budget=" + budget);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  (3) Metrics equivalence
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Metrics: chunked reports identical solveInvocations / nodeVisits / blocksRemoved")
    void metricsAreBudgetIndependent() {
        long seed = 0x5EED5L;
        Random rng = new Random(seed);
        for (int trial = 0; trial < 30; trial++) {
            long trialSeed = rng.nextLong();
            try {
                Random tr = new Random(trialSeed);
                Blueprint bp = randomBlueprint(tr);
                BlastContext ctx = randomBlast(tr, bp);

                Run atomic = runAtomic(bp, ctx);
                for (int budget : BUDGETS) {
                    Run chunked = runChunked(bp, ctx, budget);
                    assertEquals(
                            atomic.solveInvocations,
                            chunked.solveInvocations,
                            "solveInvocations differ at budget=" + budget);
                    assertEquals(atomic.nodeVisits, chunked.nodeVisits, "nodeVisits differ at budget=" + budget);
                    assertEquals(
                            atomic.blocksRemoved, chunked.blocksRemoved, "blocksRemoved differ at budget=" + budget);
                }
            } catch (AssertionError | RuntimeException e) {
                throw new AssertionError(
                        "FAILING SEED: outer=" + seed + " trial=" + trial + " trialSeed=" + trialSeed, e);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  (4) Occlusion integrity — RAYCAST cover must read the pristine graph
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Occlusion RAYCAST: deferred removal keeps cover reading the pristine graph (chunked == atomic)")
    void occlusionRaycastIsBudgetIndependent() {
        // A solid 5×5×9 tower; blast centred ABOVE it so the ray to lower blocks
        // passes through upper blocks (real cover). With per-candidate chunking the
        // upper blocks must still be present in the graph when lower candidates are
        // scanned — that is the whole order-freedom guarantee.
        MaterialSpec stone = new MaterialSpec(3.0, 100.0, 1.0);
        Blueprint bp = () -> {
            StructureGraph g = new StructureGraph();
            for (int x = 0; x < 5; x++) {
                for (int z = 0; z < 5; z++) {
                    g.addGroundBlock(new NodePos(x, 0, z));
                }
            }
            for (int y = 1; y <= 9; y++) {
                for (int x = 0; x < 5; x++) {
                    for (int z = 0; z < 5; z++) {
                        g.addBlock(new NodePos(x, y, z), stone, false);
                    }
                }
            }
            return g;
        };
        BlastContext ctx = BlastContext.builder()
                .center(new NodePos(2, 12, 2))
                .power(8.0)
                .occlusion(BlastOcclusion.RAYCAST)
                .build();

        Run atomic = runAtomic(bp, ctx);
        assertFalse(atomic.destroyed.isEmpty(), "the occlusion scenario must actually crater something");
        for (int budget : BUDGETS) {
            Run chunked = runChunked(bp, ctx, budget);
            assertEquals(atomic.destroyed, chunked.destroyed, "occlusion destroyed differs at budget=" + budget);
            assertEquals(atomic.damaged, chunked.damaged, "occlusion damaged differs at budget=" + budget);
            assertEquals(atomic.survivors, chunked.survivors, "occlusion survivors differ at budget=" + budget);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  (1d) Scan split ON a destroyed node — same destroyed ORDER at budget 1
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Pausing the scan mid-cube (budget=1) appends destroyed in the same cube order as budget=MAX")
    void destroyedOrderIsStableWhenScanIsSplit() {
        MaterialSpec fragile = new MaterialSpec(3.0, 1000.0, 0.2); // low blast resistance → big crater
        Blueprint bp = () -> {
            StructureGraph g = new StructureGraph();
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    g.addGroundBlock(new NodePos(x, 0, z));
                    for (int y = 1; y <= 5; y++) {
                        g.addBlock(new NodePos(x, y, z), fragile, false);
                    }
                }
            }
            return g;
        };
        BlastContext ctx = BlastContext.builder()
                .center(new NodePos(0, 3, 0))
                .power(6.0)
                .occlusion(BlastOcclusion.NONE)
                .build();

        List<NodePos> atomicOrder = runAtomic(bp, ctx).destroyed;
        List<NodePos> oneAtATime = runChunked(bp, ctx, 1).destroyed;

        assertTrue(atomicOrder.size() > 5, "this scenario must destroy several blocks to be meaningful");
        // Exact list equality already pins order; assert it as the headline of this test.
        assertEquals(atomicOrder, oneAtATime, "destroyed cube-scan order must not depend on where the scan paused");
    }

    @Test
    @DisplayName("process() is exactly begin()+advance(MAX): a session driven to done yields process()'s result")
    void processIsBeginThenAdvanceToInfinity() {
        MaterialSpec stone = new MaterialSpec(3.0, 100.0, 1.0);
        Blueprint bp = () -> Build.tower(stone, 4, 4, 8);
        BlastContext ctx = BlastContext.builder()
                .center(new NodePos(1, 8, 1))
                .power(5.0)
                .occlusion(BlastOcclusion.RAYCAST)
                .build();

        // Drive a session with advance(MAX) — exactly what process() does. At an
        // infinite budget the scan exhausts the cube in the first call (returning
        // true so the settle can run next), then the settle finishes (returning
        // false). This is the "atomic path IS the chunked path" contract.
        StructureGraph g = bp.build();
        StruxExplosionEngine engine = new StruxExplosionEngine(new PhysicsConfig());
        StruxExplosionEngine.BlastSession session = engine.begin(g, ctx, BlastCallback.NONE);
        int calls = 0;
        while (session.advance(Integer.MAX_VALUE)) {
            assertTrue(++calls <= 2, "advance(MAX) must finish in at most two infinite-budget steps (scan, settle)");
        }
        assertTrue(session.isDone(), "the session must be done once advance(MAX) returns false");
        BlastResult chunkedResult = session.result();

        Run atomic = runAtomic(bp, ctx);
        assertEquals(atomic.destroyed, chunkedResult.destroyed());
        assertEquals(atomic.collapsed, chunkedResult.collapsed());
        assertEquals(atomic.damaged, new HashMap<>(chunkedResult.damaged()));
    }

    // ─────────────────────────────────────────────────────────────────────
    //  (slice 2) SETTLE is resumable: a big collapse spreads over advances too
    // ─────────────────────────────────────────────────────────────────────

    /**
     * A tall, slender tower keyed off the ground at one base block. Blowing that
     * base out makes the entire shaft above float, so the SETTLE — not the scan —
     * drops the bulk of the blocks. The blast cube is tiny (low power, small
     * radius) but the floating collapse is large, which is exactly the case slice
     * 2 must chunk.
     */
    private static Blueprint slenderFloatTower(int height) {
        MaterialSpec stone = new MaterialSpec(3.0, 100.0, 0.25); // low blast resistance → base craters
        return () -> {
            StructureGraph g = new StructureGraph();
            g.addGroundBlock(new NodePos(0, 0, 0));
            for (int y = 1; y <= height; y++) {
                g.addBlock(new NodePos(0, y, 0), stone, false);
            }
            return g;
        };
    }

    @Test
    @DisplayName("Resumable settle: a big floating collapse reports more work across MANY advances at budget=1")
    void settleSpansMultipleAdvancesAtSmallBudget() {
        int height = 40;
        Blueprint bp = slenderFloatTower(height);
        BlastContext ctx = BlastContext.builder()
                .center(new NodePos(0, 1, 0))
                .power(0.5) // radius ≈ 0.75 → a single-block scan cube
                .occlusion(BlastOcclusion.NONE)
                .build();

        // Reference: the whole blast at once. It must collapse far more blocks than
        // a single advance could at budget=1, otherwise the test proves nothing.
        Run atomic = runAtomic(bp, ctx);
        int dropped = atomic.destroyed.size() + atomic.collapsed.size();
        assertTrue(dropped > 20, "scenario must drop a big batch in the settle (was " + dropped + ")");

        // Drive at budget=1: count the advances that ran AFTER the scan finished —
        // i.e. the settle's own advances. A still-atomic settle does the whole thing
        // in the single scan-completing advance, so this count would be ~0/1.
        StructureGraph g = bp.build();
        StruxExplosionEngine engine = new StruxExplosionEngine(new PhysicsConfig());
        StruxExplosionEngine.BlastSession session = engine.begin(g, ctx, BlastCallback.NONE);
        int settleAdvances = 0;
        int guard = 0;
        boolean wasScanComplete = false;
        while (true) {
            boolean scanWasCompleteBefore = session.isScanComplete();
            boolean more = session.advance(1);
            if (scanWasCompleteBefore) {
                settleAdvances++;
            }
            wasScanComplete |= session.isScanComplete();
            if (!more) {
                break;
            }
            if (++guard > 10_000_000) {
                throw new IllegalStateException("session never finished at budget=1");
            }
        }
        assertTrue(wasScanComplete, "the scan must complete before the settle starts");
        assertTrue(
                settleAdvances > 5,
                "the settle must span several advances at budget=1 (settle advances=" + settleAdvances + ")");
    }

    @Test
    @DisplayName("Settle-split determinism: budget=1 through the settle gives the same collapsed ORDER as budget=MAX")
    void settleCollapseOrderIsBudgetIndependent() {
        // A 3×3 tower on a single ground stub: blowing the fragile bottom storeys out
        // orphans the whole upper shaft, so phase-2 floating collapses a sizeable,
        // ordered batch. budget=1 forces the settle to pause between (and within)
        // batches; the canonical collapse order must survive.
        MaterialSpec fragile = new MaterialSpec(3.0, 200.0, 0.2);
        Blueprint bp = () -> {
            StructureGraph g = new StructureGraph();
            g.addGroundBlock(new NodePos(0, 0, 0)); // a single foot
            for (int y = 1; y <= 6; y++) {
                for (int x = -1; x <= 1; x++) {
                    for (int z = -1; z <= 1; z++) {
                        g.addBlock(new NodePos(x, y, z), fragile, false);
                    }
                }
            }
            return g;
        };
        BlastContext ctx = BlastContext.builder()
                .center(new NodePos(0, 1, 0))
                .power(2.0)
                .occlusion(BlastOcclusion.NONE)
                .build();

        Run atomic = runAtomic(bp, ctx);
        assertTrue(atomic.collapsed.size() > 3, "the settle must collapse several blocks to be meaningful");

        Run oneAtATime = runChunked(bp, ctx, 1);
        assertEquals(
                atomic.collapsed,
                oneAtATime.collapsed,
                "settle collapse ORDER must not depend on where the settle paused");
        assertEquals(atomic.destroyed, oneAtATime.destroyed, "destroyed ORDER must be settle-budget independent");
        assertEquals(atomic.damaged, oneAtATime.damaged, "damaged map must be settle-budget independent");
        assertEquals(atomic.survivors, oneAtATime.survivors, "surviving graph must be settle-budget independent");
    }

    @Test
    @DisplayName("Randomized BIG-collapse structures: chunked settle == atomic for every budget (seed on failure)")
    void chunkedSettleEqualsAtomicForBigCollapses() {
        long seed = 0xC0115EL; // pinned; printed on failure
        Random rng = new Random(seed);
        int trialsWithBigCollapse = 0;
        for (int trial = 0; trial < 80; trial++) {
            long trialSeed = rng.nextLong();
            try {
                Random tr = new Random(trialSeed);
                // A taller tower than the base property test so blasts tend to orphan
                // big floating regions — exercising the resumable settle, not just scan.
                int side = 3 + tr.nextInt(3); // 3..5
                int h = 6 + tr.nextInt(6); // 6..11
                Blueprint bp = tallTower(tr.nextLong(), side, h);
                BlastContext ctx = randomBlast(tr, bp);

                Run atomic = runAtomic(bp, ctx);
                if (atomic.collapsed.size() >= 3) {
                    trialsWithBigCollapse++;
                }
                for (int budget : BUDGETS) {
                    Run chunked = runChunked(bp, ctx, budget);
                    assertEquals(atomic.destroyed, chunked.destroyed, "destroyed differs at budget=" + budget);
                    assertEquals(atomic.collapsed, chunked.collapsed, "collapsed differs at budget=" + budget);
                    assertEquals(atomic.damaged, chunked.damaged, "damaged differs at budget=" + budget);
                    assertEquals(atomic.survivors, chunked.survivors, "survivors differ at budget=" + budget);
                    assertEquals(
                            atomic.solveInvocations,
                            chunked.solveInvocations,
                            "solveInvocations differ at budget=" + budget);
                    assertEquals(atomic.nodeVisits, chunked.nodeVisits, "nodeVisits differ at budget=" + budget);
                    assertEquals(
                            atomic.blocksRemoved, chunked.blocksRemoved, "blocksRemoved differ at budget=" + budget);
                }
            } catch (AssertionError | RuntimeException e) {
                throw new AssertionError(
                        "FAILING SEED: outer=" + seed + " trial=" + trial + " trialSeed=" + trialSeed, e);
            }
        }
        assertTrue(
                trialsWithBigCollapse >= 10,
                "the suite must include trials with multi-block settle collapses (had " + trialsWithBigCollapse + ")");
    }

    /** A tower with a hole-pocked upper structure so blasts orphan floating regions. */
    private static Blueprint tallTower(long structureSeed, int side, int height) {
        return () -> {
            Random rng = new Random(structureSeed);
            StructureGraph g = new StructureGraph();
            for (int x = 0; x < side; x++) {
                for (int z = 0; z < side; z++) {
                    g.addGroundBlock(new NodePos(x, 0, z));
                }
            }
            for (int y = 1; y <= height; y++) {
                for (int x = 0; x < side; x++) {
                    for (int z = 0; z < side; z++) {
                        if (rng.nextInt(6) == 0) {
                            continue; // a hole, so the topology has narrow load paths
                        }
                        double resistance = 0.3 + rng.nextDouble() * 2.0;
                        g.addBlock(new NodePos(x, y, z), new MaterialSpec(3.0, 45.0, resistance), false);
                    }
                }
            }
            return g;
        };
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HARNESS
    // ─────────────────────────────────────────────────────────────────────

    /** A reproducible structure factory: rebuilds a fresh graph for each run. */
    private interface Blueprint {
        StructureGraph build();
    }

    /** The captured outcome of one engine run, in fully comparable form. */
    private record Run(
            List<NodePos> destroyed,
            List<NodePos> collapsed,
            Map<NodePos, Double> damaged,
            Map<NodePos, Double> survivors,
            long solveInvocations,
            long nodeVisits,
            long blocksRemoved) {}

    private static Run runAtomic(Blueprint bp, BlastContext ctx) {
        StructureGraph g = bp.build();
        StruxMetrics m = new StruxMetrics();
        StruxExplosionEngine engine = new StruxExplosionEngine(new PhysicsConfig()).setMetrics(m);
        BlastResult r = engine.process(g, ctx);
        return capture(g, r, m);
    }

    private static Run runChunked(Blueprint bp, BlastContext ctx, int budget) {
        StructureGraph g = bp.build();
        StruxMetrics m = new StruxMetrics();
        StruxExplosionEngine engine = new StruxExplosionEngine(new PhysicsConfig()).setMetrics(m);
        StruxExplosionEngine.BlastSession session = engine.begin(g, ctx, BlastCallback.NONE);
        // Drain the session a fixed number of scan steps at a time, exactly as the
        // adapter would across ticks. A bounded loop guards against a stuck session.
        int guard = 0;
        while (session.advance(budget)) {
            if (++guard > 10_000_000) {
                throw new IllegalStateException("session never finished — likely advance() returns true forever");
            }
        }
        return capture(g, session.result(), m);
    }

    /**
     * Drive the EXTERNAL-overload-query protocol exactly as the adapter does:
     * advance until the session parks, answer the parked query from a
     * {@code copySubgraph} snapshot (the off-thread recipe, run inline here so
     * the test stays deterministic), supply it back, continue.
     */
    private static Run runExternal(Blueprint bp, BlastContext ctx, int budget) {
        StructureGraph g = bp.build();
        StruxMetrics m = new StruxMetrics();
        StruxExplosionEngine engine = new StruxExplosionEngine(new PhysicsConfig()).setMetrics(m);
        StruxExplosionEngine.BlastSession session = engine.beginWithExternalOverloadQueries(g, ctx, BlastCallback.NONE);
        int guard = 0;
        while (session.advance(budget)) {
            Set<NodePos> q = session.pendingOverloadQuery();
            if (q != null) {
                StructureGraph snapshot = g.copySubgraph(q);
                session.supplyOverloadBatch(engine.computeOverloadBatch(snapshot, q));
            }
            if (++guard > 10_000_000) {
                throw new IllegalStateException("external session never finished");
            }
        }
        return capture(g, session.result(), m);
    }

    private static Run capture(StructureGraph g, BlastResult r, StruxMetrics m) {
        // Surviving graph: position → damage. Map equality is order-independent, so
        // a plain HashMap compares correctly regardless of insertion order.
        Map<NodePos, Double> survivors = new HashMap<>();
        for (Node node : g.getAllNodes()) {
            if (!node.isGrounded()) {
                survivors.put(node.pos(), node.damage());
            }
        }
        return new Run(
                new ArrayList<>(r.destroyed()),
                new ArrayList<>(r.collapsed()),
                new HashMap<>(r.damaged()),
                survivors,
                m.solveInvocations,
                m.nodeVisits,
                m.blocksRemoved);
    }

    // ── random builders ───────────────────────────────────────────────────

    private static Blueprint randomBlueprint(Random seedSrc) {
        long s = seedSrc.nextLong();
        return () -> {
            Random rng = new Random(s);
            int w = 3 + rng.nextInt(5); // 3..7
            int d = 3 + rng.nextInt(5);
            int h = 2 + rng.nextInt(6); // 2..7
            StructureGraph g = new StructureGraph();
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    g.addGroundBlock(new NodePos(x, 0, z));
                }
            }
            for (int y = 1; y <= h; y++) {
                for (int x = 0; x < w; x++) {
                    for (int z = 0; z < d; z++) {
                        double resistance = 0.3 + rng.nextDouble() * 3.0; // 0.3..3.3
                        // Occasionally punch a hole so the structure has interesting topology.
                        if (rng.nextInt(7) == 0) {
                            continue;
                        }
                        g.addBlock(new NodePos(x, y, z), new MaterialSpec(3.0, 60.0, resistance), false);
                    }
                }
            }
            return g;
        };
    }

    private static BlastContext randomBlast(Random rng, Blueprint bp) {
        int cx = rng.nextInt(7);
        int cy = 1 + rng.nextInt(7);
        int cz = rng.nextInt(7);
        double power = 2.0 + rng.nextDouble() * 6.0; // 2..8
        BlastOcclusion occ = rng.nextBoolean() ? BlastOcclusion.RAYCAST : BlastOcclusion.NONE;
        return BlastContext.builder()
                .center(new NodePos(cx, cy, cz))
                .power(power)
                .occlusion(occ)
                .build();
    }

    /** Tiny structure helper local to this test. */
    private static final class Build {
        static StructureGraph tower(MaterialSpec spec, int w, int d, int h) {
            StructureGraph g = new StructureGraph();
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    g.addGroundBlock(new NodePos(x, 0, z));
                }
            }
            for (int y = 1; y <= h; y++) {
                for (int x = 0; x < w; x++) {
                    for (int z = 0; z < d; z++) {
                        g.addBlock(new NodePos(x, y, z), spec, false);
                    }
                }
            }
            return g;
        }
    }
}
