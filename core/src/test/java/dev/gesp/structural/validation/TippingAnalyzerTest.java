package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.assess.TipResult;
import dev.gesp.structural.assess.TippingAnalyzer;
import dev.gesp.structural.assess.TippingAnalyzer.Point2D;
import dev.gesp.structural.engine.StruxEngine;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.NodePos;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Global tipping statics: center of mass versus support polygon. These are
 * closed-form checks — a crane operator's rule, not a tuning knob — so they pin
 * the analyzer against the textbook, like {@link PhysicsValidationTest} does for
 * the solver.
 *
 * <pre>
 *   symmetric base  — CoM centered over its footprint → never tips
 *   one unit out     — CoM a step past a hull edge → tips, pivot at that edge
 *   one unit in      — same CoM a step inside → stable
 *   single pillar    — degenerate 1-point base handled explicitly
 *   off-center mass  — a cantilever-heavy load topples toward the overhang
 * </pre>
 */
@DisplayName("Tipping analyzer: CoM vs support polygon (real statics)")
class TippingAnalyzerTest {

    /** FP slack matching the analyzer's knife-edge epsilon. */
    private static final double EPS = 1e-9;

    /**
     * A symmetric 3×3 footprint of grounded anchors with a single uniform layer
     * of mass on top: the CoM sits dead-center over the footprint.
     */
    private static StructureGraph symmetricBox() {
        StructureGraph g = new StructureGraph();
        for (int x = 0; x <= 2; x++) {
            for (int z = 0; z <= 2; z++) {
                g.addGroundBlock(new NodePos(x, 0, z));
                g.addBlock(new NodePos(x, 1, z), TestMaterials.MEDIUM, false);
            }
        }
        return g;
    }

    @Test
    @DisplayName("Symmetric structure: CoM is centered over the footprint and does NOT tip")
    void symmetricDoesNotTip() {
        StructureGraph g = symmetricBox();
        var component = g.componentOf(new NodePos(1, 1, 1));

        Point2D com = TippingAnalyzer.centerOfMass(g, component);
        assertNotNull(com);
        assertEquals(1.0, com.x(), EPS, "CoM x is the footprint center");
        assertEquals(1.0, com.z(), EPS, "CoM z is the footprint center");

        List<Point2D> hull = TippingAnalyzer.supportPolygon(g, component);
        TipResult result = TippingAnalyzer.tips(com, hull);
        assertFalse(result.tips(), "a symmetric box centered over its base must not tip");
        assertNull(result.pivotEdgeMidpoint(), "stable structures have no pivot");
    }

    @Test
    @DisplayName("Symmetric structure has mirror symmetry: CoM unchanged under x<->2-x")
    void symmetryMirrorsCenterOfMass() {
        StructureGraph g = symmetricBox();
        var component = g.componentOf(new NodePos(1, 1, 1));
        Point2D com = TippingAnalyzer.centerOfMass(g, component);
        // Mirror about the center x=1: a symmetric body's CoM maps to itself.
        assertEquals(2.0 - com.x(), com.x(), EPS, "CoM must be its own mirror image about x=1");
    }

    @Test
    @DisplayName("CoM one unit OUTSIDE the hull edge tips; one unit inside does not; pivot is that edge")
    void comOutsideHullTipsAtNearestEdge() {
        // Square footprint with corners at (0,0)..(2,2). Hull edges run along
        // x=0, x=2, z=0, z=2. A CoM at x=3 is one unit outside the x=2 edge.
        List<Point2D> hull = List.of(new Point2D(0, 0), new Point2D(2, 0), new Point2D(2, 2), new Point2D(0, 2));

        TipResult outside = TippingAnalyzer.tips(new Point2D(3, 1), hull);
        assertTrue(outside.tips(), "CoM a full unit past the x=2 edge must tip");
        assertEquals(1.0, outside.dirX(), EPS, "topples in +x, away from the base");
        assertEquals(0.0, outside.dirZ(), EPS, "no z-component for a clean +x overhang");
        assertNotNull(outside.pivotEdgeMidpoint());
        assertEquals(2, outside.pivotEdgeMidpoint().x(), "pivot sits on the x=2 hull edge");
        assertEquals(0, outside.pivotEdgeMidpoint().y(), "pivot is reported at ground level");

        TipResult inside = TippingAnalyzer.tips(new Point2D(1, 1), hull);
        assertFalse(inside.tips(), "CoM one unit inside the same edge is stable");
    }

