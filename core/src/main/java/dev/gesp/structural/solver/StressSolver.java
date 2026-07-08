package dev.gesp.structural.solver;

import dev.gesp.structural.api.DebugCapture;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.metrics.StruxMetrics;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Calculates stress values for blocks in a structure.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                      HOW THE SOLVER WORKS                          │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  PHYSICS MODEL: totalStress = verticalStress + momentStress        │
 *   │                                                                     │
 *   │  Every block always computes both components and sums them.        │
 *   │  No special cases, no binary gates.                                │
 *   │                                                                     │
 *   │  VERTICAL STRESS (existing BFS pass):                              │
 *   │    - Each block carries its own weight                             │
 *   │    - Plus weighted share of everything above it                    │
 *   │    - Shorter paths to ground carry more load                       │
 *   │                                                                     │
 *   │  MOMENT STRESS (new second pass):                                  │
 *   │    - "Bucket at arm's length" force                                │
 *   │    - When a block supports a horizontal arm with no pillar         │
 *   │    - moment = weight × distance (linear, not exponential)          │
 *   │                                                                     │
 *   │                                                                     │
 *   │  TWO PILLARS WITH BRIDGE + ARM:                                    │
 *   │                                                                     │
 *   │         [ARM]──[D]──[BRIDGE]──[BRIDGE]                             │
 *   │                 │                │                                 │
 *   │              [PILLAR]         [PILLAR]                             │
 *   │                 │                │                                 │
 *   │              [GND]            [GND]                                │
 *   │                                                                     │
 *   │  Block D has BOTH:                                                 │
 *   │    - verticalStress from bridge load flowing through               │
 *   │    - momentStress from the cantilevered ARM                        │
 *   │                                                                     │
 *   │  D.total = D.vertical + D.moment                                   │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class StressSolver {

    private final PhysicsConfig config;

    /**
     * Optional work-counter. Null on the production path (no counting); a test or
     * benchmark attaches one via {@link #setMetrics} to observe how much the
     * solver does. See {@link StruxMetrics}.
     */
    private StruxMetrics metrics;

    /**
     * Optional engine-debug capture sink. {@link DebugCapture#NONE} on the production
     * path (no capture, no cost); a re-simulation consumer attaches a real one via
     * {@link #setDebugCapture} to harvest the stress split, ground distances and
     * load-flow edges this solver computes and would otherwise discard. Every emit is
     * guarded by {@link DebugCapture#wantsDebugCapture()}, so the no-capture path is
     * byte-identical to having no sink at all.
     */
    private DebugCapture debugCapture = DebugCapture.NONE;

    /** True only while solving the settle (final) pass, so load-flow edges are kept once per event. */
    private boolean finalPass;

    /**
     * Reusable per-solve scratch for {@link #calculateLoadShare}'s denominator cache.
     * Cleared (not reallocated) at the start of each hot-path pass ({@link #solve} and
     * {@link #findOverloadedBatch}) so a hot cascade — or a benchmark that re-solves the
     * same world every invocation — stops paying a fresh {@code Object2DoubleOpenHashMap}
     * allocation (plus its grow/rehash) every pass.
     *
     * <p>This makes a {@code StressSolver} stateful across a pass, so a single instance
     * must NOT be shared across threads. It isn't: {@link CascadeEngine} owns one solver
     * and {@link dev.gesp.structural.solver.ParallelCascadeDriver} gives every worker its
     * own {@link CascadeEngine} (hence its own solver) on an isolated graph copy. The
     * cache is only ever read within the pass that just cleared and filled it, so results
     * are bit-identical to allocating it fresh.
     */
    private final Object2DoubleOpenHashMap<NodePos> denomCache = new Object2DoubleOpenHashMap<>();

    /**
     * Create a solver with default physics config.
     */
    public StressSolver() {
        this(new PhysicsConfig());
    }

    /**
     * Create a solver with custom physics config.
     */
    public StressSolver(PhysicsConfig config) {
        this.config = config;
    }

    /**
     * Attach (or detach, with {@code null}) a work-counter. Optional; only
     * tests/benchmarks need this. Returns {@code this} for chaining.
     */
    public StressSolver setMetrics(StruxMetrics metrics) {
        this.metrics = metrics;
        return this;
    }

    /**
     * Attach (or detach, with {@code null}) an engine-debug capture sink. Optional;
     * only a re-simulation debug consumer needs this. Returns {@code this} for chaining.
     */
    public StressSolver setDebugCapture(DebugCapture debugCapture) {
        this.debugCapture = debugCapture == null ? DebugCapture.NONE : debugCapture;
        return this;
    }

    /**
     * Mark whether the next {@link #solve} call is the event's settle (final) pass —
     * the one whose load-flow edges are worth keeping. The cascade driver flips this
     * to {@code true} when it re-solves after the structure has stopped collapsing.
     * No effect unless a {@link DebugCapture} is attached and wants load-flow.
     */
    public StressSolver setFinalPass(boolean finalPass) {
        this.finalPass = finalPass;
        return this;
    }

    /**
     * Calculate stress for all blocks in the given subgraph.
     *
     * After calling this, each Node in the subgraph will have
     * its stressValue updated (verticalStress + momentStress).
     *
     * @param graph    the structure graph
     * @param subgraph positions to evaluate (usually from getDependentSubgraph)
     */
    public void solve(StructureGraph graph, Set<NodePos> subgraph) {
        if (subgraph.isEmpty()) {
            return;
        }

        // calculateDistances seeds its BFS only from grounded nodes INSIDE the subgraph.
        // A subgraph that excludes its own support — e.g. getDependentSubgraph returns
        // only the upward/dependent cone and never a grounded node — would leave every
        // block at distance MAX_VALUE, so the vertical/moment passes add nothing and the
        // solve silently writes stress = own mass over the previously-correct values.
        // Widen an unanchored subgraph to its structural closure, which walks the support
        // columns down to ground (a no-op when the subgraph is already anchored, or when
        // the component is genuinely floating — there is no ground to find either way).
        if (!containsGrounded(graph, subgraph)) {
            subgraph = graph.affectedRegion(subgraph);
        }

        if (metrics != null) {
            metrics.solveInvocations++;
            metrics.nodeVisits += subgraph.size();
        }

        boolean capturing = debugCapture.wantsDebugCapture();
        boolean captureFlow = capturing && finalPass && debugCapture.wantsLoadFlow();
        if (capturing) {
            debugCapture.beginPass(finalPass);
        }

        // Step 1: Calculate distance from ground for each block
        Object2IntOpenHashMap<NodePos> distanceFromGround = calculateDistances(graph, subgraph);

        if (capturing) {
            emitGroundDistances(subgraph, distanceFromGround);
        }

        // Step 2: Sort blocks by distance descending (farthest first)
        List<NodePos> sorted = new ArrayList<>(subgraph);
        sorted.sort((a, b) -> {
            int distA = distanceFromGround.getInt(a);
            int distB = distanceFromGround.getInt(b);
            return Integer.compare(distB, distA); // Descending
        });

        // Step 3: Reset all stress values
        for (NodePos pos : subgraph) {
            Node node = graph.getNode(pos);
            if (node != null) {
                node.resetStress();
            }
        }

        // Per-pass cache of each node's load-share denominator (see denominatorFor).
        // Reused across passes — cleared, not reallocated.
        denomCache.clear();

        // Step 4: VERTICAL PASS - process from farthest to closest
        //
        // The out-of-scope neighbour guard below only matters for a PARTIAL scope;
        // when the subgraph covers the whole graph every neighbour is trivially in
        // scope, so skip the per-edge set lookup entirely (solveAll / whole-world
        // solves are the hot unscoped case).
        //
        // size() is a sound coverage test because every caller passes a position set
        // drawn from this graph: solveAll -> getAllPositions(), the adapter's scoped
        // solve -> a dependent/affected region, and the widening above -> affectedRegion.
        // All are subsets of the graph's own key set, so |subgraph| <= |graph| always,
        // with equality iff the subgraph IS the whole graph. Hence scoped == false picks
        // out exactly the whole-graph case where the guard is a proven no-op.
        final boolean scoped = subgraph.size() < graph.size();
        for (NodePos pos : sorted) {
            Node node = graph.getNode(pos);
            if (node == null) {
                continue;
            }

            // Ground absorbs everything - stress stays 0
            if (node.isGrounded()) {
                continue;
            }

            // Start with own mass
            double verticalStress = node.mass();

            // Add load from neighbors that are FARTHER from ground
            int myDistance = distanceFromGround.getInt(pos);

            for (NodePos neighborPos : graph.neighborsOf(pos)) {
                // Only integrate load from neighbours INSIDE the solved subgraph. A
                // neighbour outside it was never reset/computed this pass: its distance
                // is absent (defaults to MAX_VALUE, so it falsely reads as "farther from
                // ground") and its verticalStress is STALE from an earlier solve. Adding
                // it funnels phantom weight into a partially-scoped structure — e.g. a
                // block placed on a curtain-wall ring, whose support-chain scope excludes
                // the ring's lateral members, collapsing wall a whole-structure solve
                // says is fine. In-scope floaters stay eligible (they carry MAX_VALUE too
                // but ARE in the subgraph), so a closed scope is unaffected.
                if (scoped && !subgraph.contains(neighborPos)) {
                    continue;
                }
                int neighborDistance = distanceFromGround.getInt(neighborPos);

                // Only receive load from blocks farther from ground
                if (neighborDistance > myDistance) {
                    Node neighbor = graph.getNode(neighborPos);
                    if (neighbor != null) {
                        // Calculate our weighted share of the neighbor's VERTICAL stress
                        double share = calculateLoadShare(graph, neighborPos, pos, distanceFromGround, denomCache);
                        if (share > 0) {
                            // Pure vertical transfer - no amplification here
                            // Moment stress is computed separately in the moment pass
                            double absoluteLoad = neighbor.verticalStress() * share;
                            verticalStress += absoluteLoad;
                            if (captureFlow) {
                                // Edge: load flows DOWN from the farther neighbor to this
                                // strictly-closer supporter. (from = higher, to = supporter).
                                debugCapture.onLoadFlowEdge(neighborPos, pos, share, absoluteLoad);
                            }
                        }
                    }
                }
            }

            node.setVerticalStress(verticalStress);
        }

        // Step 5: MOMENT PASS - compute moment stress for each block
        boolean captureArms = capturing && finalPass && debugCapture.wantsMomentArms();
        computeMomentStress(graph, subgraph, distanceFromGround, captureArms);

        if (capturing) {
            emitStressComponents(graph, subgraph);
            debugCapture.endPass();
        }
    }

    /**
     * Push the (debug-only) distance-from-ground field to the capture sink. Builds a
     * plain {@code Map} snapshot so the sink can keep it without aliasing the solver's
     * scratch; absent nodes are emitted as {@link Integer#MAX_VALUE} (floaters), the
     * same value the BFS map's default return value gives.
     */
    private void emitGroundDistances(Set<NodePos> subgraph, Object2IntOpenHashMap<NodePos> distances) {
        Map<NodePos, Integer> snapshot = new HashMap<>(subgraph.size() * 2);
        for (NodePos pos : subgraph) {
            snapshot.put(pos, distances.getInt(pos));
        }
        debugCapture.onGroundDistances(snapshot);
    }

    /**
     * Push the (debug-only) vertical/moment stress split for every settled non-ground
     * node to the capture sink, after both passes have written their components. Pure
     * reads of already-computed {@link Node} state — never mutates physics.
     */
    private void emitStressComponents(StructureGraph graph, Set<NodePos> subgraph) {
        for (NodePos pos : subgraph) {
            Node node = graph.getNode(pos);
            if (node == null || node.isGrounded()) {
                continue;
            }
            debugCapture.onStressComponents(
                    pos, node.verticalStress(), node.momentStress(), node.effectiveMaxLoad(), node.stressPercent());
        }
    }

    /** Does {@code subgraph} contain at least one grounded node (a distance-BFS seed)? */
    private static boolean containsGrounded(StructureGraph graph, Set<NodePos> subgraph) {
        for (NodePos pos : subgraph) {
            Node node = graph.getNode(pos);
            if (node != null && node.isGrounded()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Calculate stress for ALL blocks in the graph.
     */
    public void solveAll(StructureGraph graph) {
        solve(graph, graph.getAllPositions());
    }

    /**
     * Progressive stress calculation that stops at the first overloaded block.
     *
     * <pre>
     *   ┌─────────────────────────────────────────────────────────────────────┐
     *   │                   PROGRESSIVE COLLAPSE LOGIC                        │
     *   ├─────────────────────────────────────────────────────────────────────┤
     *   │                                                                     │
     *   │  PROBLEM: When pillar A fails, the solver calculates that ALL      │
     *   │  bridge load must go to pillar B. But the bridge should break      │
     *   │  near pillar A first, preventing full load from reaching B.        │
     *   │                                                                     │
     *   │  SOLUTION: Process blocks by distance level, check for failures    │
     *   │  at each level. Overloaded blocks fail BEFORE their load can       │
     *   │  propagate further.                                                │
     *   │                                                                     │
     *   │  EXAMPLE:                                                          │
     *   │                                                                     │
     *   │      dist=6   dist=5   dist=4   dist=3   dist=2   dist=1          │
     *   │      [BR_1]───[BR_2]───[BR_3]───[BR_4]───[BR_5]───[BR_6]          │
     *   │        X                                           │              │
     *   │   (pillar A                                      [TOP_B]          │
     *   │    failed)                                         │              │
     *   │                                                  [MID_B]          │
     *   │                                                    │              │
     *   │                                                   [GND]           │
     *   │                                                                     │
     *   │  Process dist=6 first: BR_1 has stress = own_mass + moment        │
     *   │    → If BR_1 is overloaded (cantilevered), it fails first          │
     *   │    → BR_2 never receives BR_1's load                               │
     *   │                                                                     │
     *   │  This creates natural break points in the structure.               │
     *   │                                                                     │
     *   │  OPTIMIZATION: "Trivially stable" blocks are skipped entirely.     │
     *   │  A block is trivially stable if NO neighbor is farther from ground │
     *   │  — meaning nothing is above it and nothing cantilevers off it.     │
     *   │  For flat terrain, this skips 99%+ of blocks while keeping full    │
     *   │  physics for structures built on top.                              │
     *   │                                                                     │
     *   └─────────────────────────────────────────────────────────────────────┘
     * </pre>
     *
     * @param graph    the structure graph
     * @param subgraph positions to evaluate
     * @return the first overloaded block (farthest from ground), or null if none
     */
    public NodePos solveProgressively(StructureGraph graph, Set<NodePos> subgraph) {
        if (subgraph.isEmpty()) {
            return null;
        }
        if (metrics != null) {
            metrics.solveInvocations++;
        }

        LevelPlan plan = planLevels(graph, subgraph);
        if (plan == null) {
            return null; // fast path: nothing needs stress calculation (flat terrain)
        }

        MomentArmIndex[] arms = {null}; // built lazily on the first true cantilever
        // Local cache: solveProgressively has no hot/repeated production caller, so unlike
        // solve()/findOverloadedBatch() it does not reuse the shared buffer field.
        Object2DoubleOpenHashMap<NodePos> denomCache = new Object2DoubleOpenHashMap<>();

        // Floating (distance MAX_VALUE) blocks first — own mass only. They almost
        // never overload, but a DAMAGED floating block can, so check it like any
        // level (this matches the old MAX_VALUE-keyed TreeMap level processed first).
        if (plan.floating() != null) {
            computeLevelStress(
                    graph, plan.floating(), Integer.MAX_VALUE, plan.distances(), plan.scope(), arms, denomCache);
            NodePos overloaded = mostOverloadedAt(graph, plan.floating());
            if (overloaded != null) {
                return overloaded;
            }
        }

        // Process each distance level from farthest to nearest.
        for (int currentDist = plan.maxDist(); currentDist >= 0; currentDist--) {
            List<NodePos> blocksAtLevel = plan.levels()[currentDist];
            if (blocksAtLevel == null) {
                continue;
            }
            computeLevelStress(graph, blocksAtLevel, currentDist, plan.distances(), plan.scope(), arms, denomCache);

            // If any block at this level is overloaded, return the most stressed
            // one (the rest are collapsed via findOverloadedBatch).
            NodePos mostOverloaded = mostOverloadedAt(graph, blocksAtLevel);
            if (mostOverloaded != null) {
                return mostOverloaded;
            }
        }

        // No overloaded blocks found
        return null;
    }

    /** The most-stressed overloaded (percent &gt; 1.0) non-ground block at a level, or null. */
    private static NodePos mostOverloadedAt(StructureGraph graph, List<NodePos> blocksAtLevel) {
        NodePos mostOverloaded = null;
        double highestPercent = 1.0;
        for (NodePos pos : blocksAtLevel) {
            Node node = graph.getNode(pos);
            if (node != null && !node.isGrounded()) {
                double percent = node.stressPercent();
                if (percent > highestPercent) {
                    highestPercent = percent;
                    mostOverloaded = pos;
                }
            }
        }
        return mostOverloaded;
    }

    /**
     * Find ALL overloaded blocks in the subgraph, batched for efficient collapse.
     * Returns blocks grouped by distance level (farthest first), so the cascade
     * can process entire levels at once instead of one block at a time.
     *
     * @return List of overloaded blocks at the farthest distance level, or empty if stable
     */
    public List<NodePos> findOverloadedBatch(StructureGraph graph, Set<NodePos> subgraph) {
        return findOverloadedBatch(graph, subgraph, null);
    }

    /**
     * {@link #findOverloadedBatch(StructureGraph, Set)} but reading distance-from-ground
     * from {@code precomputedDistances} instead of recomputing the BFS. The cascade
     * maintains those distances incrementally with a {@link GroundDistanceIndex} (one
     * build, then cheap decremental repairs per collapse) so a K-step cascade does not
     * pay K full BFS passes. {@code precomputedDistances} MUST be the exact map a
     * {@code calculateDistances(graph, subgraph)} would produce for the SAME node set —
     * the index is pinned byte-identical to that BFS by {@code GroundDistanceIndexTest},
     * so the result is unchanged. Pass {@code null} to compute the BFS inline.
     */
    public List<NodePos> findOverloadedBatch(
            StructureGraph graph, Set<NodePos> subgraph, Object2IntOpenHashMap<NodePos> precomputedDistances) {
        return findOverloadedBatch(graph, subgraph, precomputedDistances, NEVER_PAUSE, new BatchScanCursor());
    }

    /** The never-pause budget for the run-to-completion overloads. */
    private static final BooleanSupplier NEVER_PAUSE = () -> false;

    /**
     * Interruptible, resumable form of {@link #findOverloadedBatch(StructureGraph, Set,
     * Object2IntOpenHashMap)}: the SAME farthest-first level scan, but it consults
     * {@code pause} between distance levels and, when it trips with no overload yet
     * found, parks the plan and the next level in {@code cursor} and returns an empty
     * list with {@code cursor.isComplete() == false}. A later call with the same cursor
     * resumes at the parked level. This only ever pauses a CLEAN scan (a stability
     * proof over a keep-sized scope, which alone can cost hundreds of milliseconds):
     * once a level overloads, the scan returns that batch (complete) so the caller can
     * collapse it. Because a paused scan collapses nothing, the graph — and the stress
     * already finalized on the farther levels — is unchanged between calls, so a
     * resumed-to-completion scan returns the identical batch the uninterrupted scan
     * would. Callers must check {@code cursor.isComplete()} to tell "stable" (complete,
     * empty) from "paused mid-scan" (incomplete, empty).
     */
    public List<NodePos> findOverloadedBatch(
            StructureGraph graph,
            Set<NodePos> subgraph,
            Object2IntOpenHashMap<NodePos> precomputedDistances,
            BooleanSupplier pause,
            BatchScanCursor cursor) {
        if (!cursor.started) {
            if (subgraph.isEmpty()) {
                cursor.complete = true;
                return Collections.emptyList();
            }
            if (metrics != null) {
                metrics.solveInvocations++;
            }
            LevelPlan plan = planLevels(graph, subgraph, precomputedDistances);
            if (plan == null) {
                cursor.complete = true;
                return Collections.emptyList(); // fast path: nothing needs stress calc
            }
            cursor.plan = plan;
            cursor.arms = new MomentArmIndex[] {null}; // built lazily on the first true cantilever
            cursor.nextDist = plan.maxDist();
            cursor.started = true;
        }
        LevelPlan plan = cursor.plan;
        // denomCache is pure per-node memoization; rebuilding it on a resume yields the
        // identical shares (same graph, same distances), so a resumed scan stays exact.
        denomCache.clear();

        // Floating (distance MAX_VALUE) blocks first — see solveProgressively. Floating
        // is one atomic unit: the pause lands AFTER it, never inside.
        boolean floatingWork = false;
        if (!cursor.floatingDone) {
            if (plan.floating() != null) {
                computeLevelStress(
                        graph,
                        plan.floating(),
                        Integer.MAX_VALUE,
                        plan.distances(),
                        plan.scope(),
                        cursor.arms,
                        denomCache);
                List<NodePos> overloaded = overloadedAt(graph, plan.floating());
                if (!overloaded.isEmpty()) {
                    cursor.complete = true;
                    return overloaded;
                }
                floatingWork = true;
            }
            cursor.floatingDone = true;
        }

        // Process levels from farthest to closest. When we find overloaded blocks
        // at a level, return only those — the caller collapses them and re-solves,
        // which may save closer blocks due to load redistribution.
        //
        // The budget is consulted only BETWEEN levels and only when a further
        // non-null level actually remains: a scan that has no more work left proves
        // stability in this call rather than deferring an empty resume, so a small
        // stable structure is never needlessly truncated even under an always-true
        // pause. Pausing parks the NEXT level so the resume does not recompute this
        // one (which would reset its stress and re-read not-yet-finalized levels).
        int d = nextNonNullLevel(plan, cursor.nextDist);
        // A pause is legal right after the floating unit — but only if real level
        // work still remains (floating alone completing a stable graph must not
        // defer an empty resume).
        if (floatingWork && d >= 0 && pause.getAsBoolean()) {
            cursor.nextDist = d;
            return Collections.emptyList(); // incomplete
        }
        while (d >= 0) {
            List<NodePos> blocksAtLevel = plan.levels()[d];
            computeLevelStress(graph, blocksAtLevel, d, plan.distances(), plan.scope(), cursor.arms, denomCache);

            List<NodePos> overloadedAtLevel = overloadedAt(graph, blocksAtLevel);
            if (!overloadedAtLevel.isEmpty()) {
                cursor.complete = true;
                return overloadedAtLevel;
            }
            int next = nextNonNullLevel(plan, d - 1);
            if (next < 0) {
                break; // no work remains — the scan proved stability
            }
            if (pause.getAsBoolean()) {
                cursor.nextDist = next;
                return Collections.emptyList(); // incomplete
            }
            d = next;
        }

        cursor.complete = true;
        return Collections.emptyList();
    }

    /** The highest distance level {@code <= from} that has blocks to finalize, or -1. */
    private static int nextNonNullLevel(LevelPlan plan, int from) {
        for (int d = from; d >= 0; d--) {
            if (plan.levels()[d] != null) {
                return d;
            }
        }
        return -1;
    }

    /**
     * The parked state of an interruptible {@link #findOverloadedBatch(StructureGraph,
     * Set, Object2IntOpenHashMap, BooleanSupplier, BatchScanCursor) level scan}: the
     * bucketed {@link LevelPlan}, the lazily-built moment-arm index, and the next
     * distance level to finalize. A fresh cursor whose {@link #isComplete()} is still
     * false after a call is the signal that the scan paused mid-stability-proof and
     * must be driven again — on the same, un-collapsed graph — to reach the identical
     * result the uninterrupted scan would.
     */
    public static final class BatchScanCursor {
        private LevelPlan plan;
        private MomentArmIndex[] arms;
        private int nextDist;
        private boolean floatingDone;
        private boolean started;
        private boolean complete;

        /** True once the scan reached a verdict (an overloaded batch, or provably stable). */
        public boolean isComplete() {
            return complete;
        }

        /** True once the scan has begun — its plan is built and it is mid-flight or done. */
        public boolean isStarted() {
            return started;
        }
    }

    /** Every overloaded (percent &gt; 1.0) non-ground block at a level (insertion order). */
    private static List<NodePos> overloadedAt(StructureGraph graph, List<NodePos> blocksAtLevel) {
        List<NodePos> overloaded = new ArrayList<>();
        for (NodePos pos : blocksAtLevel) {
            Node node = graph.getNode(pos);
            if (node != null && !node.isGrounded() && node.stressPercent() > 1.0) {
                overloaded.add(pos);
            }
        }
        return overloaded;
    }

    /**
     * The blocks needing stress calculation, bucketed by distance-from-ground —
     * the shared prologue of {@link #solveProgressively} and
     * {@link #findOverloadedBatch}. Both pick which blocks to evaluate, group them
     * by distance, and reset their stress identically; only the per-level decision
     * (single most-overloaded vs. the whole overloaded batch) differs.
     *
     * <p>Levels are a dense {@code List<NodePos>[]} indexed directly by distance
     * (distances are small dense ints), replacing the per-pass {@code TreeMap} that
     * boxed every distance key. Iterating {@code maxDist .. 0} visits levels
     * farthest-first, exactly as the {@code TreeMap(reverseOrder())} did; absent
     * levels are {@code null}. Order WITHIN a level is irrelevant to the result (the
     * vertical pass reads only strictly-farther finalized neighbours).
     *
     * @return the plan, or {@code null} if no block needs stress calc (flat terrain)
     */
    private LevelPlan planLevels(StructureGraph graph, Set<NodePos> subgraph) {
        return planLevels(graph, subgraph, null);
    }

    private LevelPlan planLevels(
            StructureGraph graph, Set<NodePos> subgraph, Object2IntOpenHashMap<NodePos> precomputedDistances) {
        Object2IntOpenHashMap<NodePos> distanceFromGround =
                precomputedDistances != null ? precomputedDistances : calculateDistances(graph, subgraph);

        // Identify "trivially stable" blocks (no upstream neighbor, no damage) and
        // skip them: their stress is own-mass-only and always passes. A block needs
        // calculation if it is damaged, has a neighbor farther from ground (load from
        // above OR a horizontal cantilever), or sits at the edge of the structure
        // (an empty horizontal neighbor position — a potential cantilever end).
        Set<NodePos> needsStressCalc = new HashSet<>();
        int maxDist = -1;
        for (NodePos pos : subgraph) {
            int myDist = distanceFromGround.getInt(pos);
            if (myDist == Integer.MAX_VALUE) {
                continue; // floating, handled separately
            }
            Node node = graph.getNode(pos);
            if (node != null && node.damage() > 0) {
                if (needsStressCalc.add(pos)) {
                    maxDist = Math.max(maxDist, myDist);
                }
                continue;
            }
            boolean hasUpstream = false;
            for (NodePos neighbor : graph.neighborsOf(pos)) {
                if (distanceFromGround.getInt(neighbor) > myDist) {
                    hasUpstream = true;
                    break;
                }
            }
            if (hasUpstream && needsStressCalc.add(pos)) {
                maxDist = Math.max(maxDist, myDist);
            }
        }

        // Edge detection: a block with an empty horizontal neighbor position could be
        // a cantilever end and needs moment calculation. (Catches removed neighbors.)
        // This is the ONE place a floating (distance MAX_VALUE) block can enter the
        // set — it can't index the dense level array, so it goes to a separate
        // "floating" bucket processed first (it never receives load and never
        // overloads, exactly as it did at the MAX_VALUE key of the old TreeMap).
        List<NodePos> floating = null;
        for (NodePos pos : subgraph) {
            if (needsStressCalc.contains(pos)) {
                continue;
            }
            Node node = graph.getNode(pos);
            if (node == null || node.isGrounded()) {
                continue;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    if (Math.abs(dx) + Math.abs(dz) != 1) {
                        continue; // cardinal directions only
                    }
                    NodePos neighborPos = new NodePos(pos.x() + dx, pos.y(), pos.z() + dz);
                    if (!graph.hasBlock(neighborPos)) {
                        if (needsStressCalc.add(pos)) {
                            int d = distanceFromGround.getInt(pos);
                            if (d == Integer.MAX_VALUE) {
                                if (floating == null) {
                                    floating = new ArrayList<>();
                                }
                                floating.add(pos);
                            } else {
                                maxDist = Math.max(maxDist, d);
                            }
                        }
                        break;
                    }
                }
                if (needsStressCalc.contains(pos)) {
                    break;
                }
            }
        }

        if (metrics != null) {
            metrics.nodeVisits += needsStressCalc.size();
        }

        if (needsStressCalc.isEmpty()) {
            return null; // pure flat terrain — nothing to do
        }

        // Pin the SKIPPED trivially-stable blocks to their invariant fields. These
        // are not in needsStressCalc, so they are never resetStress()'d nor
        // recomputed — yet computeLevelStress reads neighbor.verticalStress() for any
        // farther neighbour, including these. A trivially-stable block (no upstream
        // neighbour, undamaged) carries only its own mass vertically and bears no
        // moment, so its true fields are (mass, 0). Without this, a downstream block
        // would read whatever stale value the field last held — 0.0 on a fresh graph
        // (undercount, missed collapse) or an old roof-loaded value after the load was
        // relieved (overcount, spurious collapse). O(subgraph), no extra pass. Grounded
        // and floating (MAX_VALUE) nodes are never read as a farther-neighbour load
        // source, so they need no pinning.
        for (NodePos pos : subgraph) {
            if (needsStressCalc.contains(pos)) {
                continue;
            }
            if (distanceFromGround.getInt(pos) == Integer.MAX_VALUE) {
                continue; // floating — never contributes downstream load
            }
            Node node = graph.getNode(pos);
            if (node == null || node.isGrounded()) {
                continue;
            }
            node.setVerticalStress(node.mass());
            node.setMomentStress(0.0);
        }

        // Bucket the finite-distance blocks into a dense array indexed by distance,
        // and reset the stress of every block we'll calculate (floating ones too).
        @SuppressWarnings("unchecked")
        List<NodePos>[] levels = (List<NodePos>[]) new List<?>[maxDist + 1];
        for (NodePos pos : needsStressCalc) {
            int dist = distanceFromGround.getInt(pos);
            Node node = graph.getNode(pos);
            if (node != null) {
                node.resetStress();
            }
            if (dist == Integer.MAX_VALUE) {
                continue; // already in the floating bucket
            }
            List<NodePos> bucket = levels[dist];
            if (bucket == null) {
                bucket = new ArrayList<>();
                levels[dist] = bucket;
            }
            bucket.add(pos);
        }

        return new LevelPlan(distanceFromGround, levels, maxDist, floating, subgraph);
    }

    /**
     * Compute vertical + moment stress for every block at one distance level — the
     * per-level body shared by {@link #solveProgressively} and
     * {@link #findOverloadedBatch}. {@code arms} is a one-element holder so the
     * lazily-built {@link MomentArmIndex} (created on the first true cantilever and
     * reused across levels) survives between calls without re-instantiation.
     */
    private void computeLevelStress(
            StructureGraph graph,
            List<NodePos> blocksAtLevel,
            int currentDist,
            Object2IntOpenHashMap<NodePos> distanceFromGround,
            Set<NodePos> scope,
            MomentArmIndex[] arms,
            Object2DoubleOpenHashMap<NodePos> denomCache) {
        // Vertical stress: own mass plus our weighted share of every neighbor
        // farther from ground (those levels are already finalized).
        for (NodePos pos : blocksAtLevel) {
            Node node = graph.getNode(pos);
            if (node == null || node.isGrounded()) {
                continue;
            }
            double verticalStress = node.mass();
            for (NodePos neighborPos : graph.neighborsOf(pos)) {
                // Only integrate load from neighbours INSIDE the solved scope. An
                // out-of-scope neighbour was never computed this pass — its distance is
                // absent (defaults to MAX_VALUE, so it falsely reads as "farther") and
                // its verticalStress is STALE — so counting it funnels phantom weight
                // into a partially-scoped structure (the curtain-wall-ring placement
                // bug). In-scope floaters keep MAX_VALUE distance but ARE in scope, so a
                // closed scope is unaffected.
                if (!scope.contains(neighborPos)) {
                    continue;
                }
                if (distanceFromGround.getInt(neighborPos) > currentDist) {
                    Node neighbor = graph.getNode(neighborPos);
                    if (neighbor != null) {
                        double share = calculateLoadShare(graph, neighborPos, pos, distanceFromGround, denomCache);
                        if (share > 0) {
                            // Pure vertical transfer — no amplification.
                            verticalStress += neighbor.verticalStress() * share;
                        }
                    }
                }
            }
            node.setVerticalStress(verticalStress);
        }

        // Moment stress with early beam detection.
        //
        // KNOWN APPROXIMATION (conservative / fails-safe): each supporting block is
        // charged the FULL moment of every true-cantilever arm hanging off it. If one
        // rigid overhang is anchored to the structure at two separate points (e.g. an
        // L-shaped arm touching a tower on two faces, where the two anchors are NOT
        // neighbours of a single arm root — that case is correctly detected as a beam),
        // each anchor independently sees "only the arm beyond me, no alternative" and is
        // billed the full arm moment, so the arm's weight is counted once per anchor
        // rather than split between them. This OVER-states stress (collapses such a shape
        // a little early); it never UNDER-states it, so it can't leave a floating block —
        // it fails safe. Splitting an arm's moment across multiple anchors would require
        // anchor-aware arm attribution in MomentArmIndex; deferred to keep this hot path
        // (the one bounded for the cascade-resume watchdog fix) simple.
        for (NodePos pos : blocksAtLevel) {
            Node node = graph.getNode(pos);
            if (node == null || node.isGrounded()) {
                continue;
            }
            double momentStress = 0.0;
            for (NodePos neighborPos : graph.neighborsOf(pos)) {
                if (neighborPos.y() != pos.y()) {
                    continue; // only horizontal neighbors create moment
                }
                if (distanceFromGround.getInt(neighborPos) > currentDist) {
                    // FAST PATH: an upstream neighbor with an alternative path to
                    // ground is a beam — contribute no moment.
                    if (hasAlternativeSupport(graph, neighborPos, pos, distanceFromGround, currentDist)) {
                        continue;
                    }
                    // True cantilever: look up the arm from the index, building it on
                    // first use. A deeper alternative support still reduces the moment
                    // inside the index, exactly as the old per-anchor walk did.
                    if (arms[0] == null) {
                        // The progressive level solver does not feed the debug capture
                        // (only the full settle solve does), so arm geometry is never
                        // captured here.
                        arms[0] = new MomentArmIndex(graph, distanceFromGround, config, false);
                    }
                    ArmInfo arm = arms[0].arm(neighborPos, currentDist);
                    double applied = arm.totalMass * arm.reach * config.getMomentMultiplier();
                    // Section modulus S = b·d²/6: a beam d nodes deep carries the
                    // same applied moment at 1/d² the stress. d = how thick the
                    // cantilever is vertically in THIS arm's direction.
                    int depth = sectionDepth(graph, pos, neighborPos);
                    momentStress += applied / ((double) depth * depth);
                }
            }
            node.setMomentStress(momentStress);
        }
    }

    /**
     * The bucketed, distance-indexed plan shared by the two progressive solvers.
     * {@code floating} holds the rare distance-MAX_VALUE blocks (null if none) that
     * cannot index {@code levels}; they are processed first and never overload.
     */
    private record LevelPlan(
            Object2IntOpenHashMap<NodePos> distances,
            List<NodePos>[] levels,
            int maxDist,
            List<NodePos> floating,
            Set<NodePos> scope) {}

    /**
     * Fast check: does the start position have alternative support (making it a beam)?
     *
     * A position has alternative support if ANY of its neighbors (except the anchor)
     * has distance <= anchor distance. This means there's another path to ground
     * that doesn't go through the anchor.
     *
     * This is O(neighbors) instead of O(arm_size), enabling early exit before
     * the expensive arm BFS.
     */
    static boolean hasAlternativeSupport(
            StructureGraph graph,
            NodePos startPos,
            NodePos anchorPos,
            Object2IntOpenHashMap<NodePos> distanceFromGround,
            int anchorDistance) {
        for (NodePos neighbor : graph.neighborsOf(startPos)) {
            if (neighbor.equals(anchorPos)) {
                continue;
            }
            int neighborDist = distanceFromGround.getInt(neighbor);
            if (neighborDist <= anchorDistance) {
                return true; // Has alternative support - it's a beam
            }
        }
        return false; // No alternative support - true cantilever
    }

    /**
     * The bending DEPTH of the cantilever at {@code anchorPos} for the arm that
     * runs toward {@code armPos}: how many nodes thick the beam is, measured along
     * the VERTICAL axis, in that arm's direction.
     *
     * <pre>
     *   A horizontal cantilever resists its applied moment with the cross-section
     *   at the anchor. A beam's section modulus is S = b·d²/6, so its bending
     *   CAPACITY scales with the SQUARE of the section DEPTH d — the dimension
     *   perpendicular to the span, which for a horizontal arm is the VERTICAL
     *   thickness of the beam:
     *
     *        d = 1                    d = 3
     *      ──[A]──[arm]…           ──[A]──[arm]…   ┐
     *                              ──[A]──[arm]…   │ three stacked arm layers:
     *                              ──[A]──[arm]…   ┘ a 3-deep beam, 9× stiffer
     *
     *   So the same applied moment produces 1/d² the stress on a d-deep beam.
     *   d = 1 → divisor 1 → a one-thick beam is byte-identical to the old model;
     *   only genuinely thicker beams change (they get stronger, like steel I-beams).
     * </pre>
     *
     * <p><b>Why we count layers of the BEAM, not the support column.</b> A pillar
     * stacked under the anchor is a column carrying axial load, not part of the
     * beam's bending section — it has no arm of its own. So a layer counts only
     * when BOTH the anchor column AND the arm column have a node at that height
     * (and they are connected): i.e. the cantilever is genuinely that many nodes
     * thick in this direction. This is also why depth is computed per arm
     * direction rather than once per anchor.
     *
     * <p>"Up" is the only positional assumption the core makes (y is up — see
     * {@code DESIGN.md}). The walk follows real edges, so a non-grid host that
     * never connects two vertically-aligned nodes gets depth 1 and the unchanged
     * moment.
     *
     * <p><b>Why we only count UPWARD.</b> When a cantilever is several layers thick,
     * only the BOTTOM layer carries the bending moment — every layer above it rests
     * on the one below and is read as a beam (two supports → no moment). So the
     * moment-bearing fibre is the bottom of the section, and the section's depth is
     * the run of beam layers stacked ABOVE it. (The few layers above carry their
     * weight to ground as vertical load through their own columns, which is why they
     * are not extra lever arm — see {@link MomentArmIndex#addMember}.)
     *
     * @return the beam depth d &ge; 1 for this arm; square it for the moment divisor
     */
    private int sectionDepth(StructureGraph graph, NodePos anchorPos, NodePos armPos) {
        if (!config.isBendingDepthEnabled()) {
            return 1; // legacy: depth never affects bending strength
        }
        return 1 + beamLayersAbove(graph, anchorPos, armPos);
    }

    /**
     * Count beam layers stacked directly above the (anchor, arm) pair: a layer
     * counts only if the anchor node, the arm node, and both vertical steps are
     * present and graph-connected — i.e. the beam really is that thick (not a bare
     * support column or an unrelated block sitting on top of just one of them).
     */
    private int beamLayersAbove(StructureGraph graph, NodePos anchorPos, NodePos armPos) {
        int count = 0;
        NodePos anchor = anchorPos;
        NodePos arm = armPos;
        while (true) {
            NodePos nextAnchor = new NodePos(anchor.x(), anchor.y() + 1, anchor.z());
            NodePos nextArm = new NodePos(arm.x(), arm.y() + 1, arm.z());
            // Both columns must continue, the new arm cell must rest on this one,
            // and the two new cells must be a beam (horizontally connected).
            if (!graph.hasBlock(nextAnchor)
                    || !graph.hasBlock(nextArm)
                    || !graph.neighborsOf(anchor).contains(nextAnchor)
                    || !graph.neighborsOf(arm).contains(nextArm)
                    || !graph.neighborsOf(nextAnchor).contains(nextArm)) {
                break;
            }
            count++;
            anchor = nextAnchor;
            arm = nextArm;
        }
        return count;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  MOMENT STRESS CALCULATION
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Compute moment stress for all blocks in the subgraph.
     *
     * <pre>
     *   For each block B:
     *     For each horizontal neighbor N that depends on B for ground access:
     *       armLoad = total mass of the arm rooted at N (away from B)
     *       armReach = number of blocks in that arm
     *       B.momentStress += armLoad × armReach × config.getMomentMultiplier()
     *
     *   EXAMPLE:
     *
     *     [E]──[D]──[C]──[B]──[A]──[GND]
     *
     *   Block A supports arm [B,C,D,E]:
     *     - armLoad = mass(B) + mass(C) + mass(D) + mass(E)
     *     - armReach = 4 blocks
     *     - A.momentStress = armLoad × 4 × config.getMomentMultiplier()
     *
     *   Block B supports arm [C,D,E]:
     *     - armLoad = mass(C) + mass(D) + mass(E)
     *     - armReach = 3 blocks
     *     - B.momentStress = armLoad × 3 × config.getMomentMultiplier()
     *
     *   And so on...
     *
     *   BEAM DETECTION (in calculateArm):
     *   Each arm is checked individually - if the arm's root has alternative support
     *   (a path to ground that doesn't go through the anchor), it's a beam connection
     *   and returns zero moment. This allows a block to have both a cantilever arm
     *   (generating moment) and a beam connection (no moment) at the same time.
     * </pre>
     */
    private void computeMomentStress(
            StructureGraph graph,
            Set<NodePos> subgraph,
            Object2IntOpenHashMap<NodePos> distanceFromGround,
            boolean captureArms) {
        // The arm index is built lazily: solid structures (every arm is a beam,
        // caught by the cheap fast path below) never pay for the contour pass.
        MomentArmIndex arms = null;

        for (NodePos pos : subgraph) {
            Node node = graph.getNode(pos);
            if (node == null || node.isGrounded()) {
                continue;
            }

            int myDistance = distanceFromGround.getInt(pos);

            double momentStress = 0.0;

            // Check each horizontal neighbor
            for (NodePos neighborPos : graph.neighborsOf(pos)) {
                // Only consider horizontal neighbors (same Y level)
                if (neighborPos.y() != pos.y()) {
                    continue;
                }

                int neighborDistance = distanceFromGround.getInt(neighborPos);

                // Neighbor is farther from ground - it's part of an arm we support
                if (neighborDistance > myDistance) {
                    // FAST PATH: if the upstream neighbor itself has an alternative
                    // path to ground, it's a beam — no moment. O(neighbors), and it
                    // means a solidly-connected structure never touches the index.
                    if (hasAlternativeSupport(graph, neighborPos, pos, distanceFromGround, myDistance)) {
                        continue;
                    }
                    // True cantilever: look up the arm (mass/reach/beam-flag) from the
                    // index, building it on first use. A deeper alternative support
                    // still reduces the moment inside the index, as the old walk did.
                    if (arms == null) {
                        arms = new MomentArmIndex(graph, distanceFromGround, config, captureArms);
                    }
                    ArmInfo arm = arms.arm(neighborPos, myDistance);

                    // moment = weight × distance (linear, not exponential),
                    // divided by the section depth² (S = b·d²/6) for THIS arm —
                    // a vertically-thicker beam carries the same moment at 1/d²
                    // the stress.
                    double applied = arm.totalMass * arm.reach * config.getMomentMultiplier();
                    int depth = sectionDepth(graph, pos, neighborPos);
                    momentStress += applied / ((double) depth * depth);

                    // Debug-only: report this arm's geometry (members/mass/reach/beam/
                    // depth/anchor) to the capture sink. Pure reads of already-computed
                    // index state — never alters the moment just added above.
                    if (captureArms) {
                        debugCapture.onMomentArm(
                                arms.members(neighborPos, myDistance),
                                arm.totalMass,
                                arm.reach,
                                arms.isBeam(neighborPos, myDistance),
                                depth,
                                pos);
                    }
                }
            }

            node.setMomentStress(momentStress);
        }
    }

    /**
     * Print debug message if debug logging is enabled.
     */
    private void debug(String message) {
        if (config.isDebugLogging()) {
            System.out.println(message);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HELPER METHODS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Calculate distance from ground for each block using BFS.
     *
     * <pre>
     *       [D] ← dist=4
     *        │
     *   [X]─[C] ← dist=3
     *        │
     *       [B] ← dist=2
     *        │
     *       [A] ← dist=1
     *        │
     *      [GND] ← dist=0
     * </pre>
     */
    private Object2IntOpenHashMap<NodePos> calculateDistances(StructureGraph graph, Set<NodePos> subgraph) {
        Object2IntOpenHashMap<NodePos> distances = new Object2IntOpenHashMap<>();
        // Absent → MAX_VALUE so getInt(...) behaves exactly like the old
        // getOrDefault(..., Integer.MAX_VALUE); ground nodes store a real 0.
        distances.defaultReturnValue(Integer.MAX_VALUE);

        // Start BFS from all ground nodes
        Queue<NodePos> queue = new ArrayDeque<>();

        for (NodePos pos : subgraph) {
            Node node = graph.getNode(pos);
            if (node != null && node.isGrounded()) {
                distances.put(pos, 0);
                queue.add(pos);
            }
        }

        // BFS to find distances
        while (!queue.isEmpty()) {
            NodePos current = queue.poll();
            int currentDist = distances.getInt(current);

            for (NodePos neighbor : graph.neighborsOf(current)) {
                if (subgraph.contains(neighbor) && !distances.containsKey(neighbor)) {
                    distances.put(neighbor, currentDist + 1);
                    queue.add(neighbor);
                }
            }
        }

        return distances;
    }

    /**
     * Calculate what fraction of a block's load goes to a specific neighbor.
     *
     * <pre>
     *   Load distributes to neighbors STRICTLY CLOSER to ground, weighted by
     *   inverse distance (shorter paths get more).
     *
     *   Strictly closer — not "closer or same" — because the vertical pass only
     *   ever DELIVERS load to strictly-closer neighbors. A same-distance
     *   neighbor in the denominator would be offered a share nobody receives,
     *   and that slice of weight would silently evaporate on its way to ground.
     *   Every reached non-ground block has a strictly-closer neighbor (its BFS
     *   parent), so the denominator is never empty. PhysicsValidationTest pins
     *   exact level-by-level load conservation against this.
     *
     *   TWO PATHS DOWN:
     *
     *        [HEAVY]
     *           │
     *        [LOG]──[STONE]
     *           │      │
     *        [GND]  [GND]
     *
     *   LOG (dist=1) carries HEAVY; LOG's supporters at dist=0: one GND.
     *   HEAVY (dist=2) has supporters LOG (dist=1) and, via STONE (dist=1),
     *   a second path: weight LOG = 1/(1+1) = 0.5, weight STONE = 0.5 —
     *   each strictly-closer neighbor gets 50%. Shares always sum to 1.
     * </pre>
     *
     * @param graph the structure graph
     * @param sourcePos the block distributing its load
     * @param targetPos the neighbor we want the share for
     * @param distanceFromGround distance map
     * @return fraction of load (0.0 to 1.0) that goes to targetPos
     */
    private double calculateLoadShare(
            StructureGraph graph,
            NodePos sourcePos,
            NodePos targetPos,
            Object2IntOpenHashMap<NodePos> distanceFromGround,
            Object2DoubleOpenHashMap<NodePos> denomCache) {
        int sourceDistance = distanceFromGround.getInt(sourcePos);
        int targetDistance = distanceFromGround.getInt(targetPos);

        // Target must be a strictly-closer supporter.
        if (targetDistance >= sourceDistance || targetDistance == Integer.MAX_VALUE) {
            return 0.0;
        }

        // targetPos is a strictly-closer neighbour of sourcePos, so its weight is
        // exactly 1/(targetDistance+1) — the same value the old loop found when it
        // hit targetPos in sourcePos's neighbour set.
        double targetWeight = 1.0 / (targetDistance + 1);

        // Denominator = Σ 1/(d+1) over sourcePos's strictly-closer supporters. It
        // depends only on sourcePos, so compute it once per pass and cache it
        // instead of re-summing for every receiving neighbour (was O(deg²)).
        double totalWeight = denominatorFor(graph, sourcePos, sourceDistance, distanceFromGround, denomCache);
        if (totalWeight == 0) {
            return 0.0;
        }
        return targetWeight / totalWeight;
    }

    /**
     * The downstream-weight denominator for {@code sourcePos}: Σ 1/(d+1) over its
     * neighbours strictly closer to ground. Memoized per pass in {@code denomCache}.
     *
     * <p>The sum walks {@code graph.neighborsOf(sourcePos)} in its native iteration
     * order — the SAME order the old inline loop used — so the cached value is
     * floating-point bit-identical to recomputing it inline. {@link ArmEquivalenceTest}
     * and the golden snapshots pin this.
     */
    private double denominatorFor(
            StructureGraph graph,
            NodePos sourcePos,
            int sourceDistance,
            Object2IntOpenHashMap<NodePos> distanceFromGround,
            Object2DoubleOpenHashMap<NodePos> denomCache) {
        double cached = denomCache.getDouble(sourcePos);
        if (cached != 0.0 || denomCache.containsKey(sourcePos)) {
            return cached;
        }
        double totalWeight = 0.0;
        for (NodePos neighbor : graph.neighborsOf(sourcePos)) {
            int neighborDistance = distanceFromGround.getInt(neighbor);
            if (neighborDistance < sourceDistance) {
                totalWeight += 1.0 / (neighborDistance + 1);
            }
        }
        denomCache.put(sourcePos, totalWeight);
        return totalWeight;
    }
}
