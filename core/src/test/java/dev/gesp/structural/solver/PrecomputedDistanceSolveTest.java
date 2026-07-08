package dev.gesp.structural.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cascade feeds {@link StressSolver#findOverloadedBatch(StructureGraph, Set,
 * it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap)} a distance map maintained
 * incrementally by a {@link GroundDistanceIndex} instead of letting the solver
 * recompute the BFS each pass. This pins that the two paths are equivalent: for the
 * SAME scope, the overloaded batch computed from index distances is identical to the
 * batch computed from the solver's own inline BFS — across a sequence of removals.
 */
@DisplayName("Solver: precomputed (indexed) distances == inline BFS distances")
class PrecomputedDistanceSolveTest {

    private static final MaterialSpec WEAK = new MaterialSpec(5.0, 6.0);

    /** A long horizontal cantilever off a single grounded post — prone to overload. */
    private static StructureGraph cantilever() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), WEAK, false);
        for (int x = 1; x <= 8; x++) {
            g.addBlock(new NodePos(x, 1, 0), WEAK, false);
        }
        return g;
    }

    private static Set<NodePos> groundedIn(StructureGraph g, Set<NodePos> scope) {
        Set<NodePos> grounded = new HashSet<>();
        for (NodePos p : scope) {
            if (g.getNode(p) != null && g.getNode(p).isGrounded()) {
                grounded.add(p);
            }
        }
        return grounded;
    }

    @Test
    @DisplayName("Indexed distances give the same overloaded batch as a fresh BFS, every removal")
    void indexedBatchMatchesInlineBatch() {
        StructureGraph g = cantilever();
        StressSolver solver = new StressSolver();
        Set<NodePos> scope = new HashSet<>(g.getAllPositions());

        // Build the incremental index over the scope.
        GroundDistanceIndex index = new GroundDistanceIndex(new HashSet<>(scope), groundedIn(g, scope), g::neighborsOf);

        // Walk the cantilever inward, removing the post's neighbour each round so the
        // structure genuinely re-routes / sheds load — exercising the repair.
        NodePos[] removals = {new NodePos(8, 1, 0), new NodePos(7, 1, 0), new NodePos(0, 1, 0)};
        for (NodePos toRemove : removals) {
            // Both ways must agree BEFORE the removal too.
            List<NodePos> inline = solver.findOverloadedBatch(g, scope, null);
            List<NodePos> indexed = solver.findOverloadedBatch(g, scope, index.distances());
            inline.sort(NodePos.CANONICAL_ORDER);
            indexed.sort(NodePos.CANONICAL_ORDER);
            assertEquals(inline, indexed, "indexed batch must equal inline batch for the same scope");

            // Apply the removal to graph, scope and index in lock-step.
            g.removeBlock(toRemove);
            scope.remove(toRemove);
            index.remove(List.of(toRemove));
        }

        // And once more after the last removal.
        List<NodePos> inline = solver.findOverloadedBatch(g, scope, null);
        List<NodePos> indexed = solver.findOverloadedBatch(g, scope, index.distances());
        inline.sort(NodePos.CANONICAL_ORDER);
        indexed.sort(NodePos.CANONICAL_ORDER);
        assertEquals(inline, indexed, "indexed batch must equal inline batch after the final removal");
    }
}
