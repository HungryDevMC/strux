package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import dev.gesp.structural.solver.CascadeResult;
import dev.gesp.structural.solver.StressSolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Test: the engine is unit-agnostic.
 *
 * <p>These tests build structures with the GENERIC API (addNode + connect)
 * instead of the grid helper (addBlock). The nodes sit at positions that are
 * NOT face-adjacent (spaced 2 apart, like prefab centers), so the only thing
 * holding the structure together is the edges WE declare. This proves the
 * solver/cascade reason purely about nodes and edges — exactly what lets a
 * consumer model prefabs, truss joints, vertices, etc. on top of strux.
 *
 * <pre>
 *            [C]  y=6   ← prefab-style nodes, spaced 2 apart
 *             ┊         (NOT grid-adjacent — connected explicitly)
 *            [B]  y=4
 *             ┊
 *            [A]  y=2
 *             ┊
 *           [GND] y=0
 * </pre>
 */
@DisplayName("Generic topology: any unit, explicit edges")
class ExplicitTopologyTest {

    private static final NodePos GROUND = new NodePos(0, 0, 0);
    private static final NodePos A = new NodePos(0, 2, 0);
    private static final NodePos B = new NodePos(0, 4, 0);
    private static final NodePos C = new NodePos(0, 6, 0);

    /** Build the spaced-out, explicitly-connected stack used by most tests. */
    private StructureGraph chain() {
        StructureGraph graph = new StructureGraph();
        graph.addGroundNode(GROUND);
        graph.addNode(A, TestMaterials.MEDIUM, false);
        graph.addNode(B, TestMaterials.MEDIUM, false);
        graph.addNode(C, TestMaterials.MEDIUM, false);
        graph.connect(GROUND, A);
        graph.connect(A, B);
        graph.connect(B, C);
        return graph;
    }

    @Test
    @DisplayName("Adjacency is explicit, not derived from position")
    void adjacencyIsExplicitNotPositional() {
        StructureGraph graph = new StructureGraph();
        graph.addGroundNode(GROUND);
        graph.addNode(A, TestMaterials.MEDIUM, false);
        graph.addNode(B, TestMaterials.MEDIUM, false);

        // Nodes are 2 apart, so the grid helper would NOT link them, and we
        // have not called connect() — therefore there are no edges at all.
        assertTrue(graph.getNeighbors(A).isEmpty(), "no edges until we declare them");
        // Nothing reaches ground, so A and B are floating.
        assertEquals(2, graph.getFloatingBlocks().size());

        // Now wire it up explicitly and the floating goes away.
        graph.connect(GROUND, A);
        graph.connect(A, B);
        assertTrue(graph.getFloatingBlocks().isEmpty());
        assertTrue(graph.getNeighbors(A).contains(GROUND));
        assertTrue(graph.getNeighbors(A).contains(B));
    }

    @Test
    @DisplayName("neighborsOf mirrors getNeighbors but is the live backing set (no wrapper)")
    void neighborsOfMatchesPublicViewWithoutWrapping() {
        StructureGraph graph = chain();

        // Same contents as the public, wrapping accessor — for the in-module hot
        // loops this is the non-allocating equivalent of getNeighbors.
        assertEquals(graph.getNeighbors(A), graph.neighborsOf(A));
        assertTrue(graph.neighborsOf(A).contains(GROUND));
        assertTrue(graph.neighborsOf(A).contains(B));

        // It returns the SAME live set object on each call (no per-call wrapper
        // allocation), which is the whole point of the accessor.
        assertSame(graph.neighborsOf(A), graph.neighborsOf(A));

        // An absent node yields an empty set, never null.
        assertTrue(graph.neighborsOf(new NodePos(99, 99, 99)).isEmpty());

        // The live set tracks edits: connecting a new edge shows up immediately.
        NodePos d = new NodePos(0, 8, 0);
        graph.addNode(d, TestMaterials.MEDIUM, false);
        graph.connect(C, d);
        assertTrue(graph.neighborsOf(C).contains(d));
    }

    @Test
    @DisplayName("Load accumulates downward through explicit edges")
    void loadAccumulatesThroughExplicitEdges() {
        StructureGraph graph = chain();
        new StressSolver().solveAll(graph);

        double stressA = graph.getNode(A).stressValue();
        double stressB = graph.getNode(B).stressValue();
        double stressC = graph.getNode(C).stressValue();

        // Same behavior as a grid column: stress grows toward the base.
        assertTrue(stressC < stressB, "top carries least");
        assertTrue(stressB < stressA, "base carries most");

        // Bottom node carries the whole stack above it.
        double totalMass = graph.getNode(A).mass()
                + graph.getNode(B).mass()
                + graph.getNode(C).mass();
        assertEquals(totalMass, stressA, 0.001, "base carries total mass above");
        assertEquals(0.0, graph.getNode(GROUND).stressValue(), "ground absorbs everything");
    }

    @Test
    @DisplayName("Cascade collapses everything that loses its path to ground")
    void cascadeFollowsExplicitEdges() {
        StructureGraph graph = chain();

        // Knock out the bottom node: B and C lose all support.
        CascadeResult result = new CascadeEngine().cascade(graph, A);

        assertEquals(2, result.totalCollapsed(), "B and C should fall");
        assertTrue(result.collapsed().contains(B));
        assertTrue(result.collapsed().contains(C));
        assertFalse(graph.hasBlock(B));
        assertFalse(graph.hasBlock(C));
    }

    @Test
    @DisplayName("copy() preserves explicit edges (not re-derived from grid)")
    void copyPreservesExplicitEdges() {
        StructureGraph original = chain();
        StructureGraph copy = original.copy();

        // The grid helper would never link nodes 2 apart; if copy() re-derived
        // adjacency from positions these would be empty. They must be preserved.
        assertTrue(copy.getNeighbors(A).contains(GROUND));
        assertTrue(copy.getNeighbors(A).contains(B));
        assertTrue(copy.getNeighbors(B).contains(C));

        // And the physics on the copy matches the original.
        StressSolver solver = new StressSolver();
        solver.solveAll(original);
        solver.solveAll(copy);
        assertEquals(original.getNode(A).stressValue(), copy.getNode(A).stressValue(), 0.001);
    }

    @Test
    @DisplayName("connect() rejects nodes that don't exist")
    void connectRejectsMissingNodes() {
        StructureGraph graph = new StructureGraph();
        graph.addGroundNode(GROUND);
        assertThrows(IllegalArgumentException.class, () -> graph.connect(GROUND, A));
    }
}
