package dev.gesp.structural.scenario;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.metrics.StruxMetrics;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Performance tests for large terrain scenarios (siege gamemode).
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                    TERRAIN PERFORMANCE TESTS                        │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  PROBLEM: Siege gamemode has ~140k connected terrain blocks.        │
 *   │  Breaking one block triggers a cascade check that must process      │
 *   │  the entire connected component - causing server lag.               │
 *   │                                                                     │
 *   │  SOLUTION: Flat terrain at y=1 (directly above ground) is           │
 *   │  trivially stable: nothing is above it, and it can never float.     │
 *   │  Skip stress calculation for these blocks entirely.                 │
 *   │                                                                     │
 *   │  TARGET PERFORMANCE:                                                │
 *   │  - Breaking a flat terrain block: < 1ms (currently O(N) → ~100ms)   │
 *   │  - Breaking a structure ON terrain: proportional to structure size  │
 *   │  - Full terrain solve: O(N) but only when explicitly requested      │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
@DisplayName("Terrain Performance: Large connected terrain scenarios")
@Tag("performance")
class TerrainPerformanceTest {

    // ─────────────────────────────────────────────────────────────────────
    //  BASELINE TESTS — measure current performance
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Baseline: 100x100 terrain (10k blocks) - break edge block")
    void baseline_10k_terrain_break_edge() {
        StructureGraph graph = Structures.terrain(100, 100, 1);
        assertEquals(20_000, graph.size(), "Should have ground + terrain");

        // Break edge block - should NOT cascade (terrain is stable)
        NodePos breakPos = new NodePos(0, 1, 0);
        StruxMetrics m = new StruxMetrics();
        CascadeEngine engine = new CascadeEngine().setMetrics(m);

        long t0 = System.nanoTime();
        var result = engine.cascade(graph, breakPos);
        long elapsed = System.nanoTime() - t0;

        report("10k-terrain-break-edge", m, elapsed, graph.size());

        assertFalse(result.hadCascade(), "Flat terrain should not cascade");
        assertTrue(m.nodeVisits <= 100, "Should visit very few nodes: " + m.nodeVisits);
    }

    @Test
    @DisplayName("Baseline: 100x100 terrain (10k blocks) - break center block")
    void baseline_10k_terrain_break_center() {
        StructureGraph graph = Structures.terrain(100, 100, 1);

        NodePos breakPos = new NodePos(50, 1, 50);
        StruxMetrics m = new StruxMetrics();
        CascadeEngine engine = new CascadeEngine().setMetrics(m);

        long t0 = System.nanoTime();
        var result = engine.cascade(graph, breakPos);
        long elapsed = System.nanoTime() - t0;

        report("10k-terrain-break-center", m, elapsed, graph.size());

        assertFalse(result.hadCascade(), "Flat terrain should not cascade");
        assertTrue(m.nodeVisits <= 100, "Should visit very few nodes: " + m.nodeVisits);
    }

    @Test
    @DisplayName("Baseline: 200x200 terrain (40k blocks) - break block")
    void baseline_40k_terrain() {
        StructureGraph graph = Structures.terrain(200, 200, 1);
        assertEquals(80_000, graph.size());

        NodePos breakPos = new NodePos(100, 1, 100);
        StruxMetrics m = new StruxMetrics();
        CascadeEngine engine = new CascadeEngine().setMetrics(m);

        long t0 = System.nanoTime();
        var result = engine.cascade(graph, breakPos);
        long elapsed = System.nanoTime() - t0;

        report("40k-terrain-break", m, elapsed, graph.size());

        assertFalse(result.hadCascade(), "Flat terrain should not cascade");
        // Budget: for trivially stable terrain, node visits should be minimal
        assertTrue(m.nodeVisits <= 200, "Should visit very few nodes: " + m.nodeVisits);
    }

