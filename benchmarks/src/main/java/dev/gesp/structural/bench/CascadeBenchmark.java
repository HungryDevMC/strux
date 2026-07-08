package dev.gesp.structural.bench;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import dev.gesp.structural.solver.CascadeResult;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Cost of a full cascade (break a support, let the structure settle). Cascade
 * mutates the graph, so each invocation works on a fresh {@link StructureGraph#copy()}
 * of a template built once per trial. The copy is cheap and constant relative to
 * the solve work, and is included in the measurement on purpose (it is real work
 * the adapter does too when it snapshots a region).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class CascadeBenchmark {

    private StructureGraph columnTemplate;
    private StructureGraph bridgeTemplate;
    private StructureGraph siegeSmallTemplate;
    private StructureGraph siegeLargeTemplate;
    private final CascadeEngine engine = new CascadeEngine();

    @Setup(Level.Trial)
    public void setUp() {
        columnTemplate = Builds.column(40);
        bridgeTemplate = Builds.bridge(21, 4);
        // Siege terrain: 50x50 terrain with 5x5x15 tower
        siegeSmallTemplate = Builds.siegeTerrain(50, 5, 15);
        // Siege terrain: 200x200 terrain with 5x5x15 tower
        siegeLargeTemplate = Builds.siegeTerrain(200, 5, 15);
    }

    /** Whole 40-tall column drops as floating debris (cheap settle, bulk removal). */
    @Benchmark
    public CascadeResult columnFullCollapse() {
        return engine.cascade(columnTemplate.copy(), new NodePos(0, 1, 0));
    }

    /** Knock out a pillar: the deck cantilevers and trims back (progressive solve). */
    @Benchmark
    public CascadeResult bridgeTrimBack() {
        return engine.cascade(bridgeTemplate.copy(), new NodePos(0, 1, 0));
    }

    /**
     * Break a block mid-tower on 50x50 terrain. Tests scoped cascade performance
     * with connected terrain (the key siege optimization scenario).
     * Tower is centered at (22,22) to (26,26), so break at (24,5,24).
     */
    @Benchmark
    public CascadeResult siegeTowerSmall() {
        // Break a block in the middle of the tower: (50-5)/2 + 2 = 24
        return engine.cascade(siegeSmallTemplate.copy(), new NodePos(24, 5, 24));
    }

    /**
     * Break a block mid-tower on 200x200 terrain. Tests that terrain size doesn't
     * affect cascade performance (scoped solve should bound work to tower size).
     * Tower is centered at (97,97) to (101,101), so break at (99,5,99).
     */
    @Benchmark
    public CascadeResult siegeTowerLarge() {
        // Break a block in the middle of the tower: (200-5)/2 + 2 = 99
        return engine.cascade(siegeLargeTemplate.copy(), new NodePos(99, 5, 99));
    }
}
