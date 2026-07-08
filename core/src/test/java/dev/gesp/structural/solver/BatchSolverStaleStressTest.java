package dev.gesp.structural.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression for the batch solver reading STALE {@code verticalStress} from
 * "trivially stable" neighbours that {@code planLevels} skips.
 *
 * <p>The discriminating geometry is a solid 3×3 footprint stacked three high. The
 * centre block of the top layer (1,2,1) is the ONLY block with no farther neighbour,
 * no damage, and all four cardinal horizontal neighbours present — so it is the one
 * block {@code planLevels} skips (every edge/corner block is caught by edge
 * detection). Its load must still flow down onto the centre of the middle layer
 * (1,1,1). The non-batch {@link StressSolver#solve} (which resets and recomputes
 * EVERY node) is the ground truth; {@link StressSolver#findOverloadedBatch} (the
 * production cascade path) must agree — on a FRESH graph whose stress fields are all
 * zero, which is exactly the state a replay/settle starts from.
 */
@DisplayName("Batch solver: skipped trivially-stable neighbours must not leak stale stress")
class BatchSolverStaleStressTest {

    // mass 1.0; maxLoad 1.5 so the true centre load (2.0) overloads but the
    // stale-read load (1.0) does not — the bug is then visible as a missing collapse.
    private static final MaterialSpec UNIT = new MaterialSpec(1.0, 1.5);

    private static final NodePos CENTER_MID = new NodePos(1, 1, 1);

    /** Solid 3×3 footprint on ground, stacked to y=1 and y=2. */
    private static StructureGraph slab3x3x3() {
        StructureGraph g = new StructureGraph();
        for (int x = 0; x <= 2; x++) {
            for (int z = 0; z <= 2; z++) {
                g.addGroundBlock(new NodePos(x, 0, z));
                g.addBlock(new NodePos(x, 1, z), UNIT, false);
                g.addBlock(new NodePos(x, 2, z), UNIT, false);
            }
        }
        return g;
    }

    @Test
    @DisplayName("findOverloadedBatch reproduces solve()'s centre stress on a fresh graph")
    void batchMatchesSolveAtCenter() {
        StressSolver solver = new StressSolver();

        // Ground truth: the full solver, on its own fresh graph.
        StructureGraph reference = slab3x3x3();
        Set<NodePos> refScope = new HashSet<>(reference.getAllPositions());
        solver.solve(reference, refScope);
        double solveCenter = reference.getNode(CENTER_MID).verticalStress();
        // Own mass (1.0) plus the full load of the roof centre above it (1.0).
        assertEquals(2.0, solveCenter, 1e-9, "solve() flows the roof centre's load onto the middle centre");

        // The batch path on a SEPARATE fresh graph (all-zero stress fields) must
        // arrive at the same centre stress. Pre-fix it reads the skipped roof
        // centre's stale 0.0 and lands at 1.0.
        StructureGraph batch = slab3x3x3();
        Set<NodePos> batchScope = new HashSet<>(batch.getAllPositions());
        solver.findOverloadedBatch(batch, batchScope, null);
        double batchCenter = batch.getNode(CENTER_MID).verticalStress();
        assertEquals(solveCenter, batchCenter, 1e-9, "batch solver must not read stale stress from skipped neighbours");
    }

    @Test
    @DisplayName("findOverloadedBatch flags the overloaded middle centre, not just the edges")
    void batchFlagsOverloadedCenter() {
        StressSolver solver = new StressSolver();
        StructureGraph g = slab3x3x3();
        Set<NodePos> scope = new HashSet<>(g.getAllPositions());

        // Distance-1 (middle) layer fails first as a whole; the centre carries the
        // roof centre's full weight (2.0 > 1.5) and MUST be reported. Pre-fix it
        // reads the skipped roof centre's stale 0.0, stays at 1.0, and is missed.
        List<NodePos> overloaded = solver.findOverloadedBatch(g, scope, null);
        assertTrue(
                overloaded.contains(CENTER_MID), "the overloaded middle centre must be detected, got: " + overloaded);
    }
}
