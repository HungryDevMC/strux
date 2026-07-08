package dev.gesp.structural.solver;

import dev.gesp.structural.api.CollapseReason;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.metrics.StruxMetrics;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine.SettleOutcome;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Settles many INDEPENDENT structural disturbances in parallel, then merges the
 * results deterministically — a faster front-end to {@link CascadeEngine} for the
 * "a siege volley hit five different buildings this tick" case.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                 WHY THIS IS SAFE (AND BYTE-IDENTICAL)               │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Two connected components never share load, so settling one can     │
 *   │  only affect the other through ONE channel: falling debris, which   │
 *   │  drops straight down its (x,z) column (see CascadeEngine            │
 *   │  .applyDebrisImpact). So two components are independent unless their │
 *   │  (x,z) footprints overlap.                                          │
 *   │                                                                     │
 *   │  We therefore: (1) group the disturbed seeds by component, (2) merge │
 *   │  any components whose (x,z) footprints touch into one CLUSTER, so a  │
 *   │  cluster is fully self-contained. Clusters cannot interact, so each  │
 *   │  is settled on its OWN isolated copy of the graph, on its own        │
 *   │  thread, with its own metrics — no shared mutable state, no locks.   │
 *   │                                                                     │
 *   │  The merge then transplants each cluster's final state back into the │
 *   │  real graph on the calling thread (single writer) and replays the    │
 *   │  collapse events in a canonical order. Because clusters are          │
 *   │  disjoint, the union of their effects equals settling them one after │
 *   │  another — the result is identical to the serial engine, just        │
 *   │  computed concurrently.                                             │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>This changes <em>when</em> the work happens, never <em>what</em> the physics
 * decides: the surviving graph, the set of collapsed nodes, and the summed
 * {@link StruxMetrics} work-counts are identical to running the same clusters
 * through {@link CascadeEngine#settleResult} sequentially. A single cluster (or no
 * independent split) delegates straight to the serial engine, so the common
 * one-structure case is unchanged and pays nothing for the partitioning machinery.
 *
 * <p>One deliberate fidelity note: in the multi-cluster parallel path the per-step
 * {@code onStressUpdated} callback is not fired (building those maps off-thread and
 * interleaving them across independent structures is neither cheap nor meaningful).
 * {@code onCascadeStep} and {@code onCascadeComplete} fire as usual. The
 * single-cluster path keeps full callback fidelity.
 */
public final class ParallelCascadeDriver {

    private final PhysicsConfig config;
    private final Executor executor;

    /** Optional work-counter; null on the production path. See {@link StruxMetrics}. */
    private StruxMetrics metrics;

    /** Parallelise across the common ForkJoin pool. */
    public ParallelCascadeDriver(PhysicsConfig config) {
        this(config, ForkJoinPool.commonPool());
    }

    /**
     * Parallelise across a caller-supplied executor. Pass {@link Runnable#run} (a
     * direct executor) to run clusters sequentially through the very same
     * copy-and-merge path — used by tests to prove the result is independent of
     * threading.
     */
    public ParallelCascadeDriver(PhysicsConfig config, Executor executor) {
        this.config = config;
        this.executor = executor;
    }

    /**
     * Attach (or detach, with {@code null}) a work-counter. The per-cluster worker
     * counts are summed into it after the merge. Returns {@code this} for chaining.
     */
    public ParallelCascadeDriver setMetrics(StruxMetrics metrics) {
        this.metrics = metrics;
        return this;
    }

