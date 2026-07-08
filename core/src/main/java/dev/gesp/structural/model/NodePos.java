package dev.gesp.structural.model;

import java.util.Comparator;

/**
 * A position in 3D space. Think of it like an address for a node.
 *
 * <pre>
 *            y (up)
 *            │
 *            │
 *            │
 *            └───────── x (east/west)
 *           /
 *          /
 *         z (north/south)
 *
 *
 *   Example: NodePos(2, 3, 1) means:
 *
 *            │
 *          3 ┼ · · [■]      ← the node is here
 *          2 ┼ · · ·
 *          1 ┼ · · ·
 *          0 ┼─┼─┼─┼── x
 *            0 1 2
 *            (z = 1, into the screen)
 * </pre>
 *
 * <p>This is a lattice coordinate, not a physical measurement. A consumer
 * decides what one unit means: it might be a 1×1×1 block, a prefab cell, a
 * truss joint, or any other unit. Only the {@code y} axis carries physical
 * meaning to the solver (it is "up"); {@code x}/{@code z} are used purely for
 * identity and for the optional grid-adjacency helper.
 *
 * <p>This class is IMMUTABLE - once created, it never changes.
 */
public final class NodePos {

    /**
     * A total, deterministic ordering over positions: lowest first by {@code y}
     * (so support fails from the bottom up in a stable order), then {@code x},
     * then {@code z}.
     *
     * <p>The physics engine processes simultaneously-failing blocks in this
     * order instead of in {@code HashSet}/{@code HashMap} iteration order, which
     * varies across JVM runs (it depends on identity hash codes and table
     * layout). Pinning the order makes a cascade REPRODUCIBLE: the same event
     * sequence always yields the same collapse set, which is what the replay
     * verifier checks. Where collapsing one block damages or removes a neighbor
     * (debris impact), the order genuinely affects the outcome — so it must be
     * fixed, not left to chance.
     */
    public static final Comparator<NodePos> CANONICAL_ORDER =
            Comparator.comparingInt(NodePos::y).thenComparingInt(NodePos::x).thenComparingInt(NodePos::z);

    private final int x;
    private final int y;
    private final int z;

    public NodePos(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    /**
     * Is this node on the ground? Ground level is y = 0.
     */
    public boolean isAtGroundLevel() {
        return y == 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NodePos nodePos = (NodePos) o;
        return x == nodePos.x && y == nodePos.y && z == nodePos.z;
    }

    @Override
    public int hashCode() {
        // Hand-rolled to avoid Objects.hash(...)'s per-call varargs-array
        // allocation — this is the single hottest method in the solver (every
        // map lookup hits it), so it must not allocate.
        int h = x;
        h = 31 * h + y;
        h = 31 * h + z;
        return h;
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
