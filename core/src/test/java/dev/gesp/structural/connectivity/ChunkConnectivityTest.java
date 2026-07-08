package dev.gesp.structural.connectivity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.model.NodePos;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
 * The whole value of {@link ChunkConnectivity} is that it is LOSSLESS — its
 * chunk-accelerated "connected to bedrock?" verdict must equal a full-world BFS,
 * exactly, on every frame. These tests pin that against a brute-force reference
 * over thousands of random add/remove frames across multiple chunks, and separately
 * confirm that an edit only ever rebuilds the edited chunk's interior (the scaling
 * claim).
 */
@DisplayName("ChunkConnectivity: chunk-accelerated reachability == full BFS, exactly")
class ChunkConnectivityTest {

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Random add/remove across chunks: verdict matches a fresh BFS every frame")
    void matchesBruteForceOverRandomFrames() {
        Random rng = new Random(0xB10C5L);
        for (int trial = 0; trial < 300; trial++) {
            int chunkSize = 2 + rng.nextInt(3); // 2..4 — small, so worlds span many chunks
            int span = 5 + rng.nextInt(3); // box 0..span on each axis
            ChunkConnectivity cc = new ChunkConnectivity(chunkSize);
            Model ref = new Model();

            for (int step = 0; step < 50; step++) {
                NodePos p = new NodePos(rng.nextInt(span), rng.nextInt(span), rng.nextInt(span));
                if (ref.nodes.contains(p)) {
                    ref.remove(p);
                    cc.remove(p);
                } else {
                    boolean ground = p.y() == 0; // bedrock layer
                    ref.add(p, ground);
                    cc.add(p, ground, faceNeighbours(p));
                }
                assertSameVerdict(ref, cc, "trial " + trial + " step " + step);
            }
        }
    }

