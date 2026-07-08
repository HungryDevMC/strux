package dev.gesp.structural.graph;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.model.NodePos;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The interruptible {@link StructureGraph#affectedRegion(StructureGraph.ScopeClosureCursor,
 * java.util.function.BooleanSupplier) closure} must, when paused and resumed to completion,
 * yield a region SET-EQUAL to the uninterrupted {@link StructureGraph#affectedRegion(Set)}.
 *
 * <p>This is the load-bearing determinism guarantee of the settle budget (AC #3 / the
 * risk note): a resumed BFS that dropped or double-counted a node would reproduce the
 * exact neighbour-encounter-order nondeterminism the {@code visited}/{@code region} split
 * at {@code StructureGraph.affectedRegion} was built to kill. We force MAXIMAL
 * fragmentation — a chunk size of 1 plus an always-true pause, so the BFS parks after
 * every single node — on an adversarial layout that mixes structural nodes, terrain-stable
 * skin, and support-column context.
 */
@DisplayName("Interruptible closure: paused-and-resumed BFS is set-equal to the uninterrupted closure")
class ClosureResumeEquivalenceTest {

    private static final BooleanSupplier ALWAYS_PAUSE = () -> true;

    /** Drive a chunk-1, always-paused closure to completion; return its region. */
    private static Set<NodePos> resumeToClosure(StructureGraph g, Set<NodePos> seeds) {
        StructureGraph.ScopeClosureCursor cursor = new StructureGraph.ScopeClosureCursor(seeds);
        int guard = 0;
        while (!cursor.isComplete()) {
            g.affectedRegion(cursor, ALWAYS_PAUSE, 1); // pause after every dequeue
            assertTrue(++guard < 100_000, "a chunk-1 closure must still terminate");
        }
        return new HashSet<>(cursor.region());
    }

    /**
     * A tower on a terrain skin: the skin is terrain-stable context, the tower is
     * structural, and the skin columns are recorded as support context (not traversed
     * laterally). Exactly the split the resume must preserve.
     */
    private static StructureGraph towerOnSkin() {
        StructureGraph g = new StructureGraph();
        for (int x = 0; x <= 6; x++) {
            g.addGroundBlock(new NodePos(x, 0, 0));
            g.addBlock(new NodePos(x, 1, 0), TestMaterials.MEDIUM, false); // terrain skin
        }
        for (int y = 2; y <= 6; y++) {
            g.addBlock(new NodePos(3, y, 0), TestMaterials.HEAVY, false); // structural tower
        }
        // A cantilever arm off the tower — structural, no grounded column of its own.
        for (int x = 4; x <= 8; x++) {
            g.addBlock(new NodePos(x, 6, 0), TestMaterials.LIGHT, false);
        }
        return g;
    }

    @Test
    @DisplayName("chunk-1 always-paused closure equals the uninterrupted closure, node for node")
    void resumedClosureEqualsUninterrupted() {
        StructureGraph g = towerOnSkin();
        Set<NodePos> seeds = Set.of(new NodePos(3, 4, 0));

        Set<NodePos> uninterrupted = new HashSet<>(g.affectedRegion(new HashSet<>(seeds)));
        Set<NodePos> resumed = resumeToClosure(g, seeds);

        assertEquals(uninterrupted, resumed, "a resumed closure must be set-equal to the one-shot closure");
        assertFalse(uninterrupted.isEmpty(), "sanity: the seed actually has a non-trivial closure");
    }

    @Test
    @DisplayName("Seeding the arm tip closes the whole cantilever the same way, paused or not")
    void resumedClosureEqualsUninterruptedFromArmTip() {
        StructureGraph g = towerOnSkin();
        Set<NodePos> seeds = Set.of(new NodePos(8, 6, 0));

        assertEquals(
                new HashSet<>(g.affectedRegion(new HashSet<>(seeds))),
                resumeToClosure(g, seeds),
                "the closure from the cantilever tip must not depend on how many chunks it took");
    }

    @Test
    @DisplayName("A closure larger than one CHUNK really pauses, then resumes to the identical set")
    void largeClosurePausesAndResumesEqual() {
        // A long structural beam (> the production CLOSURE_CHUNK) hung off a grounded
        // pillar: every beam block is structural, so the closure is the whole beam and
        // the BFS must span multiple production-size chunks.
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), TestMaterials.HEAVY, false);
        int length = StructureGraph.CLOSURE_CHUNK + 500;
        for (int x = 1; x <= length; x++) {
            g.addBlock(new NodePos(x, 1, 0), TestMaterials.LIGHT, false);
        }
        Set<NodePos> seeds = Set.of(new NodePos(1, 1, 0));

        // With the production chunk and an always-true pause, the first pass must NOT
        // finish (proof the closure is genuinely interrupted, not run whole).
        StructureGraph.ScopeClosureCursor cursor = new StructureGraph.ScopeClosureCursor(seeds);
        g.affectedRegion(cursor, ALWAYS_PAUSE); // production chunk
        assertFalse(cursor.isComplete(), "a > CHUNK closure must pause on the first pass under an always-true budget");

        // Resuming to completion reproduces the uninterrupted closure exactly.
        while (!cursor.isComplete()) {
            g.affectedRegion(cursor, ALWAYS_PAUSE);
        }
        assertEquals(new HashSet<>(g.affectedRegion(new HashSet<>(seeds))), new HashSet<>(cursor.region()));
    }
}
