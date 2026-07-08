package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.api.CollapseReason;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.metrics.StruxMetrics;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import dev.gesp.structural.solver.CascadeEngine.SettleOutcome;
import dev.gesp.structural.solver.ParallelCascadeDriver;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@link ParallelCascadeDriver} must change only WHEN the work happens, never
 * WHAT the physics decides. Every test here pins the same invariant from a
 * different angle: a parallel settle of independent structures lands on exactly the
 * graph the serial {@link CascadeEngine} would, with the same collapsed set and the
 * same summed work-counts.
 */
@DisplayName("ParallelCascadeDriver: parallel settle == serial settle")
class ParallelCascadeDriverTest {

    private static final MaterialSpec HEAVY = new MaterialSpec(3.0, 100.0);
    private static final MaterialSpec LIGHT = new MaterialSpec(1.0, 6.0);
    // Strong enough to hold a heavy block at full strength, weak enough to overload
    // once heat-softened — the discriminating capacity for isolate()'s temperature copy.
    private static final MaterialSpec SOFTENABLE = new MaterialSpec(3.0, 10.0);

    // ─────────────────────────────────────────────────────────────────────
    //  CORE INVARIANT: parallel == serial
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Two independent towers collapse identically to the serial engine")
    void twoIndependentTowersMatchSerial() {
        PhysicsConfig cfg = new PhysicsConfig();
        List<NodePos> bases = List.of(new NodePos(0, 1, 0), new NodePos(50, 1, 0));

        StructureGraph serialGraph = twoTowers(6);
        SettleOutcome serial = new CascadeEngine(cfg)
                .settleResult(serialGraph, removeAndSeed(serialGraph, bases), SolverCallback.NONE);

        StructureGraph parallelGraph = twoTowers(6);
        SettleOutcome parallel = new ParallelCascadeDriver(cfg)
                .settle(parallelGraph, removeAndSeed(parallelGraph, bases), SolverCallback.NONE);

        // Both five-block stacks float and fall: ten blocks, same set, same graph.
        assertTrue(parallel.collapsed().size() >= 10, "both towers should fully collapse");
        assertEquals(asSet(serial.collapsed()), asSet(parallel.collapsed()), "collapsed set must match serial");
        assertSameGraph(serialGraph, parallelGraph);
    }

    @Test
    @DisplayName("Debris damage on SURVIVING blocks is transplanted back exactly")
    void debrisDamageOnSurvivorsMatchesSerial() {
        PhysicsConfig cfg = new PhysicsConfig();
        cfg.setDebrisImpactScale(0.01); // turn on debris so falling blocks damage what they land on
        cfg.setMinImpactDrop(1);
        // Knock out the MIDDLE of each five-tall stack: the top floats and falls onto
        // the surviving bottom block, damaging (but not destroying) it.
        List<NodePos> bases = List.of(new NodePos(0, 2, 0), new NodePos(50, 2, 0));

        StructureGraph serialGraph = twoTowers(5);
        SettleOutcome serial = new CascadeEngine(cfg)
                .settleResult(serialGraph, removeAndSeed(serialGraph, bases), SolverCallback.NONE);

        StructureGraph parallelGraph = twoTowers(5);
        SettleOutcome parallel = new ParallelCascadeDriver(cfg)
                .settle(parallelGraph, removeAndSeed(parallelGraph, bases), SolverCallback.NONE);

        // Sanity: the bottom survivor really did take debris damage (otherwise this
        // test would not be exercising the survivor-damage transplant at all).
        double survivorDamage = serialGraph.getNode(new NodePos(0, 1, 0)).damage();
        assertTrue(survivorDamage > 0.0, "the surviving bottom block should be debris-damaged");

        assertEquals(asSet(serial.collapsed()), asSet(parallel.collapsed()));
        assertSameGraph(serialGraph, parallelGraph); // compares survivor damage exactly
    }

