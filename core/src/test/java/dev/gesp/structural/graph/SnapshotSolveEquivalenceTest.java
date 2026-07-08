package dev.gesp.structural.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Diagnostic: does solving a scoped snapshot equal solving the full graph? */
class SnapshotSolveEquivalenceTest {

    private static List<NodePos> pos(List<CollapsedNode> c) {
        List<NodePos> out = new ArrayList<>();
        for (CollapsedNode n : c) {
            out.add(n.pos());
        }
        return out;
    }

    @Test
    @DisplayName("overload cantilever: snapshot solve == full solve")
    void cantilever() {
        MaterialSpec weak = new MaterialSpec(5.0, 10.0);
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), weak, false);
        g.addBlock(new NodePos(0, 2, 0), weak, false);
        for (int x = 1; x <= 6; x++) {
            g.addBlock(new NodePos(x, 2, 0), weak, false);
        }
        Set<NodePos> scope = g.getAllPositions();

        CascadeEngine engine = new CascadeEngine();
        StructureGraph full = g.copy();
        List<NodePos> sync =
                pos(engine.settleResult(full, scope, SolverCallback.NONE).collapsed());

        Set<NodePos> closed = g.affectedRegion(scope);
        StructureGraph snap = g.copySolvableSubgraph(closed);
        List<NodePos> async =
                pos(engine.settleResult(snap, closed, SolverCallback.NONE).collapsed());

        assertEquals(sync, async, "snapshot solve diverged from full solve");
    }

    @Test
    @DisplayName("partial scope (resume-style): snapshot solve == full solve")
    void partialScope() {
        MaterialSpec weak = new MaterialSpec(5.0, 10.0);
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), weak, false);
        g.addBlock(new NodePos(0, 2, 0), weak, false);
        for (int x = 1; x <= 10; x++) {
            g.addBlock(new NodePos(x, 2, 0), weak, false);
        }
        // Resume-style seed: only the disturbed tip region, NOT the whole graph.
        Set<NodePos> seed = Set.of(new NodePos(10, 2, 0), new NodePos(9, 2, 0));

        CascadeEngine engine = new CascadeEngine();
        StructureGraph full = g.copy();
        List<NodePos> sync =
                pos(engine.settleResult(full, seed, SolverCallback.NONE).collapsed());

        Set<NodePos> closed = g.affectedRegion(seed);
        StructureGraph snap = g.copySolvableSubgraph(closed);
        List<NodePos> async =
                pos(engine.settleResult(snap, closed, SolverCallback.NONE).collapsed());

        assertEquals(sync, async, "partial-scope snapshot solve diverged from full solve");
    }
}
