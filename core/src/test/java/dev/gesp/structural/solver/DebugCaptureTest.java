package dev.gesp.structural.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.api.DebugCapture;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The optional {@link DebugCapture} sink must harvest the solver's rich intermediates
 * (vertical/moment stress split, the distance-from-ground field, and load-flow edges)
 * with known, asserted values — and stay a default no-op (zero emits) when not wanted.
 */
@DisplayName("StressSolver DebugCapture: rich intermediates captured with known physics")
class DebugCaptureTest {

    private static final MaterialSpec UNIT = new MaterialSpec(1.0, 1000.0);

    /** A capturing sink that records everything it is told. */
    private static final class Recorder implements DebugCapture {
        final Map<NodePos, double[]> components = new HashMap<>(); // pos -> {vertical, moment, max, percent}
        final List<double[]> edges = new ArrayList<>(); // {fromHash, toHash, share, absLoad} via parallel lists
        final List<NodePos[]> edgePos = new ArrayList<>(); // {from, to}
        Map<NodePos, Integer> distances;
        boolean lastFinalPass;
        int passes;
        boolean loadFlow;

        Recorder(boolean loadFlow) {
            this.loadFlow = loadFlow;
        }

        @Override
        public boolean wantsDebugCapture() {
            return true;
        }

        @Override
        public boolean wantsLoadFlow() {
            return loadFlow;
        }

        @Override
        public void beginPass(boolean finalPass) {
            this.lastFinalPass = finalPass;
            passes++;
        }

        @Override
        public void onGroundDistances(Map<NodePos, Integer> d) {
            this.distances = new HashMap<>(d);
        }

        @Override
        public void onStressComponents(
                NodePos pos, double verticalStress, double momentStress, double effectiveMaxLoad, double percent) {
            components.put(pos, new double[] {verticalStress, momentStress, effectiveMaxLoad, percent});
        }

        @Override
        public void onLoadFlowEdge(NodePos from, NodePos to, double shareFraction, double absoluteLoad) {
            edgePos.add(new NodePos[] {from, to});
            edges.add(new double[] {shareFraction, absoluteLoad});
        }
    }

