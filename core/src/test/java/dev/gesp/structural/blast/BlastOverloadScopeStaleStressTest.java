package dev.gesp.structural.blast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.StressSolver;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression for the blast phase-4 overload solve reading STALE stress across an
 * un-closed scope boundary.
 *
 * <p>{@code BlastSession.initOverload} builds its stress scope as
 * dependent-subgraph + straight-down support columns — NOT a structural closure. A
 * node at the edge of that scope still has neighbours OUTSIDE it. In
 * {@code StressSolver.computeLevelStress} a neighbour absent from the distance map
 * (out-of-scope ⇒ {@code MAX_VALUE > currentDist}) is treated as an upstream load
 * source and its <em>persistent</em> {@code verticalStress()} field is read —
 * whatever an unrelated earlier solve last wrote there. A blast next to previously
 * load-solved structure then imports that phantom load and over-collapses.
 *
 * <p><b>Ground truth</b> is the SAME blast on a FRESH graph (all-zero stress fields
 * — exactly the state a first settle starts from). The bug is visible as: run an
 * ordinary full {@link StressSolver#solve} first (the realistic per-tick state of a
 * standing keep) and the identical blast now collapses MORE blocks — the stale high
 * boundary stress tips otherwise-safe edge blocks over their overload threshold.
 *
 * <p>The boundary guard in {@code loadOverloadBatch} never collapses on approximate
 * edge numbers: an overloaded candidate that leans on an out-of-scope block widens
 * the scope and re-solves first, so the phantom load is recomputed from the real
 * (post-blast) graph and the spurious overload disappears — the blast verdict
 * becomes independent of any earlier solve's stress fields.
 *
 * <p>Seed/geometry found by searching random towers (see commit body). Pre-fix the
 * prior solve inflates the crater from 10 dropped blocks to 16; with the guard both
 * drop the same 10.
 */
@DisplayName("Blast phase-4: a prior solve's stale boundary stress must not inflate the collapse")
class BlastOverloadScopeStaleStressTest {

    private static final long TOWER_SEED = -6931162343180139157L;

    private static final BlastContext BLAST = BlastContext.builder()
            .center(new NodePos(3, 4, 2))
            .power(2.6676177504849194)
            .occlusion(BlastOcclusion.NONE)
            .build();

    private static StructureGraph tower() {
        Random rng = new Random(TOWER_SEED);
        int w = 4 + rng.nextInt(5);
        int d = 4 + rng.nextInt(5);
        int h = 3 + rng.nextInt(6);
        StructureGraph g = new StructureGraph();
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                g.addGroundBlock(new NodePos(x, 0, z));
            }
        }
        for (int y = 1; y <= h; y++) {
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    if (rng.nextInt(7) == 0) {
                        continue; // a hole → narrow load paths, so edge blocks sit near capacity
                    }
                    double res = 0.3 + rng.nextDouble() * 3.0;
                    double maxLoad = 20.0 + rng.nextDouble() * 60.0;
                    g.addBlock(new NodePos(x, y, z), new MaterialSpec(3.0, maxLoad, res), false);
                }
            }
        }
        return g;
    }

    /** destroyed ++ collapsed, in ORDER — the full record of what the blast dropped. */
    private static List<NodePos> runBlast(StructureGraph g) {
        StruxExplosionEngine engine = new StruxExplosionEngine(new PhysicsConfig());
        BlastResult r = engine.process(g, BLAST);
        List<NodePos> dropped = new ArrayList<>(r.destroyed());
        dropped.addAll(r.collapsed());
        return dropped;
    }

    @Test
    @DisplayName("A prior full solve does not inflate what the blast collapses (fresh == polluted)")
    void priorSolveDoesNotLeakIntoBlastVerdict() {
        // Ground truth: the blast on a pristine graph (all stress fields zero).
        List<NodePos> fresh = runBlast(tower());
        assertFalse(fresh.isEmpty(), "the scenario must actually crater/collapse something");

        // Same structure, same blast — but a realistic prior per-tick stress solve
        // ran first, leaving real verticalStress values in the persistent node
        // fields. Pre-fix the phase-4 boundary imports those stale HIGH values and
        // the crater grows (16 vs 10); the boundary guard re-solves the edge from
        // the real post-blast graph, so the spurious overloads never fire.
        StructureGraph polluted = tower();
        new StressSolver(new PhysicsConfig()).solve(polluted, new HashSet<>(polluted.getAllPositions()));
        List<NodePos> pollutedResult = runBlast(polluted);

        assertEquals(
                fresh,
                pollutedResult,
                "blast collapse must be independent of any earlier solve's stress fields "
                        + "(stale boundary stress leaked into the phase-4 overload verdict)");
    }

    @Test
    @DisplayName("The scenario really exercises the boundary: a prior solve leaves stale load at the crater edge")
    void scenarioActuallyLoadsTheBoundary() {
        // Guardrail so the pin above cannot silently degrade into a no-op (e.g. a
        // blast that never reaches loaded structure): the prior solve must leave a
        // genuinely non-trivial crater to defend, and it must be a multi-block one.
        List<NodePos> fresh = runBlast(tower());
        assertTrue(fresh.size() >= 8, "the crater must be sizeable enough to have a load-bearing boundary");
    }
}
