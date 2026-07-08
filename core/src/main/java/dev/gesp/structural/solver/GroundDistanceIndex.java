package dev.gesp.structural.solver;

import dev.gesp.structural.model.NodePos;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Maintains "distance from ground" (BFS hop-count to the nearest grounded node)
 * for a set of nodes, and REPAIRS it incrementally when nodes are removed —
 * instead of re-running the whole BFS from scratch every time a cascade drops a
 * block.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │              WHY INCREMENTAL, AND WHY IT IS STILL EXACT             │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  A progressive collapse re-solves the same shrinking structure many │
 *   │  times. Each solve needs distance-from-ground, and today each solve │
 *   │  recomputes it with a fresh O(N) BFS — even though removing one      │
 *   │  block only perturbs the distances of the nodes that routed THROUGH │
 *   │  it. This index keeps the distances between passes and, on a        │
 *   │  removal, fixes only the affected nodes.                            │
 *   │                                                                     │
 *   │  Removals are MONOTONE: deleting a node can only push other nodes   │
 *   │  FARTHER from ground (or cut them off entirely) — never closer. So  │
 *   │  the repair is a classic decremental-BFS relaxation: re-examine the │
 *   │  survivors that may have lost their shortest-path parent, in order  │
 *   │  of increasing distance, recomputing each from its surviving        │
 *   │  neighbours and propagating any increase to its children.          │
 *   │                                                                     │
 *   │  The result is, by construction, the exact same map a full BFS      │
 *   │  would produce on the post-removal graph — the same hop-counts, the │
 *   │  same "unreachable" set. {@code GroundDistanceIndexTest} pins this  │
 *   │  against a brute-force BFS over thousands of random removal frames. │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>The index owns its own copy of the adjacency and grounded set, so it is
 * independent of when the caller mutates the real graph. A node not present, or
 * present but with no path to ground, has distance {@link #UNREACHABLE}.
 */
public final class GroundDistanceIndex {

    /** Distance reported for a node that is absent or has no path to ground. */
    public static final int UNREACHABLE = Integer.MAX_VALUE;

    private final Map<NodePos, Set<NodePos>> adjacency = new HashMap<>();
    private final Set<NodePos> grounded = new HashSet<>();
    // Reached nodes only; getInt(absent) ⇒ UNREACHABLE via the default return value.
    private final Object2IntOpenHashMap<NodePos> dist = new Object2IntOpenHashMap<>();

    /**
     * Build the index over {@code nodes}, with {@code groundedNodes} as the BFS
     * sources and {@code neighbours} giving each node's adjacency (restricted to
     * {@code nodes} — anything outside the set is ignored).
     */
    public GroundDistanceIndex(
            Set<NodePos> nodes,
            Set<NodePos> groundedNodes,
            java.util.function.Function<NodePos, Set<NodePos>> neighbours) {
        dist.defaultReturnValue(UNREACHABLE); // absent ⇒ no path to ground
        for (NodePos n : nodes) {
            Set<NodePos> edges = new HashSet<>();
            for (NodePos m : neighbours.apply(n)) {
                if (nodes.contains(m)) {
                    edges.add(m);
                }
            }
            adjacency.put(n, edges);
            if (groundedNodes.contains(n)) {
                grounded.add(n);
            }
        }
        rebuildFromScratch();
    }

    /** Distance from ground, or {@link #UNREACHABLE} if absent / cut off. */
    public int distance(NodePos pos) {
        return dist.getInt(pos);
    }

    /**
     * The LIVE distance map (reached nodes only; {@code getInt} of an absent node
     * returns {@link #UNREACHABLE} via the default return value). The solver reads
     * this directly each pass instead of rebuilding its own BFS — so a K-step
     * cascade pays one build plus K cheap decremental repairs, not K full BFS.
     * Callers must NOT mutate it; mutate the index through {@link #remove}.
     */
    public Object2IntOpenHashMap<NodePos> distances() {
        return dist;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  DECREMENTAL UPDATE
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Remove {@code removed} from the index and repair the distances of every
     * surviving node EXACTLY — the map is afterwards identical to a full BFS over
     * the post-removal graph.
     */
    public void remove(Collection<NodePos> removed) {
        // 1. Detach the removed nodes, collecting the surviving neighbours that may
        //    have just lost their shortest-path parent.
        Set<NodePos> boundary = new HashSet<>();
        for (NodePos r : removed) {
            Set<NodePos> edges = adjacency.remove(r);
            grounded.remove(r);
            dist.removeInt(r);
            if (edges == null) {
                continue;
            }
            for (NodePos n : edges) {
                Set<NodePos> nEdges = adjacency.get(n);
                if (nEdges != null) {
                    nEdges.remove(r);
                    boundary.add(n); // n is a survivor (it is still in adjacency)
                }
            }
        }
        repair(boundary);
    }

    /**
     * Two-phase decremental BFS — the textbook-correct repair.
     *
     * <p><b>Phase 1 (identify the affected set).</b> A survivor is "affected" iff it
     * lost every shortest-path parent — i.e. it has no surviving, NOT-affected
     * neighbour at {@code dist-1}. Starting from the removed nodes' neighbours and
     * processing strictly by increasing distance, when a node is found affected we
     * re-examine its higher-distance neighbours (its children may have routed
     * through it). Processing low-to-high guarantees every {@code dist-1} verdict is
     * final before a node is judged, which is what makes a CUT-OFF CYCLE resolve:
     * two mutually-adjacent ungrounded nodes each fail the parent test (their only
     * neighbour is the other, also affected) instead of bumping each other up
     * forever.
     *
     * <p><b>Phase 2 (recompute the affected region).</b> Drop the affected nodes'
     * distances, then run a multi-source BFS into the affected region seeded from
     * its still-valid frontier (the non-affected neighbours, which kept their
     * correct distances). Any affected node the BFS never reaches is genuinely
     * unreachable. This is a plain BFS, so it always terminates — no oscillation.
     */
    private void repair(Collection<NodePos> seeds) {
        Set<NodePos> affected = identifyAffected(seeds);
        recomputeAffected(affected);
    }

    /** Phase 1: the set of survivors that lost every shortest-path parent. */
    private Set<NodePos> identifyAffected(Collection<NodePos> seeds) {
        Set<NodePos> affected = new HashSet<>();
        // Process candidates smallest-distance-first. Priority is snapshotted at
        // enqueue; distances are NOT mutated during phase 1, so it stays accurate.
        PriorityQueue<Entry> pq = new PriorityQueue<>(
                Comparator.<Entry>comparingInt(e -> e.priority).thenComparing(e -> e.pos, NodePos.CANONICAL_ORDER));
        for (NodePos s : seeds) {
            if (adjacency.containsKey(s)) {
                pq.add(new Entry(s, distance(s)));
            }
        }
        while (!pq.isEmpty()) {
            NodePos v = pq.poll().pos;
            if (affected.contains(v) || grounded.contains(v)) {
                continue; // ground is never affected; already-affected needs no re-check
            }
            int dv = distance(v);
            if (dv == UNREACHABLE || hasValidParent(v, dv, affected)) {
                continue; // still supported by a not-affected neighbour one step closer
            }
            // v lost every parent — mark it and re-examine the children that may have
            // routed through it (its neighbours one step FARTHER from ground).
            affected.add(v);
            for (NodePos w : adjacency.get(v)) {
                if (!affected.contains(w) && distance(w) == dv + 1) {
                    pq.add(new Entry(w, dv + 1));
                }
            }
        }
        return affected;
    }

    /** Does {@code v} still have a shortest-path parent: a not-affected neighbour at {@code dv - 1}? */
    private boolean hasValidParent(NodePos v, int dv, Set<NodePos> affected) {
        for (NodePos w : adjacency.get(v)) {
            if (!affected.contains(w) && distance(w) == dv - 1) {
                return true;
            }
        }
        return false;
    }

    /** Phase 2: re-BFS the affected region from its still-valid frontier; the rest is unreachable. */
    private void recomputeAffected(Set<NodePos> affected) {
        for (NodePos a : affected) {
            dist.removeInt(a); // tentatively unreachable until the BFS reaches it
        }
        // Seed each affected node with its best distance via a NON-affected (already
        // correct) neighbour, then relax outward within the affected region.
        PriorityQueue<Entry> pq = new PriorityQueue<>(
                Comparator.<Entry>comparingInt(e -> e.priority).thenComparing(e -> e.pos, NodePos.CANONICAL_ORDER));
        for (NodePos a : affected) {
            int best = grounded.contains(a) ? 0 : UNREACHABLE;
            for (NodePos w : adjacency.get(a)) {
                if (!affected.contains(w)) {
                    int dw = distance(w);
                    if (dw != UNREACHABLE && dw + 1 < best) {
                        best = dw + 1;
                    }
                }
            }
            if (best != UNREACHABLE) {
                pq.add(new Entry(a, best));
            }
        }
        while (!pq.isEmpty()) {
            Entry e = pq.poll();
            if (dist.containsKey(e.pos)) {
                continue; // already finalised at a smaller distance
            }
            dist.put(e.pos, e.priority);
            for (NodePos w : adjacency.get(e.pos)) {
                if (affected.contains(w) && !dist.containsKey(w)) {
                    pq.add(new Entry(w, e.priority + 1));
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  FULL BUILD (used once at construction)
    // ─────────────────────────────────────────────────────────────────────

    /** Plain multi-source BFS from every grounded node — the ground truth the repair must match. */
    private void rebuildFromScratch() {
        dist.clear();
        List<NodePos> frontier = new ArrayList<>();
        for (NodePos g : grounded) {
            dist.put(g, 0);
            frontier.add(g);
        }
        int d = 0;
        while (!frontier.isEmpty()) {
            List<NodePos> next = new ArrayList<>();
            for (NodePos cur : frontier) {
                for (NodePos n : adjacency.get(cur)) {
                    if (!dist.containsKey(n)) {
                        dist.put(n, d + 1);
                        next.add(n);
                    }
                }
            }
            frontier = next;
            d++;
        }
    }

    /** A heap entry pairing a node with the distance it had when enqueued. */
    private static final class Entry {
        final NodePos pos;
        final int priority;

        Entry(NodePos pos, int priority) {
            this.pos = pos;
            this.priority = priority;
        }
    }
}
