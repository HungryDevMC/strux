package dev.gesp.structural.bench;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.solver.StressSolver;
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
 * Cost of one full stress solve over a structure. Non-mutating (it only writes
 * per-node stress, not graph topology), so the same graph is reused — this
 * isolates the solver's per-pass cost, which is what the cascade/blast loops
 * call repeatedly.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class SolverBenchmark {

    @Param({"4", "10"})
    public int height;

    private StructureGraph graph;
    private final StressSolver solver = new StressSolver();

    @Setup(Level.Trial)
    public void setUp() {
        graph = Builds.tower(5, 5, height);
    }

    @Benchmark
    public StructureGraph solveAll() {
        solver.solveAll(graph);
        return graph;
    }
}
