package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.StressSolver;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Test: Horizontal beam extending from a ground support (cantilever).
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                         CANTILEVER TEST                            │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Structure:                                                        │
 *   │                                                                     │
 *   │     [GND]──[A]──[B]──[C]──[D]──[E]                                 │
 *   │       │                                                            │
 *   │       └── fixed support                                            │
 *   │                                                                     │
 *   │                                                                     │
 *   │  What we expect:                                                   │
 *   │                                                                     │
 *   │     • Block A (nearest to support) has HIGHEST stress              │
 *   │     • Block E (free end) has LOWEST stress                         │
 *   │     • Stress decreases: A > B > C > D > E                          │
 *   │                                                                     │
 *   │                                                                     │
 *   │  Why? Block A must support the weight of B, C, D, and E.          │
 *   │       Block E only supports itself.                                │
 *   │                                                                     │
 *   │       stress[A] ═══════════════════════════▶ HIGH                  │
 *   │       stress[E] ═══▶ LOW                                           │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
@DisplayName("Cantilever: Horizontal beam from ground support")
class CantileverTest {

    private StructureGraph graph;
    private StressSolver solver;

    // Block positions
    private NodePos ground;
    private NodePos blockA;
    private NodePos blockB;
    private NodePos blockC;
    private NodePos blockD;
    private NodePos blockE;

    @BeforeEach
    void setUp() {
        graph = new StructureGraph();
        solver = new StressSolver();

        // Build the cantilever: [GND]──[A]──[B]──[C]──[D]──[E]
        ground = new NodePos(0, 0, 0);
        blockA = new NodePos(1, 0, 0);
        blockB = new NodePos(2, 0, 0);
        blockC = new NodePos(3, 0, 0);
        blockD = new NodePos(4, 0, 0);
        blockE = new NodePos(5, 0, 0);

        graph.addGroundBlock(ground);
        graph.addBlock(blockA, TestMaterials.MEDIUM, false);
        graph.addBlock(blockB, TestMaterials.MEDIUM, false);
        graph.addBlock(blockC, TestMaterials.MEDIUM, false);
        graph.addBlock(blockD, TestMaterials.MEDIUM, false);
        graph.addBlock(blockE, TestMaterials.MEDIUM, false);
    }

    @Test
    @DisplayName("All blocks should be connected")
    void allBlocksConnected() {
        assertEquals(6, graph.size());
        assertTrue(graph.getNeighbors(blockA).contains(ground));
        assertTrue(graph.getNeighbors(blockA).contains(blockB));
        assertTrue(graph.getNeighbors(blockE).contains(blockD));
    }

    @Test
    @DisplayName("Stress should decrease from anchor to free end: A > B > C > D > E")
    void stressDecreasesTowardFreeEnd() {
        solver.solveAll(graph);

        double stressA = graph.getNode(blockA).stressValue();
        double stressB = graph.getNode(blockB).stressValue();
        double stressC = graph.getNode(blockC).stressValue();
        double stressD = graph.getNode(blockD).stressValue();
        double stressE = graph.getNode(blockE).stressValue();

        // Stress should decrease monotonically from anchor to free end
        assertTrue(stressA > stressB, "A should have more stress than B");
        assertTrue(stressB > stressC, "B should have more stress than C");
        assertTrue(stressC > stressD, "C should have more stress than D");
        assertTrue(stressD > stressE, "D should have more stress than E");
    }

    @Test
    @DisplayName("Block E (free end) should have minimal stress (just its own weight)")
    void freeEndHasMinimalStress() {
        solver.solveAll(graph);

        double stressE = graph.getNode(blockE).stressValue();
        double massE = graph.getNode(blockE).mass();

        // E only carries itself, so stress ≈ its own mass
        assertEquals(massE, stressE, 0.1, "Free end should only carry its own weight");
    }

    @Test
    @DisplayName("Ground should have zero stress (it absorbs everything)")
    void groundHasZeroStress() {
        solver.solveAll(graph);

        double stressGround = graph.getNode(ground).stressValue();

        assertEquals(0.0, stressGround, "Ground should have zero stress");
    }

