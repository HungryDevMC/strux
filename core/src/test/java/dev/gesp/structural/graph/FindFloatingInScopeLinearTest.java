package dev.gesp.structural.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StructureGraph#findFloatingInScope} must classify a floating region in
 * O(region), not O(scope × region). The documented worst case is O(component); the
 * bug was that every scope member of one floating region re-ran the full-component
 * BFS because only ground-connected starts were memoised, never floating ones.
 *
 * <p>This is a pure-performance guard — removing it does not change the RESULT, only
 * the work — so the regression is pinned by counting traversal work (neighbour
 * lookups) with a graph subclass, the only way to make the fix observable.
 */
@DisplayName("findFloatingInScope: a floating region is classified in O(region), not O(scope×region)")
class FindFloatingInScopeLinearTest {

    private static final MaterialSpec STONE = new MaterialSpec(1.0, 100.0);

    /** Counts every neighbour lookup so a test can assert the traversal stayed linear. */
    private static final class CountingGraph extends StructureGraph {
        int neighborLookups = 0;

        @Override
        public Set<NodePos> neighborsOf(NodePos pos) {
            neighborLookups++;
            return super.neighborsOf(pos);
        }
    }

    /** A horizontal chain of {@code n} ungrounded blocks — one floating component. */
    private static Set<NodePos> floatingChain(CountingGraph g, int n) {
        Set<NodePos> chain = new HashSet<>();
        for (int x = 0; x < n; x++) {
            NodePos p = new NodePos(x, 5, 0); // y=5, never ground (y=0)
            g.addBlock(p, STONE, false);
            chain.add(p);
        }
        return chain;
    }

    @Test
    @DisplayName("Every block of a floating region in scope is reported, with linear traversal")
    void floatingRegionClassifiedLinearly() {
        int n = 60;
        CountingGraph g = new CountingGraph();
        Set<NodePos> chain = floatingChain(g, n);

        Set<NodePos> floating = g.findFloatingInScope(new HashSet<>(chain));

        // Correctness: the whole disconnected chain is floating.
        assertEquals(chain, floating, "the entire ungrounded chain must be reported floating");

        // Performance: the first start's BFS sweeps the chain once; every later start is
        // a memoised skip. One BFS over n nodes does n neighbour lookups, so a small
        // constant multiple of n is linear. The quadratic bug would re-sweep per start:
        // ~n² = 3600 lookups for n=60, far above this bound.
        assertTrue(
                g.neighborLookups <= 3 * n,
                "traversal must be linear in the region size, was " + g.neighborLookups + " for n=" + n);
    }
}