    /**
     * Settle a graph that has already been disturbed (blocks removed and/or damaged
     * by the caller), splitting independent structures across threads. Mirrors
     * {@link CascadeEngine#settleResult(StructureGraph, Set, SolverCallback)} — same
     * inputs, same outputs — but runs independent clusters concurrently.
     */
    public SettleOutcome settle(StructureGraph graph, Set<NodePos> seeds, SolverCallback callback) {
        List<Cluster> clusters = partition(graph, seeds);

        // Delegate to the serial engine when there is nothing independent to split
        // (one cluster, or none), OR when the clusters are not debris-closed — an
        // out-of-cluster block sits in a cluster's footprint column, so falling debris
        // could cross between them and partition() (which only discovers seed
        // components) would have missed it. Either way the serial engine is the correct
        // answer, with full callback fidelity and zero copy overhead. onCascadeComplete
        // is fired here too so both paths of this method behave identically (the
        // delegated settleResult does not fire it — "the caller owns done").
        if (clusters.size() <= 1 || !canParallelize(graph, clusters)) {
            SettleOutcome outcome =
                    new CascadeEngine(config).setMetrics(metrics).settleResult(graph, seeds, callback);
            callback.onCascadeComplete(outcome.collapsed());
            return outcome;
        }

        // Solve every cluster on its own isolated copy. The executor decides whether
        // that happens on worker threads (default) or inline (direct executor).
        List<CompletableFuture<ClusterResult>> futures = new ArrayList<>(clusters.size());
        for (Cluster cluster : clusters) {
            futures.add(CompletableFuture.supplyAsync(() -> solveCluster(graph, cluster), executor));
        }

        // Merge in a canonical cluster order so the collapsed list and the replayed
        // step numbers are reproducible regardless of which worker finished first.
        List<ClusterResult> results = new ArrayList<>(futures.size());
        for (CompletableFuture<ClusterResult> f : futures) {
            results.add(f.join());
        }
        results.sort((p, q) -> NodePos.CANONICAL_ORDER.compare(p.cluster.anchor, q.cluster.anchor));

        List<CollapsedNode> allCollapsed = new ArrayList<>();
        Set<NodePos> remainingScope = new HashSet<>();
        boolean truncated = false;
        int step = 0;
        for (ClusterResult result : results) {
            // Transplant this cluster's final state into the real graph (single
            // writer = this thread). Clusters are disjoint, so no two touch the
            // same node and order between clusters does not matter.
            for (NodePos pos : result.cluster.positions) {
                Node settled = result.copy.getNode(pos);
                if (settled == null) {
                    graph.removeBlock(pos); // collapsed in the worker
                } else {
                    Node real = graph.getNode(pos);
                    if (real != null) {
                        if (real.damage() != settled.damage()) {
                            // Sync persistent debris damage onto the survivor
                            // (reinforcement is untouched by a settle). repair() +
                            // addDamage reproduces the worker's absolute value exactly.
                            real.repair();
                            real.addDamage(settled.damage());
                        }
                        // Transplant the freshly-solved stress the serial engine would
                        // have left on this survivor. It is transient, but NOT ignorable:
                        // EntityWeightTask gates/decides later collapses on
                        // stressValue()/stressPercent(), and StressVisualizer/grades render
                        // it — so dropping it makes the same input observably diverge and
                        // steer different subsequent entity-weight decisions depending on
                        // whether the multi-cluster path ran. verticalStress + momentStress
                        // are the only per-node fields the solver writes.
                        real.setVerticalStress(settled.verticalStress());
                        real.setMomentStress(settled.momentStress());
                    }
                }
            }
            // Replay collapse events in this cluster's settle order, renumbering steps
            // globally, then accumulate.
            for (int i = 0; i < result.collapsed.size(); i++) {
                CollapsedNode node = result.collapsed.get(i);
                allCollapsed.add(node);
                callback.onCascadeStep(node, ++step, result.reasons.get(i));
            }
            truncated |= result.truncated;
            // A truncated cluster left blocks mid-collapse; carry its live affected
            // region so a resume settles exactly those, per the SettleOutcome contract.
            // Positions are identical between the worker copy and the real graph.
            if (result.truncated) {
                remainingScope.addAll(result.remainingScope);
            }
            if (metrics != null) {
                metrics.add(result.metrics);
            }
        }

        callback.onCascadeComplete(allCollapsed);
        return new SettleOutcome(allCollapsed, truncated, remainingScope);
    }