    /**
     * Test: Connecting two cantilevers by placing a block in the gap.
     *
     * <pre>
     *   SCENARIO: Two separate cantilevers with a gap between them.
     *   User places a block to bridge them.
     *
     *   Before:
     *     [GND1]──[A]──[B]──[C]  GAP  [D]──[E]──[F]──[GND2]
     *
     *   After placing bridging block:
     *     [GND1]──[A]──[B]──[C]──[BRIDGE]──[D]──[E]──[F]──[GND2]
     *
     *   Expected behavior:
     *   - The BRIDGE block should NOT collapse
     *   - The BRIDGE block is now in the middle of a beam (supported from both ends)
     *   - It should have minimal moment stress (beam detection should kick in)
     *   - Total stress should be well under capacity
     * </pre>
     */
    @Test
    @DisplayName("Connecting two cantilevers should create stable beam, not collapse")
    void connectingTwoCantileversCreatesStableBeam() {
        // Create fresh graph for this test
        StructureGraph testGraph = new StructureGraph();

        // Left cantilever: GND1 -> A -> B -> C
        NodePos gnd1 = new NodePos(0, 0, 0);
        NodePos a = new NodePos(1, 0, 0);
        NodePos b = new NodePos(2, 0, 0);
        NodePos c = new NodePos(3, 0, 0);

        // Right cantilever: D -> E -> F -> GND2
        NodePos d = new NodePos(5, 0, 0);
        NodePos e = new NodePos(6, 0, 0);
        NodePos f = new NodePos(7, 0, 0);
        NodePos gnd2 = new NodePos(8, 0, 0);

        // Build left cantilever
        testGraph.addGroundBlock(gnd1);
        testGraph.addBlock(a, TestMaterials.MEDIUM, false);
        testGraph.addBlock(b, TestMaterials.MEDIUM, false);
        testGraph.addBlock(c, TestMaterials.MEDIUM, false);

        // Build right cantilever
        testGraph.addGroundBlock(gnd2);
        testGraph.addBlock(f, TestMaterials.MEDIUM, false);
        testGraph.addBlock(e, TestMaterials.MEDIUM, false);
        testGraph.addBlock(d, TestMaterials.MEDIUM, false);

        // Verify both cantilevers are stable before connecting
        StressSolver testSolver = new StressSolver();
        testSolver.solveAll(testGraph);

        double stressCBefore = testGraph.getNode(c).stressPercent();
        double stressDBefore = testGraph.getNode(d).stressPercent();
        System.out.println(
                "Before bridging: C stress=" + (stressCBefore * 100) + "%, D stress=" + (stressDBefore * 100) + "%");
        assertTrue(stressCBefore < 1.0, "C should be stable before bridging");
        assertTrue(stressDBefore < 1.0, "D should be stable before bridging");

        // Now place the bridging block at position 4
        NodePos bridge = new NodePos(4, 0, 0);
        testGraph.addBlock(bridge, TestMaterials.MEDIUM, false);

        // Recalculate stress
        testSolver.solveAll(testGraph);

        // The bridge block should NOT be overloaded
        double bridgeStress = testGraph.getNode(bridge).stressPercent();
        double bridgeMoment = testGraph.getNode(bridge).momentStress();
        double bridgeVertical = testGraph.getNode(bridge).verticalStress();

        System.out.println("After bridging: BRIDGE stress=" + (bridgeStress * 100) + "%, vertical=" + bridgeVertical
                + ", moment=" + bridgeMoment);

        // The bridge should have near-zero moment stress (it's part of a beam now)
        assertTrue(bridgeMoment < 10, "Bridge should have low moment stress (beam detection). Got: " + bridgeMoment);

        // The bridge should NOT be overloaded
        assertTrue(bridgeStress < 1.0, "Bridge should be stable (not overloaded). Got: " + (bridgeStress * 100) + "%");

        // C and D should also remain stable (and actually get relief from being connected)
        double stressCAfter = testGraph.getNode(c).stressPercent();
        double stressDAfter = testGraph.getNode(d).stressPercent();
        System.out.println(
                "After bridging: C stress=" + (stressCAfter * 100) + "%, D stress=" + (stressDAfter * 100) + "%");

        assertTrue(stressCAfter < 1.0, "C should remain stable after bridging");
        assertTrue(stressDAfter < 1.0, "D should remain stable after bridging");
    }