    @Test
    @DisplayName("Two edge-disconnected structures in the same column are kept in one cluster")
    void structuresSharingAColumnStayTogether() {
        // A grounded stub at column (0,0), and a SEPARATE grounded structure whose
        // cantilever arm reaches over column (0,0) higher up (a gap between them, so
        // they are different components but share the (x,z) footprint). When the arm
        // falls its debris drops down column (0,0) onto the stub — an interaction the
        // driver must preserve by clustering the two together.
        PhysicsConfig cfg = new PhysicsConfig();
        cfg.setDebrisImpactScale(0.01);
        cfg.setMinImpactDrop(1);

        List<NodePos> bases = List.of(new NodePos(4, 1, 0)); // break the far pillar's base

        StructureGraph serialGraph = stubUnderCantilever();
        SettleOutcome serial = new CascadeEngine(cfg)
                .settleResult(serialGraph, removeAndSeed(serialGraph, bases), SolverCallback.NONE);

        StructureGraph parallelGraph = stubUnderCantilever();
        SettleOutcome parallel = new ParallelCascadeDriver(cfg)
                .settle(parallelGraph, removeAndSeed(parallelGraph, bases), SolverCallback.NONE);

        assertEquals(asSet(serial.collapsed()), asSet(parallel.collapsed()));
        assertSameGraph(serialGraph, parallelGraph);
    }

    @Test
    @DisplayName("Surviving blocks carry IDENTICAL solved stress to the serial engine")
    void survivorStressMatchesSerial() {
        // Knock out the MIDDLE of each five-tall stack: the top two blocks float away
        // and collapse, but the bottom two survive grounded — still carrying the fresh
        // vertical stress the solver computed for them. The serial engine leaves that
        // stress on the survivors; the multi-cluster parallel path must transplant it
        // back too, or a post-settle reader sees different numbers depending on the path.
        PhysicsConfig cfg = new PhysicsConfig();
        List<NodePos> bases = List.of(new NodePos(0, 3, 0), new NodePos(50, 3, 0));

        StructureGraph serialGraph = twoTowers(5);
        SettleOutcome serial = new CascadeEngine(cfg)
                .settleResult(serialGraph, removeAndSeed(serialGraph, bases), SolverCallback.NONE);

        StructureGraph parallelGraph = twoTowers(5);
        SettleOutcome parallel = new ParallelCascadeDriver(cfg)
                .settle(parallelGraph, removeAndSeed(parallelGraph, bases), SolverCallback.NONE);

        // Sanity: some survivor really carries non-zero solved stress, or this pin is
        // vacuous. The bottom block holds the one above it, so its vertical stress > 0.
        assertTrue(
                serialGraph.getNode(new NodePos(0, 1, 0)).stressValue() > 0.0,
                "a surviving block should carry non-zero solved stress");

        assertEquals(asSet(serial.collapsed()), asSet(parallel.collapsed()), "collapsed set must match serial");
        assertSameGraph(serialGraph, parallelGraph);
        assertSameStress(serialGraph, parallelGraph);
    }

    @Test
    @DisplayName("An EntityWeightTask-style stress read decides the SAME survivors on both paths")
    void entityWeightStressReadAgreesAcrossPaths() {
        // EntityWeightTask gates collapses on node.stressPercent()/stressValue() AFTER a
        // settle. Simulate that read: settle two independent structures, then find the
        // most-stressed survivor by stressPercent(). If the parallel merge drops solved
        // stress, its survivors read 0 (or stale) and this "which block is closest to
        // failing" decision diverges from serial — even though topology is identical.
        PhysicsConfig cfg = new PhysicsConfig();
        List<NodePos> bases = List.of(new NodePos(0, 3, 0), new NodePos(50, 3, 0));

        StructureGraph serialGraph = twoTowers(5);
        new CascadeEngine(cfg).settleResult(serialGraph, removeAndSeed(serialGraph, bases), SolverCallback.NONE);

        StructureGraph parallelGraph = twoTowers(5);
        new ParallelCascadeDriver(cfg).settle(parallelGraph, removeAndSeed(parallelGraph, bases), SolverCallback.NONE);

        NodePos serialHottest = mostStressedSurvivor(serialGraph);
        NodePos parallelHottest = mostStressedSurvivor(parallelGraph);
        assertEquals(
                serialHottest,
                parallelHottest,
                "the same survivor must read as most-stressed on both paths (EntityWeightTask parity)");
        assertEquals(
                serialGraph.getNode(serialHottest).stressPercent(),
                parallelGraph.getNode(parallelHottest).stressPercent(),
                0.0,
                "the winning survivor's stressPercent must be identical across paths");
    }

