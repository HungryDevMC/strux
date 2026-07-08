package dev.gesp.structural.solver;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Every moment-arm query for ONE solve, answered in O(scope·α) by a single
 * contour-and-union-find pass instead of a per-anchor BFS.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                 ARM = COMPONENT ABOVE A CONTOUR LINE                 │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  The arm for (startPos, anchorDistance) is the connected component  │
 *   │  of {nodes with distanceFromGround > anchorDistance} that contains  │
 *   │  startPos. Lower anchor ⇒ the contour drops ⇒ more nodes qualify ⇒  │
 *   │  components grow and merge. So if we add nodes in DECREASING         │
 *   │  distance order with union-find, the union-find state right after   │
 *   │  every node with distance > a has been added IS the set of arms for │
 *   │  anchorDistance a.                                                  │
 *   │                                                                     │
 *   │      add level d=5 ──▶ arms for anchorDistance 4                    │
 *   │      add level d=4 ──▶ arms for anchorDistance 3                    │
 *   │      add level d=3 ──▶ arms for anchorDistance 2  ...               │
 *   │                                                                     │
 *   │  Per component we aggregate mass (a sum), reach (a count) and the   │
 *   │  beam flag (≥2 distinct supports — see below). A query is then just │
 *   │  a lookup of startPos's component aggregate at its anchor level.    │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p><b>Why the anchor's identity does not matter</b> (so a single threshold
 * answers every anchor at that distance): a node is in the arm iff its
 * distance is {@code > anchorDistance}, so the anchor (at exactly
 * {@code anchorDistance}) is never a member regardless of which node it is.
 * For the beam test, an arm is a cantilever iff its ONLY support at
 * {@code <= anchorDistance} is the anchor. The anchor is always one such
 * support (it is adjacent to {@code startPos} at {@code anchorDistance}), so
 * "a support other than the anchor exists" is exactly "there are at least TWO
 * distinct support nodes touching the arm" — a count that does not name the
 * anchor. Hence {@code isBeam = supports.size() >= 2}.
 *
 * <p><b>Supports sit exactly one level below the lowest member.</b> A support
 * is a neighbor of a member with {@code distance <= a}; a member has
 * {@code distance >= a+1}; face-adjacency limits the gap to 1, so a support's
 * distance is exactly {@code a}. They are therefore the nodes added on the
 * very next (lower) level, which is why we collect each member's
 * {@code distance == a} neighbours while adding level {@code a+1} and clear
 * the support sets before dropping to the next level.
 *
 * <p><b>Unreached (distance MAX_VALUE) nodes</b> are never members and never
 * supports — exactly as the old BFS treated them. Because a node adjacent to
 * any finite-distance node is itself finite (BFS gives it that distance + 1),
 * an arm's {@code startPos} (a neighbour of a finite anchor) is always finite,
 * so the seed is always a real component member.
 *
 * <p><b>Floating-point determinism:</b> the only sum is {@code totalMass}.
 * Mass values are summed in union order rather than BFS order, but in every
 * structure these solvers see the masses are small exact doubles whose totals
 * stay far below 2^53, so integer-valued sums are associative to the bit —
 * the {@code ArmEquivalenceTest} pins this against the old BFS exactly.
 */
class MomentArmIndex {

    private final StructureGraph graph;
    private final Object2IntOpenHashMap<NodePos> distanceFromGround;
    private final PhysicsConfig config;

    /** Union-find parent / aggregates, indexed by a dense node id. Absent → -1. */
    private final Object2IntOpenHashMap<NodePos> id = new Object2IntOpenHashMap<>();

    private final List<NodePos> nodeOf = new ArrayList<>();
    private int[] parent = new int[0];
    private int[] rank = new int[0];
    private double[] mass = new double[0];
    private int[] reach = new int[0];
    // Up to two distinct supports per component (we only need "≥ 2").
    private NodePos[] firstSupport = new NodePos[0];
    private NodePos[] secondSupport = new NodePos[0];
    // Node ids that had a support set this level, so we clear only those (the
    // support sets are level-local) instead of sweeping the whole array.
    private final List<Integer> supportsTouched = new ArrayList<>();

    /** Answers: (startPos, anchorDistance) → arm aggregate. */
    private final Map<ArmQuery, ArmInfo> answers = new HashMap<>();

