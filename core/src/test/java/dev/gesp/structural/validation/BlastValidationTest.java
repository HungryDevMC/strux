package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.blast.BlastContext;
import dev.gesp.structural.blast.BlastOcclusion;
import dev.gesp.structural.blast.BlastResult;
import dev.gesp.structural.blast.StruxExplosionEngine;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Law: the three sets a {@link BlastResult} reports — {@code destroyed},
 * {@code collapsed}, {@code damaged} — describe disjoint outcomes for distinct
 * blocks. A block is shattered, OR it fell, OR it survived weakened — never two
 * at once. Consumers (recorders, replay, the cracked-count HUD) rely on this to
 * avoid double-counting and to keep "damaged = survived but weakened" honest.
 *
 * <p>Before the engine pruned its overlap, a block cracked by the shockwave and
 * then dropped by the cascade appeared in BOTH {@code damaged} and
 * {@code collapsed}; forensic analysis of a real arena match saw this on every
 * blast. These tests pin the disjointness.
 */
@DisplayName("Blast law: destroyed / collapsed / damaged are pairwise disjoint")
class BlastValidationTest {

    // Normal = will crack/collapse; resistance 1 so the shockwave actually damages it.
    private static final MaterialSpec NORMAL = new MaterialSpec(1.0, 1000.0);

    private final StruxExplosionEngine engine = new StruxExplosionEngine();

    private static BlastContext blast(NodePos center, double power) {
        return BlastContext.builder()
                .center(center)
                .power(power)
                .occlusion(BlastOcclusion.NONE)
                .build();
    }

    private static void assertPairwiseDisjoint(BlastResult r, String context) {
        Set<NodePos> destroyed = new HashSet<>(r.destroyed());
        Set<NodePos> collapsed = new HashSet<>(r.collapsed());
        Set<NodePos> damaged = new HashSet<>(r.damaged().keySet());

        assertTrue(disjoint(destroyed, collapsed), context + ": destroyed ∩ collapsed must be empty");
        assertTrue(disjoint(destroyed, damaged), context + ": destroyed ∩ damaged must be empty");
        assertTrue(
                disjoint(collapsed, damaged),
                context + ": collapsed ∩ damaged must be empty (a block that fell is not 'survived weakened')");
    }

    private static boolean disjoint(Set<NodePos> a, Set<NodePos> b) {
        Set<NodePos> overlap = new HashSet<>(a);
        overlap.retainAll(b);
        return overlap.isEmpty();
    }

    @Test
    @DisplayName("A column whose upper rows crack then fall: damaged never lists a collapsed block")
    void columnCrackedThenCollapsed() {
        StructureGraph g = new StructureGraph();
        // A single tall column rooted on one ground block. A blast at the base
        // craters the lowest rows and cracks the rows just above them; once the
        // crater removes their support, those cracked rows lose their path to
        // ground and collapse. Those cracked-then-fallen rows are the trap: they
        // were added to `damaged` in phase 1, then removed by the cascade — they
        // must NOT remain in damaged.
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= 8; y++) {
            g.addBlock(new NodePos(0, y, 0), NORMAL, false);
        }

        // Blast at the base: power 4 → radius 6 reaches up the column. Low rows
        // (intensity ≥ threshold) shatter; higher rows only crack — then fall when
        // the support beneath them is gone.
        BlastResult r = engine.process(g, blast(new NodePos(0, 1, 0), 4.0));

        // Sanity: the scenario must actually exercise both a crater and a cascade,
        // otherwise it can't catch the overlap it was built to catch.
        assertTrue(!r.destroyed().isEmpty(), "the blast must shatter some blocks");
        assertTrue(!r.collapsed().isEmpty(), "the blast must cause a secondary cascade");

        assertPairwiseDisjoint(r, "wall blast");
    }

    @Test
    @DisplayName("Pinned-seed random sweep: every blast result is pairwise disjoint")
    void randomSweepDisjoint() {
        long seed = 20260605L; // pinned for reproducibility (repo rule)
        Random rng = new Random(seed);

        for (int trial = 0; trial < 200; trial++) {
            StructureGraph g = randomStructure(rng);
            NodePos center = new NodePos(rng.nextInt(8), rng.nextInt(8), rng.nextInt(4));
            double power = 1.5 + rng.nextDouble() * 5.0;

            BlastResult r = engine.process(g, blast(center, power));

            assertPairwiseDisjoint(
                    r,
                    "random trial " + trial + " (seed=" + seed + ", center=" + center + ", power="
                            + String.format("%.2f", power) + ")");
        }
    }

    /** A random ground-rooted blob of blocks: a slab of ground plus stacked columns of random height. */
    private static StructureGraph randomStructure(Random rng) {
        StructureGraph g = new StructureGraph();
        int span = 4 + rng.nextInt(4); // 4..7 wide/deep
        for (int x = 0; x < span; x++) {
            for (int z = 0; z < 3; z++) {
                g.addGroundBlock(new NodePos(x, 0, z));
                int height = 1 + rng.nextInt(6); // 1..6 tall
                for (int y = 1; y <= height; y++) {
                    g.addBlock(new NodePos(x, y, z), NORMAL, false);
                }
            }
        }
        return g;
    }
}
