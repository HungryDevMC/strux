package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.NodePos;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Test: Detecting blocks that are not connected to ground (floating).
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                      FLOATING BLOCKS TEST                          │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Structure:                                                        │
 *   │                                                                     │
 *   │        [X]──[Y]        [C]                                         │
 *   │                         │                                          │
 *   │                    [A]──[B]                                        │
 *   │                         │                                          │
 *   │                       [GND]                                        │
 *   │                                                                     │
 *   │                                                                     │
 *   │  What we expect:                                                   │
 *   │                                                                     │
 *   │     • Connected to ground: {GND, A, B, C}                          │
 *   │     • Floating (not connected): {X, Y}                             │
 *   │                                                                     │
 *   │                                                                     │
 *   │  Why does this matter?                                             │
 *   │                                                                     │
 *   │     Floating blocks should collapse IMMEDIATELY when detected.     │
 *   │     No solver needed - they have no support at all!                │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
@DisplayName("FloatingBlocks: Detect disconnected blocks")
class FloatingBlocksTest {

    private StructureGraph graph;

    // Connected structure
    private NodePos ground;
    private NodePos blockA;
    private NodePos blockB;
    private NodePos blockC;

    // Floating structure (not connected to ground)
    private NodePos blockX;
    private NodePos blockY;

    @BeforeEach
    void setUp() {
        graph = new StructureGraph();

        // Build connected structure:
        //     [C]
        //      │
        // [A]──[B]
        //      │
        //    [GND]
        ground = new NodePos(0, 0, 0);
        blockA = new NodePos(-1, 1, 0);
        blockB = new NodePos(0, 1, 0);
        blockC = new NodePos(0, 2, 0);

        graph.addGroundBlock(ground);
        graph.addBlock(blockA, TestMaterials.MEDIUM, false);
        graph.addBlock(blockB, TestMaterials.MEDIUM, false);
        graph.addBlock(blockC, TestMaterials.MEDIUM, false);

        // Build floating structure (far away, not connected):
        // [X]──[Y]
        blockX = new NodePos(10, 5, 0);
        blockY = new NodePos(11, 5, 0);

        graph.addBlock(blockX, TestMaterials.MEDIUM, false);
        graph.addBlock(blockY, TestMaterials.MEDIUM, false);
    }

    @Test
    @DisplayName("Should identify all blocks connected to ground")
    void identifyConnectedBlocks() {
        Set<NodePos> connected = graph.getBlocksConnectedToGround();

        assertTrue(connected.contains(ground), "Ground should be connected");
        assertTrue(connected.contains(blockA), "A should be connected to ground");
        assertTrue(connected.contains(blockB), "B should be connected to ground");
        assertTrue(connected.contains(blockC), "C should be connected to ground");

        assertFalse(connected.contains(blockX), "X should NOT be connected to ground");
        assertFalse(connected.contains(blockY), "Y should NOT be connected to ground");
    }

    @Test
    @DisplayName("Should identify floating blocks")
    void identifyFloatingBlocks() {
        Set<NodePos> floating = graph.getFloatingBlocks();

        assertEquals(2, floating.size(), "Should have exactly 2 floating blocks");
        assertTrue(floating.contains(blockX), "X should be floating");
        assertTrue(floating.contains(blockY), "Y should be floating");
    }

    @Test
    @DisplayName("Removing a bridge block should disconnect blocks above")
    void removingBridgeDisconnectsBlocks() {
        // Before: C is connected via B
        assertTrue(graph.getBlocksConnectedToGround().contains(blockC));

        // Remove B (the bridge)
        graph.removeBlock(blockB);

        // After: C and A are now floating (not connected to ground)
        Set<NodePos> floating = graph.getFloatingBlocks();
        assertTrue(floating.contains(blockC), "C should be floating after removing B");
        assertTrue(floating.contains(blockA), "A should be floating after removing B");
    }

    @Test
    @DisplayName("Empty graph should have no floating blocks")
    void emptyGraphHasNoFloatingBlocks() {
        StructureGraph emptyGraph = new StructureGraph();
        assertTrue(emptyGraph.getFloatingBlocks().isEmpty());
    }

    @Test
    @DisplayName("Graph with only ground should have no floating blocks")
    void onlyGroundHasNoFloatingBlocks() {
        StructureGraph groundOnly = new StructureGraph();
        groundOnly.addGroundBlock(new NodePos(0, 0, 0));

        assertTrue(groundOnly.getFloatingBlocks().isEmpty());
        assertEquals(1, groundOnly.getBlocksConnectedToGround().size());
    }
}
