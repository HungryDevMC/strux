package dev.gesp.structural.solver;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression: a SCOPED stress solve must be scope-honest — it may integrate load
 * only from neighbours inside its own subgraph.
 *
 * <p>The live bug: placing one block against a large connected curtain-wall ring
 * collapsed wall members with an "overload" cause even though a whole-structure
 * solve (and the green stress particles that reflect it) showed every node far
 * below capacity. The placement overload path solves over the placed block's
 * support chain — a partial window on the ring. Each in-scope node still read its
 * EXCLUDED lateral neighbours: those carry no distance in the scoped map (so they
 * default to {@code MAX_VALUE} and falsely read as "farther from ground") and a
 * STALE {@code verticalStress} from the earlier whole-structure solve. Counting
 * them funnelled phantom weight down the column until it "overloaded".
 */
@DisplayName("Scoped solve is scope-honest: excluded neighbours inject no phantom load")
class ScopedSolvePhantomLoadTest {

    /** A grounded column of {@code height} HEAVY blocks at (x, *, 0). */
    private static void column(StructureGraph g, int x, int height) {
        g.addGroundBlock(new NodePos(x, 0, 0));
        for (int y = 1; y <= height; y++) {
            g.addBlock(new NodePos(x, y, 0), TestMaterials.HEAVY, false);
        }
    }

    @Test
    @DisplayName("a stable column solved in isolation does not overload from a stale lateral neighbour")
    void scopedColumnIgnoresOutOfScopeLateralLoad() {
        // Two adjacent grounded columns, laterally connected at every level. Each block
        // sits directly on its own grounded column, so the WHOLE-graph structure is
        // trivially stable — the bottom block carries 8*3 = 24 vs a 100 cap (24%).
        StructureGraph g = new StructureGraph();
        column(g, 0, 8);
        column(g, 1, 8);

        StressSolver solver = new StressSolver();

        // Whole-graph solve first: proves stability AND populates a (now stale)
        // verticalStress on the x=1 column — exactly what the demo's whole-castle
        // build solve leaves on every node before a player places a block.
        solver.solveAll(g);
        for (Node n : g.getAllNodes()) {
            assertTrue(!n.isOverloaded(), "whole-graph solve must show every node stable");
        }

        // Now solve ONLY the x=0 column (its support-ancestor scope excludes the x=1
        // ring members) — the placement-overload path's scope. Scope-honest: no
        // phantom overload despite x=1's stale stress sitting one block to the side.
        Set<NodePos> scope = g.getSupportAncestors(new NodePos(0, 8, 0));
        assertTrue(scope.contains(new NodePos(0, 1, 0)), "scope is the x=0 support column");
        assertTrue(!scope.contains(new NodePos(1, 4, 0)), "scope must exclude the x=1 lateral column");

        NodePos overloaded = solver.solveProgressively(g, scope);
        assertNull(
                overloaded, "a stable column must not overload from an out-of-scope lateral neighbour's stale stress");

        // And the scoped solve computed the column's TRUE settled load: bottom = 24.
        assertTrue(
                g.getNode(new NodePos(0, 1, 0)).stressPercent() < 0.30,
                "the column base carries only its own stack (~24%), not phantom funnelled weight");
    }

    @Test
    @DisplayName("a genuinely overloaded marginal column IS still caught by the scoped solve")
    void scopedSolveStillCatchesRealOverload() {
        // A single LIGHT column (cap 20) tall enough that its own stacked mass exceeds
        // the base's capacity: 25 blocks * mass 1 = 25 > 20. A real overload the scoped
        // placement solve must still report — the fix must not silence legitimate ones.
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= 25; y++) {
            g.addBlock(new NodePos(0, y, 0), TestMaterials.LIGHT, false);
        }

        StressSolver solver = new StressSolver();
        NodePos overloaded = solver.solveProgressively(g, g.getSupportAncestors(new NodePos(0, 25, 0)));
        assertTrue(overloaded != null, "a self-overloading column must still be reported by the scoped solve");
    }
}