    @Test
    @DisplayName("A block far inside a huge connected slab edits in O(chunk), not O(world)")
    void interiorRebuildIsChunkBounded() {
        int chunkSize = 4;
        ChunkConnectivity cc = new ChunkConnectivity(chunkSize);
        Model ref = new Model();

        // One big connected grounded slab: 24 × 2 × 24 = 1152 cells spanning many chunks.
        int w = 24;
        int d = 24;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < d; z++) {
                    NodePos p = new NodePos(x, y, z);
                    boolean ground = y == 0;
                    ref.add(p, ground);
                    cc.add(p, ground, faceNeighbours(p));
                }
            }
        }
        assertSameVerdict(ref, cc, "after building the slab");

        // Edit a block deep in the interior, then flush via a query. The rebuild must
        // touch at most one chunk worth of cells — independent of the 1152-cell world.
        NodePos deep = new NodePos(12, 1, 12);
        ref.remove(deep);
        cc.remove(deep);
        assertSameVerdict(ref, cc, "after the deep edit"); // flushes just the edited chunk
        assertTrue(
                cc.lastInteriorRebuildSize() <= chunkSize * chunkSize * chunkSize,
                "edit rebuilt " + cc.lastInteriorRebuildSize() + " cells; must be <= one chunk ("
                        + (chunkSize * chunkSize * chunkSize) + ")");
        assertTrue(ref.nodes.size() > 1000, "the world really is large");

        // The hierarchical win: reachability runs over PORTS (≈ one per chunk for a
        // solid mass), not over cells — so the overlay sweep is orders of magnitude
        // smaller than a full-cell BFS would be.
        assertTrue(
                cc.portCount() * 8 < ref.nodes.size(),
                "overlay has " + cc.portCount() + " ports for " + ref.nodes.size()
                        + " cells — reachability is over ports, not cells");
    }

    @Test
    @DisplayName("Severing the only cross-chunk link cuts the far side off from bedrock")
    void severingCrossChunkLinkDisconnects() {
        // chunkSize 2: a grounded stub in chunk (0,*,0), a bridge crossing into the next
        // chunk along x, holding up a block that has no ground of its own.
        ChunkConnectivity cc = new ChunkConnectivity(2);
        Model ref = new Model();
        // grounded column at x=0,1 then a horizontal run to x=4 (crosses chunk borders at x=2,4).
        addPath(cc, ref, new NodePos(0, 0, 0), new NodePos(0, 1, 0)); // ground + 1
        addRun(cc, ref, 1, 4, 1, 0); // (1..4, 1, 0): x=2 and x=4 cross chunk borders

        assertTrue(cc.connectedToGround(new NodePos(4, 1, 0)), "far end reaches ground via the run");

        // Remove the link at x=2 (a cross-chunk border cell). The far side x=3,4 is orphaned.
        ref.remove(new NodePos(2, 1, 0));
        cc.remove(new NodePos(2, 1, 0));

        assertFalse(cc.connectedToGround(new NodePos(3, 1, 0)), "x=3 lost its only path to ground");
        assertFalse(cc.connectedToGround(new NodePos(4, 1, 0)), "x=4 lost its only path to ground");
        assertTrue(cc.connectedToGround(new NodePos(1, 1, 0)), "the grounded side still stands");
        assertSameVerdict(ref, cc, "after severing the cross-chunk link");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Generic connect/disconnect (arbitrary edges) across chunks matches a fresh BFS")
    void genericEdgesMatchBruteForce() {
        Random rng = new Random(0xED6E5L);
        for (int trial = 0; trial < 300; trial++) {
            int chunkSize = 2 + rng.nextInt(3);
            int span = 5 + rng.nextInt(2);
            ChunkConnectivity cc = new ChunkConnectivity(chunkSize);
            Model ref = new Model();

            for (int step = 0; step < 60; step++) {
                int op = rng.nextInt(4);
                if (op == 0 || ref.nodes.isEmpty()) {
                    // add a bare node (edges come only from explicit connect)
                    NodePos p = new NodePos(rng.nextInt(span), rng.nextInt(span), rng.nextInt(span));
                    if (!ref.nodes.contains(p)) {
                        boolean ground = rng.nextDouble() < 0.25;
                        ref.addBare(p, ground);
                        cc.add(p, ground, Set.of());
                    }
                } else if (op == 1) {
                    NodePos p = pick(ref.nodes, rng);
                    ref.remove(p);
                    cc.remove(p);
                } else if (op == 2) {
                    NodePos a = pick(ref.nodes, rng);
                    NodePos b = pick(ref.nodes, rng);
                    ref.connect(a, b);
                    cc.connect(a, b);
                } else {
                    NodePos a = pick(ref.nodes, rng);
                    NodePos b = pick(ref.nodes, rng);
                    ref.disconnect(a, b);
                    cc.disconnect(a, b);
                }
                assertSameVerdict(ref, cc, "trial " + trial + " step " + step);
            }
        }
    }

    private static NodePos pick(Set<NodePos> from, Random rng) {
        ArrayList<NodePos> list = new ArrayList<>(from);
        return list.get(rng.nextInt(list.size()));
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private static void assertSameVerdict(Model ref, ChunkConnectivity cc, String where) {
        Set<NodePos> reachable = ref.bfsReachableFromGround();
        for (NodePos n : ref.nodes) {
            assertEquals(
                    reachable.contains(n),
                    cc.connectedToGround(n),
                    () -> "connectivity mismatch at " + n + " (" + where + ")");
        }
    }

    private static void addPath(ChunkConnectivity cc, Model ref, NodePos ground, NodePos next) {
        ref.add(ground, true);
        cc.add(ground, true, faceNeighbours(ground));
        ref.add(next, false);
        cc.add(next, false, faceNeighbours(next));
    }

    private static void addRun(ChunkConnectivity cc, Model ref, int xFrom, int xTo, int y, int z) {
        for (int x = xFrom; x <= xTo; x++) {
            NodePos p = new NodePos(x, y, z);
            ref.add(p, false);
            cc.add(p, false, faceNeighbours(p));
        }
    }

    private static Set<NodePos> faceNeighbours(NodePos p) {
        return new HashSet<>(List.of(
                new NodePos(p.x() + 1, p.y(), p.z()),
                new NodePos(p.x() - 1, p.y(), p.z()),
                new NodePos(p.x(), p.y() + 1, p.z()),
                new NodePos(p.x(), p.y() - 1, p.z()),
                new NodePos(p.x(), p.y(), p.z() + 1),
                new NodePos(p.x(), p.y(), p.z() - 1)));
    }

    /** Reference structure with an obviously-correct brute-force reachability BFS. */
    private static final class Model {
        final Set<NodePos> nodes = new HashSet<>();
        final Set<NodePos> grounded = new HashSet<>();
        final Map<NodePos, Set<NodePos>> adj = new HashMap<>();

        void add(NodePos p, boolean ground) {
            if (!nodes.add(p)) {
                return;
            }
            adj.computeIfAbsent(p, k -> new HashSet<>());
            if (ground) {
                grounded.add(p);
            }
            for (NodePos n : faceNeighbours(p)) {
                if (nodes.contains(n)) {
                    adj.get(p).add(n);
                    adj.get(n).add(p);
                }
            }
        }

        /** Add a node with NO auto-derived edges — edges come only from {@link #connect}. */
        void addBare(NodePos p, boolean ground) {
            if (!nodes.add(p)) {
                return;
            }
            adj.computeIfAbsent(p, k -> new HashSet<>());
            if (ground) {
                grounded.add(p);
            }
        }

        void connect(NodePos a, NodePos b) {
            if (a.equals(b) || !nodes.contains(a) || !nodes.contains(b)) {
                return;
            }
            adj.get(a).add(b);
            adj.get(b).add(a);
        }

        void disconnect(NodePos a, NodePos b) {
            Set<NodePos> ea = adj.get(a);
            if (ea != null) {
                ea.remove(b);
            }
            Set<NodePos> eb = adj.get(b);
            if (eb != null) {
                eb.remove(a);
            }
        }

        void remove(NodePos p) {
            if (!nodes.remove(p)) {
                return;
            }
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

        Set<NodePos> bfsReachableFromGround() {
            Set<NodePos> seen = new HashSet<>();
            Queue<NodePos> q = new ArrayDeque<>();
            for (NodePos g : grounded) {
                if (nodes.contains(g) && seen.add(g)) {
                    q.add(g);
                }
            }
            while (!q.isEmpty()) {
                NodePos cur = q.poll();
                for (NodePos n : adj.getOrDefault(cur, Set.of())) {
                    if (nodes.contains(n) && seen.add(n)) {
                        q.add(n);
                    }
                }
            }
            return seen;
        }
    }
}
