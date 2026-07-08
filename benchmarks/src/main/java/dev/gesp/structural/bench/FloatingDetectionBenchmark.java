package dev.gesp.structural.bench;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.NodePos;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
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
 * The Lever-B win: on a large CONNECTED terrain, "what's floating?" after an edit
 * no longer re-floods the whole world.
 *
 * <pre>
 *   floatingViaIndex → StructureGraph.getFloatingBlocks(), backed by the maintained
 *                      ChunkConnectivity index: the edit re-flushes only its chunk,
 *                      reachability runs over the ports (≈ cells/chunk³).
 *   floatingViaBfs   → the old approach: a fresh whole-graph flood-fill from bedrock
 *                      every query, O(N + E).
 * </pre>
 *
 * Both churn one interior block (remove + re-add, net-no-op on a dense slab so the
 * graph is identical each invocation) to model the realistic "edit then re-check"
 * pattern, and both pay the always-on index maintenance — the benchmark isolates the
 * query strategy. The gap widens with terrain size.
 *
 * <pre>
 *   Measured (same machine, -f1 -wi3 -i5), width×width×4 connected grounded slab:
 *
 *   WHOLE-GRAPH "what's floating?" (getFloatingBlocks):
 *     width   blocks      BFS (old)   index (new)   speedup
 *      20      ~2,000        200 µs       208 µs      0.96×   (overhead lost in noise)
 *      40      ~8,000      1,008 µs       387 µs      2.6×
 *      60     ~14,000      2,858 µs       946 µs      3.0×
 *   The index avoids the flood, but getFloatingBlocks still examines every node, so it
 *   stays O(N): the constant shrinks, not the asymptote.
 *
 *   SINGLE-BLOCK "is THIS one grounded?" (isConnectedToGround) — the case incremental
 *   reachability is for:
 *     width   blocks      whole-scan    index        speedup
 *      20      ~2,000        201 µs       147 µs      1.4×
 *      40      ~8,000      1,044 µs       148 µs      7×
 *      60     ~14,000      2,835 µs       143 µs      ~20×
 *   The index is FLAT in terrain size (O(1) amortized: cached reachable set + one
 *   re-flushed chunk); the whole-scan grows O(N+E). The gap keeps widening with size.
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class FloatingDetectionBenchmark {

    /** Terrain edge length: builds a width×width×4 connected grounded slab. */
    @Param({"20", "40", "60"})
    public int width;

    private StructureGraph terrain;
    private NodePos churn;
    private NodePos query;

    @Setup(Level.Trial)
    public void setUp() {
        terrain = Builds.tower(width, width, 4); // width×width×(4+1) connected, grounded base
        churn = new NodePos(width / 2, 2, width / 2); // an interior block to flip each invocation
        query = new NodePos(0, 4, 0); // a single block to ask about
        terrain.getFloatingBlocks(); // warm the index once so steady-state is measured
    }

    /** Index-backed: the edit only dirties one chunk; the query flushes that + the ports. */
    @Benchmark
    public Set<NodePos> floatingViaIndex() {
        terrain.removeBlock(churn);
        terrain.addBlock(churn, Builds.HEAVY, false);
        return terrain.getFloatingBlocks();
    }

    /** Old approach: a fresh whole-graph BFS from bedrock on every query, O(N + E). */
    @Benchmark
    public Set<NodePos> floatingViaBfs() {
        terrain.removeBlock(churn);
        terrain.addBlock(churn, Builds.HEAVY, false);
        return bfsFloating(terrain);
    }

    /**
     * The single-block case incremental reachability is for: "is THIS block still
     * grounded?" after an edit. The index reuses its cached reachable set (the edit
     * dirties one chunk; if it does not change connectivity the reachability sweep is
     * skipped), so this is roughly O(chunk) — independent of terrain size.
     */
    @Benchmark
    public boolean singleBlockViaIndex() {
        terrain.removeBlock(churn);
        terrain.addBlock(churn, Builds.HEAVY, false);
        return terrain.isConnectedToGround(query);
    }

    /** The only way to answer that before: compute the whole floating set, O(N + E). */
    @Benchmark
    public boolean singleBlockViaWholeScan() {
        terrain.removeBlock(churn);
        terrain.addBlock(churn, Builds.HEAVY, false);
        return !bfsFloating(terrain).contains(query);
    }

    /** A from-scratch whole-graph flood-fill from bedrock, ignoring the index. */
    private static Set<NodePos> bfsFloating(StructureGraph g) {
        Set<NodePos> reachable = new HashSet<>(g.getGroundNodes());
        Deque<NodePos> queue = new ArrayDeque<>(reachable);
        while (!queue.isEmpty()) {
            NodePos cur = queue.poll();
            for (NodePos n : g.getNeighbors(cur)) {
                if (reachable.add(n)) {
                    queue.add(n);
                }
            }
        }
        Set<NodePos> floating = new HashSet<>(g.getAllPositions());
        floating.removeAll(reachable);
        return floating;
    }
}
