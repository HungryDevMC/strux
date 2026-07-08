package dev.gesp.structural.connectivity;

import dev.gesp.structural.model.NodePos;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Hierarchical, chunk-boundary "is this block still connected to bedrock?"
 * connectivity — the scalable answer for large CONNECTED terrains where a global
 * flood-fill drags across one giant bedrock-connected mass on every edit (see
 * {@code OPTIMIZATION.md}, Lever B).
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │            LOSSLESS ACCELERATION, NOT AN APPROXIMATION              │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Space is cut into CHUNKS. Per chunk we keep the connectivity        │
 *   │  PARTITION of its cells — a union-find of which cells link to which  │
 *   │  THROUGH THAT CHUNK'S INTERIOR (not a single "touches bedrock" bit;  │
 *   │  that naive over-coarsening is what would lose information).         │
 *   │                                                                     │
 *   │  Each interior class becomes a PORT (named by its canonical-minimum  │
 *   │  cell, a stable id). A small OVERLAY graph links ports across chunk  │
 *   │  borders (two boundary cells face-adjacent across a border ⇒ their   │
 *   │  ports are linked) and marks the ports whose class holds bedrock. A  │
 *   │  cantilever threading across three chunks is resolved by COMPOSING   │
 *   │  those partitions through the overlay — bit-for-bit identical to a   │
 *   │  full-world BFS (exact query accelerator, not a model change).       │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p><b>Why it scales.</b> An edit rebuilds ONLY the edited chunk's interior
 * partition and the overlay edges incident to it — O(chunk³), independent of world
 * size. Neighbouring chunks are never rebuilt. The reachable-from-bedrock set is
 * recomputed over the OVERLAY (the ports), whose size is ≈ cells / chunk³ — for a
 * solid terrain one port per chunk — so even a from-scratch reachability sweep is
 * orders of magnitude smaller than a full-cell BFS.
 *
 * <p>The verdict is pinned against brute-force BFS over thousands of random
 * add/remove frames in {@code ChunkConnectivityTest}.
 */
public final class ChunkConnectivity {

    private final int chunkSize;

    private final Set<NodePos> nodes = new HashSet<>();
    private final Set<NodePos> grounded = new HashSet<>();
    private final Map<NodePos, Set<NodePos>> adjacency = new HashMap<>();

    /** Nodes grouped by chunk; only the edited chunk is ever rebuilt. */
    private final Map<ChunkKey, Set<NodePos>> chunkNodes = new HashMap<>();

    /** Per-chunk interior union-find: parent map over the chunk's own cells. */
    private final Map<ChunkKey, Map<NodePos, NodePos>> interior = new HashMap<>();

    /** Per-chunk class → its port id (the class's canonical-minimum cell). */
    private final Map<ChunkKey, Map<NodePos, NodePos>> classPort = new HashMap<>();

    /** The set of port ids currently owned by each chunk (so a rebuild can tear them down). */
    private final Map<ChunkKey, Set<NodePos>> portsOf = new HashMap<>();

    /** The overlay: port → linked ports across chunk borders. */
    private final Map<NodePos, Set<NodePos>> portAdj = new HashMap<>();

    /** Ports whose class contains a bedrock cell. */
    private final Set<NodePos> groundedPorts = new HashSet<>();

    // Reachable-from-bedrock port set, recomputed lazily over the (small) overlay.
    private boolean reachableDirty = true;
    private final Set<NodePos> reachablePorts = new HashSet<>();

    /**
     * Chunks edited since the last query. Rebuilds are DEFERRED to the next query
     * and batched here, so a burst of edits (building a structure, or a whole
     * cascade) marks chunks dirty in O(1) and pays the rebuild once — not once per
     * edit. The hot paths that never call a whole-graph query never flush.
     */
    private final Set<ChunkKey> dirty = new HashSet<>();

    /** Instrumentation: cells the last flush's interior rebuild touched. */
    private int lastInteriorRebuildSize;

