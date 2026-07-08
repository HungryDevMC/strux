package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.StressSolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The moment pass's per-anchor arm walk used to be the solver's one super-linear
 * step: on a long true cantilever every block re-walked the whole remaining arm,
 * so a single pass was O(arm²) and a 118k-node projectile impact froze the server
 * for ~49 s. {@link StressSolver} now answers every arm with one O(scope·α)
 * contour + union-find pass.
 *
 * <p>This is the correctness net for that rewrite. It re-implements the ORIGINAL
 * per-anchor arm BFS here as a self-contained reference solver and property-tests
 * the production solver against it: for every structure, with several beam-moment
 * reductions, every node's total stress must be bit-for-bit identical. The corpus
 * is the same 400 random frames the old cache test used (true cantilevers, beams,
 * branches) plus a long cantilever and a structure with an unreachable
 * (distance = MAX_VALUE) floating node — the two cases most likely to expose a
 * divergence in the contour formulation.
 */
@DisplayName("Single-pass arm index matches the original per-anchor BFS exactly")
class ArmEquivalenceTest {

    private static final MaterialSpec H = new MaterialSpec(3.0, 1_000_000.0); // huge cap: never collapses while solving

    /** Beam reductions to test: default (beams = no moment), partial, and none. */
    private static final double[] REDUCTIONS = {1.0, 0.5, 0.0};

    @Test
    @DisplayName("400 random frames: production stress == reference (old BFS) stress")
    void randomFramesMatchReference() {
        for (long seed = 0; seed < 400; seed++) {
            for (double reduction : REDUCTIONS) {
                StructureGraph prod = randomFrame(seed);
                StructureGraph ref = randomFrame(seed); // identical build
                assertEquals(
                        referenceFingerprint(ref, reduction),
                        productionFingerprint(prod, reduction),
                        "seed " + seed + " reduction " + reduction);
            }
        }
    }

    @Test
    @DisplayName("Long one-thick cantilever (worst case for arm walks) matches reference")
    void longCantileverMatchesReference() {
        for (int len : new int[] {60, 200, 600}) {
            for (double reduction : REDUCTIONS) {
                StructureGraph prod = cantilever(len);
                StructureGraph ref = cantilever(len);
                assertEquals(
                        referenceFingerprint(ref, reduction),
                        productionFingerprint(prod, reduction),
                        "cantilever len " + len + " reduction " + reduction);
            }
        }
    }

    @Test
    @DisplayName("Structure with an unreachable (MAX_VALUE distance) floating node matches reference")
    void floatingNodeMatchesReference() {
        for (double reduction : REDUCTIONS) {
            StructureGraph prod = withFloatingArm();
            StructureGraph ref = withFloatingArm();
            assertEquals(
                    referenceFingerprint(ref, reduction),
                    productionFingerprint(prod, reduction),
                    "floating-node case, reduction " + reduction);
        }
    }

    @Test
    @DisplayName("Progressive and batch overload finders agree on a heavy true cantilever")
    void progressiveAndBatchFindersUseTheIndex() {
        // A heavy horizontal cantilever (DECK material) that overloads under its
        // own moment. This drives solveProgressively() and findOverloadedBatch()
        // — the two cascade-facing moment paths that build the index lazily — and
        // pins that they find the SAME farthest-from-ground overloaded block.
        MaterialSpec deck = new MaterialSpec(3.0, 30.0); // heavy enough to overload a long arm

        StructureGraph g1 = heavyCantilever(deck, 24);
        StructureGraph g2 = heavyCantilever(deck, 24);

        NodePos progressive = new StressSolver().solveProgressively(g1, g1.getAllPositions());
        List<NodePos> batch = new StressSolver().findOverloadedBatch(g2, g2.getAllPositions());

        assertNotNull(progressive, "a long heavy cantilever must report an overloaded block");
        assertFalse(batch.isEmpty(), "the batch finder must also report overloaded blocks");
        // solveProgressively returns the most-stressed block at the farthest
        // overloaded level; it must be one of the batch's farthest-level blocks.
        assertTrue(batch.contains(progressive), "the two finders must agree on the overloaded level");
        // And the index path agrees with the full solve's stress on the same arm.
        StructureGraph g3 = heavyCantilever(deck, 24);
        StructureGraph g4 = heavyCantilever(deck, 24);
        new StressSolver().solveAll(g3);
        new ReferenceSolver(1.0).solveAll(g4);
        assertEquals(fingerprint(g4), fingerprint(g3), "full-solve stress must match the BFS reference");
    }