    /**
     * Whether the disjoint clusters can be settled independently. Debris is the only
     * channel by which settling one cluster can affect another (two connected
     * components never share load), so with debris off the clusters are always
     * independent. With debris on, a cluster is self-contained only if every graph
     * block in one of its footprint columns is itself in that cluster — otherwise an
     * unseeded component sharing a column (which {@link #partition} never discovered)
     * would have debris fall through it. This costs one whole-graph scan, but only in
     * the rare multi-cluster + debris-on case; the common single-structure settle
     * delegates before reaching here. A column index on the graph would make it
     * cheaper.
     */
    private boolean canParallelize(StructureGraph graph, List<Cluster> clusters) {
        if (config.getDebrisImpactScale() <= 0.0) {
            return true;
        }
        Set<Long> clusterColumns = new HashSet<>();
        Set<NodePos> clusterPositions = new HashSet<>();
        for (Cluster cluster : clusters) {
            clusterPositions.addAll(cluster.positions);
            for (NodePos p : cluster.positions) {
                clusterColumns.add(columnKey(p));
            }
        }
        for (NodePos p : graph.getAllPositions()) {
            if (clusterColumns.contains(columnKey(p)) && !clusterPositions.contains(p)) {
                return false; // an out-of-cluster block shares a cluster's column
            }
        }
        return true;
    }

