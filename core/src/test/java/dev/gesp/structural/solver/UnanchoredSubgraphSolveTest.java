package dev.gesp.structural.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StressSolver#solve} must not silently degrade to own-mass-only stress when
 * handed an UNANCHORED subgraph (one with no grounded node). Its own javadoc points
 * callers at {@code getDependentSubgraph}, which by construction returns only the
 * upward/dependent cone and never a grounded node — so without anchoring, the
 * distance BFS has no seed, every block lands at distance MAX_VALUE, and the solve
 * overwrites the cone's real stress with each block's bare mass.
 */
@DisplayName("StressSolver.solve: an unanchored subgraph is widened to its support, not solved own-mass-only")
class UnanchoredSubgraphSolveTest {

    private static final MaterialSpec UNIT = new MaterialSpec(1.0, 1000.0);

    /** A grounded vertical stack of {@code height} blocks. */
    private static StructureGraph stack(int height) {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= height; y++) {
            g.addBlock(new NodePos(0, y, 0), UNIT, false);
        }
        return g;
    }

    @Test
    @DisplayName("Solving the dependent cone yields the same stress as solving the anchored component")
    void coneSolveMatchesAnchoredSolve() {
        StressSolver solver = new StressSolver();
        NodePos mid = new NodePos(0, 2, 0);

        // Ground truth: solve the whole anchored component. The mid block carries
        // itself plus everything above (blocks 2..5) = 4 units of vertical stress.
        StructureGraph anchored = stack(5);
        solver.solve(anchored, new HashSet<>(anchored.getAllPositions()));
        double anchoredStress = anchored.getNode(mid).verticalStress();
        assertEquals(4.0, anchoredStress, 1e-9, "the mid block bears its own mass plus the three blocks above");

        // The dependent cone of `mid` is {mid, and everything above} — NO grounded node.
        StructureGraph coned = stack(5);
        Set<NodePos> cone = coned.getDependentSubgraph(mid);
        assertTrue(cone.stream().noneMatch(p -> coned.getNode(p).isGrounded()), "the cone has no grounded anchor");

        solver.solve(coned, cone);
        // Pre-fix this is own-mass-only (1.0); the fix widens to the support column.
        assertEquals(
                anchoredStress,
                coned.getNode(mid).verticalStress(),
                1e-9,
                "solving the unanchored cone must give the same stress as the anchored solve");
    }
}