    @Test
    @DisplayName("A surviving cantilever arm keeps its non-zero MOMENT stress (not just vertical)")
    void survivingCantileverKeepsMomentStress() {
        // Each structure is a grounded pillar with a light one-block arm that SURVIVES
        // the settle carrying non-zero moment stress; removing the pillar top seeds the
        // cascade and forces two clusters. All-tower scenarios have momentStress == 0 on
        // every survivor, so only this pin can catch a merge that copies verticalStress
        // but drops the momentStress transplant.
        PhysicsConfig cfg = new PhysicsConfig();
        List<NodePos> tops = List.of(new NodePos(0, 3, 0), new NodePos(50, 3, 0));

        StructureGraph serialGraph = twoPillarsWithArms();
        SettleOutcome serial =
                new CascadeEngine(cfg).settleResult(serialGraph, removeAndSeed(serialGraph, tops), SolverCallback.NONE);

        StructureGraph parallelGraph = twoPillarsWithArms();
        SettleOutcome parallel = new ParallelCascadeDriver(cfg)
                .settle(parallelGraph, removeAndSeed(parallelGraph, tops), SolverCallback.NONE);

        // Sanity: the surviving pillar block supporting the arm carries non-zero MOMENT
        // stress in the serial run (moment is charged to the supporting anchor, not the
        // arm itself), or this test could not distinguish a dropped momentStress copy.
        assertTrue(
                serialGraph.getNode(new NodePos(0, 2, 0)).momentStress() > 0.0,
                "the surviving anchor should carry non-zero moment stress");

        assertEquals(asSet(serial.collapsed()), asSet(parallel.collapsed()), "collapsed set must match serial");
        assertSameGraph(serialGraph, parallelGraph);
        assertSameStress(serialGraph, parallelGraph);
    }

    @Test
    @DisplayName("A PRE-DAMAGED survivor's debris damage syncs to the worker's absolute value")
    void preDamagedSurvivorDamageMatchesSerial() {
        // The merge syncs damage with repair() + addDamage(absolute). If the repair()
        // is dropped, the worker's absolute value is ADDED on top of the survivor's
        // pre-existing damage instead of replacing it — invisible when survivors start
        // pristine (every other debris test), so pre-damage the bottom block here.
        PhysicsConfig cfg = new PhysicsConfig();
        cfg.setDebrisImpactScale(0.01);
        cfg.setMinImpactDrop(1);
        List<NodePos> bases = List.of(new NodePos(0, 2, 0), new NodePos(50, 2, 0));

        StructureGraph serialGraph = twoTowers(5);
        serialGraph.getNode(new NodePos(0, 1, 0)).addDamage(0.25);
        SettleOutcome serial = new CascadeEngine(cfg)
                .settleResult(serialGraph, removeAndSeed(serialGraph, bases), SolverCallback.NONE);

        StructureGraph parallelGraph = twoTowers(5);
        parallelGraph.getNode(new NodePos(0, 1, 0)).addDamage(0.25);
        SettleOutcome parallel = new ParallelCascadeDriver(cfg)
                .settle(parallelGraph, removeAndSeed(parallelGraph, bases), SolverCallback.NONE);

        // Sanity: debris landed on the pre-damaged survivor (damage grew past 0.25 but
        // is not maxed, so an erroneous double-add is still observable below 1.0).
        double survivorDamage = serialGraph.getNode(new NodePos(0, 1, 0)).damage();
        assertTrue(survivorDamage > 0.25 && survivorDamage < 1.0, "debris should add damage without maxing it");

        assertEquals(asSet(serial.collapsed()), asSet(parallel.collapsed()));
        assertSameGraph(serialGraph, parallelGraph); // compares survivor damage exactly
    }

    // ─────────────────────────────────────────────────────────────────────
    //  DETERMINISM + THREADING INVARIANCE
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Parallel settle is deterministic: same collapsed order every run")
    void deterministicAcrossRuns() {
        PhysicsConfig cfg = new PhysicsConfig();
        List<NodePos> bases = List.of(new NodePos(0, 1, 0), new NodePos(50, 1, 0), new NodePos(100, 1, 0));

        List<NodePos> firstRun = null;
        for (int run = 0; run < 5; run++) {
            StructureGraph g = threeTowers(6);
            SettleOutcome out = new ParallelCascadeDriver(cfg).settle(g, removeAndSeed(g, bases), SolverCallback.NONE);
            List<NodePos> order = collapsedPositions(out);
            if (firstRun == null) {
                firstRun = order;
            } else {
                assertEquals(firstRun, order, "collapsed order must be identical across runs (run " + run + ")");
            }
        }
    }

