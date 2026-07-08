package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scoped-resume soundness property, exercised in the regime the other equivalence
 * tests skip: <b>debris impact ON</b>. A capped cascade that truncates and is then
 * resumed across "ticks" (carrying {@code remainingScope} forward, exactly as
 * {@code CascadeResumeManager} does) must end in the SAME world as one uncapped settle.
 *
 * <p>Debris makes collapse outcomes order-sensitive (a faller damages the block below
 * before the next solve), so this is the case where carrying the wrong region forward, or
 * collapsing in a different order across the truncation boundary, would silently change
 * the survivor set. {@code ScopedEquivalenceTest} runs the same shapes with debris OFF;
 * this run turns it on and adds the capped→resume loop. It also exercises
 * {@code CascadeResumeManager}'s "clean pass collapsed something but didn't truncate →
 * re-settle the same region" branch.
 */
@DisplayName("Scoped resume ≡ uncapped settle WITH debris on (order-sensitive property)")
class ResumeDebrisEquivalenceTest {

    private static final int SEEDS = 30;
    private static final int TRIGGERS_PER_SEED = 5;
    private static final int CAP = 3; // small cap → most non-trivial cascades truncate

    private static PhysicsConfig config(int maxSteps) {
        PhysicsConfig c = new PhysicsConfig();
        c.setDebrisImpactScale(0.5); // ON — the order-sensitive regime
        c.setMinImpactDrop(2);
        c.setMaxCascadeSteps(maxSteps);
        return c;
    }

    // ── structure generator (same shapes as ScopedEquivalenceTest: towers, bridges, walls) ──

    private static StructureGraph generate(long seed) {
        Random rng = new Random(seed);
        StructureGraph g = new StructureGraph();
        int lanes = 3 + rng.nextInt(3);
        int laneWidth = 9;
        for (int x = 0; x < lanes * laneWidth; x++) {
            g.addGroundBlock(new NodePos(x, 0, 0));
        }
        for (int lane = 0; lane < lanes; lane++) {
            int baseX = lane * laneWidth;
            switch (rng.nextInt(3)) {
                case 0 -> towerLane(g, rng, baseX);
                case 1 -> bridgeLane(g, rng, baseX);
                case 2 -> wallLane(g, rng, baseX);
                default -> throw new AssertionError();
            }
        }
        return g;
    }

    private static void towerLane(StructureGraph g, Random rng, int baseX) {
        int height = 2 + rng.nextInt(4);
        for (int y = 1; y <= height; y++) {
            g.addBlock(new NodePos(baseX, y, 0), TestMaterials.LIGHT, false);
        }
        if (rng.nextBoolean()) {
            int armLength = 1 + rng.nextInt(3);
            for (int i = 1; i <= armLength; i++) {
                g.addBlock(new NodePos(baseX + i, height, 0), TestMaterials.LIGHT, false);
            }
            if (armLength >= 2 && rng.nextBoolean()) {
                int hang = 1 + rng.nextInt(Math.min(2, height - 1));
                for (int j = 1; j <= hang; j++) {
                    g.addBlock(new NodePos(baseX + armLength, height - j, 0), TestMaterials.LIGHT, false);
                }
            }
        }
    }

    private static void bridgeLane(StructureGraph g, Random rng, int baseX) {
        int pillarHeight = 2 + rng.nextInt(3);
        int span = 3 + rng.nextInt(3);
        int rightX = baseX + span + 1;
        for (int y = 1; y <= pillarHeight; y++) {
            g.addBlock(new NodePos(baseX, y, 0), TestMaterials.LIGHT, false);
            g.addBlock(new NodePos(rightX, y, 0), TestMaterials.LIGHT, false);
        }
        for (int x = baseX; x <= rightX; x++) {
            g.addBlock(new NodePos(x, pillarHeight + 1, 0), TestMaterials.LIGHT, false);
        }
    }

    private static void wallLane(StructureGraph g, Random rng, int baseX) {
        int width = 4 + rng.nextInt(2);
        int height = 3 + rng.nextInt(2);
        int holeX = baseX + 1 + rng.nextInt(width - 2);
        int holeY = 1 + rng.nextInt(height - 1);
        for (int x = baseX; x < baseX + width; x++) {
            for (int y = 1; y <= height; y++) {
                if (x == holeX && y == holeY) {
                    continue;
                }
                g.addBlock(new NodePos(x, y, 0), TestMaterials.LIGHT, false);
            }
        }
    }

    private static void stabilize(StructureGraph g) {
        CascadeEngine engine = new CascadeEngine(config(1000));
        for (int i = 0; i < 10; i++) {
            if (engine.settle(g, SolverCallback.NONE).isEmpty()) {
                return;
            }
        }
    }

    private static List<NodePos> sortedTargets(StructureGraph g) {
        return g.getAllNodes().stream()
                .filter(n -> !n.isGrounded())
                .map(n -> n.pos())
                .sorted((a, b) -> a.x() != b.x() ? a.x() - b.x() : a.y() != b.y() ? a.y() - b.y() : a.z() - b.z())
                .toList();
    }

    /**
     * Resume a truncated cascade to completion the way {@code CascadeResumeManager} does:
     * carry {@code remainingScope} forward; on a pass that collapsed something but did not
     * truncate (empty remainingScope), re-settle the SAME region once more to drain any
     * newly exposed work; stop when a pass collapses nothing and is not truncated.
     */
    private static void resumeToCompletion(CascadeEngine engine, StructureGraph g, Set<NodePos> firstScope) {
        Set<NodePos> scope = firstScope;
        for (int guard = 0; guard < 5000; guard++) {
            CascadeEngine.SettleOutcome out = engine.settleResult(g, scope, SolverCallback.NONE);
            if (!out.truncated() && out.collapsed().isEmpty()) {
                return;
            }
            if (!out.remainingScope().isEmpty()) {
                scope = new HashSet<>(out.remainingScope());
            }
        }
        fail("resume did not converge");
    }

    @Test
    @DisplayName("every seed × trigger: capped+resumed (debris on) ends in the same world as uncapped")
    void cappedResumeMatchesUncappedWithDebris() {
        int truncationsSeen = 0;
        for (long seed = 1; seed <= SEEDS; seed++) {
            StructureGraph ref = generate(seed);
            stabilize(ref);
            List<NodePos> targets = sortedTargets(ref);
            if (targets.isEmpty()) {
                continue;
            }
            for (int t = 0; t < TRIGGERS_PER_SEED; t++) {
                NodePos trigger = targets.get((t * 7) % targets.size());

                // Reference: one uncapped settle with debris on.
                StructureGraph uncapped = generate(seed);
                stabilize(uncapped);
                new CascadeEngine(config(1000)).cascade(uncapped, trigger);

                // Test: a capped cascade that truncates, then resumed across ticks.
                StructureGraph capped = generate(seed);
                stabilize(capped);
                CascadeEngine cappedEngine = new CascadeEngine(config(CAP));
                var first = cappedEngine.cascade(capped, trigger);
                if (first.truncated()) {
                    truncationsSeen++;
                    resumeToCompletion(cappedEngine, capped, first.remainingScope());
                }

                assertEquals(
                        new HashSet<>(uncapped.getAllPositions()),
                        new HashSet<>(capped.getAllPositions()),
                        "seed " + seed + ", trigger " + trigger
                                + ": capped+resumed (debris on) must match the uncapped settle");
            }
        }
        // The property is only meaningful if the cap actually cut some cascades short.
        assertTrue(truncationsSeen > 0, "no cascade truncated — the resume path was never exercised");
    }
}
