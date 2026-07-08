package dev.gesp.structural.bench;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.StressSolver;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Demonstrates the region-scoping win: solving ONE structure's component should
 * cost the same no matter how many OTHER structures share the world graph, while
 * solving the whole world grows linearly with the structure count.
 *
 * <pre>
 *   solveOneComponent  → flat across `structures` (only ever touches one build)
 *   solveWholeWorld    → grows linearly (the old "recompute everything" cost)
 * </pre>
 *
 * Non-mutating (a solve only writes per-node stress), so the same world graph is
 * reused every invocation — no copy/rebuild noise, just the solver cost.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class ScopedSolveBenchmark {

    @Param({"1", "50", "200"})
    public int structures;

    private StructureGraph world;
    private Set<NodePos> oneComponent;
    private final StressSolver solver = new StressSolver();

    @Setup(Level.Trial)
    public void setUp() {
        world = Builds.manyColumns(structures, 20);
        oneComponent = world.componentOf(new NodePos(0, 1, 0));
    }

    /** Scoped solve of a single structure — should be flat across `structures`. */
    @Benchmark
    public StructureGraph solveOneComponent() {
        solver.solve(world, oneComponent);
        return world;
    }

    /** The old whole-world solve — should grow linearly with `structures`. */
    @Benchmark
    public StructureGraph solveWholeWorld() {
        solver.solveAll(world);
        return world;
    }
}