    @Test
    @DisplayName("Running clusters inline vs on a thread pool gives identical results AND metrics")
    void directExecutorMatchesThreadPool() {
        PhysicsConfig cfg = new PhysicsConfig();
        List<NodePos> bases = List.of(new NodePos(0, 1, 0), new NodePos(50, 1, 0));

        StructureGraph directGraph = twoTowers(6);
        StruxMetrics directMetrics = new StruxMetrics();
        SettleOutcome direct = new ParallelCascadeDriver(cfg, Runnable::run)
                .setMetrics(directMetrics)
                .settle(directGraph, removeAndSeed(directGraph, bases), SolverCallback.NONE);

        StructureGraph poolGraph = twoTowers(6);
        StruxMetrics poolMetrics = new StruxMetrics();
        SettleOutcome pool = new ParallelCascadeDriver(cfg)
                .setMetrics(poolMetrics)
                .settle(poolGraph, removeAndSeed(poolGraph, bases), SolverCallback.NONE);

        assertEquals(collapsedPositions(direct), collapsedPositions(pool), "threading must not change the result");
        assertSameGraph(directGraph, poolGraph);
        assertEquals(directMetrics.solveInvocations, poolMetrics.solveInvocations);
        assertEquals(directMetrics.nodeVisits, poolMetrics.nodeVisits);
        assertEquals(directMetrics.blocksRemoved, poolMetrics.blocksRemoved);
        assertTrue(poolMetrics.blocksRemoved > 0, "metrics should have been summed from the workers");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  SINGLE-CLUSTER DELEGATION + CALLBACKS
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A single structure delegates straight to the serial engine, counts included")
    void singleStructureDelegatesToSerialEngine() {
        PhysicsConfig cfg = new PhysicsConfig();
        List<NodePos> base = List.of(new NodePos(0, 1, 0));

        StructureGraph serialGraph = oneTower(6);
        StruxMetrics serialMetrics = new StruxMetrics();
        SettleOutcome serial = new CascadeEngine(cfg)
                .setMetrics(serialMetrics)
                .settleResult(serialGraph, removeAndSeed(serialGraph, base), SolverCallback.NONE);

        StructureGraph parallelGraph = oneTower(6);
        StruxMetrics parallelMetrics = new StruxMetrics();
        SettleOutcome parallel = new ParallelCascadeDriver(cfg)
                .setMetrics(parallelMetrics)
                .settle(parallelGraph, removeAndSeed(parallelGraph, base), SolverCallback.NONE);

        // Delegation means byte-for-byte the same path: identical collapsed list (order included).
        assertEquals(collapsedPositions(serial), collapsedPositions(parallel));
        assertSameGraph(serialGraph, parallelGraph);
        assertEquals(serialMetrics.solveInvocations, parallelMetrics.solveInvocations);
        assertEquals(serialMetrics.nodeVisits, parallelMetrics.nodeVisits);
        assertEquals(serialMetrics.blocksRemoved, parallelMetrics.blocksRemoved);
    }

    @Test
    @DisplayName("The merged result is reported through the callback")
    void firesCallbacksWithMergedResult() {
        PhysicsConfig cfg = new PhysicsConfig();
        List<NodePos> bases = List.of(new NodePos(0, 1, 0), new NodePos(50, 1, 0));

        StructureGraph g = twoTowers(6);
        RecordingCallback callback = new RecordingCallback();
        SettleOutcome out = new ParallelCascadeDriver(cfg).settle(g, removeAndSeed(g, bases), callback);

        // Every collapsed node was reported once as a step, then handed back whole at completion.
        assertEquals(out.collapsed().size(), callback.steps.size(), "one step per collapsed block");
        assertEquals(collapsedPositions(out), positions(callback.steps), "step order matches the result");
        assertEquals(collapsedPositions(out), positions(callback.completed), "complete carries the full list");
        // Steps are numbered 1..N in order.
        for (int i = 0; i < callback.stepNumbers.size(); i++) {
            assertEquals(i + 1, callback.stepNumbers.get(i), "steps renumbered globally");
        }
    }

    @Test
    @DisplayName("An UNSEEDED component sharing a column is honoured (debris): falls back to serial")
    void unseededStubSharingAColumnMatchesSerial() {
        // Two seeded structures (so the driver tries its multi-cluster path), plus a
        // THIRD grounded stub that no seed touches but that sits in the falling
        // cantilever's column. The serial engine rains the arm's debris onto the stub;
        // the parallel partition only ever discovered the seed components, so without
        // the column-completeness guard the stub would be absent from every cluster
        // copy and debris would fall through it — a divergence assertSameGraph catches.
        PhysicsConfig cfg = new PhysicsConfig();
        cfg.setDebrisImpactScale(0.01);
        cfg.setMinImpactDrop(1);

        List<NodePos> bases = List.of(new NodePos(4, 1, 0), new NodePos(50, 1, 0));

        StructureGraph serialGraph = stubUnderCantileverPlusFarTower();
        SettleOutcome serial = new CascadeEngine(cfg)
                .settleResult(serialGraph, removeAndSeed(serialGraph, bases), SolverCallback.NONE);

        StructureGraph parallelGraph = stubUnderCantileverPlusFarTower();
        SettleOutcome parallel = new ParallelCascadeDriver(cfg)
                .settle(parallelGraph, removeAndSeed(parallelGraph, bases), SolverCallback.NONE);

        // Sanity: the unseeded stub really did take debris damage in the serial run.
        double stubDamage = serialGraph.getNode(new NodePos(0, 2, 0)).damage();
        assertTrue(stubDamage > 0.0, "the unseeded stub should be debris-damaged in the serial engine");

        assertEquals(asSet(serial.collapsed()), asSet(parallel.collapsed()));
        assertSameGraph(serialGraph, parallelGraph);
    }

    @Test
    @DisplayName("The single-cluster path also fires onCascadeComplete (both paths agree)")
    void singleClusterFiresOnCascadeComplete() {
        PhysicsConfig cfg = new PhysicsConfig();
        List<NodePos> base = List.of(new NodePos(0, 1, 0));

        StructureGraph g = oneTower(6);
        RecordingCallback callback = new RecordingCallback();
        SettleOutcome out = new ParallelCascadeDriver(cfg).settle(g, removeAndSeed(g, base), callback);

        assertTrue(out.collapsed().size() > 0, "the single tower should collapse");
        assertEquals(
                collapsedPositions(out),
                positions(callback.completed),
                "the delegated single-cluster path must still fire onCascadeComplete with the full list");
    }

    @Test
    @DisplayName("A truncated multi-cluster settle reports a remainingScope covering every cut-short cluster")
    void truncatedMultiClusterUnionsRemainingScope() {
        // Two independent overloaded arms; a tiny step cap cuts each cluster's overload
        // trim short, so both report truncated with live work left. The merged outcome
        // must carry a remainingScope spanning BOTH clusters — otherwise a resume
        // following the contract (settle remainingScope until !truncated) would next
        // settle an empty scope and strand the half-collapsed structures forever.
        PhysicsConfig cfg = new PhysicsConfig();
        cfg.setMaxCascadeSteps(2);

        StructureGraph g = twoOverloadedArms();
        // Seed each arm's pillar top so the driver finds two components → two clusters.
        Set<NodePos> seeds = new HashSet<>(List.of(new NodePos(0, 2, 0), new NodePos(50, 2, 0)));
        SettleOutcome out = new ParallelCascadeDriver(cfg).settle(g, seeds, SolverCallback.NONE);

        assertTrue(out.truncated(), "the cap left both clusters mid-collapse");
        assertTrue(!out.remainingScope().isEmpty(), "a truncated settle must hand back a non-empty resume scope");
        boolean coversLeft = out.remainingScope().stream().anyMatch(p -> p.x() < 25);
        boolean coversRight = out.remainingScope().stream().anyMatch(p -> p.x() >= 25);
        assertTrue(
                coversLeft && coversRight,
                "remainingScope must union BOTH truncated clusters: " + out.remainingScope());
    }

    @Test
    @DisplayName("A thermally-softened block is simulated at its reduced strength in the parallel path")
    void thermallySoftenedBlockMatchesSerial() {
        // Structure A's mid block is heat-softened enough to overload and collapse;
        // structure B elsewhere forces the two-cluster parallel path. If isolate() drops
        // temperatureCapacityFactor, the worker evaluates A's block at full strength, it
        // survives, and the wrong graph is transplanted back.
        PhysicsConfig cfg = new PhysicsConfig();

        StructureGraph serialGraph = softenedPillarPlusFarTower();
        SettleOutcome serial = new CascadeEngine(cfg).settleResult(serialGraph, softenedSeeds(), SolverCallback.NONE);

        StructureGraph parallelGraph = softenedPillarPlusFarTower();
        SettleOutcome parallel =
                new ParallelCascadeDriver(cfg).settle(parallelGraph, softenedSeeds(), SolverCallback.NONE);

        // Sanity: the softened mid block actually collapsed in the serial run.
        assertTrue(asSet(serial.collapsed()).contains(new NodePos(0, 1, 0)), "the softened block should overload");
        assertEquals(asSet(serial.collapsed()), asSet(parallel.collapsed()), "collapsed set must match serial");
        assertSameGraph(serialGraph, parallelGraph);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PATH SELECTION (parallel vs serial) — observed via onStressUpdated
    // ─────────────────────────────────────────────────────────────────────
    //  The serial CascadeEngine fires onStressUpdated; the multi-cluster parallel
    //  path never does (documented fidelity note). A stress-watching callback thus
    //  reveals WHICH path ran — the only way to pin path selection, since both paths
    //  are designed to land on the same graph.

    @Test
    @DisplayName("A single structure takes the SERIAL path (fires onStressUpdated)")
    void singleStructureTakesSerialPath() {
        PhysicsConfig cfg = new PhysicsConfig();
        StructureGraph g = oneTower(6);
        StressWatch watch = new StressWatch();
        new ParallelCascadeDriver(cfg).settle(g, removeAndSeed(g, List.of(new NodePos(0, 1, 0))), watch);
        assertTrue(
                watch.stressUpdates > 0, "single-cluster delegates to the serial engine, which fires onStressUpdated");
    }

    @Test
    @DisplayName("Two independent structures take the PARALLEL path (no onStressUpdated)")
    void twoIndependentStructuresTakeParallelPath() {
        // Distinct columns by X (same Z): exercises the X half of the column key.
        PhysicsConfig cfg = new PhysicsConfig();
        StructureGraph g = twoTowers(6); // (0,0) and (50,0)
        StressWatch watch = new StressWatch();
        new ParallelCascadeDriver(cfg)
                .settle(g, removeAndSeed(g, List.of(new NodePos(0, 1, 0), new NodePos(50, 1, 0))), watch);
        assertEquals(
                0, watch.stressUpdates, "two disjoint clusters settle in parallel, which never fires onStressUpdated");
    }

    @Test
    @DisplayName("Two structures distinguished only by Z still cluster apart (parallel path)")
    void twoStructuresDifferingByZTakeParallelPath() {
        // Same X, distinct Z: exercises the Z half of the column key. A key that drops
        // Z would collide these two columns, union them into one cluster, and fall to
        // the serial path — which this assertion catches.
        PhysicsConfig cfg = new PhysicsConfig();
        StructureGraph g = new StructureGraph();
        addColumn(g, 0, 0, 6);
        addColumn(g, 0, 50, 6);
        StressWatch watch = new StressWatch();
        new ParallelCascadeDriver(cfg)
                .settle(g, removeAndSeed(g, List.of(new NodePos(0, 1, 0), new NodePos(0, 1, 50))), watch);
        assertEquals(0, watch.stressUpdates, "columns differing only in Z must not collide into one cluster");
    }

    @Test
    @DisplayName("Debris OFF: a shared unseeded column does NOT force serial (still parallel)")
    void debrisOffSharedColumnStaysParallel() {
        // With debris off there is no cross-cluster channel, so the early return makes
        // the driver parallelise even though an unseeded stub shares a cluster's column.
        // If that early return were removed, the column scan would find the stub and
        // fall back to serial — the stress watch would then fire.
        PhysicsConfig cfg = new PhysicsConfig(); // debris scale defaults to 0
        StructureGraph g = stubUnderCantileverPlusFarTower();
        StressWatch watch = new StressWatch();
        new ParallelCascadeDriver(cfg)
                .settle(g, removeAndSeed(g, List.of(new NodePos(4, 1, 0), new NodePos(50, 1, 0))), watch);
        assertEquals(0, watch.stressUpdates, "debris off ⇒ shared columns are harmless ⇒ parallel path");
    }

    @Test
    @DisplayName("Debris ON + shared unseeded column DOES force the serial path")
    void debrisOnSharedColumnForcesSerial() {
        PhysicsConfig cfg = new PhysicsConfig();
        cfg.setDebrisImpactScale(0.01);
        cfg.setMinImpactDrop(1);
        StructureGraph g = stubUnderCantileverPlusFarTower();
        StressWatch watch = new StressWatch();
        new ParallelCascadeDriver(cfg)
                .settle(g, removeAndSeed(g, List.of(new NodePos(4, 1, 0), new NodePos(50, 1, 0))), watch);
        assertTrue(watch.stressUpdates > 0, "debris on + an unseeded block in a cluster column ⇒ serial fallback");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private static StructureGraph oneTower(int height) {
        StructureGraph g = new StructureGraph();
        addColumn(g, 0, 0, height);
        return g;
    }

    private static StructureGraph twoTowers(int height) {
        StructureGraph g = new StructureGraph();
        addColumn(g, 0, 0, height);
        addColumn(g, 50, 0, height);
        return g;
    }

    private static StructureGraph threeTowers(int height) {
        StructureGraph g = new StructureGraph();
        addColumn(g, 0, 0, height);
        addColumn(g, 50, 0, height);
        addColumn(g, 100, 0, height);
        return g;
    }

    private static void addColumn(StructureGraph g, int x, int z, int height) {
        g.addGroundBlock(new NodePos(x, 0, z));
        for (int y = 1; y <= height; y++) {
            g.addBlock(new NodePos(x, y, z), HEAVY, false);
        }
    }

    /**
     * Two independent grounded pillars (x=0 and x=50), each three tall with a LIGHT
     * one-block cantilever arm at y=2 that survives a settle carrying non-zero moment
     * stress. The pillar top at y=3 is the removal target that seeds each cluster.
     */
    private static StructureGraph twoPillarsWithArms() {
        StructureGraph g = new StructureGraph();
        for (int base : new int[] {0, 50}) {
            g.addGroundBlock(new NodePos(base, 0, 0));
            for (int y = 1; y <= 3; y++) {
                g.addBlock(new NodePos(base, y, 0), HEAVY, false);
            }
            g.addBlock(new NodePos(base + 1, 2, 0), LIGHT, false); // the surviving arm
        }
        return g;
    }

    /**
     * A short grounded stub in column (0,0), plus a separate grounded pillar at x=4
     * whose cantilever arm reaches back over column (0,0) at y=4 — two components
     * that share the (x,z)=(0,0) footprint with a vertical gap between them.
     */
    private static StructureGraph stubUnderCantilever() {
        StructureGraph g = new StructureGraph();
        // Stub in column (0,0): ground + two blocks (tops out at y=2).
        addColumn(g, 0, 0, 2);
        // Far pillar at x=4, four tall, then an arm running back x=3..0 at y=4.
        g.addGroundBlock(new NodePos(4, 0, 0));
        for (int y = 1; y <= 4; y++) {
            g.addBlock(new NodePos(4, y, 0), HEAVY, false);
        }
        for (int x = 3; x >= 0; x--) {
            g.addBlock(new NodePos(x, 4, 0), HEAVY, false);
        }
        return g;
    }

    /** {@link #stubUnderCantilever} plus an independent grounded tower at x=50. */
    private static StructureGraph stubUnderCantileverPlusFarTower() {
        StructureGraph g = stubUnderCantilever();
        addColumn(g, 50, 0, 6);
        return g;
    }

    /** Two independent overloaded cantilever arms 50 apart — each truncates under a low cap. */
    private static StructureGraph twoOverloadedArms() {
        StructureGraph g = new StructureGraph();
        for (int base : new int[] {0, 50}) {
            g.addGroundBlock(new NodePos(base, 0, 0));
            g.addBlock(new NodePos(base, 1, 0), HEAVY, false);
            g.addBlock(new NodePos(base, 2, 0), HEAVY, false);
            for (int dx = 1; dx <= 8; dx++) {
                g.addBlock(new NodePos(base + dx, 2, 0), LIGHT, false);
            }
        }
        return g;
    }

    /**
     * A two-block pillar whose MID block is heat-softened until the stack above
     * overloads it, plus an independent grounded tower at x=50 to force two clusters.
     */
    private static StructureGraph softenedPillarPlusFarTower() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), SOFTENABLE, false);
        g.addBlock(new NodePos(0, 2, 0), HEAVY, false);
        // effectiveMaxLoad = 10 × 0.5 = 5, below the ~6 the stack puts on it → overload.
        // At full strength (10) it would survive: the discriminating value for isolate().
        g.getNode(new NodePos(0, 1, 0)).setTemperatureCapacityFactor(0.5);
        addColumn(g, 50, 0, 6);
        return g;
    }

