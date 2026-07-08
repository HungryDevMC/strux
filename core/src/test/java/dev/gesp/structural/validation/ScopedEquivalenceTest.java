package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * THE soundness property of the scoped solve: for any structure and any
 * trigger, the scoped cascade must end in exactly the same world as the
 * brute-force reference (remove the trigger, settle the WHOLE graph). The
 * optimizations are allowed to skip work — never to change the outcome.
 *
 * <p>Structures are generated from fixed seeds: towers on a shared ground
 * strip, some carrying beams, some beams carrying hanging chains — the shapes
 * that have historically broken the scoped traversals. Debris impact is
 * disabled so outcomes are order-independent and the comparison is exact.
 *
 * <p>This is a property test, not an example test: a regression in any
 * scoping heuristic shows up here as a survivor-set mismatch on some seed
 * long before anyone builds that exact shape in a world.
 */
@DisplayName("Scoped cascade ≡ whole-graph settle (property over generated structures)")
class ScopedEquivalenceTest {

    private static final int SEEDS = 25;
    private static final int TRIGGERS_PER_SEED = 5;

    /** Debris off: collapse outcomes become order-independent, so equality is exact. */
    private static PhysicsConfig config() {
        PhysicsConfig config = new PhysicsConfig();
        config.setDebrisImpactScale(0.0);
        return config;
    }

    /**
     * Deterministic world from a seed: a shared ground strip with one shape
     * per lane — towers (optionally carrying a beam and a hanging chain),
     * BRIDGES (two pillars + deck: the load-REDISTRIBUTION shape — losing one
     * pillar must shift the deck's load to the other), and WALLS with an
     * opening (the arch shape: load must route around the hole).
     */
    private static StructureGraph generate(long seed) {
        Random rng = new Random(seed);
        StructureGraph g = new StructureGraph();
        int lanes = 3 + rng.nextInt(3); // 3..5 shapes
        int laneWidth = 9; // widest shape (bridge span 5 + pillars) + clearance

        // One contiguous ground strip — structures share terrain like a real world.
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

    /** A tower, optionally with a beam off the top, optionally with a chain hanging from the beam tip. */
    private static void towerLane(StructureGraph g, Random rng, int baseX) {
        int height = 2 + rng.nextInt(4); // 2..5
        for (int y = 1; y <= height; y++) {
            g.addBlock(new NodePos(baseX, y, 0), TestMaterials.LIGHT, false);
        }
        if (rng.nextBoolean()) {
            int armLength = 1 + rng.nextInt(3); // 1..3
            for (int i = 1; i <= armLength; i++) {
                g.addBlock(new NodePos(baseX + i, height, 0), TestMaterials.LIGHT, false);
            }
            if (armLength >= 2 && rng.nextBoolean()) {
                int hang = 1 + rng.nextInt(Math.min(2, height - 1)); // keep clear of the ground
                for (int j = 1; j <= hang; j++) {
                    g.addBlock(new NodePos(baseX + armLength, height - j, 0), TestMaterials.LIGHT, false);
                }
            }
        }
    }

    /** Two pillars carrying a deck — breaking one pillar must re-route load to the other. */
    private static void bridgeLane(StructureGraph g, Random rng, int baseX) {
        int pillarHeight = 2 + rng.nextInt(3); // 2..4
        int span = 3 + rng.nextInt(3); // 3..5 deck blocks between the pillars
        int rightX = baseX + span + 1;
        for (int y = 1; y <= pillarHeight; y++) {
            g.addBlock(new NodePos(baseX, y, 0), TestMaterials.LIGHT, false);
            g.addBlock(new NodePos(rightX, y, 0), TestMaterials.LIGHT, false);
        }
        for (int x = baseX; x <= rightX; x++) {
            g.addBlock(new NodePos(x, pillarHeight + 1, 0), TestMaterials.LIGHT, false);
        }
    }

    /** A wall with a one-block opening punched in it — load must arch around the hole. */
    private static void wallLane(StructureGraph g, Random rng, int baseX) {
        int width = 4 + rng.nextInt(2); // 4..5
        int height = 3 + rng.nextInt(2); // 3..4
        int holeX = baseX + 1 + rng.nextInt(width - 2); // never the wall ends
        int holeY = 1 + rng.nextInt(height - 1); // never the top row
        for (int x = baseX; x < baseX + width; x++) {
            for (int y = 1; y <= height; y++) {
                if (x == holeX && y == holeY) {
                    continue; // the opening
                }
                g.addBlock(new NodePos(x, y, 0), TestMaterials.LIGHT, false);
            }
        }
    }

    /** Settle the whole graph until stable (generated shapes may start overloaded). */
    private static void stabilize(StructureGraph g) {
        CascadeEngine engine = new CascadeEngine(config());
        for (int i = 0; i < 10; i++) {
            if (engine.settle(g, SolverCallback.NONE).isEmpty()) {
                return; // a pass that removes nothing = stable
            }
        }
    }

    /** Deterministic trigger choice: nth non-ground position in sorted order. */
    private static List<NodePos> sortedTargets(StructureGraph g) {
        List<NodePos> targets = new ArrayList<>();
        for (var node : g.getAllNodes()) {
            if (!node.isGrounded()) {
                targets.add(node.pos());
            }
        }
        targets.sort((a, b) -> a.x() != b.x() ? a.x() - b.x() : a.y() != b.y() ? a.y() - b.y() : a.z() - b.z());
        return targets;
    }

    @Test
    @DisplayName("Every seed, every trigger: scoped result equals brute-force result")
    void scopedCascadeMatchesBruteForce() {
        for (long seed = 1; seed <= SEEDS; seed++) {
            // Stabilize once (on a throwaway instance) to find the standing shape.
            StructureGraph reference = generate(seed);
            stabilize(reference);
            List<NodePos> targets = sortedTargets(reference);
            if (targets.isEmpty()) {
                continue;
            }

            for (int t = 0; t < TRIGGERS_PER_SEED; t++) {
                NodePos trigger = targets.get((t * 7) % targets.size());

                // SCOPED: the production path.
                StructureGraph scoped = generate(seed);
                stabilize(scoped);
                new CascadeEngine(config()).cascade(scoped, trigger);

                // BRUTE FORCE: remove, then settle the whole world.
                StructureGraph brute = generate(seed);
                stabilize(brute);
                brute.removeBlock(trigger);
                new CascadeEngine(config()).settle(brute, SolverCallback.NONE);

                assertEquals(
                        new HashSet<>(brute.getAllPositions()),
                        new HashSet<>(scoped.getAllPositions()),
                        "seed " + seed + ", trigger " + trigger
                                + ": the scoped cascade must end in the same world as the brute-force settle");
            }
        }
    }

    @Test
    @DisplayName("After any cascade: nothing floats and a re-settle removes nothing")
    void cascadeLeavesAStableWorld() {
        for (long seed = 1; seed <= SEEDS; seed++) {
            StructureGraph g = generate(seed);
            stabilize(g);
            List<NodePos> targets = sortedTargets(g);
            if (targets.isEmpty()) {
                continue;
            }
            new CascadeEngine(config()).cascade(g, targets.get(targets.size() / 2));

            assertTrue(
                    g.getFloatingBlocks().isEmpty(),
                    "seed " + seed + ": a finished cascade must never leave floating blocks");
            Set<NodePos> before = new HashSet<>(g.getAllPositions());
            new CascadeEngine(config()).settle(g, SolverCallback.NONE);
            assertEquals(
                    before,
                    new HashSet<>(g.getAllPositions()),
                    "seed " + seed + ": settling an already-settled world must be a no-op");
        }
    }
}
