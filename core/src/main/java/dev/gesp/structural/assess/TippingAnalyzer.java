package dev.gesp.structural.assess;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Global tipping check: does a connected component stand, or does it topple as a
 * rigid body? This is real statics — the center of mass versus the support
 * polygon — with no fudge factors.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                       TIPPING ANALYZER                             │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │   center of mass (x,z) ──► is it inside the support polygon? ──►    │
 *   │   ground-contact footprint    ├─ inside  → stable                  │
 *   │   (convex hull)               └─ outside → tips about nearest edge  │
 *   │                                                                     │
 *   │  The support polygon is the 2D convex hull of where the body       │
 *   │  actually touches the ground. The CoM is the mass-weighted mean    │
 *   │  of the (x,z) of every block above ground. If the CoM projects     │
 *   │  inside the footprint, gravity is held by the base; if it walks    │
 *   │  out past an edge, gravity rotates the body about that edge.       │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>This is a PURE READ-ONLY QUERY: it never mutates the graph, the solver, or
 * any node's stress. It is the statics question the cascade does NOT yet ask;
 * use it to predict a topple before deciding to settle.
 *
 * <p><b>Determinism.</b> Mass sums run in {@link NodePos#CANONICAL_ORDER} so the
 * floating-point total is bit-for-bit reproducible, and the convex hull is built
 * by Andrew's monotone chain on integer coordinates with fixed tie-breaks — no
 * RNG, no hash-iteration order.
 */
public final class TippingAnalyzer {

    /**
     * Slack on the inside/outside test, in lattice units. The CoM must clear the
     * hull edge by more than this before we call it a topple, so a CoM sitting
     * exactly over an edge (the knife-edge balance) reads as stable rather than
     * flickering on floating-point noise.
     */
    private static final double EPS = 1e-9;

    private TippingAnalyzer() {
        // Utility class
    }

    /**
     * A 2D point in the ground plane (x, z). For the hull these are integer
     * lattice coordinates; for the center of mass the values are real.
     */
    public record Point2D(double x, double z) {}

    /**
     * The mass-weighted mean of the {@code (x, z)} of every non-ground node in
     * the component. Ground nodes are anchors, not mass, so they are skipped.
     * The sum runs in {@link NodePos#CANONICAL_ORDER} for a reproducible total.
     *
     * @return the center of mass, or {@code null} if the component has no
     *     non-ground mass (nothing to balance).
     */
    public static Point2D centerOfMass(StructureGraph graph, Set<NodePos> component) {
        List<NodePos> ordered = new ArrayList<>(component);
        ordered.sort(NodePos.CANONICAL_ORDER);

        double sumMass = 0.0;
        double sumX = 0.0;
        double sumZ = 0.0;
        for (NodePos pos : ordered) {
            Node node = graph.getNode(pos);
            if (node == null || node.isGrounded()) {
                continue; // ground is an anchor, not mass
            }
            double m = node.mass();
            sumMass += m;
            sumX += m * pos.x();
            sumZ += m * pos.z();
        }
        if (sumMass <= 0.0) {
            return null;
        }
        return new Point2D(sumX / sumMass, sumZ / sumMass);
    }

    /**
     * The 2D convex hull of the component's ground-contact footprint: the
     * {@code (x, z)} of every non-ground node that either stands on its own
     * grounded column or sits face-adjacent to a grounded node. These are the
     * points where the body's weight actually reaches the ground, so their hull
     * is the polygon the CoM must stay inside.
     *
     * <p>Each contact cell contributes its four unit-face corners, so the hull is
     * the real footprint dilated by half a block. The hull is built by Andrew's
     * monotone-chain algorithm, deterministic by construction. The result is empty
     * only when nothing touches ground; any single contact cell already yields a
     * 1×1 square (four corners), so the degenerate pin/segment cases no longer
     * arise from a real footprint (though {@link #tips} still handles them for
     * callers that pass an explicit hull).
     */
    public static List<Point2D> supportPolygon(StructureGraph graph, Set<NodePos> component) {
        // Collect the DISTINCT (x,z) footprint cells in canonical order.
        List<NodePos> ordered = new ArrayList<>(component);
        ordered.sort(NodePos.CANONICAL_ORDER);

        Set<Long> seenColumns = new HashSet<>();
        List<Point2D> contacts = new ArrayList<>();
        for (NodePos pos : ordered) {
            Node node = graph.getNode(pos);
            if (node == null || node.isGrounded()) {
                continue;
            }
            if (!isGroundContact(graph, pos)) {
                continue;
            }
            // One footprint cell per (x,z): a tall pillar touches the ground at
            // one cell, not once per block. A voxel bears on its full UNIT FACE, so
            // the contact is the cell, not its centre point: add the four corners
            // (x±0.5, z±0.5) and let the hull take the real footprint. Using bare
            // centres understated the base by half a block in each axis — a 1×1
            // column read as a knife-edge pin and tipped for any CoM offset, when a
            // rigid body on a unit base is stable up to a 0.5 offset. (Minkowski
            // dilation of the centres hull by the half-cell.)
            long key = (((long) pos.x()) << 32) ^ (pos.z() & 0xffffffffL);
            if (seenColumns.add(key)) {
                double cx = pos.x();
                double cz = pos.z();
                contacts.add(new Point2D(cx - 0.5, cz - 0.5));
                contacts.add(new Point2D(cx + 0.5, cz - 0.5));
                contacts.add(new Point2D(cx - 0.5, cz + 0.5));
                contacts.add(new Point2D(cx + 0.5, cz + 0.5));
            }
        }
        return convexHull(contacts);
    }

    /**
     * Does the body topple? The CoM is in if it lies inside (or on, within
     * {@link #EPS}) the support polygon; otherwise the nearest hull edge is the
     * pivot and its outward normal is the topple direction.
     *
     * <p>Degenerate polygons are handled explicitly:
     *
     * <ul>
     *   <li><b>0 points</b> — nothing touches ground, so the body is unsupported
     *       and topples; pivot/direction are undefined ({@code null} / zero).
     *   <li><b>1 point</b> — a single pin: stable only if the CoM sits exactly
     *       over the pin, else it topples directly away from it.
     *   <li><b>2 points</b> — a line segment: stable only if the CoM projects
     *       onto the segment AND lies on it, else it topples about the nearest
     *       end or perpendicular to the line.
     * </ul>
     */
    public static TipResult tips(Point2D centroid, List<Point2D> polygon) {
        if (centroid == null) {
            return TipResult.stable(); // no mass to balance
        }
        int n = polygon.size();
        if (n == 0) {
            // Unsupported: it falls, but there is no edge to rotate about.
            return new TipResult(true, null, 0.0, 0.0);
        }
        if (n == 1) {
            return tipsAboutPoint(centroid, polygon.get(0));
        }
        if (n == 2) {
            return tipsOverSegment(centroid, polygon.get(0), polygon.get(1));
        }
        return tipsInPolygon(centroid, polygon);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  GROUND CONTACT
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Is this non-ground node a point where the body's weight reaches the
     * ground — i.e. does it stand on a straight-down grounded column, or sit
     * face-adjacent to a grounded node? The column walk follows EDGES (a node
     * over an unconnected one carries no load), mirroring the graph's own
     * support rule.
     */
    private static boolean isGroundContact(StructureGraph graph, NodePos pos) {
        for (NodePos neighbor : graph.getNeighbors(pos)) {
            Node n = graph.getNode(neighbor);
            if (n != null && n.isGrounded()) {
                return true;
            }
        }
        return hasGroundedColumnBelow(graph, pos);
    }

    /**
     * Does a straight-down column of CONNECTED nodes from {@code pos} reach a
     * grounded node with no gaps? Same rule as the graph's private support test,
     * walked here over the public neighbor API.
     */
    private static boolean hasGroundedColumnBelow(StructureGraph graph, NodePos pos) {
        Node node = graph.getNode(pos);
        while (node != null) {
            if (node.isGrounded()) {
                return true;
            }
            NodePos below = new NodePos(pos.x(), pos.y() - 1, pos.z());
            if (!graph.getNeighbors(pos).contains(below)) {
                return false; // no edge to the node below — not a load path
            }
            pos = below;
            node = graph.getNode(pos);
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  CONVEX HULL — Andrew's monotone chain (deterministic, integer input)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Andrew's monotone-chain convex hull of the given footprint points. Input
     * points carry integer coordinates; the result is the hull vertices in
     * counter-clockwise order with no collinear points and no duplicate of the
     * first vertex. Returns the input unchanged for 0, 1 or 2 distinct points.
     */
    private static List<Point2D> convexHull(List<Point2D> points) {
        // De-duplicate, then sort by (x, then z) — the canonical scan order.
        List<Point2D> pts = new ArrayList<>(new LinkedHashSet<>(points));
        if (pts.size() <= 2) {
            return pts;
        }
        pts.sort((a, b) -> a.x() != b.x() ? Double.compare(a.x(), b.x()) : Double.compare(a.z(), b.z()));

        Point2D[] hull = new Point2D[pts.size() * 2];
        int k = 0;
        // Lower hull.
        for (Point2D p : pts) {
            while (k >= 2 && cross(hull[k - 2], hull[k - 1], p) <= 0) {
                k--;
            }
            hull[k++] = p;
        }
        // Upper hull.
        int lower = k + 1;
        for (int i = pts.size() - 2; i >= 0; i--) {
            Point2D p = pts.get(i);
            while (k >= lower && cross(hull[k - 2], hull[k - 1], p) <= 0) {
                k--;
            }
            hull[k++] = p;
        }
        // The last point is the start point repeated — drop it.
        List<Point2D> result = new ArrayList<>(k - 1);
        for (int i = 0; i < k - 1; i++) {
            result.add(hull[i]);
        }
        return result;
    }

    /** 2D cross product of (b-a) × (c-a): >0 left turn, <0 right turn, 0 collinear. */
    private static double cross(Point2D a, Point2D b, Point2D c) {
        return (b.x() - a.x()) * (c.z() - a.z()) - (b.z() - a.z()) * (c.x() - a.x());
    }

    // ─────────────────────────────────────────────────────────────────────
    //  POINT-IN-POLYGON + NEAREST-EDGE PIVOT
    // ─────────────────────────────────────────────────────────────────────

    /** Tip test against a full (≥3-vertex) convex polygon in CCW order. */
    private static TipResult tipsInPolygon(Point2D c, List<Point2D> hull) {
        int n = hull.size();
        // Inside a CCW convex polygon iff the point is left-of (or on) every edge.
        boolean inside = true;
        for (int i = 0; i < n; i++) {
            Point2D a = hull.get(i);
            Point2D b = hull.get((i + 1) % n);
            if (cross(a, b, c) < -EPS) {
                inside = false;
                break;
            }
        }
        if (inside) {
            return TipResult.stable();
        }
        // Outside: find the nearest edge; that edge is the pivot.
        return topplesAboutNearestEdge(c, hull);
    }

    /**
     * The nearest point on the hull boundary is the pivot, and the body topples
     * in the direction FROM that pivot TO the center of mass. When the nearest
     * feature is an edge interior, that direction IS the edge's outward normal;
     * when it is a vertex (the CoM walked out past a corner), the body pivots
     * about the corner and topples radially away from it — which the
     * closest-point direction captures uniformly, with no edge/corner special
     * case.
     */
    private static TipResult topplesAboutNearestEdge(Point2D c, List<Point2D> hull) {
        int n = hull.size();
        double bestDist = Double.POSITIVE_INFINITY;
        Point2D bestClosest = hull.get(0);
        for (int i = 0; i < n; i++) {
            Point2D a = hull.get(i);
            Point2D b = hull.get((i + 1) % n);
            Point2D closest = closestOnSegment(c, a, b);
            double d = dist2(c, closest);
            if (d < bestDist - EPS) {
                bestDist = d;
                bestClosest = closest;
            }
        }
        double dx = c.x() - bestClosest.x();
        double dz = c.z() - bestClosest.z();
        return toppleResult(bestClosest, dx, dz, c);
    }

    /** Single-pin base: stable only if the CoM sits exactly over the pin. */
    private static TipResult tipsAboutPoint(Point2D c, Point2D pin) {
        double dx = c.x() - pin.x();
        double dz = c.z() - pin.z();
        if (dx * dx + dz * dz <= EPS * EPS) {
            return TipResult.stable();
        }
        // Topples directly away from the pin; pivot is the pin itself.
        return toppleResult(pin, dx, dz, c);
    }

    /** Two-point (line-segment) base: stable only if the CoM lies on the segment. */
    private static TipResult tipsOverSegment(Point2D c, Point2D a, Point2D b) {
        Point2D closest = closestOnSegment(c, a, b);
        if (dist2(c, closest) <= EPS * EPS) {
            return TipResult.stable();
        }
        // Topples about the nearest point on the segment, away from it.
        double dx = c.x() - closest.x();
        double dz = c.z() - closest.z();
        return toppleResult(closest, dx, dz, c);
    }

    /**
     * Build a tipping result: a unit outward direction (oriented to point from
     * the pivot toward the CoM) and the pivot midpoint rounded to integer
     * lattice coordinates at {@code y = 0}.
     */
    private static TipResult toppleResult(Point2D pivot, double normalX, double normalZ, Point2D com) {
        // Orient the normal so it points outward (from pivot toward the CoM).
        double toComX = com.x() - pivot.x();
        double toComZ = com.z() - pivot.z();
        if (normalX * toComX + normalZ * toComZ < 0) {
            normalX = -normalX;
            normalZ = -normalZ;
        }
        double len = Math.hypot(normalX, normalZ);
        double dirX = len > 0 ? normalX / len : 0.0;
        double dirZ = len > 0 ? normalZ / len : 0.0;
        NodePos pivotPos = new NodePos((int) Math.round(pivot.x()), 0, (int) Math.round(pivot.z()));
        return new TipResult(true, pivotPos, dirX, dirZ);
    }

    /** The closest point on segment a→b to point c. */
    private static Point2D closestOnSegment(Point2D c, Point2D a, Point2D b) {
        double ex = b.x() - a.x();
        double ez = b.z() - a.z();
        double len2 = ex * ex + ez * ez;
        if (len2 <= 0.0) {
            return a; // degenerate segment
        }
        double t = ((c.x() - a.x()) * ex + (c.z() - a.z()) * ez) / len2;
        t = Math.max(0.0, Math.min(1.0, t));
        return new Point2D(a.x() + t * ex, a.z() + t * ez);
    }

    /** Squared distance between two points. */
    private static double dist2(Point2D a, Point2D b) {
        double dx = a.x() - b.x();
        double dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }
}
