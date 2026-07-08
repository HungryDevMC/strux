package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * When several blocks fail at once, the cascade must process them in a fixed,
 * run-stable order — not in whatever order a {@code HashSet}/{@code HashMap}
 * happens to iterate. Otherwise a recorded session can replay to a DIFFERENT
 * final collapse set (debris from a falling block damages the block below it,
 * so which block falls first genuinely changes the outcome).
 *
 * <p>The engines now sort every batch of simultaneous collapse candidates by
 * {@link NodePos#CANONICAL_ORDER} (y, then x, then z) before processing them.
 * These tests pin that.
 */
@DisplayName("Cascade processes simultaneous failures in a deterministic order")
class DeterministicCascadeOrderTest {

    private static PhysicsConfig debrisConfig() {
        PhysicsConfig c = new PhysicsConfig();
        c.setDebrisImpactScale(0.5);
        c.setMinImpactDrop(2);
        return c;
    }

    /**
     * A flat row of heavy blocks floating side by side at the same height, each
     * over its own light target. They all become "floating" at the same time and
     * fall onto their targets — the canonical order decides the step sequence.
     */
    private static StructureGraph fanOfFallers() {
        StructureGraph g = new StructureGraph();
        for (int x = 0; x <= 4; x++) {
            g.addGroundBlock(new NodePos(x, 0, 0));
            g.addBlock(new NodePos(x, 1, 0), new MaterialSpec(2.0, 50.0), false); // target
            // Heavy faller four blocks up, no neighbours → floating.
            g.addBlock(new NodePos(x, 5, 0), new MaterialSpec(30.0, 1000.0), false);
        }
        return g;
    }

    private static List<NodePos> collapseOrder(StructureGraph g) {
        List<CollapsedNode> fell = new CascadeEngine(debrisConfig()).settle(g, SolverCallback.NONE);
        List<NodePos> order = new ArrayList<>();
        for (CollapsedNode cn : fell) {
            order.add(cn.pos());
        }
        return order;
    }

    @Test
    @DisplayName("The five simultaneous fallers collapse lowest-y-then-x first")
    void fallersCollapseInCanonicalOrder() {
        List<NodePos> order = collapseOrder(fanOfFallers());

        // The five fallers (y=5) all float at once; canonical order is x ascending.
        List<NodePos> fallers = order.stream().filter(p -> p.y() == 5).toList();
        assertEquals(5, fallers.size(), "all five fallers collapse");
        List<NodePos> expected = List.of(
                new NodePos(0, 5, 0),
                new NodePos(1, 5, 0),
                new NodePos(2, 5, 0),
                new NodePos(3, 5, 0),
                new NodePos(4, 5, 0));
        assertEquals(expected, fallers, "simultaneous floaters collapse in canonical (y,x,z) order");
    }

    @Test
    @DisplayName("Repeated runs produce an identical collapse sequence")
    void collapseSequenceIsStableAcrossRuns() {
        List<NodePos> first = collapseOrder(fanOfFallers());
        for (int run = 0; run < 20; run++) {
            assertEquals(first, collapseOrder(fanOfFallers()), "collapse order must not vary between runs");
        }
        // Sanity: debris pancaked every target too, so the whole structure came down.
        assertTrue(first.size() >= 10, "fallers plus the targets they pancaked all collapse");
    }
}
