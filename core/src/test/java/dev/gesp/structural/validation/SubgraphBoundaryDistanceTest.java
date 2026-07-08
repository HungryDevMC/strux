package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.StressSolver;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the "out-of-subgraph node reads as infinitely far" contract of the stress
 * solver's distance map.
 *
 * <p>The solver builds a distance-from-ground map for the positions it is asked to
 * solve. A node that is NOT in that subgraph has no entry, and every distance
 * lookup must then treat it as {@code Integer.MAX_VALUE} — infinitely far from
 * ground — NOT as distance 0 (ground level). Getting this wrong silently turns an
 * unseen neighbour into a phantom supporter and dilutes the real supporter's share
 * of the load. The {@link dev.gesp.structural.solver.CascadeEngine} boundary guard
 * (which widens the scope when an overloaded block touches the scope edge) relies
 * on exactly this: at the edge, the unseen side must look far, not grounded.
 *
 * <pre>
 *        [B]──[E]      E is a horizontal neighbour of B but is OUTSIDE the
 *         │            solved subgraph {GND, A, B}. When B shares its load
 *        [A]           downward it must go entirely to A (the only in-scope
 *         │            strictly-closer supporter). If E were mistaken for a
 *       [GND]          distance-0 supporter, it would steal most of A's share.
 * </pre>
 */
@DisplayName("Subgraph boundary: out-of-scope neighbours read as infinitely far")
class SubgraphBoundaryDistanceTest {

    @Test
    @DisplayName("A neighbour outside the solved subgraph is not a phantom supporter")
    void outOfSubgraphNeighbourIsNotAPhantomSupporter() {
        StructureGraph graph = new StructureGraph();

        // Generous capacities — this test is about load ROUTING, not failure.
        MaterialSpec light = new MaterialSpec(1.0, 1000.0);
        MaterialSpec heavy = new MaterialSpec(6.0, 1000.0);

        NodePos gnd = new NodePos(0, 0, 0);
        NodePos a = new NodePos(0, 1, 0);
        NodePos b = new NodePos(0, 2, 0);
        NodePos e = new NodePos(1, 2, 0); // horizontal neighbour of B, deliberately left out of the subgraph

        graph.addGroundBlock(gnd);
        graph.addBlock(a, light, false);
        graph.addBlock(b, heavy, false);
        graph.addBlock(e, light, false);

        StressSolver solver = new StressSolver();

        // Solve ONLY {gnd, a, b}. E exists and is a neighbour of B, but has no
        // distance entry, so it must be treated as infinitely far — a same-level
        // node that is NOT a downhill supporter of B.
        solver.solve(graph, Set.of(gnd, a, b));

        // B (distance 2) routes its load to its only in-scope strictly-closer
        // neighbour, A (distance 1), in full. So A carries its own mass plus all
        // of B's mass: 1 + 6 = 7.
        //
        // If the out-of-subgraph default were 0 instead of MAX_VALUE, E would
        // count as a distance-0 supporter of B and steal the lion's share of the
        // load (A would carry only 1 + 6 * (0.5 / 1.5) = 3), which this exact
        // assertion rejects.
        assertEquals(
                7.0,
                graph.getNode(a).verticalStress(),
                1e-9,
                "A must carry B's full load; an out-of-subgraph neighbour must not act as a supporter");
    }
}
