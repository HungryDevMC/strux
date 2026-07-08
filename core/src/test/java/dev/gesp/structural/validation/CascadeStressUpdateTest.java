package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.api.CollapseReason;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The opt-in stress-update stream: a callback that asks for stress maps (by
 * overriding {@code wantsStressUpdates}) receives one snapshot per settle pass,
 * so FX/visualizers can animate stress between collapses. The default callback
 * pays nothing for this — these tests pin both halves of that contract.
 */
@DisplayName("Cascade: opt-in per-pass stress updates")
class CascadeStressUpdateTest {

    /** A weak arm that needs several overload passes to fully trim — so settle loops. */
    private static StructureGraph overloadedArm() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), new MaterialSpec(5.0, 50.0), false);
        for (int x = 1; x <= 5; x++) {
            g.addBlock(new NodePos(x, 1, 0), new MaterialSpec(5.0, 5.0), false);
        }
        return g;
    }

    @Test
    @DisplayName("A callback that wants stress updates receives a non-empty stress map")
    void stressUpdatesDelivered() {
        List<Map<NodePos, Double>> maps = new ArrayList<>();
        SolverCallback cb = new SolverCallback() {
            @Override
            public boolean wantsStressUpdates() {
                return true;
            }

            @Override
            public void onStressUpdated(Map<NodePos, Double> stressMap) {
                maps.add(stressMap);
            }

            @Override
            public void onCascadeStep(CollapsedNode collapsed, int stepNumber, CollapseReason reason) {}

            @Override
            public void onCascadeComplete(List<CollapsedNode> allCollapsed) {}
        };

        new CascadeEngine(new PhysicsConfig()).settle(overloadedArm(), cb);

        assertFalse(maps.isEmpty(), "an interested callback gets at least one stress snapshot");
        Map<NodePos, Double> first = maps.get(0);
        assertFalse(first.isEmpty(), "the stress map covers the live blocks");
        // Every standing block reports a finite, non-negative stress fraction.
        for (double v : first.values()) {
            assertTrue(v >= 0.0 && Double.isFinite(v), "stress fractions are real and non-negative");
        }
    }

    @Test
    @DisplayName("The stress map is scoped to the affected region, not the whole world")
    void stressMapIsScopedToAffectedRegion() {
        // A long flat-terrain strip with a small two-tall structure at one end.
        // Breaking under the structure disturbs only its corner; the stress map
        // must NOT re-report the far terrain blocks (they never re-solved).
        StructureGraph g = new StructureGraph();
        for (int x = 0; x <= 40; x++) {
            g.addGroundBlock(new NodePos(x, 0, 0));
            g.addBlock(new NodePos(x, 1, 0), new MaterialSpec(5.0, 50.0), false);
        }
        g.addBlock(new NodePos(0, 2, 0), new MaterialSpec(5.0, 50.0), false);

        List<Map<NodePos, Double>> maps = new ArrayList<>();
        SolverCallback cb = new SolverCallback() {
            @Override
            public boolean wantsStressUpdates() {
                return true;
            }

            @Override
            public void onStressUpdated(Map<NodePos, Double> stressMap) {
                maps.add(stressMap);
            }

            @Override
            public void onCascadeStep(CollapsedNode collapsed, int stepNumber, CollapseReason reason) {}

            @Override
            public void onCascadeComplete(List<CollapsedNode> allCollapsed) {}
        };

        // Cascade from one end. The scope is the disturbed corner's closure.
        new CascadeEngine(new PhysicsConfig()).cascade(g, new NodePos(0, 1, 0), cb);

        assertFalse(maps.isEmpty(), "an interested callback gets at least one stress snapshot");
        for (Map<NodePos, Double> map : maps) {
            // The far end of the strip is untouched terrain — it must never appear
            // in a scoped stress snapshot (it would on a whole-graph build).
            assertFalse(
                    map.containsKey(new NodePos(40, 1, 0)), "scoped stress map must not include untouched far terrain");
            // And the map stays far smaller than the whole graph (~83 blocks).
            assertTrue(map.size() < 40, "scoped stress map is bounded by the disturbance, not the world");
        }
    }

    @Test
    @DisplayName("The default (uninterested) callback is never handed a stress map")
    void noUpdatesForUninterestedCallback() {
        boolean[] gotMap = {false};
        SolverCallback cb = new SolverCallback() {
            @Override
            public void onStressUpdated(Map<NodePos, Double> stressMap) {
                gotMap[0] = true;
            }

            @Override
            public void onCascadeStep(CollapsedNode collapsed, int stepNumber, CollapseReason reason) {}

            @Override
            public void onCascadeComplete(List<CollapsedNode> allCollapsed) {}
            // wantsStressUpdates() left at its false default
        };

        new CascadeEngine(new PhysicsConfig()).settle(overloadedArm(), cb);

        assertFalse(gotMap[0], "the engine must not build/deliver stress maps no one asked for");
    }
}