    /** A grounded post with a long, heavy, one-thick horizontal arm off its top. */
    private static StructureGraph heavyCantilever(MaterialSpec mat, int armLength) {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), mat, false);
        for (int x = 1; x <= armLength; x++) {
            g.addBlock(new NodePos(x, 1, 0), mat, false);
        }
        return g;
    }

    // ── fingerprints ───────────────────────────────────────────────────────

    private static List<Double> productionFingerprint(StructureGraph g, double reduction) {
        return productionFingerprint(g, reduction, false);
    }

    private static List<Double> productionFingerprint(StructureGraph g, double reduction, boolean bendingDepth) {
        PhysicsConfig cfg = new PhysicsConfig();
        cfg.setBeamMomentReduction(reduction);
        cfg.setBendingDepthEnabled(bendingDepth);
        new StressSolver(cfg).solveAll(g);
        return fingerprint(g);
    }

    private static List<Double> referenceFingerprint(StructureGraph g, double reduction) {
        return referenceFingerprint(g, reduction, false);
    }

    private static List<Double> referenceFingerprint(StructureGraph g, double reduction, boolean bendingDepth) {
        new ReferenceSolver(reduction, bendingDepth).solveAll(g);
        return fingerprint(g);
    }

    private static List<Double> fingerprint(StructureGraph g) {
        List<NodePos> positions = new ArrayList<>(g.getAllPositions());
        positions.sort(NodePos.CANONICAL_ORDER);
        List<Double> out = new ArrayList<>();
        for (NodePos p : positions) {
            Node n = g.getNode(p);
            out.add(n == null ? 0.0 : n.stressValue());
        }
        return out;
    }

    // ── structures ───────────────────────────────────────────────────────────

    /** A long, one-thick horizontal cantilever — the worst case for arm walks. */
    private static StructureGraph cantilever(int armLength) {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), H, false);
        for (int x = 1; x <= armLength; x++) {
            g.addBlock(new NodePos(x, 1, 0), H, false);
        }
        return g;
    }

    /**
     * A grounded cantilever PLUS a separate floating arm with no path to ground:
     * those nodes get distance = Integer.MAX_VALUE, exercising the unreached-node
     * branch of both solvers.
     */
    private static StructureGraph withFloatingArm() {
        StructureGraph g = cantilever(8);
        // floating slab far away, not connected to the ground structure
        for (int x = 50; x <= 58; x++) {
            g.addBlock(new NodePos(x, 5, 0), H, false);
        }
        for (int x = 50; x <= 58; x++) {
            g.addBlock(new NodePos(x, 6, 0), H, false);
        }
        return g;
    }

    /**
     * A random skeletal frame: a couple of grounded pillars with horizontal arms
     * and cross-links at various heights. Mixes true cantilevers (one support),
     * beams (two supports), and branches — the cases the beam-vs-cantilever
     * reformulation must get right.
     */
    private static StructureGraph randomFrame(long seed) {
        Random r = new Random(seed);
        StructureGraph g = new StructureGraph();
        int pillars = 1 + r.nextInt(3);
        int height = 2 + r.nextInt(4);
        for (int p = 0; p < pillars; p++) {
            int baseX = p * (3 + r.nextInt(3));
            g.addGroundBlock(new NodePos(baseX, 0, 0));
            for (int y = 1; y <= height; y++) {
                g.addBlock(new NodePos(baseX, y, 0), H, false);
            }
            int armY = 1 + r.nextInt(height);
            int armLen = 1 + r.nextInt(5);
            for (int x = 1; x <= armLen; x++) {
                g.addBlock(new NodePos(baseX + x, armY, 0), H, false);
            }
        }
        return g;
    }

    /**
     * A cantilever {@code depth} nodes thick (vertically) with a {@code armLength}
     * arm at every layer — the structure where section-depth physics diverges most
     * from the legacy lumped arm, and the one most likely to expose a same-y union
     * vs. same-plane BFS mismatch in the contour formulation.
     */
    private static StructureGraph thickCantilever(int depth, int armLength) {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int k = 0; k < depth; k++) {
            int y = 1 + k;
            g.addBlock(new NodePos(0, y, 0), H, false);
            for (int x = 1; x <= armLength; x++) {
                g.addBlock(new NodePos(x, y, 0), H, false);
            }
        }
        return g;
    }

    @Test
    @DisplayName("Section-depth physics ON: single-pass index matches the same-plane BFS reference")
    void bendingDepthMatchesReference() {
        // Random frames (true cantilevers, beams, branches) plus deliberately thick
        // cantilevers — all solved with the d² section-depth physics enabled. The
        // index's same-y union must still agree bit-for-bit with the per-anchor
        // same-plane BFS oracle.
        for (double reduction : REDUCTIONS) {
            for (long seed = 0; seed < 400; seed++) {
                StructureGraph prod = randomFrame(seed);
                StructureGraph ref = randomFrame(seed);
                assertEquals(
                        referenceFingerprint(ref, reduction, true),
                        productionFingerprint(prod, reduction, true),
                        "bendingDepth seed " + seed + " reduction " + reduction);
            }
            for (int depth : new int[] {1, 2, 3, 4}) {
                for (int arm : new int[] {1, 4, 12}) {
                    StructureGraph prod = thickCantilever(depth, arm);
                    StructureGraph ref = thickCantilever(depth, arm);
                    assertEquals(
                            referenceFingerprint(ref, reduction, true),
                            productionFingerprint(prod, reduction, true),
                            "bendingDepth thick depth=" + depth + " arm=" + arm + " reduction " + reduction);
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  REFERENCE SOLVER — a faithful copy of the ORIGINAL StressSolver.solve()
    //  with the per-anchor arm BFS, used purely as the equivalence oracle.
    // ─────────────────────────────────────────────────────────────────────────

    private static final class ReferenceSolver {
        private static final double MOMENT_MULTIPLIER = 1.0; // PhysicsConfig default
        private final double beamReduction;
        private final boolean bendingDepth;

        ReferenceSolver(double beamReduction) {
            this(beamReduction, false);
        }

        ReferenceSolver(double beamReduction, boolean bendingDepth) {
            this.beamReduction = beamReduction;
            this.bendingDepth = bendingDepth;
        }

        void solveAll(StructureGraph graph) {
            Set<NodePos> subgraph = graph.getAllPositions();
            if (subgraph.isEmpty()) {
                return;
            }
            Map<NodePos, Integer> dist = calculateDistances(graph, subgraph);

            List<NodePos> sorted = new ArrayList<>(subgraph);
            sorted.sort((a, b) ->
                    Integer.compare(dist.getOrDefault(b, Integer.MAX_VALUE), dist.getOrDefault(a, Integer.MAX_VALUE)));

            for (NodePos pos : subgraph) {
                Node node = graph.getNode(pos);
                if (node != null) {
                    node.resetStress();
                }
            }

            for (NodePos pos : sorted) {
                Node node = graph.getNode(pos);
                if (node == null || node.isGrounded()) {
                    continue;
                }
                double verticalStress = node.mass();
                int myDistance = dist.getOrDefault(pos, Integer.MAX_VALUE);
                for (NodePos neighborPos : graph.getNeighbors(pos)) {
                    int neighborDistance = dist.getOrDefault(neighborPos, Integer.MAX_VALUE);
                    if (neighborDistance > myDistance) {
                        Node neighbor = graph.getNode(neighborPos);
                        if (neighbor != null) {
                            double share = loadShare(graph, neighborPos, pos, dist);
                            if (share > 0) {
                                verticalStress += neighbor.verticalStress() * share;
                            }
                        }
                    }
                }
                node.setVerticalStress(verticalStress);
            }

            for (NodePos pos : subgraph) {
                Node node = graph.getNode(pos);
                if (node == null || node.isGrounded()) {
                    continue;
                }
                int myDistance = dist.getOrDefault(pos, Integer.MAX_VALUE);
                double momentStress = 0.0;
                for (NodePos neighborPos : graph.getNeighbors(pos)) {
                    if (neighborPos.y() != pos.y()) {
                        continue;
                    }
                    int neighborDistance = dist.getOrDefault(neighborPos, Integer.MAX_VALUE);
                    if (neighborDistance > myDistance) {
                        // Same cheap fast path the production call sites use: a beam by
                        // direct support contributes no moment. The only thing under test
                        // is that the original per-anchor BFS and the new single-pass
                        // index agree on the arms that survive this filter.
                        if (hasAlternativeSupport(graph, neighborPos, pos, dist, myDistance)) {
                            continue;
                        }
                        double[] arm = computeArm(graph, neighborPos, myDistance, dist);
                        double applied = arm[0] * arm[1] * MOMENT_MULTIPLIER;
                        // Section-modulus normalization, identical to production:
                        // a beam d nodes deep (vertically, in THIS arm's direction)
                        // carries the same applied moment at 1/d² the stress. d=1
                        // leaves it unchanged.
                        int depth = sectionDepth(graph, pos, neighborPos);
                        momentStress += applied / ((double) depth * depth);
                    }
                }
                node.setMomentStress(momentStress);
            }
        }

        /** Vertical beam depth for one arm — mirrors production sectionDepth. */
        private int sectionDepth(StructureGraph graph, NodePos anchorPos, NodePos armPos) {
            if (!bendingDepth) {
                return 1; // legacy: depth never affects bending strength
            }
            return 1 + beamLayersAbove(graph, anchorPos, armPos);
        }

        private int beamLayersAbove(StructureGraph graph, NodePos anchorPos, NodePos armPos) {
            int count = 0;
            NodePos anchor = anchorPos;
            NodePos arm = armPos;
            while (true) {
                NodePos nextAnchor = new NodePos(anchor.x(), anchor.y() + 1, anchor.z());
                NodePos nextArm = new NodePos(arm.x(), arm.y() + 1, arm.z());
                if (!graph.hasBlock(nextAnchor)
                        || !graph.hasBlock(nextArm)
                        || !graph.getNeighbors(anchor).contains(nextAnchor)
                        || !graph.getNeighbors(arm).contains(nextArm)
                        || !graph.getNeighbors(nextAnchor).contains(nextArm)) {
                    break;
                }
                count++;
                anchor = nextAnchor;
                arm = nextArm;
            }
            return count;
        }

        private boolean hasAlternativeSupport(
                StructureGraph graph,
                NodePos startPos,
                NodePos anchorPos,
                Map<NodePos, Integer> dist,
                int anchorDistance) {
            for (NodePos neighbor : graph.getNeighbors(startPos)) {
                if (neighbor.equals(anchorPos)) {
                    continue;
                }
                if (dist.getOrDefault(neighbor, Integer.MAX_VALUE) <= anchorDistance) {
                    return true;
                }
            }
            return false;
        }

        /** Original per-anchor arm BFS. Returns {totalMass (post-beam), reach}. */
        private double[] computeArm(
                StructureGraph graph, NodePos startPos, int anchorDistance, Map<NodePos, Integer> dist) {
            Set<NodePos> visited = new HashSet<>();
            Queue<NodePos> queue = new LinkedList<>();
            queue.add(startPos);
            visited.add(startPos);
            double totalMass = 0.0;
            int reach = 0;
            Set<NodePos> supports = new HashSet<>();
            while (!queue.isEmpty()) {
                NodePos current = queue.poll();
                Node node = graph.getNode(current);
                if (node == null) {
                    continue;
                }
                totalMass += node.mass();
                reach++;
                for (NodePos neighbor : graph.getNeighbors(current)) {
                    if (visited.contains(neighbor)) {
                        continue;
                    }
                    int neighborDist = dist.getOrDefault(neighbor, Integer.MAX_VALUE);
                    if (neighborDist <= anchorDistance) {
                        supports.add(neighbor);
                    } else if (neighborDist != Integer.MAX_VALUE && (!bendingDepth || neighbor.y() == startPos.y())) {
                        // Same-plane arm growth only when section-depth physics is on:
                        // a vertically-stacked node is then a separate beam layer, not
                        // more lever arm (mirrors the index's same-y union). With the
                        // flag off — or for a single-plane structure — this is the legacy
                        // all-direction flood and the oracle stays bit-identical.
                        visited.add(neighbor);
                        queue.add(neighbor);
                    }
                }
            }
            if (supports.size() >= 2) {
                return new double[] {totalMass * (1.0 - beamReduction), reach};
            }
            return new double[] {totalMass, reach};
        }

        private Map<NodePos, Integer> calculateDistances(StructureGraph graph, Set<NodePos> subgraph) {
            Map<NodePos, Integer> distances = new HashMap<>();
            Queue<NodePos> queue = new LinkedList<>();
            for (NodePos pos : subgraph) {
                Node node = graph.getNode(pos);
                if (node != null && node.isGrounded()) {
                    distances.put(pos, 0);
                    queue.add(pos);
                }
            }
            while (!queue.isEmpty()) {
                NodePos current = queue.poll();
                int currentDist = distances.get(current);
                for (NodePos neighbor : graph.getNeighbors(current)) {
                    if (subgraph.contains(neighbor) && !distances.containsKey(neighbor)) {
                        distances.put(neighbor, currentDist + 1);
                        queue.add(neighbor);
                    }
                }
            }
            return distances;
        }

        private double loadShare(
                StructureGraph graph, NodePos sourcePos, NodePos targetPos, Map<NodePos, Integer> dist) {
            // Mirrors production calculateLoadShare: strictly-closer supporters
            // only (same-distance shares would evaporate — see the conservation
            // pins in PhysicsValidationTest). This oracle is about the ARM walk;
            // the share rule must simply match production.
            int sourceDistance = dist.getOrDefault(sourcePos, Integer.MAX_VALUE);
            int targetDistance = dist.getOrDefault(targetPos, Integer.MAX_VALUE);
            if (targetDistance >= sourceDistance || targetDistance == Integer.MAX_VALUE) {
                return 0.0;
            }
            double totalWeight = 0.0;
            double targetWeight = 0.0;
            for (NodePos neighbor : graph.getNeighbors(sourcePos)) {
                int neighborDistance = dist.getOrDefault(neighbor, Integer.MAX_VALUE);
                if (neighborDistance < sourceDistance) {
                    double weight = 1.0 / (neighborDistance + 1);
                    totalWeight += weight;
                    if (neighbor.equals(targetPos)) {
                        targetWeight = weight;
                    }
                }
            }
            if (totalWeight == 0) {
                return 0.0;
            }
            return targetWeight / totalWeight;
        }
    }
}
