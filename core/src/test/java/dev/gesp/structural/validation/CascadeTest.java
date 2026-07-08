package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.api.CollapseReason;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import dev.gesp.structural.solver.CascadeResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Test: Cascade collapse when blocks are removed.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                         CASCADE TEST                               │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Scenario 1: FLOATING COLLAPSE                                     │
 *   │                                                                     │
 *   │       [D]          Remove [B]:                                     │
 *   │        │           → C, D are now floating                         │
 *   │       [C]          → They collapse immediately                     │
 *   │        │                                                           │
 *   │       [B] ← remove                                                 │
 *   │        │                                                           │
 *   │       [A]          A stays (connected to ground)                   │
 *   │        │                                                           │
 *   │      [GND]                                                         │
 *   │                                                                     │
 *   │                                                                     │
 *   │  Scenario 2: STRESS COLLAPSE                                       │
 *   │                                                                     │
 *   │      [HEAVY]        Remove one support:                            │
 *   │       /   \         → Remaining support is overloaded              │
 *   │   [sup1] [sup2]     → It collapses                                 │
 *   │      \   /          → Heavy block falls                            │
 *   │      [GND]                                                         │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
@DisplayName("Cascade: Collapse chain reactions")
class CascadeTest {

    private StructureGraph graph;
    private CascadeEngine engine;
    private TestCallback callback;

    // Weak material that breaks easily (mass=5, maxLoad=8 → can only hold ~1.6 blocks above)
    private static final MaterialSpec WEAK = new MaterialSpec(5.0, 8.0);

    // Normal material
    private static final MaterialSpec NORMAL = new MaterialSpec(2.0, 50.0);

