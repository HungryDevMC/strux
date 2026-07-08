package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import dev.gesp.structural.solver.CascadeResult;
import dev.gesp.structural.solver.StressSolver;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * North-star laws for the CASCADE engine, the counterpart of
 * {@link PhysicsValidationTest} (which pins the solver). A cascade may be
 * dramatic, but it must never be sloppy:
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                        CASCADE LAWS                                 │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  ACCOUNTING — every block is a survivor, a collapse casualty, or   │
 *   │               the broken trigger. Nothing vanishes, nothing is     │
 *   │               counted twice.                                        │
 *   │  STABILITY  — when the cascade says "done", it must BE done:       │
 *   │               nothing overloaded, nothing floating, and a second   │
 *   │               settle removes nothing.                              │
 *   │  GROUND     — grounded nodes are anchors; no cascade may eat one.  │
 *   │  TEXTBOOK   — break a plain column at height k: exactly the        │
 *   │               blocks above k fall, exactly the ones below stand.   │
 *   │                                                                     │
 *   │  The laws are checked on hand-built shapes AND a pinned-seed       │
 *   │  random sweep, so they hold for structures nobody thought of.      │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * Seeds are FIXED (0..N) so failures reproduce; every assertion message
 * carries its seed.
 */
@DisplayName("Cascade validation: accounting, stability, and textbook collapses")
class CascadeValidationTest {

    private static final int RANDOM_SWEEP_SEEDS = 300;

    /**
     * Lift the safety cap and disable debris damage for the LAW checks: the
     * cap legitimately freezes mid-fall (tested separately by CascadeCapTest),
     * and debris damage re-wounds survivors during the fall — both would blur
     * the pure laws. A debris-on stability case is included separately.
     */
    private static PhysicsConfig lawConfig() {
        PhysicsConfig c = new PhysicsConfig();
        c.setDebrisImpactScale(0.0);
        c.setMaxCascadeSteps(1_000_000);
        return c;
    }

