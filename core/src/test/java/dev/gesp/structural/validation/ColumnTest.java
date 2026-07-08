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
 * Test: Vertical stack of blocks on ground (column).
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                           COLUMN TEST                              │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Structure:                                                        │
 *   │                                                                     │
 *   │            [D]  ← top                                              │
 *   │             │                                                      │
 *   │            [C]                                                     │
 *   │             │                                                      │
 *   │            [B]                                                     │
 *   │             │                                                      │
 *   │            [A]  ← bottom (not ground, but on ground)               │
 *   │             │                                                      │
 *   │           [GND]                                                    │
 *   │                                                                     │
 *   │                                                                     │
 *   │  What we expect:                                                   │
 *   │                                                                     │
 *   │     • Block A (bottom) has HIGHEST stress                          │
 *   │     • Block D (top) has LOWEST stress                              │
 *   │     • Stress increases toward base: D < C < B < A                  │
 *   │                                                                     │
 *   │                                                                     │
 *   │  Why? Each block carries all blocks above it.                      │
 *   │                                                                     │
 *   │       D carries: just itself           = 1 × mass                  │
 *   │       C carries: D + itself            = 2 × mass                  │
 *   │       B carries: D + C + itself        = 3 × mass                  │
 *   │       A carries: D + C + B + itself    = 4 × mass                  │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
@DisplayName("Column: Vertical stack on ground")
class ColumnTest {

    private StructureGraph graph;
    private StressSolver solver;

    // Block positions (bottom to top)
    private NodePos ground;
    private NodePos blockA; // y=1
    private NodePos blockB; // y=2
    private NodePos blockC; // y=3
    private NodePos blockD; // y=4

    @BeforeEach
    void setUp() {
        graph = new StructureGraph();
        solver = new StressSolver();

        // Build the column (vertical stack)
        ground = new NodePos(0, 0, 0);
        blockA = new NodePos(0, 1, 0);
        blockB = new NodePos(0, 2, 0);
        blockC = new NodePos(0, 3, 0);
        blockD = new NodePos(0, 4, 0);

        graph.addGroundBlock(ground);
        graph.addBlock(blockA, TestMaterials.MEDIUM, false);
        graph.addBlock(blockB, TestMaterials.MEDIUM, false);
        graph.addBlock(blockC, TestMaterials.MEDIUM, false);
        graph.addBlock(blockD, TestMaterials.MEDIUM, false);
    }

    @Test
    @DisplayName("All blocks should be connected vertically")
    void allBlocksConnected() {
        assertEquals(5, graph.size());
        assertTrue(graph.getNeighbors(blockA).contains(ground));
        assertTrue(graph.getNeighbors(blockA).contains(blockB));
        assertTrue(graph.getNeighbors(blockD).contains(blockC));
    }

    @Test
    @DisplayName("Stress should increase toward base: D < C < B < A")
    void stressIncreasesTowardBase() {
        solver.solveAll(graph);

        double stressA = graph.getNode(blockA).stressValue();
        double stressB = graph.getNode(blockB).stressValue();
        double stressC = graph.getNode(blockC).stressValue();
        double stressD = graph.getNode(blockD).stressValue();

        // Stress should increase from top to bottom
        assertTrue(stressD < stressC, "D (top) should have less stress than C");
        assertTrue(stressC < stressB, "C should have less stress than B");
        assertTrue(stressB < stressA, "B should have less stress than A (bottom)");
    }

    @Test
    @DisplayName("Top block should carry just its own weight")
    void topBlockCarriesOnlyItself() {
        solver.solveAll(graph);

        double stressD = graph.getNode(blockD).stressValue();
        double massD = graph.getNode(blockD).mass();

        // Top block only carries itself
        assertEquals(massD, stressD, 0.1, "Top block should only carry its own weight");
    }

    @Test
    @DisplayName("Bottom block should carry all blocks above it")
    void bottomBlockCarriesAll() {
        solver.solveAll(graph);

        double stressA = graph.getNode(blockA).stressValue();
        double totalMass = graph.getNode(blockA).mass()
                + graph.getNode(blockB).mass()
                + graph.getNode(blockC).mass()
                + graph.getNode(blockD).mass();

        // Bottom block carries all 4 blocks
        assertEquals(totalMass, stressA, 0.1, "Bottom block should carry total weight above");
    }

    @Test
    @DisplayName("Ground should have zero stress")
    void groundHasZeroStress() {
        solver.solveAll(graph);

        assertEquals(0.0, graph.getNode(ground).stressValue(), "Ground should have zero stress");
    }
}