    /**
     * Debug-only geometry per answered query: the arm's union-find root id and its
     * beam flag, so {@link #members} can enumerate the component and {@link #isBeam}
     * can report it WITHOUT recomputing anything. Populated only when arm capture is
     * on; empty (and free) otherwise.
     */
    private final Map<ArmQuery, ArmDetail> details = new HashMap<>();

    private final boolean captureArms;

    MomentArmIndex(
            StructureGraph graph,
            Object2IntOpenHashMap<NodePos> distanceFromGround,
            PhysicsConfig config,
            boolean captureArms) {
        this.graph = graph;
        this.distanceFromGround = distanceFromGround;
        this.config = config;
        this.captureArms = captureArms;
        id.defaultReturnValue(-1); // dense ids are >= 0, so -1 means "not yet assigned"
        build();
    }

    /**
     * Arm aggregate for {@code startPos} as seen from an anchor at
     * {@code anchorDistance}. The caller guarantees {@code startPos} is a
     * horizontal neighbour of the anchor with a strictly greater distance, so
     * the query was enumerated during {@link #build()} and is always present.
     */
    ArmInfo arm(NodePos startPos, int anchorDistance) {
        ArmInfo info = answers.get(new ArmQuery(startPos, anchorDistance));
        // Defensive: an unexpected query (no member at distance anchorDistance+1)
        // means an empty arm — matches a BFS that walks nothing reachable.
        return info != null ? info : new ArmInfo(0.0, 0);
    }

    // ── one contour pass ───────────────────────────────────────────────

    private void build() {
        // (a) Enumerate the TRUE-cantilever queries — the only ones the moment
        //     pass actually asks the index (beams are killed by the cheap fast
        //     path at the call site). For each node c, each horizontal neighbour
        //     s that is farther from ground AND has no alternative support is an
        //     arm root anchored at distance(c). Group roots by anchor; remember
        //     the lowest anchor so we only flood the region above it.
        Map<Integer, List<NodePos>> rootsByAnchor = new HashMap<>();
        int minAnchor = Integer.MAX_VALUE;

        // distanceFromGround only ever holds REACHED nodes (calculateDistances
        // never stores MAX_VALUE), so every key here is a finite-distance anchor;
        // unreached neighbours surface as the getOrDefault MAX_VALUE below.
        for (NodePos pos : distanceFromGround.keySet()) {
            int distance = distanceFromGround.getInt(pos);
            for (NodePos neighbor : graph.neighborsOf(pos)) {
                if (neighbor.y() != pos.y()) {
                    continue; // only horizontal neighbours seed moment arms
                }
                int neighborDistance = distanceFromGround.getInt(neighbor);
                if (neighborDistance != Integer.MAX_VALUE
                        && neighborDistance > distance
                        && !StressSolver.hasAlternativeSupport(graph, neighbor, pos, distanceFromGround, distance)) {
                    rootsByAnchor.computeIfAbsent(distance, newList()).add(neighbor);
                    minAnchor = Math.min(minAnchor, distance);
                }
            }
        }
        if (rootsByAnchor.isEmpty()) {
            return; // no true cantilevers — nothing to answer
        }

        // (b) Flood from every root through nodes strictly above the lowest
        //     anchor (distance > minAnchor). That is exactly the set of nodes
        //     that can be an arm member for ANY of these queries, so the contour
        //     only touches the cantilever region, never the whole structure.
        Map<Integer, List<NodePos>> nodesByLevel = collectMembers(rootsByAnchor, minAnchor);

        // (c) Walk levels from the top down. After fully adding level L, the
        //     union-find holds every member with distance >= L, i.e. the arms for
        //     anchorDistance = L-1; resolve that anchor's queries, then clear the
        //     freshly-built supports before dropping to the next level.
        for (Map.Entry<Integer, List<NodePos>> entry : nodesByLevel.entrySet()) {
            int level = entry.getKey();
            clearSupports();
            for (NodePos member : entry.getValue()) {
                addMember(member, level);
            }
            List<NodePos> roots = rootsByAnchor.get(level - 1);
            if (roots != null) {
                for (NodePos rootNode : roots) {
                    ArmQuery query = new ArmQuery(rootNode, level - 1);
                    int root = find(idOf(rootNode));
                    answers.putIfAbsent(query, aggregate(root));
                    if (captureArms) {
                        // Snapshot the geometry now, while the union-find holds exactly
                        // this anchor's arms (membership is level-local). Done lazily so
                        // the no-capture path never builds these.
                        details.putIfAbsent(query, snapshotDetail(rootNode, level - 1, root));
                    }
                }
            }
        }
    }

