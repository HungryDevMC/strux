package dev.gesp.structural.scenario;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.metrics.StruxMetrics;
import dev.gesp.structural.model.NodePos;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Deterministic performance gate — the "red" you can refactor against.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │              WHY THESE ASSERTIONS ARE ON COUNTS, NOT TIME           │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  solveInvocations and nodeVisits are deterministic: the same        │
 *   │  structure + trigger always produce the same numbers, on any        │
 *   │  machine. So a budget like "≤ N node visits" is a stable gate —     │
 *   │  it goes red only when the ALGORITHM does more work, which is       │
 *   │  exactly the regression worth catching during a refactor.           │
 *   │                                                                     │
 *   │  Budgets are set ~1.6× the measured value, leaving headroom for     │
 *   │  benign variation while still catching real blow-ups (e.g. a        │
 *   │  refactor that re-solves on every floating block would send the     │
 *   │  full-collapse case from a handful of passes to O(N)).              │
 *   │                                                                     │
 *   │  Wall-clock per run is printed for context but NEVER asserted —     │
 *   │  that belongs in the :benchmarks JMH module.                        │
 *   │                                                                     │
 *   │  When you intentionally change the algorithm's cost, update the     │
 *   │  budget constant in the same commit, with a note on why.            │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
@DisplayName("Performance gate: deterministic work budgets")
class PerformanceGateTest {

    @Test
    @DisplayName("Full-collapse of a tall column stays cheap (floating, not re-solved per block)")
    void columnFullCollapseIsCheap() {
        // 80 blocks all lose support at once. This MUST stay a handful of solver
        // passes — if a refactor re-solves per falling block it becomes O(N).
        StruxMetrics m = measure("perf-column-80-break-base", () -> Structures.column(80), new NodePos(0, 1, 0));

        // Measured: 0 passes / 0 node visits (all 80 collapse as floating, no
        // stress solve). A regression that re-solves per block would be O(N) here.
        assertTrue(m.solveInvocations <= 2, "column full-collapse solver passes regressed: " + m.solveInvocations);
        assertTrue(m.nodeVisits <= 200, "column full-collapse node visits regressed: " + m.nodeVisits);
    }

    @Test
    @DisplayName("Bridge cantilever trim-back stays within its progressive-collapse budget")
    void bridgeProgressiveCollapseBudget() {
        StruxMetrics m = measure("perf-bridge-31-break-pillar", () -> Structures.bridge(31, 4), new NodePos(0, 1, 0));

        // Measured: 5 passes / 120 node visits.
        assertTrue(m.solveInvocations <= 8, "bridge solver passes regressed: " + m.solveInvocations);
        assertTrue(m.nodeVisits <= 250, "bridge node visits regressed: " + m.nodeVisits);
    }

    @Test
    @DisplayName("Knocking out a base block of a long wall settles within budget")
    void wallBreakBudget() {
        StruxMetrics m = measure("perf-wall-41x10-break-base", () -> Structures.wall(41, 10), new NodePos(20, 1, 0));

        // Measured: 1 pass / 450 node visits (one settle pass confirms stability).
        assertTrue(m.solveInvocations <= 3, "wall solver passes regressed: " + m.solveInvocations);
        assertTrue(m.nodeVisits <= 900, "wall node visits regressed: " + m.nodeVisits);
    }

    @Test
    @DisplayName("Blasting a solid tower settles within budget")
    void towerBlastBudget() {
        StructureGraph tower = Structures.tower(6, 6, 10);
        long t0 = System.nanoTime();
        ScenarioOutcome out = Scenario.on(tower).blast(new NodePos(0, 10, 0), 6.0);
        report("perf-tower-6x6x10-blast", out.metrics(), System.nanoTime() - t0);

        StruxMetrics m = out.metrics();
        // Measured: 1 pass / 162 node visits (most removal is direct blast +
        // floating; one stress solve confirms the remainder holds).
        assertTrue(m.solveInvocations <= 4, "tower blast solver passes regressed: " + m.solveInvocations);
        assertTrue(m.nodeVisits <= 400, "tower blast node visits regressed: " + m.nodeVisits);
    }

    @Test
    @DisplayName("Ramming through a solid tower settles within budget")
    void towerImpactBudget() {
        StructureGraph tower = Structures.tower(6, 6, 10);
        long t0 = System.nanoTime();
        ScenarioOutcome out = Scenario.on(tower).impact(new NodePos(0, 5, 0), 1, 0, 0, 40.0);
        report("perf-tower-6x6x10-impact", out.metrics(), System.nanoTime() - t0);

        StruxMetrics m = out.metrics();
        // A single impact bores at most impactMaxPenetration blocks, then one
        // settle pass confirms the rest holds. Must NOT re-solve per bored block.
        assertTrue(m.solveInvocations <= 4, "tower impact solver passes regressed: " + m.solveInvocations);
        assertTrue(m.nodeVisits <= 600, "tower impact node visits regressed: " + m.nodeVisits);
    }

    // ─────────────────────────────────────────────────────────────────────

    /** Build the structure (untimed), then run+time the break, print, return metrics. */
    private static StruxMetrics measure(String name, Supplier<StructureGraph> build, NodePos breakAt) {
        StructureGraph graph = build.get();
        long t0 = System.nanoTime();
        ScenarioOutcome out = Scenario.on(graph).breakAt(breakAt);
        report(name, out.metrics(), System.nanoTime() - t0);
        return out.metrics();
    }

    private static void report(String name, StruxMetrics m, long nanos) {
        System.out.printf(
                "[perf] %-32s solvePasses=%-4d nodeVisits=%-7d removed=%-5d  (%.2f ms)%n",
                name, m.solveInvocations, m.nodeVisits, m.blocksRemoved, nanos / 1_000_000.0);
    }
}
