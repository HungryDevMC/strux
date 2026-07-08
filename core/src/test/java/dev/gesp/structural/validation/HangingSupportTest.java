package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Hanging blocks (supported from above or sideways, not by a column to ground)
 * must still collapse when their support is cut. The scoped-solve optimizations
 * use "has a block directly below" as a proxy for "independently supported" —
 * which a hanging chain defeats: the block below may itself be dangling off the
 * thing being removed. These tests pin the sound behavior.
 *
 * <pre>
 *   THE SHAPE (tower + beam + hanging chain):
 *
 *   y=5  [A5][B1][B2]      A* = tower column (grounded)
 *   y=4  [A4]    [H1]      B* = beam off the tower
 *   y=3  [A3]    [H2]      H* = chain hanging from the beam tip
 *   y=2  [A2]
 *   y=1  [A1]
 *   y=0  [GND]
 * </pre>
 */
@DisplayName("Hanging chains collapse when their support is cut")
class HangingSupportTest {

    private static final NodePos GND = new NodePos(0, 0, 0);
    private static final NodePos B1 = new NodePos(1, 5, 0);
    private static final NodePos B2 = new NodePos(2, 5, 0);
    private static final NodePos H1 = new NodePos(2, 4, 0);
    private static final NodePos H2 = new NodePos(2, 3, 0);

    /** Build the tower + beam + hanging chain from the class diagram. */
    private static StructureGraph towerBeamHang() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(GND);
        for (int y = 1; y <= 5; y++) {
            g.addBlock(new NodePos(0, y, 0), TestMaterials.LIGHT, false);
        }
        g.addBlock(B1, TestMaterials.LIGHT, false);
        g.addBlock(B2, TestMaterials.LIGHT, false);
        g.addBlock(H1, TestMaterials.LIGHT, false);
        g.addBlock(H2, TestMaterials.LIGHT, false);
        return g;
    }

    @Test
    @DisplayName("Breaking mid-beam drops the beam tip AND the chain hanging from it")
    void hangingChainFallsWithItsBeam() {
        StructureGraph g = towerBeamHang();
        new CascadeEngine().cascade(g, B1);

        assertFalse(g.hasBlock(B2), "beam tip lost its only support");
        assertFalse(g.hasBlock(H1), "chain hangs off the beam tip — must fall with it");
        assertFalse(g.hasBlock(H2), "the whole chain must fall, not just the part near the break");
        assertTrue(g.hasBlock(new NodePos(0, 5, 0)), "tower is untouched");
    }

    @Test
    @DisplayName("Breaking the beam tip drops the chain hanging directly below it")
    void chainBelowBrokenBlockFalls() {
        StructureGraph g = towerBeamHang();
        new CascadeEngine().cascade(g, B2);

        assertFalse(g.hasBlock(H1), "chain hung from the broken block itself");
        assertFalse(g.hasBlock(H2), "the whole chain must fall");
        assertTrue(g.hasBlock(B1), "the rest of the beam keeps its tower support");
    }

    @Test
    @DisplayName("getDependentSubgraph: a lateral neighbor on a hanging column is a dependent")
    void dependentSubgraphSeesHangingLateral() {
        StructureGraph g = towerBeamHang();

        // B2 has H1 directly below it — but H1 hangs; it is NOT support. B2's
        // only real support is B1, so B2 (and its chain) depend on B1.
        Set<NodePos> deps = g.getDependentSubgraph(B1);
        assertTrue(deps.contains(B2), "B2's below-column never reaches ground — it depends on B1");
        assertTrue(deps.contains(H1), "H1 hangs from B2, it doesn't support it");
        assertTrue(deps.contains(H2), "the whole hanging chain is dependent");
    }

    @Test
    @DisplayName("getSupportAncestors: a block above a hanging column finds the real support path")
    void supportAncestorsSkipPastHangingColumn() {
        StructureGraph g = towerBeamHang();
        // Place a block on top of the beam tip: its load cannot go down through
        // the hanging chain — it flows through the beam into the tower.
        NodePos onTip = new NodePos(2, 6, 0);
        g.addBlock(onTip, TestMaterials.LIGHT, false);

        Set<NodePos> ancestors = g.getSupportAncestors(onTip);
        assertTrue(ancestors.contains(B1), "load must route through the beam...");
        assertTrue(ancestors.contains(new NodePos(0, 5, 0)), "...into the tower");
    }

    @Test
    @DisplayName("findFloatingInScope reports provably-floating blocks even outside the scope")
    void floatingDetectionCoversWholeGroundlessRegion() {
        StructureGraph g = towerBeamHang();
        g.removeBlock(B1); // beam tip + chain now have no path to ground

        // Scope only contains B2 — but the BFS proves H1/H2 float too.
        Set<NodePos> floating = g.findFloatingInScope(Set.of(B2));
        assertTrue(floating.contains(B2));
        assertTrue(floating.contains(H1), "H1 was proven floating by the same BFS");
        assertTrue(floating.contains(H2), "H2 was proven floating by the same BFS");
    }
}
