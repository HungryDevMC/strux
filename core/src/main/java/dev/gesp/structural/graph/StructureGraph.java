package dev.gesp.structural.graph;

import dev.gesp.structural.connectivity.ChunkConnectivity;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * A graph of connected blocks in a structure.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                      WHAT IS A STRUCTURE GRAPH?                     │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  It's a collection of blocks that know about their neighbors.      │
 *   │                                                                     │
 *   │  Example structure:                                                │
 *   │                                                                     │
 *   │           [C]                                                      │
 *   │            │                                                       │
 *   │       [A]─[B]─[D]         Each block knows its neighbors:          │
 *   │            │              • B is connected to A, C, D, GND         │
 *   │          [GND]            • A is connected to B only               │
 *   │                           • C is connected to B only               │
 *   │                                                                     │
 *   │                                                                     │
 *   │  TWO BLOCKS ARE NEIGHBORS IF THEY SHARE A FACE:                    │
 *   │                                                                     │
 *   │       [A][B]  ← neighbors (share a face)                           │
 *   │                                                                     │
 *   │       [A]                                                          │
 *   │          [B]  ← NOT neighbors (only touch at corner)               │
 *   │                                                                     │
 *   │                                                                     │
 *   │  THE 6 POSSIBLE NEIGHBORS (face-adjacent):                         │
 *   │                                                                     │
 *   │              [+Y]           (+Y = above)                           │
 *   │               │             (-Y = below)                           │
 *   │        [−X]──[■]──[+X]      (+X = east)                            │
 *   │              /│             (-X = west)                            │
 *   │           [+Z]              (+Z = south)                           │
 *   │            [-Z]             (-Z = north)                           │
 *   │           (behind)                                                 │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class StructureGraph {

    private final Map<NodePos, Node> nodes = new HashMap<>();
    private final Map<NodePos, Set<NodePos>> adjacency = new HashMap<>();

    /**
     * Chunk size for the maintained {@link ChunkConnectivity} index. Kept small so
     * the per-edit interior rebuild (bounded by the occupied cells in one chunk) is
     * cheap on the common single-block break, at the cost of a slightly larger
     * overlay for the rarer whole-graph floating query.
     */
    private static final int CONNECTIVITY_CHUNK = 8;

    /**
     * A hierarchical "connected to bedrock?" index kept in lock-step with every
     * topology edit (see {@link #addNode}, {@link #removeBlock}, {@link #connect},
     * {@link #disconnect}). The whole-graph {@link #getFloatingBlocks()} /
     * {@link #getBlocksConnectedToGround()} answer from it instead of re-flooding
     * the world — O(ports) over the chunk overlay rather than O(N) BFS. Its verdict
     * is exact (pinned byte-for-byte against BFS in {@code ChunkConnectivityTest}),
     * so this is purely an accelerator: the floating set is unchanged.
     */
    private final ChunkConnectivity connectivity = new ChunkConnectivity(CONNECTIVITY_CHUNK);

    /**
     * Monotonic counter bumped on every topology change (node added/removed, edge
     * connected/disconnected). It does NOT change for stress, damage or
     * reinforcement edits — only for the shape of the graph. Caches that depend
     * purely on connectivity (e.g. {@link dev.gesp.structural.assess.CollapseAtlas})
     * use this to know when to invalidate, so they survive the repeated
     * damage-only hits of a siege.
     */
    private long version;

    /** Topology version (see {@link #version}). */
    public long version() {
        return version;
    }

    /**
     * Monotonic modification stamp bumped on <em>any</em> state change that can
     * alter a settle's outcome: topology (node added/removed, edge connected/
     * disconnected) <em>and</em> per-node damage, repair and reinforcement. Unlike
     * {@link #version} — which is topology-only so connectivity caches survive
     * damage-only siege hits — this stamp also moves for damage/reinforcement.
     *
     * <p>The async settle path snapshots this value alongside a
     * {@link #copySubgraph(Set)} of the scope; when the off-thread solve completes
     * the main thread compares the live stamp against the snapshot's to decide
     * whether the graph changed underneath the in-flight answer (a stale solve
     * must be discarded and re-run). See {@code AsyncSettleCoordinator}.
     */
    private long modCount;

    /** Modification stamp (see {@link #modCount}). */
    public long modCount() {
        return modCount;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  ADDING AND REMOVING BLOCKS
    // ─────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────
    //  GENERIC API: define your own nodes and connections
    //
    //  Use these when a node is NOT a 1×1×1 block on a grid — e.g. a prefab,
    //  a truss joint, or a vertex. You decide what connects to what by calling
    //  connect() yourself, instead of letting the engine derive adjacency from
    //  grid positions. The solver only ever looks at nodes + their edges, so
    //  any topology you build here behaves like a real structure.
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Add a node WITHOUT auto-connecting it to anything.
     *
     * <p>The node starts with no edges. Wire it up with {@link #connect}.
     * No-op if a node already exists at this position.
     */
    public void addNode(NodePos pos, MaterialSpec spec, boolean grounded) {
        if (nodes.containsKey(pos)) {
            return; // Already exists
        }
        nodes.put(pos, new Node(pos, spec, grounded));
        adjacency.put(pos, new HashSet<>());
        connectivity.add(pos, grounded, Set.of()); // edges arrive via connect()
        version++;
        modCount++;
    }

    /**
     * Convenience: add a ground/foundation node (no auto-connect).
     */
    public void addGroundNode(NodePos pos) {
        addNode(pos, MaterialSpec.GROUND, true);
    }

    /**
     * Create an undirected connection between two existing nodes.
     *
     * <p>This is how load travels between nodes. Connecting two nodes is what
     * makes one able to support (or be supported by) the other. Idempotent;
     * connecting a node to itself is a no-op.
     *
     * @throws IllegalArgumentException if either node does not exist
     */
    public void connect(NodePos a, NodePos b) {
        if (a.equals(b)) {
            return;
        }
        if (!nodes.containsKey(a) || !nodes.containsKey(b)) {
            throw new IllegalArgumentException("Cannot connect non-existent nodes: " + a + " <-> " + b);
        }
        boolean changed = adjacency.get(a).add(b);
        changed |= adjacency.get(b).add(a);
        if (changed) {
            connectivity.connect(a, b);
            version++; // only a REAL change invalidates topology caches
            modCount++;
        }
    }

    /**
     * Remove the connection between two nodes (if any). The nodes themselves
     * are left in place.
     */
    public void disconnect(NodePos a, NodePos b) {
        boolean changed = false;
        Set<NodePos> edgesA = adjacency.get(a);
        if (edgesA != null) {
            changed = edgesA.remove(b);
        }
        Set<NodePos> edgesB = adjacency.get(b);
        if (edgesB != null) {
            changed |= edgesB.remove(a);
        }
        if (changed) {
            connectivity.disconnect(a, b);
            version++; // only a REAL change invalidates topology caches
            modCount++;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  GRID CONVENIENCE: nodes are 1×1×1 cubes, edges = shared faces
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Add a block to the structure, auto-connecting it to any existing
     * face-adjacent neighbors (6-connectivity).
     *
     * <p>This is sugar over {@link #addNode} + {@link #connect} for the common
     * grid case (e.g. the Minecraft adapter). For non-grid topologies, use the
     * generic API above instead.
     *
     * <pre>
     *   Before:        After addBlock(B):
     *
     *      [A]            [A]
     *       │              │
     *     [GND]          [GND]─[B]   ← B is now connected to GND
     * </pre>
     */
    public void addBlock(NodePos pos, MaterialSpec spec, boolean grounded) {
        if (nodes.containsKey(pos)) {
            return; // Already exists
        }
        addNode(pos, spec, grounded);

        // Connect to existing face-adjacent neighbors
        for (NodePos neighborPos : getAdjacentPositions(pos)) {
            if (nodes.containsKey(neighborPos)) {
                connect(pos, neighborPos);
            }
        }
    }

    /**
     * Convenience: add a ground block at the given position (grid-connected).
     */
    public void addGroundBlock(NodePos pos) {
        addBlock(pos, MaterialSpec.GROUND, true);
    }

    /**
     * Remove a block from the structure.
     *
     * <pre>
     *   Before:           After removeBlock(B):
     *
     *      [C]               [C]     ← C is now disconnected!
     *       │
     *   [A]─[B]─[D]      [A]   [D]   ← A and D lose their connection to B
     *       │
     *     [GND]            [GND]
     * </pre>
     *
     * @return the removed node, or null if it didn't exist
     */
    public Node removeBlock(NodePos pos) {
        Node removed = nodes.remove(pos);
        if (removed == null) {
            return null;
        }
        version++;
        modCount++;
        connectivity.remove(pos); // detaches pos and its edges from the index too

        // Remove edges from neighbors
        Set<NodePos> neighbors = adjacency.remove(pos);
        if (neighbors != null) {
            for (NodePos neighborPos : neighbors) {
                Set<NodePos> neighborEdges = adjacency.get(neighborPos);
                if (neighborEdges != null) {
                    neighborEdges.remove(pos);
                }
            }
        }

        return removed;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PER-NODE STATE MUTATORS (bump {@link #modCount} — see the async settle path)
    //
    //  These delegate to the Node but route through the graph so the modification
    //  stamp moves on damage/repair/reinforcement, not just topology. Adapters that
    //  weaken or reinforce a block mid-siege should mutate through these (rather than
    //  Node directly) so an in-flight async solve over that block re-solves instead
    //  of applying a stale answer.
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Add persistent damage to the node at {@code pos}. No-op (returns false) if
     * no node exists there.
     */
    public boolean applyDamage(NodePos pos, double amount) {
        Node node = nodes.get(pos);
        if (node == null) {
            return false;
        }
        node.addDamage(amount);
        modCount++;
        return true;
    }

    /**
     * Clear all persistent damage on the node at {@code pos}. No-op (returns
     * false) if no node exists there.
     */
    public boolean repairNode(NodePos pos) {
        Node node = nodes.get(pos);
        if (node == null) {
            return false;
        }
        node.repair();
        modCount++;
        return true;
    }

    /**
     * Set the reinforcement multiplier on the node at {@code pos}. No-op (returns
     * false) if no node exists there.
     */
    public boolean reinforceNode(NodePos pos, double multiplier) {
        Node node = nodes.get(pos);
        if (node == null) {
            return false;
        }
        node.setReinforcement(multiplier);
        modCount++;
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  QUERYING THE GRAPH
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Does a block exist at this position?
     */
    public boolean hasBlock(NodePos pos) {
        return nodes.containsKey(pos);
    }

    /**
     * Get the block at this position.
     *
     * @return the node, or null if no block exists there
     */
    public Node getNode(NodePos pos) {
        return nodes.get(pos);
    }

    /**
     * Get all blocks in this structure.
     */
    public Collection<Node> getAllNodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    /**
     * Get all positions in this structure.
     */
    public Set<NodePos> getAllPositions() {
        return Collections.unmodifiableSet(nodes.keySet());
    }

    /**
     * How many blocks are in this structure?
     */
    public int size() {
        return nodes.size();
    }

    /**
     * Is this structure empty?
     */
    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  NEIGHBORS AND ADJACENCY
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Get all neighbors of a block (blocks that share a face with it).
     *
     * <pre>
     *       [C]
     *        │
     *   [A]─[B]─[D]     getNeighbors(B) = {A, C, D, GND}
     *        │
     *      [GND]
     * </pre>
     *
     * @return set of neighbor positions (empty if block doesn't exist)
     */
    public Set<NodePos> getNeighbors(NodePos pos) {
        Set<NodePos> neighbors = adjacency.get(pos);
        if (neighbors == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(neighbors);
    }

    /**
     * In-module hot-loop accessor for neighbors: returns the BACKING set directly
     * (no {@link Collections#unmodifiableSet} wrapper allocation), so a solver or
     * cascade pass that calls this per node per pass does not allocate a wrapper on
     * every call. The returned set is the live adjacency set — callers MUST only
     * iterate it, never mutate it. Same contents/iteration order as
     * {@link #getNeighbors(NodePos)}; {@code public} for the {@code solver} package
     * but not part of the external API. Empty set for an absent node.
     */
    public Set<NodePos> neighborsOf(NodePos pos) {
        Set<NodePos> neighbors = adjacency.get(pos);
        return neighbors == null ? Collections.emptySet() : neighbors;
    }

    /**
     * Get all 6 face-adjacent positions (whether or not blocks exist there).
     *
     * <pre>
     *   For position (5, 10, 3), returns:
     *     (6, 10, 3)   +X
     *     (4, 10, 3)   -X
     *     (5, 11, 3)   +Y (above)
     *     (5, 9, 3)    -Y (below)
     *     (5, 10, 4)   +Z
     *     (5, 10, 2)   -Z
     * </pre>
     */
    public List<NodePos> getAdjacentPositions(NodePos pos) {
        return List.of(
                new NodePos(pos.x() + 1, pos.y(), pos.z()), // +X
                new NodePos(pos.x() - 1, pos.y(), pos.z()), // -X
                new NodePos(pos.x(), pos.y() + 1, pos.z()), // +Y (above)
                new NodePos(pos.x(), pos.y() - 1, pos.z()), // -Y (below)
                new NodePos(pos.x(), pos.y(), pos.z() + 1), // +Z
                new NodePos(pos.x(), pos.y(), pos.z() - 1) // -Z
                );
    }

    // ─────────────────────────────────────────────────────────────────────
    //  GROUND NODES
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Get all ground/foundation nodes.
     *
     * <pre>
     *      [A]
     *       │
     *   [G1]─[B]─[G2]     getGroundNodes() = {G1, G2}
     * </pre>
     */
    public Set<NodePos> getGroundNodes() {
        Set<NodePos> groundNodes = new HashSet<>();
        for (Node node : nodes.values()) {
            if (node.isGrounded()) {
                groundNodes.add(node.pos());
            }
        }
        return groundNodes;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  CONNECTIVITY AND DEPENDENT SUBGRAPH
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Find all blocks that are connected to any ground node.
     *
     * <pre>
     *      [A]─[B]        [X]─[Y]
     *       │
     *     [GND]
     *
     *   getBlocksConnectedToGround() = {GND, A, B}
     *   (X and Y are floating - not connected to ground)
     * </pre>
     */
    public Set<NodePos> getBlocksConnectedToGround() {
        // Answered from the maintained chunk index instead of a fresh whole-graph
        // BFS. The verdict is exact (pinned against BFS in ChunkConnectivityTest),
        // so this set is identical to the flood-fill it replaces.
        Set<NodePos> connected = new HashSet<>();
        for (NodePos pos : nodes.keySet()) {
            if (connectivity.connectedToGround(pos)) {
                connected.add(pos);
            }
        }
        return connected;
    }

    /**
     * Find all blocks that are NOT connected to ground (floating).
     *
     * <pre>
     *      [A]─[B]        [X]─[Y]
     *       │
     *     [GND]
     *
     *   getFloatingBlocks() = {X, Y}
     * </pre>
     */
    public Set<NodePos> getFloatingBlocks() {
        // Whole-graph floating = every node the chunk index says is NOT connected to
        // bedrock. Byte-identical to the old `nodes − BFS(ground)`; just not a BFS.
        Set<NodePos> floating = new HashSet<>();
        for (NodePos pos : nodes.keySet()) {
            if (!connectivity.connectedToGround(pos)) {
                floating.add(pos);
            }
        }
        return floating;
    }

    /**
     * Is the block at {@code pos} still connected to bedrock? Answered from the
     * chunk-connectivity index, so a SINGLE-block check is O(1) amortized (the
     * reachable-from-ground set is maintained incrementally and reused) rather than
     * the O(N) cost of computing {@link #getFloatingBlocks()} and testing membership.
     * Use this when you only care about one block — e.g. "did breaking here strand
     * this neighbour?". {@code false} for a block that isn't in the graph.
     */
    public boolean isConnectedToGround(NodePos pos) {
        return connectivity.connectedToGround(pos);
    }

    /**
     * Like {@link #getFloatingBlocks()} but restricted to a {@code scope} of
     * positions — only blocks within the scope are considered, and only paths
     * to ground that stay within the scope count as support.
     *
     * <p>This is what lets a cascade re-settle just the affected structure
     * instead of the whole world. It is exactly equivalent to
     * {@link #getFloatingBlocks()} when {@code scope} is all positions, because
     * a connected component's path to ground never leaves the component.
     */
    public Set<NodePos> getFloatingBlocks(Set<NodePos> scope) {
        // BFS to ground, staying inside the scope.
        Set<NodePos> connected = new HashSet<>();
        Queue<NodePos> queue = new ArrayDeque<>();
        for (NodePos pos : scope) {
            Node node = nodes.get(pos);
            if (node != null && node.isGrounded()) {
                connected.add(pos);
                queue.add(pos);
            }
        }
        while (!queue.isEmpty()) {
            NodePos current = queue.poll();
            for (NodePos neighbor : neighborsOf(current)) {
                if (scope.contains(neighbor) && connected.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }
        Set<NodePos> floating = new HashSet<>();
        for (NodePos pos : scope) {
            if (nodes.containsKey(pos) && !connected.contains(pos)) {
                floating.add(pos);
            }
        }
        return floating;
    }

    /**
     * Find blocks that are floating (no path to any ground block), starting
     * from the given scope. Unlike {@link #getFloatingBlocks(Set)}, the search
     * may traverse blocks OUTSIDE the scope — we're checking if scope blocks
     * are truly disconnected from ground, not just disconnected within the
     * scope. The result can therefore also CONTAIN out-of-scope blocks: when a
     * search exhausts a region without reaching ground, every block in that
     * region is provably floating and all of them are reported.
     *
     * <p>This is O(scope) when scope blocks are well-connected to ground (the
     * common case), since BFS stops as soon as it finds ground. Worst case is
     * O(component) if blocks are floating or far from ground.
     */
    public Set<NodePos> findFloatingInScope(Set<NodePos> scope) {
        // Answer "can this block reach ground?" from the chunk-connectivity index —
        // O(1) amortized per block (the index is maintained on every add/remove and
        // its verdict is exact, pinned byte-for-byte against BFS in
        // ChunkConnectivityTest) — instead of a fresh BFS ball per scope member.
        // The old per-start BFS ran until it found ANY grounded node: with ground
        // far away (a deep buried slab, a tall structure anchored only at the
        // base) each ball was O(distance³), re-explored by every start the
        // previous balls didn't happen to visit, and the settle loop re-ran the
        // whole thing once per batch round — the 10s+ watchdog stalls on the
        // 134k-node siege graph sat exactly here.
        Set<NodePos> floating = new HashSet<>();
        for (NodePos startPos : scope) {
            if (!nodes.containsKey(startPos) || floating.contains(startPos)) {
                continue;
            }
            if (connectivity.connectedToGround(startPos)) {
                continue;
            }
            // Provably floating. Report its WHOLE component (in scope or not),
            // matching the BFS contract: an exhausted region with no ground is
            // floating in its entirety, and restricting to scope members would
            // leave the rest of a hanging chain dangling forever — nothing later
            // re-examines it. Components collected here are about to collapse,
            // so this BFS only ever walks blocks that are coming down anyway.
            floating.addAll(componentOf(startPos));
        }
        return floating;
    }

    /**
     * All nodes reachable from {@code start} through edges — i.e. the connected
     * component it belongs to (including {@code start} and any ground nodes in
     * the component). Empty if {@code start} isn't in the graph.
     *
     * <p>This is the "region of interest" for a local change: removing or
     * loading {@code start} can only affect nodes in its component, so a solver
     * pass scoped to this set is equivalent to solving the whole graph — but
     * costs nothing for the other structures in the world.
     */
    public Set<NodePos> componentOf(NodePos start) {
        Set<NodePos> seen = new HashSet<>();
        if (!nodes.containsKey(start)) {
            return seen;
        }
        Queue<NodePos> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            NodePos current = queue.poll();
            for (NodePos neighbor : neighborsOf(current)) {
                if (seen.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }
        return seen;
    }

    /**
     * Get all blocks whose load passes through the given position.
     * These are blocks that might be affected if the given block is removed.
     *
     * <pre>
     *      [D]
     *       │
     *      [C]          If we remove [B], blocks C and D might fall.
     *       │           getDependentSubgraph(B) = {B, C, D}
     *      [B]
     *       │
     *      [A]          A is below B, so it's not dependent on B.
     *       │
     *     [GND]
     * </pre>
     *
     * <p>Algorithm: BFS upward, plus laterally for neighbors that do NOT have
     * their own straight-down column to ground (cantilever arms, bridge decks,
     * hanging chains). A lateral neighbor whose column reaches a ground node is
     * independently supported and skipped — that's what keeps flat terrain
     * cheap.
     *
     * <pre>
     *   FLAT TERRAIN OPTIMIZATION:
     *
     *   [T1]─[T2]─[T3]─[T4]    ← All at y=1, nothing above any of them
     *    │    │    │    │
     *   [G1]─[G2]─[G3]─[G4]    ← Ground at y=0
     *
     *   getDependentSubgraph(T2) = {T2} (NOT all terrain!)
     *   Because T1, T3 stand on their own ground columns - they don't depend
     *   on T2 for support. Nothing is above T2 to cascade.
     *
     *   CANTILEVER / HANGING CASE:
     *
     *   [A2]─[B1]─[B2]         ← beam at y=2 off a tower
     *   [A1]       [H1]        ← H1 HANGS below the beam tip
     *   [GND]
     *
     *   getDependentSubgraph(B1) = {B1, B2, H1} — B2 has a block below (H1),
     *   but H1's column never reaches ground, so it is no support at all: B2
     *   still depends on B1, and H1 itself HANGS from B2 (a below-neighbor
     *   with no ground under it falls with us, it doesn't hold us up).
     * </pre>
     */
    public Set<NodePos> getDependentSubgraph(NodePos pos) {
        return getDependentSubgraph(List.of(pos));
    }

    /**
     * Multi-seed {@link #getDependentSubgraph(NodePos)}: the union of the
     * dependent subgraphs of every seed, computed in ONE shared BFS instead of
     * one BFS per seed. Seeding all positions at once and sharing the visited
     * ({@code dependents}) set means a block reachable from several seeds is
     * visited once, not once per seed — exactly the union with no redundant
     * re-exploration. Seeds not present in the graph are skipped.
     *
     * <p>This returns the same set as taking the union of
     * {@code getDependentSubgraph(seed)} over every seed, because dependency is
     * a per-edge rule that does not depend on which seed a block was reached
     * from: whether a neighbor is a dependent is decided purely by its position
     * relative to the {@code current} block being expanded (above / lateral /
     * below + {@link #hasGroundedColumnBelow}), so the first dequeue of any
     * block enqueues exactly the dependents the per-seed BFS would have, and
     * the shared visited set only removes the redundant re-visits.
     */
    public Set<NodePos> getDependentSubgraph(Collection<NodePos> seeds) {
        Set<NodePos> dependents = new HashSet<>();

        // BFS: start from EVERY seed at once, sharing one visited (dependents) set.
        // - Always go UP (y+1) - these definitely depend on us
        // - Go LATERAL (same y) only if the lateral neighbor has something ABOVE it
        //   or if we ourselves have something above (cantilever/bridge case)
        Queue<NodePos> queue = new ArrayDeque<>();
        for (NodePos seed : seeds) {
            if (nodes.containsKey(seed) && dependents.add(seed)) {
                queue.add(seed);
            }
        }

        while (!queue.isEmpty()) {
            NodePos current = queue.poll();

            for (NodePos neighbor : neighborsOf(current)) {
                if (dependents.contains(neighbor)) {
                    continue; // Already visited
                }

                if (neighbor.y() > current.y()) {
                    // Blocks ABOVE always depend on us
                    dependents.add(neighbor);
                    queue.add(neighbor);
                } else if (neighbor.y() == current.y()) {
                    // LATERAL neighbors: only include if they DEPEND on us for support.
                    // A neighbor with its own independent downward support path doesn't
                    // depend on us - this is the key optimization for multi-layer terrain.
                    //
                    // "Independent support" means a straight-down column that actually
                    // reaches a ground node. Merely having SOME block below is not
                    // support: that block may itself be hanging off us (a chain dangling
                    // from a cantilever), and skipping the neighbor would leave the
                    // whole chain floating after we're gone.
                    if (!hasGroundedColumnBelow(neighbor)) {
                        dependents.add(neighbor);
                        queue.add(neighbor);
                    }
                } else {
                    // BELOW neighbors: normally they SUPPORT us and are not
                    // dependents — except when their column never reaches
                    // ground. Then they aren't support at all: they HANG from
                    // us (a chain dangling under a beam) and fall when we go.
                    if (!hasGroundedColumnBelow(neighbor)) {
                        dependents.add(neighbor);
                        queue.add(neighbor);
                    }
                }
            }
        }

        return dependents;
    }

    /**
     * Get all blocks in the support chain below the given position.
     * These are blocks that bear load from the given block - i.e., the path
     * from the block down to ground. Stops at ground nodes.
     *
     * <pre>
     *   VERTICAL SUPPORT (direct block below):
     *      [D]  ← start here
     *       │
     *      [C]          getSupportAncestors(D) = {D, C, B, A}
     *       │           Only goes straight down - no lateral spread
     *      [B]
     *       │
     *      [A]
     *       │
     *     [GND]
     *
     *   CANTILEVER (no direct block below):
     *      [D]--[E]     getSupportAncestors(E) = {E, D, C, B, A}
     *       │           E has no block below, so lateral support (D) is included
     *      [C]
     *       │
     *     [GND]
     * </pre>
     *
     * <p>This is the inverse of {@link #getDependentSubgraph}: where that method
     * finds blocks whose load passes THROUGH a position (above), this finds
     * blocks that SUPPORT a position (below). Used when adding load to determine
     * which blocks might become overloaded.
     */
    public Set<NodePos> getSupportAncestors(NodePos pos) {
        Set<NodePos> ancestors = new HashSet<>();

        if (!nodes.containsKey(pos)) {
            return ancestors;
        }

        // BFS downward: follow support paths to ground
        Queue<NodePos> queue = new ArrayDeque<>();
        queue.add(pos);
        ancestors.add(pos);

        while (!queue.isEmpty()) {
            NodePos current = queue.poll();
            Node currentNode = nodes.get(current);

            // Ground nodes are load sinks - don't traverse further
            if (currentNode != null && currentNode.isGrounded()) {
                continue;
            }

            // Check if this node has direct vertical support — a straight-down
            // column that reaches ground. A block hanging below us (no ground
            // under it) is NOT support; load placed on us must route laterally
            // (beam into a tower), so we still need to traverse sideways.
            boolean hasDirectSupport = hasGroundedColumnBelow(current);

            for (NodePos neighbor : neighborsOf(current)) {
                if (ancestors.contains(neighbor)) {
                    continue; // Already visited
                }

                Node neighborNode = nodes.get(neighbor);
                if (neighborNode == null) {
                    continue;
                }

                if (neighbor.y() < current.y()) {
                    // Always include blocks below
                    ancestors.add(neighbor);
                    queue.add(neighbor);
                } else if (neighbor.y() == current.y() && !hasDirectSupport) {
                    // Include lateral neighbors ONLY if this block has no direct support below
                    // (cantilever case). If it has direct support, skip lateral to avoid
                    // spreading across entire terrain layers.
                    ancestors.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }

        return ancestors;
    }

    /**
     * The STRUCTURAL CLOSURE of a disturbance: every node whose stress can be
     * affected, found by BFS across edges from the seed positions — pruned at
     * nodes that are trivially terrain-stable. Such nodes are included (the
     * solver needs them as context for distances and load shares) but never
     * traversed THROUGH, so the closure of a break under a tower climbs the
     * tower and dies at the terrain skin instead of sweeping the world.
     *
     * <pre>
     *   terrain-stable = grounded column below + nothing above + undamaged
     *   (ground nodes are likewise context-only — otherwise the BFS would
     *   fan out along the bedrock and drag in every structure on it)
     * </pre>
     *
     * <p>WHY closure and not "dependents + a ring": the stress solver's
     * numbers are only exact when the subgraph contains each evaluated node's
     * full upstream cone (vertical stress reads upstream values; the moment
     * pass classifies unseen neighbors as cantilever arms) and the support
     * paths underneath it. The closure is the smallest set with that property
     * that still excludes inert terrain — on a wall it is the wall, on plain
     * terrain it is a handful of skin blocks.
     */
    public Set<NodePos> affectedRegion(Set<NodePos> seeds) {
        ScopeClosureCursor cursor = new ScopeClosureCursor(seeds);
        affectedRegion(cursor, NEVER_PAUSE);
        return cursor.region();
    }

    /** The never-pause budget for the run-to-completion {@link #affectedRegion(Set)}. */
    private static final BooleanSupplier NEVER_PAUSE = () -> false;

    /**
     * How many BFS nodes one uninterruptible chunk of {@link #affectedRegion(
     * ScopeClosureCursor, BooleanSupplier)} expands before it may honour {@code pause}.
     * A chunk is the atomic unit of the interruptible closure: the budget is only
     * consulted on chunk boundaries, so one chunk always overshoots the budget (the
     * documented 2× tolerance) but a too-small chunk would call {@code System.nanoTime}
     * per node. This value keeps the per-chunk cost well under a millisecond on a keep,
     * so the whole closure never blows through a keep-sized budget by more than one chunk.
     */
    public static final int CLOSURE_CHUNK = 4096;

    /**
     * Interruptible, resumable form of {@link #affectedRegion(Set)}: the SAME closure
     * BFS, but it consults {@code pause} on chunk boundaries and, when it trips, parks
     * its live state (visited set, region, queue) in {@code cursor} and returns with
     * {@code cursor.isComplete() == false}. A later call with the same cursor resumes
     * exactly where it stopped. Because the pause only gates traversal — never which
     * nodes are visited or in what order — the region a resumed-to-completion closure
     * yields is set-equal to the uninterrupted {@link #affectedRegion(Set)} for the
     * same seeds. This is the anti-freeze hook for a keep-sized closure that, in one
     * shot, could cost hundreds of milliseconds.
     *
     * @return {@code cursor.region()} — the live region (complete only when {@code
     *     cursor.isComplete()}); callers must check completeness, not the return value.
     */
    public Set<NodePos> affectedRegion(ScopeClosureCursor cursor, BooleanSupplier pause) {
        return affectedRegion(cursor, pause, CLOSURE_CHUNK);
    }

    /**
     * Package-visible seam for the interruptible closure with an explicit chunk size, so
     * tests can force a pause after every node on a small pinned adversarial layout (the
     * closure-resume-equivalence risk) without needing a >{@value #CLOSURE_CHUNK}-node graph.
     */
    Set<NodePos> affectedRegion(ScopeClosureCursor cursor, BooleanSupplier pause, int chunk) {
        // `visited` is the BFS traversal guard; `region` is the result — every visited
        // node PLUS the support-column context added below. These must be SEPARATE:
        // addGroundedColumn records a column node as context without enqueuing it, so if
        // `region` doubled as the guard, a structural node that the BFS reaches later
        // through that column would see region.add()==false and never expand — silently
        // dropping a genuine load path (a block leaning on the column) from the closure.
        // That made the closure depend on neighbour-encounter order, settling identical
        // geometry differently run to run. A column node that the BFS genuinely reaches
        // is still expanded (it is structural — something rests on it); a column node
        // only ever seen as context is never enqueued, so plain terrain still dies at
        // the skin.
        Set<NodePos> visited = cursor.visited;
        Set<NodePos> region = cursor.region;
        Queue<NodePos> queue = cursor.queue;
        if (!cursor.started) {
            for (NodePos seed : cursor.seeds) {
                if (nodes.containsKey(seed) && visited.add(seed)) {
                    region.add(seed);
                    queue.add(seed);
                }
            }
            cursor.started = true;
        }
        int sinceCheck = 0;
        while (!queue.isEmpty()) {
            // Consult the budget on chunk boundaries and only AFTER at least `chunk`
            // nodes have been fully processed this run — so a resumed closure always
            // makes progress (the queue holds strictly fewer un-processed nodes each
            // call) and never re-parks on an un-expanded node. The guaranteed-progress
            // contract: with chunk=1 the BFS still advances one node per call.
            if (sinceCheck >= chunk) {
                sinceCheck = 0;
                if (pause.getAsBoolean()) {
                    return region; // incomplete — cursor keeps visited/region/queue
                }
            }
            NodePos current = queue.poll();
            sinceCheck++;
            if (isTerrainStable(current)) {
                // Context only — do not traverse laterally through inert
                // blocks. But DO record the column that supports this node:
                // a bridge deck-end looks exactly like terrain skin from
                // here (nothing above, grounded column), and the solver
                // still needs its pillar for distances and load routing.
                // On real terrain the column is the 1–2 anchor blocks
                // below, so this stays cheap.
                addGroundedColumn(current, region);
                continue;
            }
            if (isBuriedTerrainColumnContext(current)) {
                // A BURIED terrain cell whose connected column is capped by
                // inert skin and leaned on by nobody routes load strictly
                // vertically: the solver needs it (and its support column) as
                // a sink, but expanding it LATERALLY would flood the closure
                // through the entire buried slab — isTerrainStable alone
                // cannot stop that, because every buried cell has a block
                // above it and so reads "structural". Without this barrier a
                // tower-on-terrain closure was the whole map (one settle's
                // nodeVisits grew 13x and a 50x50x3 world solved 8x slower);
                // the predicate is pure geometry per cell, so unlike the
                // pre-determinism-fix accidental barrier it cannot depend on
                // neighbour-encounter order.
                addGroundedColumn(current, region);
                continue;
            }
            for (NodePos neighbor : neighborsOf(current)) {
                if (visited.add(neighbor)) {
                    region.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }
        cursor.complete = true;
        return region;
    }

    /**
     * The parked state of an interruptible {@link #affectedRegion(ScopeClosureCursor,
     * BooleanSupplier) closure} — its BFS visited set, accumulated region, and pending
     * queue — so a budget-paused closure resumes exactly where it stopped on the next
     * call instead of restarting. Created from the seed positions; the graph fills it.
     * A fresh cursor whose {@link #isComplete()} is still false after a call is the
     * signal that the closure paused and must be driven again.
     */
    public static final class ScopeClosureCursor {
        private final Set<NodePos> seeds;
        private final Set<NodePos> visited = new HashSet<>();
        private final Set<NodePos> region = new HashSet<>();
        private final Queue<NodePos> queue = new ArrayDeque<>();
        private boolean started;
        private boolean complete;

        public ScopeClosureCursor(Set<NodePos> seeds) {
            this.seeds = Set.copyOf(seeds);
        }

        /** True once the BFS drained its queue: {@link #region()} is now the full closure. */
        public boolean isComplete() {
            return complete;
        }

        /** The live region (complete only when {@link #isComplete()}). */
        public Set<NodePos> region() {
            return region;
        }
    }

    /**
     * Is {@code pos} a buried, pristine terrain-column cell — one whose
     * connected straight-up column ends at an {@link #isTerrainStable
     * terrain-stable} skin cell, with its own grounded column below and no
     * lateral leaner anywhere on the way up?
     *
     * <p>Such a cell carries exactly the weight of its own inert column and
     * routes it straight down; nothing structural can reach it except through
     * the skin above (inert by definition) or a leaner (which disqualifies
     * it). It therefore belongs in the closure only as support CONTEXT —
     * traversing it laterally would connect every buried cell of a terrain
     * slab to every other and drag the whole map into each solve.
     *
     * <p>Any deviation — damage anywhere in the column (craters must
     * re-solve their surroundings), a leaner (a real lateral load path, the
     * exact case the visited/region split exists to keep), a column that
     * tops out under a structure instead of stable skin (a foundation cell —
     * structural) — fails the test and the cell is expanded normally.
     */
    private boolean isBuriedTerrainColumnContext(NodePos pos) {
        Node node = nodes.get(pos);
        if (node == null || node.isGrounded()) {
            return false; // absent/grounded cells are handled by the callers' other guards
        }
        if (!hasGroundedColumnBelow(pos)) {
            return false; // not self-supported — it hangs on something structural
        }
        NodePos cur = pos;
        while (true) {
            Node curNode = nodes.get(cur);
            if (curNode == null || curNode.isGrounded() || curNode.damage() > 0) {
                return false; // damaged columns always re-solve in full
            }
            if (curNode.temperatureCapacityFactor() < 1.0) {
                // Heat-softened capacity is a weakened state just like damage —
                // a fire-softened column can overload under nothing but its own
                // inert weight, and the solver must get to say so.
                return false;
            }
            for (NodePos n : neighborsOf(cur)) {
                if (n.y() == cur.y() && !hasGroundedColumnBelow(n)) {
                    return false; // a leaner routes load through this column — structural
                }
            }
            NodePos above = new NodePos(cur.x(), cur.y() + 1, cur.z());
            Set<NodePos> edges = adjacency.get(cur);
            if (edges == null || !edges.contains(above) || !nodes.containsKey(above)) {
                // The column tops out here without inert skin above: pos sits
                // under open air mid-column (the skin case is caught below) or
                // under a disconnected block — treat as structural.
                return false;
            }
            if (isTerrainStable(above)) {
                return true; // capped by inert skin — pure vertical conduit
            }
            cur = above;
        }
    }

    /** Add the connected straight-down column under {@code pos} (to ground) to {@code region}. */
    private void addGroundedColumn(NodePos pos, Set<NodePos> region) {
        Node node = nodes.get(pos);
        while (node != null && !node.isGrounded()) {
            NodePos below = new NodePos(pos.x(), pos.y() - 1, pos.z());
            Set<NodePos> edges = adjacency.get(pos);
            if (edges == null || !edges.contains(below)) {
                return;
            }
            region.add(below);
            pos = below;
            node = nodes.get(pos);
        }
    }

    /**
     * Can this node's stress be taken as settled without solving — i.e. is it
     * inert terrain? Three things must hold: nothing rests on it, it stands on
     * its own grounded column, and NO LATERAL NEIGHBOR leans on it (a
     * neighbor without its own grounded column routes load through us — a
     * wall's top row arching over an opening, a beam resting against a post).
     * Ground nodes are likewise never traversed through: they anchor
     * distances, but the bedrock connects everything.
     */
    private boolean isTerrainStable(NodePos pos) {
        Node node = nodes.get(pos);
        if (node == null) {
            return true;
        }
        if (node.isGrounded()) {
            return true;
        }
        if (node.damage() > 0) {
            return false; // damaged blocks must always re-solve
        }
        for (NodePos neighbor : neighborsOf(pos)) {
            if (neighbor.y() > pos.y()) {
                return false; // something rests on it — structural
            }
            if (neighbor.y() == pos.y() && !hasGroundedColumnBelow(neighbor)) {
                return false; // a lateral neighbor leans on it — load path
            }
        }
        return hasGroundedColumnBelow(pos);
    }

    /**
     * Does a straight-down column of CONNECTED nodes from {@code pos} reach a
     * grounded node with no gaps? This is the test for "independently
     * supported": merely having SOME block below is not enough — that block
     * may itself be hanging (held up from above or sideways), in which case
     * it offers no support at all.
     *
     * <p>The walk follows EDGES, not coordinates: on the generic API
     * ({@code addNode} + {@code connect}) a node can sit directly above
     * another without being connected to it, and a column you are not
     * connected to carries none of your load. (On the grid API the two are
     * the same thing — {@code addBlock} auto-connects face neighbors —
     * unless {@code disconnect} severed the joint, which must count too.)
     *
     * <p>O(column height). On scanned structures the ground anchor sits
     * directly below the resting block (see the adapter's RegionScanner), so
     * on terrain this is typically a 1–2 step walk — the flat-terrain perf
     * win of the scoped traversals is preserved.
     */
    private boolean hasGroundedColumnBelow(NodePos pos) {
        Node node = nodes.get(pos);
        while (node != null) {
            if (node.isGrounded()) {
                return true;
            }
            NodePos below = new NodePos(pos.x(), pos.y() - 1, pos.z());
            Set<NodePos> edges = adjacency.get(pos);
            if (edges == null || !edges.contains(below)) {
                return false; // no EDGE to the node below — coordinates alone are not a load path
            }
            pos = below;
            node = nodes.get(pos);
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  COPY
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Deep-copy ONLY the given positions (plus the edges among them) into a new
     * graph — the cheap snapshot for a scope-closed read like the solver's
     * overload query, which by construction reads nothing outside its scope.
     * All per-node persistent state (grounded flag, damage, reinforcement,
     * heat-softening) is preserved, so any computation over the copy that stays
     * inside {@code positions} is bit-identical to running it on this graph.
     * Positions not in the graph are skipped. O(|positions|), independent of
     * graph size — safe to take on a game tick and hand to a worker thread.
     */
    public StructureGraph copySubgraph(Set<NodePos> positions) {
        // Write the node and adjacency maps DIRECTLY, bypassing addNode/connect:
        // those feed the chunk-connectivity index per node, which made the
        // snapshot cost rival the query it was taken for — and a solver query
        // never consults that index. The snapshot is therefore NOT valid for
        // connectivity-backed reads (getFloatingBlocks / isConnectedToGround /
        // findFloatingInScope); it exists for solver passes, which read only
        // nodes, adjacency and per-node state.
        StructureGraph copy = new StructureGraph();
        for (NodePos pos : positions) {
            Node node = nodes.get(pos);
            if (node == null) {
                continue;
            }
            Node copied = new Node(pos, node.spec(), node.isGrounded());
            copied.addDamage(node.damage());
            copied.setReinforcement(node.reinforcement());
            copied.setTemperatureCapacityFactor(node.temperatureCapacityFactor());
            copy.nodes.put(pos, copied);
        }
        for (NodePos pos : copy.nodes.keySet()) {
            Set<NodePos> edges = adjacency.get(pos);
            if (edges == null) {
                continue;
            }
            Set<NodePos> copiedEdges = new HashSet<>();
            for (NodePos neighbor : edges) {
                if (copy.nodes.containsKey(neighbor)) {
                    copiedEdges.add(neighbor);
                }
            }
            if (!copiedEdges.isEmpty()) {
                copy.adjacency.put(pos, copiedEdges);
            }
        }
        return copy;
    }

    /**
     * Like {@link #copySubgraph(Set)}, but builds the copy through
     * {@link #addNode}/{@link #connect} so the chunk-connectivity index IS
     * populated — making the snapshot valid for connectivity-backed reads
     * ({@link #findFloatingInScope}, {@link #getFloatingBlocks},
     * {@link #isConnectedToGround}). Use this (not {@code copySubgraph}) when the
     * off-thread work is a full {@link dev.gesp.structural.solver.CascadeEngine#settleResult}
     * pass, whose initial floating drain consults that index — {@code copySubgraph}
     * leaves it empty, so every scope node reads as floating and the solve collapses
     * blocks a synchronous settle would leave standing.
     *
     * <p>Correctness rests on {@code scope} being a ground-closed connected component
     * (as {@link #affectedRegion} returns): paths to ground within the scope match
     * paths in the full graph, so the scoped connectivity verdict equals the whole
     * graph's. Costs more than {@code copySubgraph} (per-node index updates) but is
     * still O(|positions|) — safe on a tick, safe to hand to a worker thread.
     */
    public StructureGraph copySolvableSubgraph(Set<NodePos> positions) {
        StructureGraph copy = new StructureGraph();
        for (NodePos pos : positions) {
            Node node = nodes.get(pos);
            if (node == null) {
                continue;
            }
            copy.addNode(pos, node.spec(), node.isGrounded());
            Node copied = copy.nodes.get(pos);
            copied.addDamage(node.damage());
            copied.setReinforcement(node.reinforcement());
            copied.setTemperatureCapacityFactor(node.temperatureCapacityFactor());
        }
        for (NodePos pos : positions) {
            Set<NodePos> edges = adjacency.get(pos);
            if (edges == null) {
                continue;
            }
            for (NodePos neighbor : edges) {
                if (copy.nodes.containsKey(neighbor)) {
                    copy.connect(pos, neighbor);
                }
            }
        }
        return copy;
    }

    /**
     * Create a deep copy of this graph.
     * Useful for "what-if" simulations.
     */
    public StructureGraph copy() {
        StructureGraph copy = new StructureGraph();
        // Copy nodes WITHOUT deriving adjacency from the grid...
        for (Node node : getAllNodes()) {
            copy.addNode(node.pos(), node.spec(), node.isGrounded());
            // ...including the persistent state: a copy that silently heals
            // damage (or forgets reinforcement, or the heat-softening factor) makes
            // what-if sims lie. temperatureCapacityFactor is the third multiplicative
            // term in effectiveMaxLoad(); without it a wouldBeStable/prefab preview on a
            // fire-heated structure runs at full cold strength and approves a placement
            // that overloads the real graph.
            Node copied = copy.nodes.get(node.pos());
            copied.addDamage(node.damage());
            copied.setReinforcement(node.reinforcement());
            copied.setTemperatureCapacityFactor(node.temperatureCapacityFactor());
        }
        // ...then reproduce the exact edges, so non-grid topologies survive a copy.
        for (Map.Entry<NodePos, Set<NodePos>> entry : adjacency.entrySet()) {
            for (NodePos neighbor : entry.getValue()) {
                copy.connect(entry.getKey(), neighbor);
            }
        }
        return copy;
    }
}
