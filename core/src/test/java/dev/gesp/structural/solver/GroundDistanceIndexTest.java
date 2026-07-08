package dev.gesp.structural.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.gesp.structural.model.NodePos;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The whole point of {@link GroundDistanceIndex} is that its incremental repair is
 * EXACT — byte-for-byte the distance map a full BFS would produce on the
 * post-removal graph. These tests pin that against a brute-force reference over
 * thousands of random structures and random removal sequences. A single divergence
 * anywhere would mean the physics that reads these distances could differ, so the
 * bar is exact equality at every frame, not "close".
 */
@DisplayName("GroundDistanceIndex: incremental repair == full BFS, exactly")
class GroundDistanceIndexTest {

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS) // a decremental-BFS bug would hang, not just fail — cap it
    @DisplayName("Grid structures: repair matches a fresh BFS after every removal (2000 frames)")
    void gridStructuresMatchBruteForce() {
        Random rng = new Random(0x5747D15L);
        for (int trial = 0; trial < 400; trial++) {
            Model model = randomGrid(rng);
            runRemovalSequence(model, rng, "grid seed-trial " + trial);
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Arbitrary-edge graphs: repair matches a fresh BFS after every removal (2000 frames)")
    void arbitraryGraphsMatchBruteForce() {
        Random rng = new Random(0xC0FFEEL);
        for (int trial = 0; trial < 400; trial++) {
            Model model = randomGraph(rng);
            runRemovalSequence(model, rng, "graph seed-trial " + trial);
        }
    }

    @Test
    @DisplayName("Removing an articulation node cuts its dependents off to UNREACHABLE")
    void removingSupportCutsOffDependents() {
        // GND(0) - a(1) - b(2) - c(3): a single chain. Remove a → b,c unreachable.
        Model model = new Model();
        NodePos g = new NodePos(0, 0, 0);
        NodePos a = new NodePos(0, 1, 0);
        NodePos b = new NodePos(0, 2, 0);
        NodePos c = new NodePos(0, 3, 0);
        model.addGround(g);
        model.add(a);
        model.add(b);
        model.add(c);
        model.connect(g, a);
        model.connect(a, b);
        model.connect(b, c);

        GroundDistanceIndex index = model.buildIndex();
        assertEquals(2, index.distance(b));
        assertEquals(3, index.distance(c));

        model.remove(a);
        index.remove(List.of(a));

        assertEquals(GroundDistanceIndex.UNREACHABLE, index.distance(b), "b lost its only path to ground");
        assertEquals(GroundDistanceIndex.UNREACHABLE, index.distance(c), "c lost its only path to ground");
        assertExactMatch(model, index, "after cutting the chain");
    }

    @Test
    @DisplayName("Losing the short path reroutes dependents to the longer surviving path")
    void losingShortPathReroutesToLongerOne() {
        // A diamond: GND - a - top, and GND - b1 - b2 - top. Short path via a (dist 2),
        // long path via b1,b2 (dist 3). Remove a → top must rise from 2 to 3.
        Model model = new Model();
        NodePos g = new NodePos(0, 0, 0);
        NodePos a = new NodePos(1, 1, 0);
        NodePos b1 = new NodePos(-1, 1, 0);
        NodePos b2 = new NodePos(-1, 2, 0);
        NodePos top = new NodePos(0, 3, 0);
        model.addGround(g);
        model.add(a);
        model.add(b1);
        model.add(b2);
        model.add(top);
        model.connect(g, a);
        model.connect(a, top);
        model.connect(g, b1);
        model.connect(b1, b2);
        model.connect(b2, top);

        GroundDistanceIndex index = model.buildIndex();
        assertEquals(2, index.distance(top), "top starts on the short path");

        model.remove(a);
        index.remove(List.of(a));

        assertEquals(3, index.distance(top), "top reroutes onto the longer surviving path");
        assertExactMatch(model, index, "after losing the short path");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  RANDOM-FRAME ENGINE
    // ─────────────────────────────────────────────────────────────────────

    private static void runRemovalSequence(Model model, Random rng, String where) {
        GroundDistanceIndex index = model.buildIndex();
        assertExactMatch(model, index, where + " @ build");

        int frame = 0;
        while (!model.nodes.isEmpty()) {
            // Remove a small random batch each frame.
            List<NodePos> alive = new ArrayList<>(model.nodes);
            Collections.shuffle(alive, rng);
            int batch = 1 + rng.nextInt(Math.min(3, alive.size()));
            List<NodePos> removed = alive.subList(0, batch);

            for (NodePos r : removed) {
                model.remove(r);
            }
            index.remove(removed);

            assertExactMatch(model, index, where + " @ frame " + frame);
            frame++;
        }
    }

    /** Every node the model still has must have the exact distance a fresh BFS gives. */
    private static void assertExactMatch(Model model, GroundDistanceIndex index, String where) {
        Map<NodePos, Integer> expected = model.bruteForceDistances();
        for (NodePos n : model.nodes) {
            int want = expected.getOrDefault(n, GroundDistanceIndex.UNREACHABLE);
            int got = index.distance(n);
            assertEquals(want, got, () -> "distance mismatch at " + n + " (" + where + ")");
        }
    }

    private static Model randomGrid(Random rng) {
        Model model = new Model();
        int w = 2 + rng.nextInt(3);
        int h = 2 + rng.nextInt(4);
        int d = 1 + rng.nextInt(2);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                for (int z = 0; z < d; z++) {
                    if (rng.nextDouble() < 0.78) {
                        NodePos p = new NodePos(x, y, z);
                        if (y == 0) {
                            model.addGround(p);
                        } else {
                            model.add(p);
                        }
                    }
                }
            }
        }
        // Face-adjacency edges among present nodes.
        for (NodePos p : new ArrayList<>(model.nodes)) {
            for (NodePos n : faceNeighbours(p)) {
                if (model.nodes.contains(n)) {
                    model.connect(p, n);
                }
            }
        }
        return model;
    }

    private static Model randomGraph(Random rng) {
        Model model = new Model();
        int count = 4 + rng.nextInt(8);
        List<NodePos> all = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            NodePos p = new NodePos(i, 0, 0);
            all.add(p);
            if (rng.nextDouble() < 0.3) {
                model.addGround(p);
            } else {
                model.add(p);
            }
        }
        // Ensure at least one ground so distances are interesting.
        if (model.grounded.isEmpty()) {
            model.makeGround(all.get(0));
        }
        // Random edges.
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                if (rng.nextDouble() < 0.35) {
                    model.connect(all.get(i), all.get(j));
                }
            }
        }
        return model;
    }

    private static List<NodePos> faceNeighbours(NodePos p) {
        return List.of(
                new NodePos(p.x() + 1, p.y(), p.z()),
                new NodePos(p.x() - 1, p.y(), p.z()),
                new NodePos(p.x(), p.y() + 1, p.z()),
                new NodePos(p.x(), p.y() - 1, p.z()),
                new NodePos(p.x(), p.y(), p.z() + 1),
                new NodePos(p.x(), p.y(), p.z() - 1));
    }

    /** A plain reference structure with an obviously-correct brute-force BFS. */
    private static final class Model {
        final Set<NodePos> nodes = new HashSet<>();
        final Set<NodePos> grounded = new HashSet<>();
        final Map<NodePos, Set<NodePos>> adj = new HashMap<>();

        void add(NodePos p) {
            nodes.add(p);
            adj.computeIfAbsent(p, k -> new HashSet<>());
        }

        void addGround(NodePos p) {
            add(p);
            grounded.add(p);
        }

        void makeGround(NodePos p) {
            grounded.add(p);
        }

        void connect(NodePos a, NodePos b) {
            adj.computeIfAbsent(a, k -> new HashSet<>()).add(b);
            adj.computeIfAbsent(b, k -> new HashSet<>()).add(a);
        }

        void remove(NodePos p) {
            nodes.remove(p);
            grounded.remove(p);
            Set<NodePos> edges = adj.remove(p);
            if (edges != null) {
                for (NodePos n : edges) {
                    Set<NodePos> ne = adj.get(n);
                    if (ne != null) {
                        ne.remove(p);
                    }
                }
            }
        }

        GroundDistanceIndex buildIndex() {
            return new GroundDistanceIndex(
                    new HashSet<>(nodes), new HashSet<>(grounded), n -> adj.getOrDefault(n, Set.of()));
        }

        Map<NodePos, Integer> bruteForceDistances() {
            Map<NodePos, Integer> dist = new HashMap<>();
            Queue<NodePos> queue = new ArrayDeque<>();
            for (NodePos g : grounded) {
                if (nodes.contains(g)) {
                    dist.put(g, 0);
                    queue.add(g);
                }
            }
            while (!queue.isEmpty()) {
                NodePos cur = queue.poll();
                int next = dist.get(cur) + 1;
                for (NodePos n : adj.getOrDefault(cur, Set.of())) {
                    if (nodes.contains(n) && !dist.containsKey(n)) {
                        dist.put(n, next);
                        queue.add(n);
                    }
                }
            }
            return dist;
        }
    }
}