    /** Same blocky generator family as CascadeLeavesNoFloatersTest. */
    private static StructureGraph generate(long seed, PhysicsConfig cfg) {
        Random r = new Random(seed);
        StructureGraph g = new StructureGraph();
        int w = 6 + r.nextInt(6);
        int d = 1 + r.nextInt(3);
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                g.addGroundBlock(new NodePos(x, 0, z));
            }
        }
        int blocks = 50 + r.nextInt(90);
        for (int i = 0; i < blocks; i++) {
            g.addBlock(new NodePos(r.nextInt(w), 1 + r.nextInt(8), r.nextInt(d)), TestMaterials.LIGHT, false);
        }
        // Start from a fully-settled, fully-grounded structure so the laws
        // measure only what the triggered cascade does.
        for (int k = 0; k < 5; k++) {
            new CascadeEngine(cfg).settle(g, SolverCallback.NONE);
        }
        for (NodePos p : g.getFloatingBlocks()) {
            g.removeBlock(p);
        }
        return g;
    }

    /** Test-local reachability oracle: every survivor must reach ground. */
    private static Set<NodePos> unreachableFromGround(StructureGraph g) {
        Set<NodePos> reached = new HashSet<>();
        Queue<NodePos> queue = new ArrayDeque<>();
        for (Node n : g.getAllNodes()) {
            if (n.isGrounded()) {
                reached.add(n.pos());
                queue.add(n.pos());
            }
        }
        while (!queue.isEmpty()) {
            NodePos current = queue.poll();
            for (NodePos n : g.getNeighbors(current)) {
                if (g.hasBlock(n) && reached.add(n)) {
                    queue.add(n);
                }
            }
        }
        Set<NodePos> floating = new HashSet<>();
        for (Node n : g.getAllNodes()) {
            if (!reached.contains(n.pos())) {
                floating.add(n.pos());
            }
        }
        return floating;
    }

    /** Assert every cascade law on one structure + trigger. */
    private static void assertCascadeLaws(StructureGraph g, NodePos trigger, PhysicsConfig cfg, String label) {
        Set<NodePos> before = new HashSet<>(g.getAllPositions());

        CascadeResult result = new CascadeEngine(cfg).cascade(g, trigger);

        // ── ACCOUNTING ──────────────────────────────────────────────────
        Set<NodePos> survivors = new HashSet<>(g.getAllPositions());
        Set<NodePos> collapsed = new HashSet<>(result.collapsed());
        assertEquals(result.collapsed().size(), collapsed.size(), label + ": no block may be reported collapsed twice");
        for (NodePos p : collapsed) {
            assertFalse(survivors.contains(p), label + ": " + p + " is both survivor and casualty");
            assertTrue(before.contains(p), label + ": " + p + " collapsed but never existed");
        }
        Set<NodePos> accounted = new HashSet<>(survivors);
        accounted.addAll(collapsed);
        accounted.add(trigger); // the broken block itself
        assertEquals(before, accounted, label + ": every block must be survivor, casualty, or the trigger");

        // ── GROUND ──────────────────────────────────────────────────────
        for (NodePos p : collapsed) {
            // survivors set no longer has it, so check the BEFORE graph's
            // grounded-ness via position y=0 ground strip convention is not
            // enough — ask the result: a grounded node must never collapse.
            assertNotEquals(0, p.y(), label + ": cascade consumed ground-level anchor " + p);
        }

        // ── STABILITY: nothing floating, nothing overloaded, settled twice ──
        assertEquals(Set.of(), unreachableFromGround(g), label + ": floaters survived the settle");
        new StressSolver(cfg).solveAll(g);
        for (Node n : g.getAllNodes()) {
            assertTrue(
                    n.stressPercent() <= 1.0 + 1e-9,
                    label + ": cascade finished but " + n.pos() + " is still overloaded at " + n.stressPercent());
        }
        assertEquals(
                0,
                new CascadeEngine(cfg).settle(g, SolverCallback.NONE).size(),
                label + ": a second settle must be a no-op");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  TEXTBOOK: the one collapse whose exact answer everyone knows
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Column broken at height k: exactly the blocks above k fall")
    void columnCollapseIsExact() {
        int height = 12;
        int breakAt = 5;
        PhysicsConfig cfg = lawConfig();

        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= height; y++) {
            g.addBlock(new NodePos(0, y, 0), TestMaterials.LIGHT, false);
        }

        CascadeResult result = new CascadeEngine(cfg).cascade(g, new NodePos(0, breakAt, 0));

        Set<NodePos> expected = new HashSet<>();
        for (int y = breakAt + 1; y <= height; y++) {
            expected.add(new NodePos(0, y, 0));
        }
        assertEquals(expected, new HashSet<>(result.collapsed()), "exactly the blocks above the break must fall");
        for (int y = 1; y < breakAt; y++) {
            assertTrue(g.hasBlock(new NodePos(0, y, 0)), "block below the break at y=" + y + " must stand");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  LAWS on hand-built shapes
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Cascade laws hold on tower+arm and bridge shapes")
    void lawsHoldOnCanonicalShapes() {
        PhysicsConfig cfg = lawConfig();

        // Tower with a long cantilever arm, broken at the tower's waist.
        StructureGraph towerArm = new StructureGraph();
        towerArm.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= 6; y++) {
            towerArm.addBlock(new NodePos(0, y, 0), TestMaterials.HEAVY, false);
        }
        for (int x = 1; x <= 4; x++) {
            towerArm.addBlock(new NodePos(x, 6, 0), TestMaterials.LIGHT, false);
        }
        assertCascadeLaws(towerArm, new NodePos(0, 3, 0), cfg, "tower+arm");

        // Two-pier bridge, one pier base knocked out.
        StructureGraph bridge = new StructureGraph();
        for (int x : new int[] {0, 6}) {
            bridge.addGroundBlock(new NodePos(x, 0, 0));
            for (int y = 1; y <= 4; y++) {
                bridge.addBlock(new NodePos(x, y, 0), TestMaterials.HEAVY, false);
            }
        }
        for (int x = 1; x <= 5; x++) {
            bridge.addBlock(new NodePos(x, 4, 0), TestMaterials.LIGHT, false);
        }
        assertCascadeLaws(bridge, new NodePos(0, 1, 0), cfg, "bridge");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  LAWS on a pinned-seed random sweep
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Cascade laws hold across a pinned-seed random sweep")
    void lawsHoldOnRandomStructures() {
        PhysicsConfig cfg = lawConfig();
        for (long seed = 0; seed < RANDOM_SWEEP_SEEDS; seed++) {
            StructureGraph g = generate(seed, cfg);
            // Deterministically pick a standing block to break.
            NodePos trigger = null;
            for (Node n : g.getAllNodes()) {
                if (!n.isGrounded() && (trigger == null || NodePos.CANONICAL_ORDER.compare(n.pos(), trigger) < 0)) {
                    trigger = n.pos();
                }
            }
            if (trigger == null) {
                continue; // seed generated only ground; nothing to break
            }
            assertCascadeLaws(g, trigger, cfg, "seed=" + seed);
        }
    }

    /**
     * Stability must also hold with debris damage ON (production default):
     * falling rubble wounds survivors mid-cascade, but when the engine says
     * "done", the wounded survivors must still hold what they carry.
     */
    @Test
    @DisplayName("With debris damage on, a finished cascade is still a stable fixpoint")
    void debrisOnStillEndsStable() {
        PhysicsConfig cfg = new PhysicsConfig();
        cfg.setMaxCascadeSteps(1_000_000);
        for (long seed = 0; seed < 50; seed++) {
            StructureGraph g = generate(seed, cfg);
            NodePos trigger = null;
            for (Node n : g.getAllNodes()) {
                if (!n.isGrounded() && (trigger == null || NodePos.CANONICAL_ORDER.compare(n.pos(), trigger) < 0)) {
                    trigger = n.pos();
                }
            }
            if (trigger == null) {
                continue;
            }
            new CascadeEngine(cfg).cascade(g, trigger);

            assertEquals(Set.of(), unreachableFromGround(g), "seed=" + seed + ": floaters with debris on");
            assertEquals(
                    0,
                    new CascadeEngine(cfg).settle(g, SolverCallback.NONE).size(),
                    "seed=" + seed + ": a second settle must be a no-op even with debris damage");
        }
    }
}
