package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.metrics.StruxMetrics;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Region-scoped cascade: breaking one structure must not re-solve the other
 * structures sharing the world graph. Behavior is unchanged (the other
 * structures are untouched); the win is that the solver never visits them.
 */
@DisplayName("Scoped cascade: a break only solves its own component")
class ScopedCascadeTest {

    /** A grounded column of the given height at x, in z=0. */
    private static void column(StructureGraph g, int x, int height) {
        g.addGroundBlock(new NodePos(x, 0, 0));
        for (int y = 1; y <= height; y++) {
            g.addBlock(new NodePos(x, y, 0), TestMaterials.HEAVY, false);
        }
    }

    @Test
    @DisplayName("componentOf returns only the connected structure")
    void componentIsolatesStructures() {
        StructureGraph g = new StructureGraph();
        column(g, 0, 6); // structure A
        column(g, 5, 6); // structure B (5 apart — not connected)

        Set<NodePos> compA = g.componentOf(new NodePos(0, 3, 0));
        assertEquals(7, compA.size(), "A's component = 6 blocks + its ground");
        assertTrue(compA.contains(new NodePos(0, 0, 0)));
        assertFalse(compA.contains(new NodePos(5, 3, 0)), "B must not be in A's component");
    }

    @Test
    @DisplayName("getFloatingBlocks(scope) matches the whole-graph version when scope is everything")
    void scopedFloatingMatchesGlobalForFullScope() {
        StructureGraph g = new StructureGraph();
        column(g, 0, 4);
        g.addBlock(new NodePos(0, 6, 0), TestMaterials.HEAVY, false); // a floating block (gap at y=5)

        assertEquals(g.getFloatingBlocks(), g.getFloatingBlocks(g.getAllPositions()));
    }

    @Test
    @DisplayName("Breaking A in a world with B costs the same solver work as breaking A alone")
    void breakDoesNotSolveOtherStructures() {
        // World with two independent columns.
        StructureGraph world = new StructureGraph();
        column(world, 0, 10); // A
        column(world, 5, 10); // B
        StruxMetrics worldMetrics = new StruxMetrics();
        CascadeEngine worldEngine = new CascadeEngine().setMetrics(worldMetrics);
        worldEngine.cascade(world, new NodePos(0, 1, 0)); // break A's base

        // Same break, but A is the ONLY structure.
        StructureGraph solo = new StructureGraph();
        column(solo, 0, 10);
        StruxMetrics soloMetrics = new StruxMetrics();
        CascadeEngine soloEngine = new CascadeEngine().setMetrics(soloMetrics);
        soloEngine.cascade(solo, new NodePos(0, 1, 0));

        // The scoped solve never visits B: the work is identical to A-alone.
        assertEquals(
                soloMetrics.nodeVisits,
                worldMetrics.nodeVisits,
                "breaking A must not cost extra solver work for B (got world=" + worldMetrics.nodeVisits + ", solo="
                        + soloMetrics.nodeVisits + ")");

        // And B is completely untouched.
        assertTrue(world.hasBlock(new NodePos(5, 10, 0)), "B's top block still stands");
        assertTrue(world.hasBlock(new NodePos(5, 1, 0)), "B's base still stands");
        assertFalse(world.hasBlock(new NodePos(0, 5, 0)), "A collapsed");
    }
}
