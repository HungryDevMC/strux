package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Debris impact loading: a collapsing block drops its mass onto the first
 * standing block below as damage, so collapses can pancake downward. Off by
 * default (engine), opt-in via config.
 *
 * <pre>
 *   [F] heavy, floating          [F] collapses, debris falls 4 blocks...
 *    ┊                            ↓  ...onto [T], overloading it
 *   [T] light, grounded   ==>    [T] also collapses (only with debris on)
 *    │                            ·
 *  [GND]                        [GND]
 * </pre>
 */
@DisplayName("Debris impact: collapses pancake onto what's below")
class DebrisImpactTest {

    private static final NodePos GROUND = new NodePos(0, 0, 0);
    private static final NodePos T = new NodePos(0, 1, 0); // light, grounded target
    private static final NodePos F = new NodePos(0, 5, 0); // heavy, floating faller

    /** Target survives its own weight; faller is heavy and unsupported (floats). */
    private StructureGraph scene() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(GROUND);
        g.addBlock(T, new MaterialSpec(2.0, 50.0), false); // carries ~2, cap 50 → fine alone
        g.addBlock(F, new MaterialSpec(30.0, 1000.0), false); // no neighbours → floating
        return g;
    }

    private static PhysicsConfig config(double debrisScale, int minDrop) {
        PhysicsConfig c = new PhysicsConfig();
        c.setDebrisImpactScale(debrisScale);
        c.setMinImpactDrop(minDrop);
        return c;
    }

    @Test
    @DisplayName("With debris OFF, the faller vanishes and the block below survives")
    void debrisOffLeavesTargetStanding() {
        StructureGraph g = scene();
        List<CollapsedNode> collapsed = new CascadeEngine(config(0.0, 2)).settle(g, SolverCallback.NONE);
        List<NodePos> positions = collapsed.stream().map(CollapsedNode::pos).toList();

        assertTrue(positions.contains(F));
        assertFalse(positions.contains(T));
        assertTrue(g.hasBlock(T), "no debris loading → target keeps standing");
    }

    @Test
    @DisplayName("With debris ON, the falling mass pancakes the block below")
    void debrisOnPancakesTarget() {
        StructureGraph g = scene();
        // impact on T = 30 mass × 4 drop × 0.5 / 50 cap = 1.2 → destroyed
        List<CollapsedNode> collapsed = new CascadeEngine(config(0.5, 2)).settle(g, SolverCallback.NONE);
        List<NodePos> positions = collapsed.stream().map(CollapsedNode::pos).toList();

        assertTrue(positions.contains(F));
        assertTrue(positions.contains(T), "debris overloads the target, which also collapses");
        assertFalse(g.hasBlock(T));
        assertEquals(1, g.size(), "only the ground node remains");
    }

    @Test
    @DisplayName("minImpactDrop gates short falls (nothing chains)")
    void minImpactDropGatesShortFalls() {
        StructureGraph g = scene();
        // Same heavy faller, but require a 10-block fall — our 4-block drop won't count.
        List<CollapsedNode> collapsed = new CascadeEngine(config(0.5, 10)).settle(g, SolverCallback.NONE);
        List<NodePos> positions = collapsed.stream().map(CollapsedNode::pos).toList();

        assertTrue(positions.contains(F));
        assertFalse(positions.contains(T));
        assertTrue(g.hasBlock(T));
    }
}