    /**
     * Flood outward from the arm roots through nodes with
     * {@code distance > minAnchor}, returning those member candidates grouped
     * by distance level (highest first). Every arm of every enumerated query
     * lives inside this set, so the contour pass scans only the cantilever
     * region instead of the entire subgraph.
     */
    private Map<Integer, List<NodePos>> collectMembers(Map<Integer, List<NodePos>> rootsByAnchor, int minAnchor) {
        Map<Integer, List<NodePos>> nodesByLevel = new TreeMap<>(Collections.reverseOrder());
        Set<NodePos> seen = new HashSet<>();
        Queue<NodePos> queue = new ArrayDeque<>();
        for (List<NodePos> roots : rootsByAnchor.values()) {
            for (NodePos rootNode : roots) {
                if (seen.add(rootNode)) {
                    queue.add(rootNode);
                }
            }
        }
        while (!queue.isEmpty()) {
            NodePos current = queue.poll();
            int distance = distanceFromGround.getInt(current);
            nodesByLevel.computeIfAbsent(distance, newList()).add(current);
            for (NodePos neighbor : graph.neighborsOf(current)) {
                int neighborDistance = distanceFromGround.getInt(neighbor);
                if (neighborDistance != Integer.MAX_VALUE && neighborDistance > minAnchor && seen.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }
        return nodesByLevel;
    }

    /** Add one node at distance {@code level}, unioning it into already-present arms. */
    private void addMember(NodePos member, int level) {
        int memberId = idOf(member);
        for (NodePos neighbor : graph.neighborsOf(member)) {
            int neighborDistance = distanceFromGround.getInt(neighbor);
            if (neighborDistance == level - 1) {
                // A support sitting one level below: an alternative ground path.
                addSupport(memberId, neighbor);
            } else if (neighborDistance >= level
                    && neighborDistance != Integer.MAX_VALUE
                    && id.containsKey(neighbor)
                    && sameBeam(member, neighbor)) {
                // Already-added member of the same arm (same or higher distance).
                // When section-depth physics is on, a horizontal cantilever bends
                // in its own plane, so we only union same-y members: a node stacked
                // vertically on the arm is then a separate beam layer (it reaches
                // ground down its own column as vertical load), not more lever arm.
                // That lets the section-depth (d²) term make a genuinely deeper beam
                // stronger. With the flag off — or for a one-thick (single-plane)
                // structure — every arm is one plane, so this is the legacy union and
                // the result is byte-identical to the old index.
                union(memberId, idOf(neighbor));
            }
        }
    }

    /**
     * Are {@code first} and {@code second} part of the same moment arm? Always yes in
     * the legacy model; with section-depth physics on, only if they share a
     * y-level (a horizontal beam bends in its own plane — see {@link #addMember}).
     */
    private boolean sameBeam(NodePos first, NodePos second) {
        return !config.isBendingDepthEnabled() || first.y() == second.y();
    }

    /**
     * Debug-only: snapshot the arm's geometry at the moment its anchor is resolved.
     * Members are every already-added node whose union-find root is {@code root}
     * (the connected component above the contour); the beam flag is read from the
     * component's two-support test, exactly as {@link #aggregate} reads it. Member
     * order is canonical so the trace is run-stable.
     */
    private ArmDetail snapshotDetail(NodePos startPos, int anchorDistance, int root) {
        List<NodePos> members = new ArrayList<>();
        for (NodePos pos : nodeOf) {
            if (find(idOf(pos)) == root) {
                members.add(pos);
            }
        }
        members.sort(NodePos.CANONICAL_ORDER);
        boolean isBeam = firstSupport[root] != null && secondSupport[root] != null;
        return new ArmDetail(List.copyOf(members), isBeam);
    }

    /** The arm's member positions for an answered query, or empty if capture was off. */
    List<NodePos> members(NodePos startPos, int anchorDistance) {
        ArmDetail detail = details.get(new ArmQuery(startPos, anchorDistance));
        return detail != null ? detail.members() : List.of();
    }

    /** Whether the arm for an answered query had a second support (a beam), false if capture was off. */
    boolean isBeam(NodePos startPos, int anchorDistance) {
        ArmDetail detail = details.get(new ArmQuery(startPos, anchorDistance));
        return detail != null && detail.isBeam();
    }

    private ArmInfo aggregate(int root) {
        boolean isBeam = firstSupport[root] != null && secondSupport[root] != null; // ≥ 2 distinct supports
        if (isBeam) {
            double reduced = mass[root] * (1.0 - config.getBeamMomentReduction());
            return new ArmInfo(reduced, reach[root]);
        }
        return new ArmInfo(mass[root], reach[root]);
    }

    // ── union-find with per-root aggregates ────────────────────────────

    private int idOf(NodePos pos) {
        int existing = id.getInt(pos);
        if (existing != -1) {
            return existing;
        }
        int newId = nodeOf.size();
        id.put(pos, newId);
        nodeOf.add(pos);
        ensureCapacity(newId + 1);
        parent[newId] = newId;
        rank[newId] = 0;
        Node node = graph.getNode(pos);
        mass[newId] = node == null ? 0.0 : node.mass();
        reach[newId] = 1;
        firstSupport[newId] = null;
        secondSupport[newId] = null;
        return newId;
    }

    private int find(int nodeId) {
        while (parent[nodeId] != nodeId) {
            parent[nodeId] = parent[parent[nodeId]]; // path halving
            nodeId = parent[nodeId];
        }
        return nodeId;
    }

    private void union(int nodeA, int nodeB) {
        int rootA = find(nodeA);
        int rootB = find(nodeB);
        if (rootA == rootB) {
            return;
        }
        // Union by rank; merge aggregates into the surviving root.
        if (rank[rootA] < rank[rootB]) {
            int temp = rootA;
            rootA = rootB;
            rootB = temp;
        }
        parent[rootB] = rootA;
        if (rank[rootA] == rank[rootB]) {
            rank[rootA]++;
        }
        mass[rootA] += mass[rootB];
        reach[rootA] += reach[rootB];
        mergeSupports(rootA, firstSupport[rootB]);
        mergeSupports(rootA, secondSupport[rootB]);
    }

    private void addSupport(int nodeId, NodePos support) {
        mergeSupports(find(nodeId), support);
    }

    private void mergeSupports(int root, NodePos support) {
        if (support == null) {
            return;
        }
        if (firstSupport[root] == null) {
            firstSupport[root] = support;
            supportsTouched.add(root);
        } else if (!firstSupport[root].equals(support) && secondSupport[root] == null) {
            secondSupport[root] = support; // second DISTINCT support — that's all we need
        }
    }

    /** Wipe the (level-local) support sets, touching only the roots we set. */
    private void clearSupports() {
        for (int rootId : supportsTouched) {
            firstSupport[rootId] = null;
            secondSupport[rootId] = null;
        }
        supportsTouched.clear();
    }

    private void ensureCapacity(int needed) {
        if (parent.length >= needed) {
            return;
        }
        int capacity = Math.max(needed, parent.length * 2 + 16);
        parent = grow(parent, capacity);
        rank = grow(rank, capacity);
        reach = grow(reach, capacity);
        mass = grow(mass, capacity);
        firstSupport = grow(firstSupport, capacity);
        secondSupport = grow(secondSupport, capacity);
    }

    private int[] grow(int[] source, int capacity) {
        int[] grown = new int[capacity];
        System.arraycopy(source, 0, grown, 0, source.length);
        return grown;
    }

    private double[] grow(double[] source, int capacity) {
        double[] grown = new double[capacity];
        System.arraycopy(source, 0, grown, 0, source.length);
        return grown;
    }

    private NodePos[] grow(NodePos[] source, int capacity) {
        NodePos[] grown = new NodePos[capacity];
        System.arraycopy(source, 0, grown, 0, source.length);
        return grown;
    }

    private Function<Integer, List<NodePos>> newList() {
        return key -> new ArrayList<>();
    }
}