    public ChunkConnectivity(int chunkSize) {
        if (chunkSize < 1) {
            throw new IllegalArgumentException("chunkSize must be >= 1");
        }
        this.chunkSize = chunkSize;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  EDITS  (touch only the edited cell's chunk)
    // ─────────────────────────────────────────────────────────────────────

    /** Add a node, linking it to whichever of {@code neighbours} already exist. */
    public void add(NodePos pos, boolean isGround, Set<NodePos> neighbours) {
        if (!nodes.add(pos)) {
            return;
        }
        adjacency.computeIfAbsent(pos, k -> new HashSet<>());
        if (isGround) {
            grounded.add(pos);
        }
        for (NodePos n : neighbours) {
            if (nodes.contains(n)) {
                adjacency.get(pos).add(n);
                adjacency.get(n).add(pos);
            }
        }
        chunkNodes.computeIfAbsent(chunkOf(pos), k -> new HashSet<>()).add(pos);
        markDirty(chunkOf(pos));
    }

    /** Remove a node and detach its edges. */
    public void remove(NodePos pos) {
        if (!nodes.remove(pos)) {
            return;
        }
        grounded.remove(pos);
        Set<NodePos> edges = adjacency.remove(pos);
        if (edges != null) {
            for (NodePos n : edges) {
                Set<NodePos> ne = adjacency.get(n);
                if (ne != null) {
                    ne.remove(pos);
                }
            }
        }
        ChunkKey ck = chunkOf(pos);
        Set<NodePos> cn = chunkNodes.get(ck);
        if (cn != null) {
            cn.remove(pos);
            if (cn.isEmpty()) {
                chunkNodes.remove(ck);
            }
        }
        markDirty(ck);
    }

    /**
     * Add an edge between two existing nodes (the generic-graph operation, where
     * adjacency is not derived from grid coordinates). No-op if either node is
     * absent, they are the same, or the edge already exists.
     */
    public void connect(NodePos a, NodePos b) {
        if (a.equals(b)
                || !nodes.contains(a)
                || !nodes.contains(b)
                || !adjacency.get(a).add(b)) {
            return;
        }
        adjacency.get(b).add(a);
        // Rebuilding a's chunk is sufficient for BOTH cases. Intra-chunk: it re-merges
        // the interior partition. Cross-chunk: a's rebuild re-derives a's overlay edges
        // and adds the symmetric link into b's port (b's interior never changes for an
        // edge that leaves its chunk), so b's chunk needs no rebuild.
        markDirty(chunkOf(a));
    }

    /** Remove the edge between two nodes (if any), splitting interior classes as needed. */
    public void disconnect(NodePos a, NodePos b) {
        Set<NodePos> ea = adjacency.get(a);
        if (ea == null || !ea.remove(b)) {
            return; // no such edge
        }
        adjacency.get(b).remove(a);
        // a's rebuild tears down a's ports (dropping the overlay link from b's side too)
        // and re-derives them without the edge — sufficient for intra- and cross-chunk.
        markDirty(chunkOf(a));
    }

    // ─────────────────────────────────────────────────────────────────────
    //  QUERY
    // ─────────────────────────────────────────────────────────────────────

    /** Is {@code pos} connected to any bedrock node? Exact — equals a full BFS verdict. */
    public boolean connectedToGround(NodePos pos) {
        if (!nodes.contains(pos)) {
            return false;
        }
        flush();
        ensureReachable();
        NodePos port = portOf(pos);
        return port != null && reachablePorts.contains(port);
    }

    /** Cells the most recent flush's interior rebuild touched (for the chunk-bounded test). */
    public int lastInteriorRebuildSize() {
        return lastInteriorRebuildSize;
    }

    /** Number of overlay ports — ≈ cells / chunk³; the size a reachability sweep traverses. */
    public int portCount() {
        flush();
        return portAdj.size();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  DEFERRED CHUNK REBUILD  (mark dirty on edit; flush in two passes on query)
    // ─────────────────────────────────────────────────────────────────────

    private void markDirty(ChunkKey ck) {
        dirty.add(ck);
        // NB: we deliberately do NOT set reachableDirty here. The flush decides
        // whether the edit actually changed port-level connectivity (see below);
        // an edit that leaves the chunk's boundary ports intact keeps the cached
        // reachable-from-ground set valid — incremental reachability.
    }

    /**
     * Rebuild every chunk edited since the last query. Two passes so cross-chunk
     * edges see all current interiors: pass 1 rebuilds each dirty chunk's interior
     * partition + ports (tearing the old ones out of the overlay); pass 2 re-derives
     * the overlay edges incident to the dirty chunks, by which point every port id
     * they reference is up to date.
     *
     * <p>Incremental reachability: we snapshot each dirty chunk's overlay contribution
     * (its ports, which are bedrock, and their links) before the rebuild and compare
     * after. If nothing changed — the common case for an edit deep inside a chunk that
     * does not touch its boundary — the cached reachable-from-ground set is still
     * exact, so the O(ports) reachability sweep is skipped entirely. Because
     * non-dirty chunks are never touched, "all dirty chunks unchanged" proves the whole
     * overlay is unchanged.
     */
    private void flush() {
        if (dirty.isEmpty()) {
            return;
        }
        Map<ChunkKey, Map<NodePos, String>> before = new HashMap<>();
        for (ChunkKey ck : dirty) {
            before.put(ck, chunkSignature(ck));
        }
        int rebuilt = 0;
        for (ChunkKey ck : dirty) {
            rebuilt += rebuildInterior(ck);
        }
        for (ChunkKey ck : dirty) {
            deriveCrossEdges(ck);
        }
        for (ChunkKey ck : dirty) {
            if (!chunkSignature(ck).equals(before.get(ck))) {
                reachableDirty = true; // connectivity genuinely changed → reachability must recompute
                break;
            }
        }
        lastInteriorRebuildSize = rebuilt;
        dirty.clear();
    }

    /**
     * A chunk's contribution to the overlay: each of its ports, whether the port is
     * bedrock, and the ports it links to. Two signatures being equal across a rebuild
     * means the chunk changed nothing the global reachability depends on.
     */
    private Map<NodePos, String> chunkSignature(ChunkKey ck) {
        Set<NodePos> ports = portsOf.get(ck);
        if (ports == null) {
            return Map.of();
        }
        Map<NodePos, String> sig = new HashMap<>();
        for (NodePos p : ports) {
            List<NodePos> links = new ArrayList<>(portAdj.getOrDefault(p, Set.of()));
            links.sort(NodePos.CANONICAL_ORDER);
            sig.put(p, (groundedPorts.contains(p) ? "G" : "_") + links);
        }
        return sig;
    }

    /** Pass 1: interior partition, ports and bedrock ports for one chunk. Returns cells touched. */
    private int rebuildInterior(ChunkKey ck) {
        teardownPorts(ck);
        Set<NodePos> cells = chunkNodes.get(ck);
        if (cells == null || cells.isEmpty()) {
            interior.remove(ck);
            classPort.remove(ck);
            return 0;
        }

        // Union-find over the chunk's own cells, intra-chunk edges only.
        Map<NodePos, NodePos> parent = new HashMap<>();
        for (NodePos c : cells) {
            parent.put(c, c);
        }
        for (NodePos a : cells) {
            for (NodePos b : adjacency.get(a)) {
                if (chunkOf(b).equals(ck)) {
                    union(parent, a, b);
                }
            }
        }
        interior.put(ck, parent);

        // Name each class by its canonical-minimum cell → a stable port id.
        Map<NodePos, NodePos> ports = new HashMap<>();
        for (NodePos c : cells) {
            NodePos root = find(parent, c);
            NodePos cur = ports.get(root);
            if (cur == null || NodePos.CANONICAL_ORDER.compare(c, cur) < 0) {
                ports.put(root, c);
            }
        }
        classPort.put(ck, ports);
        Set<NodePos> myPorts = new HashSet<>(ports.values());
        portsOf.put(ck, myPorts);
        for (NodePos p : myPorts) {
            portAdj.computeIfAbsent(p, k -> new HashSet<>());
        }
        for (NodePos c : cells) {
            if (grounded.contains(c)) {
                groundedPorts.add(ports.get(find(parent, c)));
            }
        }
        return cells.size();
    }

    /** Pass 2: overlay edges from this chunk's cells to neighbouring chunks (all interiors current). */
    private void deriveCrossEdges(ChunkKey ck) {
        Set<NodePos> cells = chunkNodes.get(ck);
        if (cells == null) {
            return;
        }
        for (NodePos c : cells) {
            NodePos myPort = portOf(c);
            for (NodePos b : adjacency.get(c)) {
                if (!chunkOf(b).equals(ck)) {
                    NodePos other = portOf(b);
                    if (other != null) {
                        portAdj.get(myPort).add(other);
                        portAdj.computeIfAbsent(other, k -> new HashSet<>()).add(myPort);
                    }
                }
            }
        }
    }

    /** Detach the chunk's current ports from the overlay (so a rebuild starts clean). */
    private void teardownPorts(ChunkKey ck) {
        Set<NodePos> old = portsOf.remove(ck);
        if (old == null) {
            return;
        }
        for (NodePos p : old) {
            Set<NodePos> linked = portAdj.remove(p);
            if (linked != null) {
                for (NodePos q : linked) {
                    Set<NodePos> qs = portAdj.get(q);
                    if (qs != null) {
                        qs.remove(p);
                    }
                }
            }
            groundedPorts.remove(p);
        }
    }

    /** The port (class id) a cell currently belongs to, or null if its chunk isn't built. */
    private NodePos portOf(NodePos cell) {
        ChunkKey ck = chunkOf(cell);
        Map<NodePos, NodePos> parent = interior.get(ck);
        if (parent == null) {
            return null;
        }
        return classPort.get(ck).get(find(parent, cell));
    }

    // ─────────────────────────────────────────────────────────────────────
    //  REACHABILITY over the overlay (recomputed lazily; O(ports))
    // ─────────────────────────────────────────────────────────────────────

    private void ensureReachable() {
        if (!reachableDirty) {
            return;
        }
        reachablePorts.clear();
        Queue<NodePos> q = new ArrayDeque<>();
        for (NodePos g : groundedPorts) {
            if (reachablePorts.add(g)) {
                q.add(g);
            }
        }
        while (!q.isEmpty()) {
            NodePos p = q.poll();
            for (NodePos n : portAdj.getOrDefault(p, Set.of())) {
                if (reachablePorts.add(n)) {
                    q.add(n);
                }
            }
        }
        reachableDirty = false;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PLAIN UNION-FIND over a parent map
    // ─────────────────────────────────────────────────────────────────────

    private static NodePos find(Map<NodePos, NodePos> parent, NodePos x) {
        NodePos root = x;
        while (!parent.get(root).equals(root)) {
            root = parent.get(root);
        }
        NodePos cur = x;
        while (!parent.get(cur).equals(root)) {
            NodePos next = parent.get(cur);
            parent.put(cur, root);
            cur = next;
        }
        return root;
    }

    private static void union(Map<NodePos, NodePos> parent, NodePos a, NodePos b) {
        NodePos ra = find(parent, a);
        NodePos rb = find(parent, b);
        if (!ra.equals(rb)) {
            parent.put(ra, rb);
        }
    }

    private ChunkKey chunkOf(NodePos p) {
        return new ChunkKey(
                Math.floorDiv(p.x(), chunkSize), Math.floorDiv(p.y(), chunkSize), Math.floorDiv(p.z(), chunkSize));
    }

    /** Which chunk a cell lives in. */
    private record ChunkKey(int cx, int cy, int cz) {}
}
