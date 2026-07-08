package dev.gesp.structural.assess;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Precomputed "what falls if I break this?" lookups for a structure.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                        COLLAPSE ATLAS                              │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  For each block, which blocks lose ALL support if it is removed?   │
 *   │  (i.e. become disconnected from every ground node.)                │
 *   │                                                                     │
 *   │       [D]                                                          │
 *   │        │     break [B] → {C, D} lose their only path to ground     │
 *   │       [C]            → dependents(B) = {C, D},  collapseSize = 2    │
 *   │        │                                                           │
 *   │       [B] ← critical support                                       │
 *   │        │                                                           │
 *   │       [A]                                                          │
 *   │        │                                                           │
 *   │      [GND]                                                         │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p><b>Why this is the right thing to precompute.</b> A full cascade also depends
 * on mutable stress (damage, reinforcement), so caching full outcomes would be
 * invalidated by every hit. <em>Connectivity</em> collapse — losing the path to
 * ground — depends only on the graph's <b>topology</b>, so these answers survive
 * the repeated damage-only hits a siege is made of, and only need rebuilding when
 * a block is actually added or removed. The cache keys on
 * {@link StructureGraph#version()} and clears itself when the shape changes.
 *
 * <p><b>Scope (honest).</b> This is the loss-of-support collapse — the dominant and
 * headline mode ("knock out the column, the tower comes down"). It does not include
 * the progressive overload trim of cantilevers or debris pancaking, which depend on
 * live stress and are still the solver's job. So {@code dependents(x)} is a lower
 * bound on (and the connectivity core of) a real cascade.
 *
 * <p>Powers cheap prediction: a what-if UI ("breaking this drops 47 blocks"),
 * weak-point / criticality ranking, and siege-weapon targeting — none of which want
 * to run a full simulation per candidate.
 */
public final class CollapseAtlas {

    private final StructureGraph graph;
    private final Map<NodePos, Set<NodePos>> dependentsCache = new HashMap<>();
    private long builtForVersion = Long.MIN_VALUE;

    /**
     * Ground nodes for the current version — computed once when the cache is
     * primed and reused by every {@link #computeDependents} call in the same
     * version. This avoids an O(V)-scan + HashSet allocation per cache miss
     * (the previous {@code graph.getGroundNodes()} call inside each BFS).
     */
    private Set<NodePos> cachedGroundNodes = Set.of();

    public CollapseAtlas(StructureGraph graph) {
        this.graph = graph;
    }

    /** The graph this atlas reads (so callers can detect a swapped-out graph). */
    public StructureGraph graph() {
        return graph;
    }

    /**
     * The set of non-ground blocks that lose all support — every path to ground —
     * if {@code pos} is removed. Cached; the cache is invalidated automatically
     * when the graph's topology changes.
     *
     * @return an unmodifiable set (empty if {@code pos} isn't load-bearing here)
     */
    public Set<NodePos> dependents(NodePos pos) {
        invalidateIfStale();
        return dependentsCache.computeIfAbsent(pos, this::computeDependents);
    }

    /** How many blocks fall (lose support) if {@code pos} is removed. */
    public int collapseSize(NodePos pos) {
        return dependents(pos).size();
    }

    /**
     * Rank load-bearing blocks by how much collapses if each is removed, most
     * critical first. O(N·E) — for "find the weak points," not the hot path.
     *
     * @param topN cap on results (use {@link Integer#MAX_VALUE} for all)
     */
    public List<Critical> rankBySupport(int topN) {
        invalidateIfStale();
        List<Critical> ranked = new ArrayList<>();
        for (NodePos pos : graph.getAllPositions()) {
            Node node = graph.getNode(pos);
            if (node == null || node.isGrounded()) {
                continue;
            }
            int size = collapseSize(pos);
            if (size > 0) {
                ranked.add(new Critical(pos, size));
            }
        }
        ranked.sort((a, b) -> Integer.compare(b.collapseSize(), a.collapseSize()));
        return ranked.size() > topN ? ranked.subList(0, topN) : ranked;
    }

    /** A load-bearing block and how many blocks depend on it for support. */
    public record Critical(NodePos pos, int collapseSize) {}

    // ─────────────────────────────────────────────────────────────────────

    private void invalidateIfStale() {
        if (graph.version() != builtForVersion) {
            dependentsCache.clear();
            builtForVersion = graph.version();
            // Hoist the ground-node scan: O(V) + one HashSet allocation, paid once per
            // atlas version instead of once per cache miss inside computeDependents.
            cachedGroundNodes = graph.getGroundNodes();
        }
    }

    /** BFS to ground with {@code removed} taken out; anything non-ground left unreached depends on it. */
    private Set<NodePos> computeDependents(NodePos removed) {
        if (!graph.hasBlock(removed)) {
            return Collections.emptySet();
        }
        Set<NodePos> reachesGround = new HashSet<>();
        Queue<NodePos> queue = new LinkedList<>();
        for (NodePos ground : cachedGroundNodes) {
            if (!ground.equals(removed) && reachesGround.add(ground)) {
                queue.add(ground);
            }
        }
        while (!queue.isEmpty()) {
            NodePos current = queue.poll();
            for (NodePos neighbor : graph.getNeighbors(current)) {
                if (!neighbor.equals(removed) && reachesGround.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }
        Set<NodePos> dependents = new HashSet<>();
        for (NodePos pos : graph.getAllPositions()) {
            if (pos.equals(removed)) {
                continue;
            }
            Node node = graph.getNode(pos);
            if (node != null && !node.isGrounded() && !reachesGround.contains(pos)) {
                dependents.add(pos);
            }
        }
        return Collections.unmodifiableSet(dependents);
    }
}
