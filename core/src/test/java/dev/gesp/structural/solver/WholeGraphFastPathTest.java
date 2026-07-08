package dev.gesp.structural.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the whole-graph fast path in {@link StressSolver#solve}: when the subgraph
 * covers the whole graph the per-edge scope-membership check is skipped
 * ({@code scoped == false}), because every neighbour is trivially in scope.
 *
 * <p>Two obligations, one per test:
 *
 * <ol>
 *   <li>the fast path is a NO-OP on physics — a whole-graph solve is byte-identical to
 *       a solve that still runs the guard over an all-but-one-disconnected-node scope; and
 *   <li>the guard still ENGAGES for a genuine partial scope — otherwise stale
 *       out-of-scope stress leaks phantom load in (this is what the {@code scoped} flag
 *       negation mutant would break, on the {@link StressSolver#solve} vertical pass).
 * </ol>
 */
@DisplayName("Whole-graph solve fast-paths the scope guard without changing physics")
class WholeGraphFastPathTest {

    /** A grounded column of {@code height} HEAVY blocks at (x, *, 0). */
    private static void column(StructureGraph g, int x, int height) {
        g.addGroundBlock(new NodePos(x, 0, 0));
        for (int y = 1; y <= height; y++) {
            g.addBlock(new NodePos(x, y, 0), TestMaterials.HEAVY, false);
        }
    }

    /** A moment-bearing scene: two grounded columns bridged by a cantilevered arm. */
    private static StructureGraph scene() {
        StructureGraph g = new StructureGraph();
        column(g, 0, 6);
        column(g, 4, 6);
        // Horizontal span at y=6 linking the two towers (exercises the moment pass too).
        for (int x = 1; x <= 3; x++) {
            g.addBlock(new NodePos(x, 6, 0), TestMaterials.HEAVY, false);
        }
        // A cantilevered nub off the span so momentStress is non-trivial.
        g.addBlock(new NodePos(2, 7, 0), TestMaterials.HEAVY, false);
        return g;
    }

    @Test
    @DisplayName("fast path (scoped=false) equals a guard-engaged solve over all real positions, byte-identical")
    void wholeGraphFastPathMatchesForcedScopedSolve() {
        // --- Fast path: solveAll -> subgraph.size() == graph.size() -> scoped == false.
        StructureGraph fast = scene();
        new StressSolver().solveAll(fast);

        // --- Forced-scoped: same structure plus ONE disconnected grounded block. Solving
        // over just the real positions makes subgraph.size() < graph.size(), so scoped ==
        // true and the guard runs on every edge. The extra block neighbours nothing, so it
        // cannot change any real node's stress — only the guard flag differs between runs.
        StructureGraph scoped = scene();
        scoped.addGroundBlock(new NodePos(100, 0, 0)); // disconnected sentinel
        Set<NodePos> realPositions = new HashSet<>();
        for (Node n : scene().getAllNodes()) {
            realPositions.add(n.pos());
        }
        assertTrue(
                realPositions.size() < scoped.size(),
                "the sentinel must make the scope a strict subset so the guard engages");
        new StressSolver().solve(scoped, realPositions);

        // Byte-identical per-node stress across every real node.
        for (Node fastNode : fast.getAllNodes()) {
            Node scopedNode = scoped.getNode(fastNode.pos());
            assertEquals(
                    Double.doubleToRawLongBits(fastNode.verticalStress()),
                    Double.doubleToRawLongBits(scopedNode.verticalStress()),
                    "verticalStress differs at " + fastNode.pos());
            assertEquals(
                    Double.doubleToRawLongBits(fastNode.momentStress()),
                    Double.doubleToRawLongBits(scopedNode.momentStress()),
                    "momentStress differs at " + fastNode.pos());
        }
    }

    @Test
    @DisplayName("the guard still engages on a partial scope: excluded lateral neighbours inject no phantom load")
    void partialScopeStillGuardsAgainstStaleLateralLoad() {
        // Two adjacent grounded columns. The whole structure is trivially stable (each
        // block sits on its own column), so no node is anywhere near its cap.
        StructureGraph g = new StructureGraph();
        column(g, 0, 8);
        column(g, 1, 8);

        StressSolver solver = new StressSolver();

        // Whole-graph solve first: stable everywhere, and it leaves a (soon-to-be-stale)
        // verticalStress on the x=1 column.
        solver.solveAll(g);
        for (Node n : g.getAllNodes()) {
            assertTrue(!n.isOverloaded(), "whole-graph solve must show every node stable");
        }

        // Now solve ONLY the x=0 support column via the solve() vertical pass. The scope
        // excludes the x=1 lateral members: subgraph.size() < graph.size() -> scoped ==
        // true, so the guard skips their stale stress. If the guard flag were negated to
        // false, x=1's stale verticalStress would funnel phantom weight into x=0's base.
        Set<NodePos> scope = g.getSupportAncestors(new NodePos(0, 8, 0));
        assertTrue(scope.contains(new NodePos(0, 1, 0)), "scope is the x=0 support column");
        assertTrue(!scope.contains(new NodePos(1, 4, 0)), "scope must exclude the x=1 lateral column");
        assertTrue(scope.size() < g.size(), "scope must be a strict subset so the guard engages");

        solver.solve(g, scope);

        // The column base carries only its own stack (~24%), not phantom funnelled weight.
        assertTrue(
                g.getNode(new NodePos(0, 1, 0)).stressPercent() < 0.30,
                "guard must keep out-of-scope stale stress from overloading the base column");
    }
}
