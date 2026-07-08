package dev.gesp.structural.metrics;

/**
 * A plain, mutable bag of work-counters for the physics engine.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                    WHY COUNT WORK, NOT TIME?                        │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Wall-clock timing is noisy: it changes with the machine, the CPU  │
 *   │  governor, other processes, JIT warmup. A test that asserts        │
 *   │  "under 2ms" is green on your laptop and red on CI for no real     │
 *   │  reason.                                                            │
 *   │                                                                     │
 *   │  These counters are DETERMINISTIC. The same structure + the same   │
 *   │  trigger always produce the same numbers, on any machine. So they  │
 *   │  make a stable red/green gate: if a refactor makes the algorithm   │
 *   │  do MORE work (more solver passes, more node visits), the counter  │
 *   │  goes up and the gate fails — that is exactly the regression you   │
 *   │  want to catch.                                                    │
 *   │                                                                     │
 *   │  For real ns/op numbers (per-pass cost), see the :benchmarks JMH   │
 *   │  module. Counters catch algorithmic regressions; JMH catches       │
 *   │  per-operation cost. Together they cover "tune this".              │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>Attaching a metrics object is entirely optional. The engine classes hold a
 * nullable reference and only touch it when present, so the production path is
 * unaffected — nothing allocates or counts unless a test/benchmark asks for it.
 */
public final class StruxMetrics {

    /**
     * How many times the stress solver ran a full pass
     * ({@code solve} / {@code solveProgressively}). A cascade or blast triggers
     * one solve per settle step, so this tracks how many times the structure had
     * to be re-evaluated before it stabilised.
     */
    public long solveInvocations;

    /**
     * Cumulative number of nodes the solver iterated over, summed across every
     * pass. This is the closest cheap proxy for "total CPU work": it scales with
     * both structure size and the number of passes (so an O(N²) collapse on a
     * tall column shows up clearly).
     */
    public long nodeVisits;

    /**
     * How many blocks the engines removed from the graph (the collapse trigger,
     * plus every block that fell or was shattered).
     */
    public long blocksRemoved;

    /**
     * Fold another counter's totals into this one. Used to sum the per-worker
     * counts back together after a parallel settle (see
     * {@link dev.gesp.structural.solver.ParallelCascadeDriver}); summation is
     * commutative, so the merged totals do not depend on which worker finished
     * first. No-op if {@code other} is null.
     */
    public void add(StruxMetrics other) {
        if (other == null) {
            return;
        }
        solveInvocations += other.solveInvocations;
        nodeVisits += other.nodeVisits;
        blocksRemoved += other.blocksRemoved;
    }

    /** Zero every counter so the same object can be reused across runs. */
    public void reset() {
        solveInvocations = 0;
        nodeVisits = 0;
        blocksRemoved = 0;
    }

    @Override
    public String toString() {
        return "StruxMetrics{solveInvocations=" + solveInvocations
                + ", nodeVisits=" + nodeVisits
                + ", blocksRemoved=" + blocksRemoved + '}';
    }
}