    private static Set<NodePos> softenedSeeds() {
        return new HashSet<>(List.of(new NodePos(0, 1, 0), new NodePos(50, 1, 0)));
    }

    /** Capture the seeds a cascade would carry, then apply the removals. */
    private static Set<NodePos> removeAndSeed(StructureGraph g, List<NodePos> bases) {
        Set<NodePos> seeds = new HashSet<>();
        for (NodePos base : bases) {
            seeds.addAll(g.getNeighbors(base));
            seeds.addAll(g.getDependentSubgraph(base));
        }
        for (NodePos base : bases) {
            g.removeBlock(base);
            seeds.remove(base);
        }
        return seeds;
    }

    private static Set<NodePos> asSet(List<CollapsedNode> collapsed) {
        Set<NodePos> set = new HashSet<>();
        for (CollapsedNode c : collapsed) {
            set.add(c.pos());
        }
        return set;
    }

    private static List<NodePos> collapsedPositions(SettleOutcome out) {
        return positions(out.collapsed());
    }

    private static List<NodePos> positions(List<CollapsedNode> collapsed) {
        List<NodePos> list = new ArrayList<>();
        for (CollapsedNode c : collapsed) {
            list.add(c.pos());
        }
        return list;
    }

    /** Two graphs are "the same" if they have the same surviving nodes with the same persistent damage. */
    private static void assertSameGraph(StructureGraph expected, StructureGraph actual) {
        assertEquals(expected.getAllPositions(), actual.getAllPositions(), "surviving positions differ");
        for (NodePos pos : expected.getAllPositions()) {
            assertEquals(
                    expected.getNode(pos).damage(),
                    actual.getNode(pos).damage(),
                    0.0,
                    "persistent damage differs at " + pos);
        }
    }

