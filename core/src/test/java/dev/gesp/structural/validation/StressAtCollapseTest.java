package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.api.CollapseReason;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link CollapsedNode#stressAtCollapse()} records how loaded a node was when it failed —
 * but only for an overloaded collapse. A floating collapse failed for lack of support, not
 * load, so it carries 0.0. Adapters use this to tell a "barely held" near miss from a block
 * that was hopelessly overloaded.
 */
@DisplayName("CollapsedNode.stressAtCollapse: load captured only for overloaded failures")
class StressAtCollapseTest {

    private StructureGraph graph;
    private CascadeEngine engine;
    private ReasonCapturingCallback callback;

    private static final MaterialSpec WEAK = new MaterialSpec(5.0, 8.0);
    private static final MaterialSpec NORMAL = new MaterialSpec(2.0, 50.0);

    @BeforeEach
    void setUp() {
        graph = new StructureGraph();
        engine = new CascadeEngine();
        callback = new ReasonCapturingCallback();
    }

    @Test
    @DisplayName("An overloaded collapse records stressAtCollapse above 1.0")
    void overloadedCollapseRecordsStress() {
        // Same overload rig as CascadeTest: removing support1 forces the right-hand
        // path to carry all of the heavy block's weight, overloading a weak support.
        NodePos ground = new NodePos(0, 0, 0);
        NodePos ground2 = new NodePos(1, 0, 0);
        NodePos support1 = new NodePos(0, 1, 0);
        NodePos pillar = new NodePos(1, 1, 0);
        NodePos heavy = new NodePos(0, 2, 0);
        NodePos support2 = new NodePos(1, 2, 0);

        MaterialSpec veryHeavy = new MaterialSpec(10.0, 50.0);

        graph.addGroundBlock(ground);
        graph.addGroundBlock(ground2);
        graph.addBlock(support1, WEAK, false);
        graph.addBlock(pillar, WEAK, false);
        graph.addBlock(heavy, veryHeavy, false);
        graph.addBlock(support2, WEAK, false);

        engine.cascade(graph, support1, callback);

        List<CollapsedNode> overloaded = callback.byReason.get(CollapseReason.OVERLOADED);
        assertFalse(overloaded.isEmpty(), "An overloaded block should have collapsed");

        // Every overloaded collapse was past capacity by definition — the field
        // captures exactly that, so each one reads strictly above 1.0.
        for (CollapsedNode node : overloaded) {
            assertTrue(
                    node.stressAtCollapse() > 1.0,
                    "overloaded node should record stress above capacity, was " + node.stressAtCollapse());
        }
    }

    @Test
    @DisplayName("A floating collapse records stressAtCollapse of exactly 0.0")
    void floatingCollapseRecordsZeroStress() {
        // GND - A - B - C - D column. Removing B leaves C and D with no path to
        // ground: they fall as floaters, not from load.
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

        engine.cascade(graph, blockB, callback);

        List<CollapsedNode> floating = callback.byReason.get(CollapseReason.FLOATING);
        assertEquals(2, floating.size(), "C and D should fall as floaters");
        assertTrue(
                callback.byReason.get(CollapseReason.OVERLOADED).isEmpty(),
                "nothing was overloaded in a pure floating collapse");

        for (CollapsedNode node : floating) {
            assertEquals(
                    0.0,
                    node.stressAtCollapse(),
                    "floating collapse fell for lack of support, so it records no stress");
        }
    }

    private static final class ReasonCapturingCallback implements SolverCallback {
        final Map<CollapseReason, List<CollapsedNode>> byReason = new EnumMap<>(CollapseReason.class);

        ReasonCapturingCallback() {
            for (CollapseReason reason : CollapseReason.values()) {
                byReason.put(reason, new ArrayList<>());
            }
        }

        @Override
        public void onStressUpdated(Map<NodePos, Double> stressMap) {}

        @Override
        public void onCascadeStep(CollapsedNode collapsed, int stepNumber, CollapseReason reason) {
            byReason.get(reason).add(collapsed);
        }

        @Override
        public void onCascadeComplete(List<CollapsedNode> allCollapsed) {}
    }
}