    private static StructureGraph stack(int height) {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= height; y++) {
            g.addBlock(new NodePos(0, y, 0), UNIT, false);
        }
        return g;
    }

    @Test
    @DisplayName("Vertical stack: ground distances, vertical-stress split, and a floater MAX_VALUE are captured")
    void capturesDistancesAndStressSplit() {
        StructureGraph g = stack(3); // ground at y0, blocks y1..y3
        NodePos floater = new NodePos(10, 20, 10);
        g.addBlock(floater, UNIT, false); // disconnected -> no path to ground

        Recorder rec = new Recorder(false);
        StressSolver solver = new StressSolver().setDebugCapture(rec).setFinalPass(true);
        solver.solve(g, new HashSet<>(g.getAllPositions()));

        assertEquals(1, rec.passes, "one solve, one pass");
        assertTrue(rec.lastFinalPass, "marked as the final pass");

        // Ground distances: y1=1, y2=2, y3=3, ground y0=0, floater=MAX_VALUE.
        assertEquals(0, rec.distances.get(new NodePos(0, 0, 0)));
        assertEquals(1, rec.distances.get(new NodePos(0, 1, 0)));
        assertEquals(3, rec.distances.get(new NodePos(0, 3, 0)));
        assertEquals(Integer.MAX_VALUE, rec.distances.get(floater), "the disconnected block is a floater");

        // Stress split: y1 bears itself + the two blocks above = 3.0 vertical, 0 moment.
        double[] y1 = rec.components.get(new NodePos(0, 1, 0));
        assertEquals(3.0, y1[0], 1e-9, "y1 vertical stress = own mass + 2 above");
        assertEquals(0.0, y1[1], 1e-9, "a straight stack has no moment stress");
        assertEquals(1000.0, y1[2], 1e-9, "effectiveMaxLoad = material maxLoad");
        assertEquals(3.0 / 1000.0, y1[3], 1e-9, "percent = total / maxLoad");

        // The top block bears only itself.
        assertEquals(1.0, rec.components.get(new NodePos(0, 3, 0))[0], 1e-9, "the top block bears only its own mass");

        // Ground nodes are not emitted (they absorb load, stress stays 0).
        assertFalse(rec.components.containsKey(new NodePos(0, 0, 0)), "ground nodes are not reported");
    }

    @Test
    @DisplayName("Y-fork: load splits 50/50 down two equal paths — captured as two share=0.5 edges")
    void capturesLoadFlowShareFractions() {
        // A heavy block at the top, two equal one-step paths to ground:
        //        [TOP] (y2)
        //       /      \
        //   [L](y1)  [R](y1)
        //     |          |
        //  [GL](y0)    [GR](y0)
        StructureGraph g = new StructureGraph();
        NodePos gl = new NodePos(-1, 0, 0);
        NodePos gr = new NodePos(1, 0, 0);
        NodePos l = new NodePos(-1, 1, 0);
        NodePos r = new NodePos(1, 1, 0);
        NodePos top = new NodePos(0, 2, 0);
        g.addGroundBlock(gl);
        g.addGroundBlock(gr);
        g.addBlock(l, UNIT, false);
        g.addBlock(r, UNIT, false);
        g.addBlock(top, UNIT, false);
        // Connect top to both l and r (top is a diagonal-free face neighbour? use direct edges)
        g.connect(top, l);
        g.connect(top, r);

        Recorder rec = new Recorder(true);
        StressSolver solver = new StressSolver().setDebugCapture(rec).setFinalPass(true);
        solver.solve(g, new HashSet<>(g.getAllPositions()));

        // top (dist 2) sends its vertical load to its two strictly-closer supporters
        // l and r (both dist 1), equally: share 0.5 each, absolute = 1.0 * 0.5 = 0.5.
        List<double[]> topEdges = new ArrayList<>();
        for (int i = 0; i < rec.edgePos.size(); i++) {
            if (rec.edgePos.get(i)[0].equals(top)) {
                topEdges.add(rec.edges.get(i));
            }
        }
        assertEquals(2, topEdges.size(), "top routes to exactly two supporters");
        for (double[] e : topEdges) {
            assertEquals(0.5, e[0], 1e-9, "each equal path gets half the load");
            assertEquals(0.5, e[1], 1e-9, "absolute load = top vertical (1.0) * share (0.5)");
        }
    }

    @Test
    @DisplayName("No load-flow edges captured when wantsLoadFlow() is off")
    void loadFlowGatedOff() {
        StructureGraph g = stack(3);
        Recorder rec = new Recorder(false);
        new StressSolver().setDebugCapture(rec).setFinalPass(true).solve(g, new HashSet<>(g.getAllPositions()));
        assertTrue(rec.edgePos.isEmpty(), "load-flow is gated off, so no edges");
        assertFalse(rec.components.isEmpty(), "but the cheap stress split is still captured");
    }

    @Test
    @DisplayName("Load-flow edges captured only on the final pass, never on a non-final pass")
    void loadFlowGatedToFinalPass() {
        StructureGraph g = stack(3);
        Recorder rec = new Recorder(true);
        // Non-final pass: edges suppressed even though wantsLoadFlow() is on.
        new StressSolver().setDebugCapture(rec).setFinalPass(false).solve(g, new HashSet<>(g.getAllPositions()));
        assertTrue(rec.edgePos.isEmpty(), "a non-final pass keeps no load-flow edges");

        // Final pass: now edges flow.
        Recorder rec2 = new Recorder(true);
        new StressSolver().setDebugCapture(rec2).setFinalPass(true).solve(g, new HashSet<>(g.getAllPositions()));
        assertFalse(rec2.edgePos.isEmpty(), "the final pass keeps load-flow edges");
    }

    @Test
    @DisplayName("Default DebugCapture.NONE wants nothing and is never driven")
    void noneSinkIsInert() {
        assertFalse(DebugCapture.NONE.wantsDebugCapture(), "NONE opts out");
        assertFalse(DebugCapture.NONE.wantsLoadFlow(), "NONE opts out of load-flow too");

        // A solver with NO sink attached emits nothing (the production path).
        StructureGraph g = stack(3);
        Recorder rec = new Recorder(true);
        StressSolver solver = new StressSolver(); // no setDebugCapture
        solver.solve(g, new HashSet<>(g.getAllPositions()));
        assertEquals(0, rec.passes, "an unattached recorder is never told anything");
    }
}
