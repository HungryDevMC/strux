package dev.gesp.structural.bench;

import dev.gesp.structural.blast.BlastContext;
import dev.gesp.structural.blast.BlastOcclusion;
import dev.gesp.structural.blast.BlastResult;
import dev.gesp.structural.blast.StruxExplosionEngine;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.NodePos;
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
 * Cost of a full explosion (direct blast + occlusion + gravity cascade) on a
 * solid tower. Mutates the graph, so each invocation runs on a fresh copy of a
 * template built once per trial.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class BlastBenchmark {

    @Param({"NONE", "RAYCAST"})
    public BlastOcclusion occlusion;

    private StructureGraph towerTemplate;
    private final StruxExplosionEngine engine = new StruxExplosionEngine();

    @Setup(Level.Trial)
    public void setUp() {
        towerTemplate = Builds.tower(6, 6, 10);
    }

    @Benchmark
    public BlastResult towerBlast() {
        BlastContext ctx = BlastContext.builder()
                .center(new NodePos(0, 10, 0))
                .power(6.0)
                .occlusion(occlusion)
                .build();
        return engine.process(towerTemplate.copy(), ctx);
    }
}