    /**
     * Test: Bridge block with VERTICAL support connecting two horizontal arms.
     *
     * <pre>
     *   This mimics the actual Minecraft scenario where the bridge block
     *   has a pillar below it, giving it a shorter distance to ground than
     *   the horizontal arms extending from it.
     *
     *   Structure (side view):
     *
     *     GND1──A──B──C──BRIDGE──D──E──F──GND2   (y=2, horizontal beam)
     *                      │
     *                   PILLAR                   (y=1)
     *                      │
     *                    GND_V                   (y=0, vertical ground)
     *
     *   Expected distances:
     *   - BRIDGE: d=2 (via pillar)
     *   - C and D: d=3 (via bridge)
     *   - B and E: d=4
     *   - A and F: d=5
     *   - GND1 and GND2: d=6 and d=6
     *
     *   When checking arms from BRIDGE:
     *   - C and D have d=3 > 2 (bridge's d), so they ARE arms
     *   - But C's arm leads to GND1, and D's arm leads to GND2
     *   - Both arms should be detected as BEAMs (connected to ground)
     * </pre>
     */
    @Test
    @DisplayName("Bridge with vertical support should detect horizontal beams, not cantilevers")
    void bridgeWithVerticalSupportDetectsBeams() {
        StructureGraph testGraph = new StructureGraph();

        // Vertical support: GND_V -> PILLAR -> BRIDGE
        NodePos gndV = new NodePos(4, 0, 0); // Ground below bridge
        NodePos pillar = new NodePos(4, 1, 0);
        NodePos bridge = new NodePos(4, 2, 0);

        // Left arm: A -> B -> C -> (connects to bridge)
        NodePos gnd1 = new NodePos(0, 2, 0); // Ground on left
        NodePos a = new NodePos(1, 2, 0);
        NodePos b = new NodePos(2, 2, 0);
        NodePos c = new NodePos(3, 2, 0);

        // Right arm: (connects to bridge) -> D -> E -> F -> GND2
        NodePos d = new NodePos(5, 2, 0);
        NodePos e = new NodePos(6, 2, 0);
        NodePos f = new NodePos(7, 2, 0);
        NodePos gnd2 = new NodePos(8, 2, 0); // Ground on right

        // Build vertical support first
        testGraph.addGroundBlock(gndV);
        testGraph.addBlock(pillar, TestMaterials.MEDIUM, false);
        testGraph.addBlock(bridge, TestMaterials.MEDIUM, false);

        // Build left arm (from ground toward bridge)
        testGraph.addGroundBlock(gnd1);
        testGraph.addBlock(a, TestMaterials.MEDIUM, false);
        testGraph.addBlock(b, TestMaterials.MEDIUM, false);
        testGraph.addBlock(c, TestMaterials.MEDIUM, false);

        // Build right arm (from bridge toward ground)
        testGraph.addBlock(d, TestMaterials.MEDIUM, false);
        testGraph.addBlock(e, TestMaterials.MEDIUM, false);
        testGraph.addBlock(f, TestMaterials.MEDIUM, false);
        testGraph.addGroundBlock(gnd2);

        // Calculate stress
        StressSolver testSolver = new StressSolver();
        testSolver.solveAll(testGraph);

        // Debug output
        System.out.println("Bridge with vertical support test:");
        System.out.println("  Bridge: d=" + distanceFromGraph(testGraph, bridge) + ", moment="
                + testGraph.getNode(bridge).momentStress() + ", stress="
                + (testGraph.getNode(bridge).stressPercent() * 100) + "%");
        System.out.println("  C: d=" + distanceFromGraph(testGraph, c) + ", moment="
                + testGraph.getNode(c).momentStress());
        System.out.println("  D: d=" + distanceFromGraph(testGraph, d) + ", moment="
                + testGraph.getNode(d).momentStress());

        // The bridge should NOT be overloaded
        double bridgeStress = testGraph.getNode(bridge).stressPercent();
        double bridgeMoment = testGraph.getNode(bridge).momentStress();

        // Key assertion: Bridge should have LOW moment stress
        // because both arms connect to ground (beam detection)
        assertTrue(
                bridgeMoment < 10,
                "Bridge should have low moment stress (beams, not cantilevers). Got: " + bridgeMoment);

        assertTrue(bridgeStress < 1.0, "Bridge should be stable. Got: " + (bridgeStress * 100) + "%");
    }

    // Helper to calculate distance (simplified - just for debug)
    private int distanceFromGraph(StructureGraph graph, NodePos pos) {
        // BFS from ground to find distance
        Map<NodePos, Integer> distances = new HashMap<>();
        Queue<NodePos> queue = new LinkedList<>();

        for (NodePos p : graph.getAllPositions()) {
            if (graph.getNode(p).isGrounded()) {
                distances.put(p, 0);
                queue.add(p);
            }
        }

        while (!queue.isEmpty()) {
            NodePos current = queue.poll();
            int currentDist = distances.get(current);
            for (NodePos neighbor : graph.getNeighbors(current)) {
                if (!distances.containsKey(neighbor)) {
                    distances.put(neighbor, currentDist + 1);
                    queue.add(neighbor);
                }
            }
        }

        return distances.getOrDefault(pos, -1);
    }
}
