package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.StressSolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Test: Horizontal span supported at both ends (simple beam).
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                        SIMPLE SPAN TEST                            │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Structure:                                                        │
 *   │                                                                     │
 *   │     [G1]──[A]──[B]──[C]──[D]──[G2]                                 │
 *   │       │                         │                                  │
 *   │       └── support       support ┘                                  │
 *   │                                                                     │
 *   │                                                                     │
 *   │  How load flows (toward nearest ground):                           │
 *   │                                                                     │
 *   │     [G1]←─[A]←─[B]    [C]─→[D]─→[G2]                               │
 *   │       ▲         │    │         ▲                                   │
 *   │       │         └────┘         │                                   │
 *   │       │     (B→A, C→D)         │                                   │
 *   │       └── load absorbed ───────┘                                   │
 *   │                                                                     │
 *   │                                                                     │
 *   │  What we expect (axial load-flow model):                           │
 *   │                                                                     │
 *   │     • End blocks (A, D) collect load from middle blocks            │
 *   │     • So A and D have HIGHER stress than B and C                   │
 *   │     • Structure is symmetric: stress[A] ≈ stress[D]                │
 *   │                                                                     │
 *   │                                                                     │
 *   │  NOTE: A bending model would show max stress at midspan.           │
 *   │        Our compression-only model shows max at supports.           │
 *   │        This is physically correct for axial load transfer.         │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
@DisplayName("SimpleSpan: Beam supported at both ends")
class SimpleSpanTest {

    private StructureGraph graph;
    private StressSolver solver;

    // Block positions
    private NodePos groundLeft;
    private NodePos blockA;
    private NodePos blockB;
    private NodePos blockC;
    private NodePos blockD;
    private NodePos groundRight;

    @BeforeEach
    void setUp() {
        graph = new StructureGraph();
        solver = new StressSolver();

        // Build the span: [G1]──[A]──[B]──[C]──[D]──[G2]
        groundLeft = new NodePos(0, 0, 0);
        blockA = new NodePos(1, 0, 0);
        blockB = new NodePos(2, 0, 0);
        blockC = new NodePos(3, 0, 0);
        blockD = new NodePos(4, 0, 0);
        groundRight = new NodePos(5, 0, 0);

        graph.addGroundBlock(groundLeft);
        graph.addBlock(blockA, TestMaterials.MEDIUM, false);
        graph.addBlock(blockB, TestMaterials.MEDIUM, false);
        graph.addBlock(blockC, TestMaterials.MEDIUM, false);
        graph.addBlock(blockD, TestMaterials.MEDIUM, false);
        graph.addGroundBlock(groundRight);
    }

    @Test
    @DisplayName("All blocks should be connected in a line")
    void allBlocksConnected() {
        assertEquals(6, graph.size());
        assertTrue(graph.getNeighbors(blockA).contains(groundLeft));
        assertTrue(graph.getNeighbors(blockA).contains(blockB));
        assertTrue(graph.getNeighbors(blockD).contains(groundRight));
    }

    @Test
    @DisplayName("Structure should be symmetric: stress[A] ≈ stress[D], stress[B] ≈ stress[C]")
    void stressIsSymmetric() {
        solver.solveAll(graph);

        double stressA = graph.getNode(blockA).stressValue();
        double stressB = graph.getNode(blockB).stressValue();
        double stressC = graph.getNode(blockC).stressValue();
        double stressD = graph.getNode(blockD).stressValue();

        // Should be symmetric (within 5% tolerance)
        assertEquals(stressA, stressD, stressA * 0.05, "A and D should have equal stress (symmetric)");
        assertEquals(stressB, stressC, stressB * 0.05, "B and C should have equal stress (symmetric)");
    }

    @Test
    @DisplayName("End blocks (near supports) should have higher stress than middle blocks")
    void endBlocksCollectLoad() {
        solver.solveAll(graph);

        double stressA = graph.getNode(blockA).stressValue();
        double stressB = graph.getNode(blockB).stressValue();
        double stressC = graph.getNode(blockC).stressValue();
        double stressD = graph.getNode(blockD).stressValue();

        // In axial load-flow: end blocks (A, D) collect load from middle blocks (B, C)
        // So end blocks have MORE stress, not less
        assertTrue(stressA >= stressB, "End block A collects load from B, so A >= B");
        assertTrue(stressD >= stressC, "End block D collects load from C, so D >= C");
    }

    @Test
    @DisplayName("Both ground blocks should have zero stress")
    void groundHasZeroStress() {
        solver.solveAll(graph);

        assertEquals(0.0, graph.getNode(groundLeft).stressValue(), "Left ground should have zero stress");
        assertEquals(0.0, graph.getNode(groundRight).stressValue(), "Right ground should have zero stress");
    }
}