    @Test
    @DisplayName("Single 1x1 column: stable for any CoM within its unit cell, tips only past the half-block edge")
    void singleColumnUnitFootprint() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(5, 0, 5));
        for (int y = 1; y <= 6; y++) {
            g.addBlock(new NodePos(5, y, 5), TestMaterials.LIGHT, false);
        }
        var component = g.componentOf(new NodePos(5, 3, 5));

        // A voxel bears on its full unit face, so its footprint is a 1×1 square
        // (four corners), not a knife-edge pin.
        List<Point2D> hull = TippingAnalyzer.supportPolygon(g, component);
        assertEquals(4, hull.size(), "a single column bears on its full unit cell — a 1x1 square");

        Point2D com = TippingAnalyzer.centerOfMass(g, component);
        assertFalse(TippingAnalyzer.tips(com, hull).tips(), "a vertical column is centred over its cell");

        // The whole point of the fix: a CoM that overhangs by LESS than half a block
        // still sits over the unit base, so the body is stable — it does NOT tip.
        assertFalse(
                TippingAnalyzer.tips(new Point2D(5 + 0.4, 5), hull).tips(),
                "a 0.4-block overhang is still within the unit base — stable, not a topple");

        // Past the half-block edge of the cell it finally topples, away from the base.
        TipResult nudged = TippingAnalyzer.tips(new Point2D(5 + 0.9, 5), hull);
        assertTrue(nudged.tips(), "a CoM past the cell's +x edge tips");
        assertEquals(1.0, nudged.dirX(), EPS, "topples in +x, away from the base");
        assertEquals(0.0, nudged.dirZ(), EPS);
    }

    @Test
    @DisplayName("Off-center cantilever-heavy mass tips toward the overhang")
    void cantileverHeavyMassTipsTowardOverhang() {
        // A single grounded pillar at x=0 with a long heavy arm reaching out in
        // +x. All the mass hangs off one side, so the CoM walks past the base.
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), TestMaterials.LIGHT, false);
        for (int x = 1; x <= 6; x++) {
            g.addBlock(new NodePos(x, 1, 0), TestMaterials.HEAVY, false);
        }
        var component = g.componentOf(new NodePos(0, 1, 0));

        Point2D com = TippingAnalyzer.centerOfMass(g, component);
        assertTrue(com.x() > 0.0, "the heavy arm drags the CoM out past the base column at x=0");

        TipResult result = TippingAnalyzer.tips(com, TippingAnalyzer.supportPolygon(g, component));
        assertTrue(result.tips(), "a one-sided cantilever load must topple");
        assertEquals(1.0, result.dirX(), EPS, "it tips toward the overhang (+x)");
        assertEquals(0.0, result.dirZ(), EPS);
        // The base cell at x=0 bears on its unit face, so the pivot is that cell's +x
        // edge (x=0.5, reported at the lattice line x=1), not the block centre.
        assertEquals(1, result.pivotEdgeMidpoint().x(), "pivot is the +x edge of the base cell");
    }

    @Test
    @DisplayName("Diagonal overhang topples on the 45° diagonal (unit direction is normalized)")
    void diagonalToppleIsNormalized() {
        List<Point2D> hull = List.of(new Point2D(0, 0), new Point2D(2, 0), new Point2D(2, 2), new Point2D(0, 2));
        // CoM beyond the (2,2) corner along the diagonal: tips past that corner.
        TipResult result = TippingAnalyzer.tips(new Point2D(4, 4), hull);
        assertTrue(result.tips());
        double inv = 1.0 / Math.sqrt(2.0);
        assertEquals(inv, result.dirX(), 1e-9, "diagonal topple is normalized in +x");
        assertEquals(inv, result.dirZ(), 1e-9, "diagonal topple is normalized in +z");
        assertEquals(1.0, Math.hypot(result.dirX(), result.dirZ()), 1e-9, "direction is a unit vector");
        assertEquals(2, result.pivotEdgeMidpoint().x(), "pivot is the (2,2) corner");
        assertEquals(2, result.pivotEdgeMidpoint().z(), "pivot is the (2,2) corner");
    }

    @Test
    @DisplayName("Nearest hull edge wins: a CoM just past one edge pivots about THAT edge, not another")
    void nearestEdgeIsChosen() {
        List<Point2D> hull = List.of(new Point2D(0, 0), new Point2D(10, 0), new Point2D(10, 4), new Point2D(0, 4));
        // Just past the z=0 edge (one unit below it), well inside x — the z=0
        // edge is unambiguously nearest, so the body tips in -z about z=0.
        TipResult result = TippingAnalyzer.tips(new Point2D(5, -1), hull);
        assertTrue(result.tips());
        assertEquals(0.0, result.dirX(), EPS);
        assertEquals(-1.0, result.dirZ(), EPS, "tips in -z, away from the z=0 edge");
        assertEquals(0, result.pivotEdgeMidpoint().z(), "pivot lies on the z=0 edge");
        assertEquals(5, result.pivotEdgeMidpoint().x(), "pivot is the foot of the perpendicular at x=5");
    }

    @Test
    @DisplayName("Two-point base: CoM past a segment END pivots about that end, not the line")
    void twoPointBeyondEnd() {
        List<Point2D> segment = List.of(new Point2D(0, 0), new Point2D(4, 0));
        // x = 6 is two units past the (4,0) end: closest point is the end itself.
        TipResult result = TippingAnalyzer.tips(new Point2D(6, 0), segment);
        assertTrue(result.tips());
        assertEquals(1.0, result.dirX(), EPS, "tips in +x, away from the near end");
        assertEquals(0.0, result.dirZ(), EPS);
        assertEquals(4, result.pivotEdgeMidpoint().x(), "pivot is the (4,0) end of the segment");
    }

    @Test
    @DisplayName("Empty support (no ground contact) is reported as tipping with no pivot")
    void noGroundContactTips() {
        TipResult result = TippingAnalyzer.tips(new Point2D(0, 0), List.of());
        assertTrue(result.tips(), "nothing touches ground — it falls");
        assertNull(result.pivotEdgeMidpoint(), "an unsupported body has no edge to rotate about");
    }

    @Test
    @DisplayName("Two-point base (line segment): on the line is stable, off the line tips")
    void twoPointSegmentBase() {
        List<Point2D> segment = List.of(new Point2D(0, 0), new Point2D(4, 0));

        TipResult onLine = TippingAnalyzer.tips(new Point2D(2, 0), segment);
        assertFalse(onLine.tips(), "a CoM on the support line is balanced");

        TipResult offLine = TippingAnalyzer.tips(new Point2D(2, 1), segment);
        assertTrue(offLine.tips(), "a CoM off the support line topples perpendicular to it");
        assertEquals(0.0, offLine.dirX(), EPS);
        assertEquals(1.0, offLine.dirZ(), EPS, "tips in +z, away from the line");
    }

    @Test
    @DisplayName("Component with no non-ground mass has no CoM and reads as stable")
    void groundOnlyHasNoCenterOfMass() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        var component = g.componentOf(new NodePos(0, 0, 0));

        assertNull(TippingAnalyzer.centerOfMass(g, component), "ground carries no mass");
        TipResult result = TippingAnalyzer.tips(null, TippingAnalyzer.supportPolygon(g, component));
        assertFalse(result.tips(), "nothing to balance is not a topple");
    }

    @Test
    @DisplayName("SDK facade: StruxEngine.wouldTip routes through the analyzer")
    void engineWouldTip() {
        StruxEngine stable = new StruxEngine();
        for (int x = 0; x <= 2; x++) {
            for (int z = 0; z <= 2; z++) {
                stable.addGround(x, 0, z);
                stable.addBlock(x, 1, z, TestMaterials.MEDIUM);
            }
        }
        assertFalse(stable.wouldTip(1, 1, 1).tips(), "a centered box does not tip");

        StruxEngine leaning = new StruxEngine();
        leaning.addGround(0, 0, 0);
        leaning.addBlock(0, 1, 0, TestMaterials.LIGHT);
        for (int x = 1; x <= 6; x++) {
            leaning.addBlock(x, 1, 0, TestMaterials.HEAVY);
        }
        TipResult tip = leaning.wouldTip(0, 1, 0);
        assertTrue(tip.tips(), "a one-sided cantilever topples");
        assertEquals(1.0, tip.dirX(), EPS, "toward the overhang");

        assertFalse(stable.wouldTip(99, 99, 99).tips(), "an absent position reads as stable");
    }
}
