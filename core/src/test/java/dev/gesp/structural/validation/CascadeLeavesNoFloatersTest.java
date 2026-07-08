package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.blast.BlastContext;
import dev.gesp.structural.blast.StruxExplosionEngine;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.impact.ImpactContext;
import dev.gesp.structural.impact.ImpactEngine;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * THE post-condition the deterministic-replay invariant checker enforces: after
 * any destructive event settles, NO block may be left with zero edge-path to a
 * grounded node. A leftover floater is the "floating blocks survive cascade
 * settle" bug — the cascade missed a region that lost all support.
 *
 * <pre>
 *   THE GAP THIS PINS:
 *
 *   The scoped blast/impact engines bound their work to the disturbed region's
 *   "dependent subgraph". But removing an interior support can ORPHAN a block
 *   that hangs off a DIFFERENT part of the structure — a block that is NOT in
 *   that dependent subgraph and is, after the removal, no longer reachable from
 *   the removed block at all. If the severed boundary is not seeded into the
 *   settle's scope, findFloatingInScope never starts a BFS there and the
 *   orphaned region floats forever.
 *
 *      [H]──[S2]          H hangs off S2 (no block directly under H).
 *            │            S2's support chain S2→S1→GND is overloaded and
 *           [S1]          removed by a blast elsewhere. H is far from the blast,
 *            │            so it is not in the blast's scope — yet it must fall.
 *          [GND]
 * </pre>
 *
 * <p>This is a property test over generated structures (debris off, so collapse
 * is order-independent and "floating" is pure edge-reachability): a regression
 * in any scope-seeding heuristic shows up here as a surviving floater on some
 * seed, long before anyone builds that exact shape in a world.
 */
@DisplayName("After any blast or impact settles, nothing is left floating")
class CascadeLeavesNoFloatersTest {

    private static final MaterialSpec L = new MaterialSpec(1.0, 20.0);

    /**
     * Debris off (collapse order-independent, so "floating" is exact
     * edge-reachability) and the per-trigger cascade cap lifted — the cap is a
     * deliberate safety limit that can legitimately leave a partially-collapsed
     * structure mid-fall, which is NOT the bug under test. With the cap out of
     * the way, any leftover floater is a genuine missed-region bug.
     */
    private static PhysicsConfig config() {
        PhysicsConfig c = new PhysicsConfig();
        c.setDebrisImpactScale(0.0);
        c.setMaxCascadeSteps(1_000_000);
        return c;
    }

    /** A deterministic blocky structure on a shared ground strip, then settled clean. */
    private static StructureGraph generate(long seed, PhysicsConfig cfg) {
        Random r = new Random(seed);
        StructureGraph g = new StructureGraph();
        int w = 6 + r.nextInt(6);
        int d = 1 + r.nextInt(3);
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                g.addGroundBlock(new NodePos(x, 0, z));
            }
        }
        int blocks = 50 + r.nextInt(90);
        for (int i = 0; i < blocks; i++) {
            g.addBlock(new NodePos(r.nextInt(w), 1 + r.nextInt(8), r.nextInt(d)), L, false);
        }
        // Settle the as-built structure and clear any blocks that were placed
        // floating, so the test starts from a fully-grounded world and only
        // measures floaters the EVENT creates.
        for (int k = 0; k < 5; k++) {
            new CascadeEngine(cfg).settle(g, SolverCallback.NONE);
        }
        for (NodePos p : g.getFloatingBlocks()) {
            g.removeBlock(p);
        }
        return g;
    }

    private static List<NodePos> standing(StructureGraph g) {
        List<NodePos> out = new ArrayList<>();
        for (var n : g.getAllNodes()) {
            if (!n.isGrounded()) {
                out.add(n.pos());
            }
        }
        return out;
    }

    @Test
    @DisplayName("Blasting a fully-grounded structure never strands a floating block")
    void blastLeavesNoFloaters() {
        PhysicsConfig cfg = config();
        for (long seed = 0; seed < 3000; seed++) {
            StructureGraph g = generate(seed, cfg);
            List<NodePos> standing = standing(g);
            if (standing.isEmpty()) {
                continue;
            }
            Random r = new Random(seed * 131 + 5);
            NodePos center = standing.get(r.nextInt(standing.size()));
            new StruxExplosionEngine(cfg)
                    .process(
                            g,
                            BlastContext.builder()
                                    .center(center)
                                    .power(1.5 + r.nextInt(4))
                                    .build());

            Set<NodePos> floating = g.getFloatingBlocks();
            assertTrue(
                    floating.isEmpty(), "blast at " + center + " (seed " + seed + ") stranded floaters: " + floating);
        }
    }

    @Test
    @DisplayName("Impacting a fully-grounded structure never strands a floating block")
    void impactLeavesNoFloaters() {
        PhysicsConfig cfg = config();
        for (long seed = 0; seed < 3000; seed++) {
            StructureGraph g = generate(seed, cfg);
            List<NodePos> standing = standing(g);
            if (standing.isEmpty()) {
                continue;
            }
            Random r = new Random(seed * 137 + 11);
            NodePos hit = standing.get(r.nextInt(standing.size()));
            new ImpactEngine(cfg)
                    .process(
                            g,
                            ImpactContext.builder()
                                    .origin(hit)
                                    .direction(r.nextInt(3) - 1, r.nextInt(3) - 1, r.nextInt(3) - 1)
                                    .energy(2 + r.nextInt(30))
                                    .build());

            Set<NodePos> floating = g.getFloatingBlocks();
            assertTrue(floating.isEmpty(), "impact at " + hit + " (seed " + seed + ") stranded floaters: " + floating);
        }
    }
}