    /**
     * Two graphs carry "the same stress" if every surviving node has identical solved
     * verticalStress and momentStress — the transient fields the solver writes and that
     * post-settle readers (EntityWeightTask, StressVisualizer, grades) consume.
     */
    private static void assertSameStress(StructureGraph expected, StructureGraph actual) {
        for (NodePos pos : expected.getAllPositions()) {
            assertEquals(
                    expected.getNode(pos).verticalStress(),
                    actual.getNode(pos).verticalStress(),
                    0.0,
                    "vertical stress differs at " + pos);
            assertEquals(
                    expected.getNode(pos).momentStress(),
                    actual.getNode(pos).momentStress(),
                    0.0,
                    "moment stress differs at " + pos);
        }
    }

    /** The surviving node with the highest stressPercent — mirrors an EntityWeightTask "who is closest to failing" read. */
    private static NodePos mostStressedSurvivor(StructureGraph g) {
        NodePos best = null;
        double bestPct = -1.0;
        for (NodePos pos : g.getAllPositions()) {
            double pct = g.getNode(pos).stressPercent();
            if (pct > bestPct) {
                bestPct = pct;
                best = pos;
            }
        }
        return best;
    }

    /**
     * Counts onStressUpdated calls, opting in via wantsStressUpdates. The serial engine
     * fires it; the multi-cluster parallel path never does — so a non-zero count means
     * the serial path ran and zero means the parallel path ran.
     */
    private static final class StressWatch implements SolverCallback {
        int stressUpdates = 0;

        @Override
        public boolean wantsStressUpdates() {
            return true;
        }

        @Override
        public void onStressUpdated(Map<NodePos, Double> stressMap) {
            stressUpdates++;
        }

        @Override
        public void onCascadeStep(CollapsedNode collapsed, int stepNumber, CollapseReason reason) {}

        @Override
        public void onCascadeComplete(List<CollapsedNode> allCollapsed) {}
    }

    /** Records the callback stream so a test can assert what the driver reported. */
    private static final class RecordingCallback implements SolverCallback {
        final List<CollapsedNode> steps = new ArrayList<>();
        final List<Integer> stepNumbers = new ArrayList<>();
        List<CollapsedNode> completed = List.of();

        @Override
        public void onCascadeStep(CollapsedNode collapsed, int stepNumber, CollapseReason reason) {
            steps.add(collapsed);
            stepNumbers.add(stepNumber);
        }

        @Override
        public void onStressUpdated(Map<NodePos, Double> stressMap) {}

        @Override
        public void onCascadeComplete(List<CollapsedNode> allCollapsed) {
            completed = allCollapsed;
        }
    }
}