    /** Pack an (x,z) column into a single long key. */
    private static long columnKey(NodePos p) {
        return (((long) p.x()) << 32) | (p.z() & 0xFFFFFFFFL);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  CLUSTERING
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Group the seeds into independent clusters: first by connected component, then
     * union any components whose (x,z) footprints overlap (the only way debris from
     * one can reach another). Each returned cluster is fully self-contained.
     */
    private List<Cluster> partition(StructureGraph graph, Set<NodePos> seeds) {
        // Discover the distinct components the seeds fall in, remembering which seeds
        // belong to each (a component reached by several seeds is found once).
        List<Set<NodePos>> comps = new ArrayList<>();
        List<List<NodePos>> compSeeds = new ArrayList<>();
        Map<NodePos, Integer> compOf = new HashMap<>();
        for (NodePos seed : seeds) {
            if (!graph.hasBlock(seed)) {
                continue;
            }
            Integer ci = compOf.get(seed);
            if (ci == null) {
                Set<NodePos> comp = graph.componentOf(seed);
                ci = comps.size();
                comps.add(comp);
                compSeeds.add(new ArrayList<>());
                for (NodePos p : comp) {
                    compOf.put(p, ci);
                }
            }
            compSeeds.get(ci).add(seed);
        }
        if (comps.isEmpty()) {
            return List.of();
        }

        // Union components that share any (x,z) column — vertically stacked but
        // edge-disconnected structures can still rain debris on each other.
        int[] parent = new int[comps.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        Map<Long, Integer> columnOwner = new HashMap<>();
        for (int i = 0; i < comps.size(); i++) {
            for (NodePos p : comps.get(i)) {
                long column = columnKey(p);
                Integer owner = columnOwner.putIfAbsent(column, i);
                if (owner != null) {
                    union(parent, owner, i);
                }
            }
        }

        // Coalesce components by union-find root into final clusters.
        Map<Integer, Cluster> byRoot = new HashMap<>();
        for (int i = 0; i < comps.size(); i++) {
            Cluster cluster = byRoot.computeIfAbsent(find(parent, i), k -> new Cluster());
            cluster.positions.addAll(comps.get(i));
            cluster.seeds.addAll(compSeeds.get(i));
        }
        List<Cluster> clusters = new ArrayList<>(byRoot.values());
        for (Cluster cluster : clusters) {
            cluster.anchor = min(cluster.positions); // canonical id for ordering the merge
        }
        return clusters;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }

    private static NodePos min(Collection<NodePos> positions) {
        NodePos best = null;
        for (NodePos p : positions) {
            if (best == null || NodePos.CANONICAL_ORDER.compare(p, best) < 0) {
                best = p;
            }
        }
        return best;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PER-CLUSTER WORKER  (runs on a worker thread; touches only its own copy)
    // ─────────────────────────────────────────────────────────────────────

    private ClusterResult solveCluster(StructureGraph graph, Cluster cluster) {
        StructureGraph copy = isolate(graph, cluster.positions);
        StruxMetrics workerMetrics = metrics == null ? null : new StruxMetrics();
        Recording recording = new Recording();
        SettleOutcome outcome = new CascadeEngine(config)
                .setMetrics(workerMetrics)
                .settleResult(copy, new HashSet<>(cluster.seeds), recording);
        return new ClusterResult(
                cluster,
                copy,
                outcome.collapsed(),
                recording.reasons,
                outcome.truncated(),
                outcome.remainingScope(),
                workerMetrics);
    }

    /** A deep copy of just {@code positions} and the edges among them. */
    private static StructureGraph isolate(StructureGraph graph, Set<NodePos> positions) {
        StructureGraph copy = new StructureGraph();
        for (NodePos pos : positions) {
            Node node = graph.getNode(pos);
            if (node == null) {
                continue;
            }
            copy.addNode(pos, node.spec(), node.isGrounded());
            // Carry the persistent state, or a what-if copy would silently heal damage.
            // temperatureCapacityFactor feeds effectiveMaxLoad() (and so every overload
            // and debris-damage decision); dropping it would simulate thermally-softened
            // blocks at full strength in the worker and transplant the wrong survivors.
            Node copied = copy.getNode(pos);
            copied.addDamage(node.damage());
            copied.setReinforcement(node.reinforcement());
            copied.setTemperatureCapacityFactor(node.temperatureCapacityFactor());
        }
        for (NodePos pos : positions) {
            for (NodePos neighbor : graph.getNeighbors(pos)) {
                if (positions.contains(neighbor)) {
                    copy.connect(pos, neighbor);
                }
            }
        }
        return copy;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  VALUE TYPES
    // ─────────────────────────────────────────────────────────────────────

    /** One self-contained unit of work: positions to copy + the seeds that disturbed it. */
    private static final class Cluster {
        final Set<NodePos> positions = new HashSet<>();
        final Set<NodePos> seeds = new HashSet<>();
        NodePos anchor;
    }

    /** What a worker produced for its cluster, awaiting the serial merge. */
    private static final class ClusterResult {
        final Cluster cluster;
        final StructureGraph copy;
        final List<CollapsedNode> collapsed;
        final List<CollapseReason> reasons;
        final boolean truncated;
        final Set<NodePos> remainingScope;
        final StruxMetrics metrics;

        ClusterResult(
                Cluster cluster,
                StructureGraph copy,
                List<CollapsedNode> collapsed,
                List<CollapseReason> reasons,
                boolean truncated,
                Set<NodePos> remainingScope,
                StruxMetrics metrics) {
            this.cluster = cluster;
            this.copy = copy;
            this.collapsed = collapsed;
            this.reasons = reasons;
            this.truncated = truncated;
            this.remainingScope = remainingScope;
            this.metrics = metrics;
        }
    }

    /** Captures cascade steps off-thread so the driver can replay them in canonical order. */
    private static final class Recording implements SolverCallback {
        final List<CollapseReason> reasons = new ArrayList<>();

        @Override
        public void onCascadeStep(CollapsedNode collapsed, int stepNumber, CollapseReason reason) {
            reasons.add(reason);
        }

        @Override
        public void onStressUpdated(Map<NodePos, Double> stressMap) {}

        @Override
        public void onCascadeComplete(List<CollapsedNode> allCollapsed) {}
    }
}
