package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scoped traversals must reason about EDGES, not coordinates. On the
 * generic API ({@code addNode} + {@code connect}) two nodes can sit stacked
 * by position without any edge between them — and a column you are not
 * connected to is no support at all. Treating it as support (because the
 * coordinates line up) silently excludes real dependents from the scoped
 * solve, and the un-solved blocks float forever.
 *
 * <pre>
 *   COORDINATE LIE (column exists, but no edges to it):
 *
 *   y=2   [B]──[C]      C sits directly above U1 — but C↔U1 was never
 *          │            connected. C's ONLY support is the edge to B.
 *   y=1   [A]  [U1]     U1–U2–GND2 is a separate, unconnected stack.
 *          │    ┆
 *   y=0  [GND] [U2,GND2]
 * </pre>
 */
@DisplayName("Scoped traversals follow edges, not coordinates")
class ExplicitTopologyScopingTest {

    private static final NodePos GND = new NodePos(0, 0, 0);
    private static final NodePos A = new NodePos(0, 1, 0);
    private static final NodePos B = new NodePos(0, 2, 0);
    private static final NodePos C = new NodePos(1, 2, 0);
    private static final NodePos U1 = new NodePos(1, 1, 0);
    private static final NodePos GND2 = new NodePos(1, 0, 0);

    /** Build the diagram: grounded A–B tower, C hanging off B, unconnected stack U1/GND2 under C. */
    private static StructureGraph build() {
        StructureGraph g = new StructureGraph();
        g.addNode(GND, MaterialSpec.GROUND, true);
        g.addNode(A, TestMaterials.LIGHT, false);
        g.addNode(B, TestMaterials.LIGHT, false);
        g.addNode(C, TestMaterials.LIGHT, false);
        g.addNode(U1, TestMaterials.LIGHT, false);
        g.addNode(GND2, MaterialSpec.GROUND, true);
        g.connect(GND, A);
        g.connect(A, B);
        g.connect(B, C); // C's only support
        g.connect(U1, GND2); // separate stack; deliberately NOT connected to C
        return g;
    }

    @Test
    @DisplayName("getDependentSubgraph: a coordinate column without edges is not support")
    void unconnectedColumnIsNotSupport() {
        StructureGraph g = build();
        Set<NodePos> deps = g.getDependentSubgraph(B);
        assertTrue(
                deps.contains(C),
                "C's only edge-support is B; the unconnected stack below C must not count as support");
    }

    @Test
    @DisplayName("Cascade: node above an unconnected column still falls when its real support breaks")
    void cascadeDropsNodeAboveUnconnectedColumn() {
        StructureGraph g = build();
        new CascadeEngine().cascade(g, B);

        assertFalse(g.hasBlock(C), "C lost its only edge — it must fall, coordinates below notwithstanding");
        assertTrue(g.hasBlock(U1), "the separate stack is untouched");
        assertTrue(g.hasBlock(A), "the tower below the break is untouched");
    }

    @Test
    @DisplayName("getSupportAncestors: load routes through edges, not through coordinate neighbours")
    void supportAncestorsFollowEdges() {
        StructureGraph g = build();
        Set<NodePos> ancestors = g.getSupportAncestors(C);
        assertTrue(ancestors.contains(B), "C's load flows through its edge to B...");
        assertTrue(ancestors.contains(A), "...down the tower");
        assertFalse(ancestors.contains(U1), "the unconnected stack below carries nothing for C");
    }

    @Test
    @DisplayName("Grid sugar: a disconnect()ed face-adjacent pair is not support either")
    void disconnectedGridPairIsNotSupport() {
        // Same lie on the grid API: addBlock auto-connects, then disconnect()
        // severs the load path while the coordinates still line up.
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addGroundBlock(new NodePos(1, 0, 0)); // ground under D — the coordinate column DOES reach ground
        g.addBlock(new NodePos(0, 1, 0), TestMaterials.LIGHT, false); // A (tower)
        g.addBlock(new NodePos(0, 2, 0), TestMaterials.LIGHT, false); // B (tower top)
        g.addBlock(new NodePos(1, 1, 0), TestMaterials.LIGHT, false); // D, its own grounded stump
        g.addBlock(new NodePos(1, 2, 0), TestMaterials.LIGHT, false); // C, on D and beside B
        g.disconnect(new NodePos(1, 2, 0), new NodePos(1, 1, 0)); // sever C↔D: C now hangs on B alone

        new CascadeEngine().cascade(g, new NodePos(0, 2, 0)); // break B

        assertFalse(
                g.hasBlock(new NodePos(1, 2, 0)),
                "C's only remaining edge was to B — the disconnect()ed grounded stump below is not support");
        assertTrue(g.hasBlock(new NodePos(1, 1, 0)), "D keeps standing on its own ground");
    }
}