    @BeforeEach
    void setUp() {
        graph = new StructureGraph();
        engine = new CascadeEngine();
        callback = new TestCallback();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  FLOATING COLLAPSE TESTS
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Removing a block should collapse floating blocks above it")
    void floatingBlocksCollapse() {
        // Build: GND - A - B - C - D (vertical column)
        NodePos ground = new NodePos(0, 0, 0);
        NodePos blockA = new NodePos(0, 1, 0);
        NodePos blockB = new NodePos(0, 2, 0);
        NodePos blockC = new NodePos(0, 3, 0);
        NodePos blockD = new NodePos(0, 4, 0);

        graph.addGroundBlock(ground);
        graph.addBlock(blockA, NORMAL, false);
        graph.addBlock(blockB, NORMAL, false);
        graph.addBlock(blockC, NORMAL, false);
        graph.addBlock(blockD, NORMAL, false);

        // Remove B - should cause C and D to float and collapse
        CascadeResult result = engine.cascade(graph, blockB, callback);

        assertTrue(result.hadCascade(), "Should have cascade");
        assertEquals(2, result.totalCollapsed(), "C and D should collapse");
        assertTrue(result.collapsed().contains(blockC), "C should collapse");
        assertTrue(result.collapsed().contains(blockD), "D should collapse");

        // A should still exist
        assertTrue(graph.hasBlock(blockA), "A should still exist");
        assertTrue(graph.hasBlock(ground), "Ground should still exist");

        // B, C, D should be gone
        assertFalse(graph.hasBlock(blockB), "B should be removed");
        assertFalse(graph.hasBlock(blockC), "C should be collapsed");
        assertFalse(graph.hasBlock(blockD), "D should be collapsed");
    }

    @Test
    @DisplayName("Removing ground block should collapse everything above")
    void removingGroundCollapsesAll() {
        // Build: GND - A - B
        NodePos ground = new NodePos(0, 0, 0);
        NodePos blockA = new NodePos(0, 1, 0);
        NodePos blockB = new NodePos(0, 2, 0);

        graph.addGroundBlock(ground);
        graph.addBlock(blockA, NORMAL, false);
        graph.addBlock(blockB, NORMAL, false);

        // Remove ground - everything floats
        CascadeResult result = engine.cascade(graph, ground, callback);

        assertEquals(2, result.totalCollapsed(), "A and B should collapse");
        assertTrue(graph.isEmpty(), "Graph should be empty");
    }

    @Test
    @DisplayName("Removing a block with no dependents should not cause cascade")
    void noDependentsNoCascade() {
        // Build: GND - A - B
        NodePos ground = new NodePos(0, 0, 0);
        NodePos blockA = new NodePos(0, 1, 0);
        NodePos blockB = new NodePos(0, 2, 0);

        graph.addGroundBlock(ground);
        graph.addBlock(blockA, NORMAL, false);
        graph.addBlock(blockB, NORMAL, false);

        // Remove B (top block) - nothing depends on it
        CascadeResult result = engine.cascade(graph, blockB, callback);

        assertFalse(result.hadCascade(), "Should not have cascade");
        assertEquals(0, result.totalCollapsed());

        // A and ground should still exist
        assertTrue(graph.hasBlock(blockA));
        assertTrue(graph.hasBlock(ground));
    }

    // ─────────────────────────────────────────────────────────────────────
    //  STRESS COLLAPSE TESTS
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Overloaded block should collapse")
    void overloadedBlockCollapses() {
        // Build a structure where removing one support overloads the other.
        // Uses face-adjacent positions so blocks actually connect:
        //
        //   heavy(0,2,0) --- support2(1,2,0)    ← heavy supported by both
        //        |               |
        //   support1(0,1,0)  pillar(1,1,0)      ← weak supports (maxLoad=8)
        //        |               |
        //   ground(0,0,0) --- ground2(1,0,0)

        NodePos ground = new NodePos(0, 0, 0);
        NodePos ground2 = new NodePos(1, 0, 0);
        NodePos support1 = new NodePos(0, 1, 0);
        NodePos pillar = new NodePos(1, 1, 0);
        NodePos heavy = new NodePos(0, 2, 0);
        NodePos support2 = new NodePos(1, 2, 0);

        MaterialSpec veryHeavy = new MaterialSpec(10.0, 50.0);

        graph.addGroundBlock(ground);
        graph.addGroundBlock(ground2);
        graph.addBlock(support1, WEAK, false); // maxLoad=8
        graph.addBlock(pillar, WEAK, false); // maxLoad=8, supports support2
        graph.addBlock(heavy, veryHeavy, false); // mass=10
        graph.addBlock(support2, WEAK, false); // maxLoad=8

        // Initially: heavy's load splits between support1 (below) and support2 (right)
        // Each path carries ~5 stress, within capacity

        // Remove support1 - now support2 path must carry all of heavy's weight
        // support2 or pillar becomes overloaded → collapse
        CascadeResult result = engine.cascade(graph, support1, callback);

        // Something should collapse (overload propagates through the structure)
        assertTrue(result.hadCascade(), "Should have cascade");
        assertTrue(result.totalCollapsed() >= 1, "At least one block should collapse");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  CALLBACK TESTS
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Callback should receive cascade events in order")
    void callbackReceivesEvents() {
        // Build: GND - A - B - C
        NodePos ground = new NodePos(0, 0, 0);
        NodePos blockA = new NodePos(0, 1, 0);
        NodePos blockB = new NodePos(0, 2, 0);
        NodePos blockC = new NodePos(0, 3, 0);

        graph.addGroundBlock(ground);
        graph.addBlock(blockA, NORMAL, false);
        graph.addBlock(blockB, NORMAL, false);
        graph.addBlock(blockC, NORMAL, false);

        // Remove A - B and C should cascade
        engine.cascade(graph, blockA, callback);

        // Should have received cascade step events
        assertFalse(callback.cascadeSteps.isEmpty(), "Should receive cascade steps");

        // Should have received completion event
        assertNotNull(callback.completedCollapsed, "Should receive completion");
        assertEquals(2, callback.completedCollapsed.size(), "B and C should be in completion list");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  TEST CALLBACK IMPLEMENTATION
    // ─────────────────────────────────────────────────────────────────────

    private static class TestCallback implements SolverCallback {
        List<Map<NodePos, Double>> stressUpdates = new ArrayList<>();
        List<NodePos> cascadeSteps = new ArrayList<>();
        List<CollapsedNode> completedCollapsed = null;

        @Override
        public void onStressUpdated(Map<NodePos, Double> stressMap) {
            stressUpdates.add(stressMap);
        }

        @Override
        public void onCascadeStep(CollapsedNode collapsed, int stepNumber, CollapseReason reason) {
            cascadeSteps.add(collapsed.pos());
        }

        @Override
        public void onCascadeComplete(List<CollapsedNode> allCollapsed) {
            completedCollapsed = new ArrayList<>(allCollapsed);
        }
    }
}
