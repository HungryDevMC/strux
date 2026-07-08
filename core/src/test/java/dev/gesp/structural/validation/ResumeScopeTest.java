package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.metrics.StruxMetrics;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import dev.gesp.structural.solver.CascadeResult;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A truncated cascade must RESUME over the disturbed region only, not the whole
 * (terrain-sized) graph. Re-seeding {@code getAllPositions()} forced a full-graph
 * {@code MomentArmIndex} rebuild on every settle step and tripped Paper's watchdog
 * on large arenas; the fix carries {@code SettleOutcome.remainingScope()} forward
 * so each resume tick's solver work tracks the collapsing structure.
 *
 * <p>The lever is observable as solver work ({@link StruxMetrics#nodeVisits}): with
 * the fix it is independent of how much inert terrain shares the world graph.
 */
@DisplayName("Cascade resume stays scoped to the disturbed region")
class ResumeScopeTest {

    /**
     * A two-pier bridge with one pier already knocked out — an overloaded
     * cantilever that trims in cap-bound OVERLOAD steps (so a small cap
     * truncates and leaves a real resume frontier), plus {@code terrainBlocks}
     * disconnected grounded blocks that only bloat the whole-graph scope.
     */
    private static StructureGraph worldWithTerrain(int terrainBlocks) {
        StructureGraph g = new StructureGraph();
        // Two short piers carrying a long span: stable as a bridge. The trigger
        // knocks out one pier's base so the span becomes an overloaded cantilever
        // off the survivor — it trims in cap-bound OVERLOAD steps (not floaters),
        // so a small cap truncates and leaves a real resume frontier.
        for (int pier : new int[] {0, 8}) {
            g.addGroundBlock(new NodePos(pier, 0, 0));
            g.addBlock(new NodePos(pier, 1, 0), TestMaterials.HEAVY, false);
            g.addBlock(new NodePos(pier, 2, 0), TestMaterials.HEAVY, false);
        }
        for (int x = 1; x <= 7; x++) {
            g.addBlock(new NodePos(x, 2, 0), TestMaterials.LIGHT, false);
        }
        // Inert terrain: disconnected, stable, NON-grounded blocks far away (each
        // resting on its own ground anchor — the siege arena registers only bedrock
        // as ground, all soil above it as physics blocks). They never collapse and
        // are never reachable from the bridge's scope, but the whole-graph solver
        // still enumerates them (distance BFS + moment index) — exactly the cost the
        // old getAllPositions() resume paid every step.
        for (int i = 0; i < terrainBlocks; i++) {
            int tx = 100 + (i % 64);
            int tz = 100 + (i / 64);
            g.addGroundBlock(new NodePos(tx, 0, tz));
            g.addBlock(new NodePos(tx, 1, tz), TestMaterials.LIGHT, false);
        }
        return g;
    }

    /** Truncate the bridge, then resume on the carried scope to completion; return resume-only node visits. */
    private static long resumeWorkOverScope(StructureGraph g) {
        PhysicsConfig config = new PhysicsConfig();
        config.setMaxCascadeSteps(2);
        CascadeEngine engine = new CascadeEngine(config);

        CascadeResult first = engine.cascade(g, new NodePos(8, 1, 0)); // disturb the span's anchor end
        assertTrue(first.truncated(), "cap=2 on this cantilever must truncate so there is something to resume");
        Set<NodePos> scope = first.remainingScope();
        assertFalse(scope.isEmpty(), "a truncated cascade must expose a non-empty resume scope");

        // Count ONLY the resume passes (the cascade above ran without metrics).
        StruxMetrics m = new StruxMetrics();
        engine.setMetrics(m);
        for (int guard = 0; guard < 1_000; guard++) {
            CascadeEngine.SettleOutcome out = engine.settleResult(g, scope, SolverCallback.NONE);
            if (!out.truncated() && out.collapsed().isEmpty()) {
                return m.nodeVisits; // settled
            }
            if (!out.remainingScope().isEmpty()) {
                scope = new HashSet<>(out.remainingScope());
            }
        }
        fail("resume did not converge within the guard bound");
        return -1;
    }

    @Test
    @DisplayName("Resume node-visits do not grow with inert terrain (the watchdog fix)")
    void resumeWorkIsTerrainIndependent() {
        long small = resumeWorkOverScope(worldWithTerrain(50));
        long large = resumeWorkOverScope(worldWithTerrain(50_000)); // 1000× the inert terrain

        assertEquals(
                small,
                large,
                "scoped resume must cost the same regardless of terrain size (small=" + small + ", large=" + large
                        + "); a regression here is the whole-graph re-seed that tripped the watchdog");
        assertTrue(small > 0, "the resume should have done real solver work");
    }

    @Test
    @DisplayName("Scoped resume does far less work than the old whole-graph resume")
    void scopedResumeBeatsWholeGraphResume() {
        PhysicsConfig config = new PhysicsConfig();
        config.setMaxCascadeSteps(2);

        // Old behaviour: resume by re-seeding the entire graph.
        StructureGraph wholeWorld = worldWithTerrain(50_000);
        new CascadeEngine(config).cascade(wholeWorld, new NodePos(8, 1, 0));
        StruxMetrics wholeMetrics = new StruxMetrics();
        new CascadeEngine(config)
                .setMetrics(wholeMetrics)
                .settleResult(wholeWorld, new HashSet<>(wholeWorld.getAllPositions()), SolverCallback.NONE);

        long scoped = resumeWorkOverScope(worldWithTerrain(50_000));

        assertTrue(
                scoped < wholeMetrics.nodeVisits,
                "scoped resume (" + scoped + ") must visit far fewer nodes than a whole-graph resume ("
                        + wholeMetrics.nodeVisits + ")");
    }

    @Test
    @DisplayName("Resuming on the carried scope collapses the same blocks as one uncapped settle")
    void resumeMatchesUncappedSettle() {
        // Capped + resumed.
        StructureGraph capped = worldWithTerrain(0);
        PhysicsConfig small = new PhysicsConfig();
        small.setMaxCascadeSteps(2);
        CascadeEngine cappedEngine = new CascadeEngine(small);
        Set<NodePos> scope = cappedEngine.cascade(capped, new NodePos(8, 1, 0)).remainingScope();
        for (int guard = 0; guard < 1_000 && !scope.isEmpty(); guard++) {
            CascadeEngine.SettleOutcome out = cappedEngine.settleResult(capped, scope, SolverCallback.NONE);
            if (!out.truncated() && out.collapsed().isEmpty()) {
                break;
            }
            scope = new HashSet<>(out.remainingScope());
        }

        // Uncapped single settle of the same disturbance.
        StructureGraph uncappedGraph = worldWithTerrain(0);
        PhysicsConfig big = new PhysicsConfig();
        big.setMaxCascadeSteps(1_000);
        new CascadeEngine(big).cascade(uncappedGraph, new NodePos(8, 1, 0));

        assertEquals(
                standing(uncappedGraph),
                standing(capped),
                "the resumed collapse must leave exactly the same blocks standing as one uncapped settle");
    }

    /** The set of non-ground positions still in the graph. */
    private static Set<NodePos> standing(StructureGraph g) {
        Set<NodePos> live = new HashSet<>();
        for (NodePos pos : g.getAllPositions()) {
            if (!g.getNode(pos).isGrounded()) {
                live.add(pos);
            }
        }
        return live;
    }
}