    @Test
    @DisplayName("Baseline: 375x375 terrain (~140k blocks) - break block")
    void baseline_140k_terrain() {
        // This simulates the siege gamemode scenario
        StructureGraph graph = Structures.terrain(375, 375, 1);
        int size = graph.size();
        assertTrue(size >= 280_000, "Should have ~280k blocks (ground + terrain)");

        NodePos breakPos = new NodePos(187, 1, 187);
        StruxMetrics m = new StruxMetrics();
        CascadeEngine engine = new CascadeEngine().setMetrics(m);

        long t0 = System.nanoTime();
        var result = engine.cascade(graph, breakPos);
        long elapsed = System.nanoTime() - t0;

        report("140k-terrain-break", m, elapsed, size);

        assertFalse(result.hadCascade(), "Flat terrain should not cascade");
        // This is the critical budget: breaking a terrain block should NOT visit
        // the entire 140k component
        // The deterministic nodeVisits budget below is the real O(N)-regression guard.
        // Wall-clock is printed for context (see report() above) but NEVER asserted:
        // time-based bounds are machine/load dependent and flake under the PIT minions
        // and parallel worktree builds that run this suite. Any real ns/op expectation
        // belongs in the :benchmarks JMH module, not the always-on :core:test suite.
        assertTrue(
                m.nodeVisits <= 500,
                "Breaking terrain should visit minimal nodes, not " + m.nodeVisits + " (size=" + size + ")");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  STRUCTURE ON TERRAIN — tests for structures built on large terrain
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Structure on terrain: break terrain under tower should cascade tower only")
    void structure_on_terrain_break_support() {
        // 200x200 terrain with a 4x4x10 tower in the center
        StructureGraph graph = Structures.terrainWithStructure(200, 200, 4, 10);
        int size = graph.size();

        // Break the terrain block directly under the tower
        int centerX = 100;
        int centerZ = 100;
        NodePos breakPos = new NodePos(centerX, 1, centerZ);

        StruxMetrics m = new StruxMetrics();
        CascadeEngine engine = new CascadeEngine().setMetrics(m);

        long t0 = System.nanoTime();
        var result = engine.cascade(graph, breakPos);
        long elapsed = System.nanoTime() - t0;

        report("terrain-with-tower-break-support", m, elapsed, size);

        // The tower portion above should cascade, but NOT the entire terrain
        // Tower is 4×4×10 = 160 blocks
        assertTrue(
                result.totalCollapsed() <= 200, "Should only collapse tower portion, not " + result.totalCollapsed());
        assertTrue(
                m.nodeVisits <= 2000,
                "Should visit tower + local terrain only, not " + m.nodeVisits + " (size=" + size + ")");
    }

    @Test
    @DisplayName("Structure on terrain: break tower block should not affect terrain")
    void structure_on_terrain_break_tower() {
        StructureGraph graph = Structures.terrainWithStructure(200, 200, 4, 10);

        // Break a block in the middle of the tower
        int towerX = 100;
        int towerZ = 100;
        int towerMidY = 7; // tower sits at y=2..11
        NodePos breakPos = new NodePos(towerX, towerMidY, towerZ);

        StruxMetrics m = new StruxMetrics();
        CascadeEngine engine = new CascadeEngine().setMetrics(m);

        long t0 = System.nanoTime();
        var result = engine.cascade(graph, breakPos);
        long elapsed = System.nanoTime() - t0;

        report("terrain-with-tower-break-mid", m, elapsed, graph.size());

        // Only upper part of tower should cascade
        assertTrue(result.totalCollapsed() <= 100, "Should only collapse upper tower, not " + result.totalCollapsed());
        assertTrue(m.nodeVisits <= 500, "Should visit small area: " + m.nodeVisits);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  COMPONENT SIZE TESTS — verify scoped solving bounds work correctly
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Component detection should be O(1) for flat terrain break")
    void component_detection_cost() {
        StructureGraph graph = Structures.terrain(200, 200, 1);
        NodePos pos = new NodePos(100, 1, 100);

        // This tests the getDependentSubgraph fast path
        long t0 = System.nanoTime();
        Set<NodePos> dependents = graph.getDependentSubgraph(pos);
        long elapsed = System.nanoTime() - t0;

        System.out.printf(
                "[perf] getDependentSubgraph(flat terrain 80k): %d dependents in %.2f ms%n",
                dependents.size(), elapsed / 1_000_000.0);

        // For flat terrain, nothing is above, so dependents should be just the block itself
        assertEquals(1, dependents.size(), "Flat terrain block has no dependents above");
    }

    // ─────────────────────────────────────────────────────────────────────

    private static void report(String name, StruxMetrics m, long nanos, int graphSize) {
        System.out.printf(
                "[perf] %-35s size=%-7d solvePasses=%-4d nodeVisits=%-7d removed=%-5d  (%.2f ms)%n",
                name, graphSize, m.solveInvocations, m.nodeVisits, m.blocksRemoved, nanos / 1_000_000.0);
    }
}
