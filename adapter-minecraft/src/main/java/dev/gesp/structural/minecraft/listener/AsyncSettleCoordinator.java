package dev.gesp.structural.minecraft.listener;

import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.perf.TaskTimings;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.World;

/**
 * Computes cascade settles off the main thread and hands the answer back for a
 * budgeted main-thread apply.
 *
 * <pre>
 *   ┌──────────────────────────────────────────────────────────────────────┐
 *   │                      ASYNC SETTLE COORDINATOR                        │
 *   ├──────────────────────────────────────────────────────────────────────┤
 *   │  A keep-sized settle costs more than a tick, and its atomic units are │
 *   │  too coarse for the settle-budget pause to help. So:                  │
 *   │                                                                      │
 *   │  submit()  main thread: close the scope (affectedRegion), snapshot it │
 *   │            (copySubgraph), stamp graph.modCount(), hand the solve to a │
 *   │            worker — returns within the same tick.                     │
 *   │  drain()   main thread: when the worker is done, compare the live     │
 *   │            modCount + the per-scope node state against the snapshot.   │
 *   │              match    → remove collapsed from the live graph, return   │
 *   │                         the ordered collapse for the budgeted world    │
 *   │                         apply (DelayedCollapseManager).                │
 *   │              damage↑  → an in-scope node only got MORE damaged (fire    │
 *   │                         scorch); the solved collapse is a conservative  │
 *   │                         subset, so apply it — no strike (see below).    │
 *   │              conflict → topology changed in scope: discard + re-solve;  │
 *   │                         after 3 strikes run one inline synchronous      │
 *   │                         settle under settleDeadline.                    │
 *   │            A worker exception logs SEVERE + falls back inline — a       │
 *   │            cascade is never dropped.                                   │
 *   └──────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>Determinism: the solve runs on a {@code copySubgraph} snapshot, which is
 * bit-identical to a synchronous solve (the same seam the blast overload query
 * uses, pinned by {@code BlastSessionEquivalenceTest}); collapses are returned in
 * {@code NodePos.canonicalOrder}; and the recorder still sees them in main-thread
 * apply order. So a recording is byte-identical to the synchronous path.
 */
public final class AsyncSettleCoordinator {

    /** Consecutive version conflicts on one job before we give up and settle inline. */
    static final int MAX_STRIKES = 3;

    private final StructureManager structureManager;
    private final CascadeEngine engine;

    /** The worker; {@code null} means "solve inline on the caller's thread" (tests). */
    private final ExecutorService worker;

    private final Logger logger;
    private final TaskTimings taskTimings;

    /** Deterministic work counters (see {@code TaskTimings}-style perf pinning). */
    private long asyncSettleSolves;

    private long asyncSettleConflicts;

    /**
     * In-scope mutations that only <em>worsened</em> damage (never topology) and so did
     * NOT strike — the solved collapse is still applied as a conservative subset. Counting
     * these separately keeps {@link #asyncSettleConflicts} honest: a fire charring blocks
     * the settle already covers is not a re-solve, it is an applied result.
     */
    private long asyncSettleDamageSkips;

    private final Map<UUID, Job> jobs = new HashMap<>();

    /**
     * While this reports true, no new solve is submitted and a conflicted job parks
     * (retires without a strike) instead of striking toward the inline fallback.
     * Wired to {@code BlastProcessor.hasActiveBlast()}: an active blast carves the
     * crater a few graph blocks per tick, so a solve over that region is stale the
     * moment it completes — submitting during the blast just burns strikes until
     * the 3-strike INLINE fallback runs a keep-sized settle on the main thread
     * (the exact spike this feature removes). Deferring until the blast finishes
     * gives one clean off-thread solve instead; the resume job simply waits.
     */
    private BooleanSupplier deferWhile = () -> false;

    /** See {@link #deferWhile}. */
    public void setDeferWhile(BooleanSupplier deferWhile) {
        this.deferWhile = deferWhile;
    }

    /** One world's in-flight solve. */
    private static final class Job {
        Set<NodePos> scope;
        long snapModCount;

        /**
         * Pre-solve state of every scope node, captured on the main thread BEFORE the
         * solve runs. The solve MUTATES its snapshot (it removes collapsed nodes), so
         * the snapshot itself can't serve as the conflict reference — this pristine
         * fingerprint can. A scope pos missing from the map means "did not exist at
         * snapshot time".
         */
        Map<NodePos, NodeState> refState;

        Future<CascadeEngine.SettleOutcome> future; // null on the inline path
        CascadeEngine.SettleOutcome inlineResult; // set when worker == null
        int strikes;
    }

    /** The scalar per-node state that can change a settle's outcome. */
    private record NodeState(double damage, double reinforcement, boolean grounded) {
        static NodeState of(Node node) {
            return new NodeState(node.damage(), node.reinforcement(), node.isGrounded());
        }
    }

    public AsyncSettleCoordinator(
            StructureManager structureManager,
            CascadeEngine engine,
            ExecutorService worker,
            Logger logger,
            TaskTimings taskTimings) {
        this.structureManager = structureManager;
        this.engine = engine;
        this.worker = worker;
        this.logger = logger;
        this.taskTimings = taskTimings;
    }

    /** Is a solve for this world still in flight (submitted, not yet drained)? */
    public boolean inFlight(World world) {
        return world != null && jobs.containsKey(world.getUID());
    }

    public long asyncSettleSolves() {
        return asyncSettleSolves;
    }

    public long asyncSettleConflicts() {
        return asyncSettleConflicts;
    }

    public long asyncSettleDamageSkips() {
        return asyncSettleDamageSkips;
    }

    /**
     * Snapshot the settle scope and hand the solve to the worker. No-op if a solve
     * for this world is already in flight, or the world's graph is empty/missing.
     */
    public void submit(World world, Set<NodePos> seeds) {
        if (world == null || inFlight(world) || deferWhile.getAsBoolean()) {
            return;
        }
        StructureGraph graph = structureManager.getGraph(world);
        if (graph == null || graph.isEmpty()) {
            return;
        }
        Job job = new Job();
        startSolve(world, graph, seeds, job);
        jobs.put(world.getUID(), job);
    }

    /** Close the scope, snapshot it, stamp the modCount, and submit the solve. */
    private void startSolve(World world, StructureGraph graph, Set<NodePos> seeds, Job job) {
        Set<NodePos> requested =
                (seeds == null || seeds.isEmpty()) ? new HashSet<>(graph.getAllPositions()) : new HashSet<>(seeds);
        Set<NodePos> closed = graph.affectedRegion(requested);
        // affectedRegion already ground-closes the scope, so the solver's boundary
        // guard rarely needs to expand — but when an overloaded candidate touches a
        // node just outside the closure, the guard reaches one neighbour ring out
        // (CascadeEngine "BOUNDARY GUARD"). Give the snapshot that one cheap ring so
        // the guard sees the same blocks it would on the live graph. The solve scope
        // stays `closed`; the halo is only there for the guard to expand into. (A
        // deeper support-ancestor halo was measured O(scope × height) on the main
        // thread for no test-observable benefit — the closure already carries the
        // support paths.)
        Set<NodePos> snapScope = new HashSet<>(closed);
        for (NodePos p : closed) {
            for (NodePos nb : graph.getNeighbors(p)) {
                if (graph.hasBlock(nb)) {
                    snapScope.add(nb);
                }
            }
        }
        // copySolvableSubgraph (NOT copySubgraph): a full settleResult begins with a
        // connectivity-backed floating drain, which needs the chunk-connectivity index
        // that the cheaper copySubgraph leaves empty.
        StructureGraph snapshot = graph.copySolvableSubgraph(snapScope);
        job.scope = closed;
        job.snapModCount = graph.modCount();
        // Capture the pristine pre-solve scope state for conflict detection — the
        // solve below mutates its snapshot, so we cannot read it back afterwards.
        Map<NodePos, NodeState> refState = new HashMap<>(closed.size() * 2);
        for (NodePos pos : closed) {
            Node node = snapshot.getNode(pos);
            if (node != null) {
                refState.put(pos, NodeState.of(node));
            }
        }
        job.refState = refState;
        if (worker == null) {
            // Inline seam (MockBukkit has no scheduler threads): solve now, drain later.
            job.inlineResult = engine.settleResult(snapshot, closed, SolverCallback.NONE);
            job.future = null;
        } else {
            job.inlineResult = null;
            job.future = worker.submit(() -> engine.settleResult(snapshot, closed, SolverCallback.NONE));
        }
    }

    /**
     * If this world's solve has finished, decide match/conflict and return the
     * ordered collapse to apply. Returns {@code null} if the solve is still running
     * (park across ticks) or was discarded for a re-solve.
     */
    public CascadeEngine.SettleOutcome drainCompleted(World world) {
        Job job = jobs.get(world.getUID());
        if (job == null) {
            return null;
        }
        long start = System.nanoTime();

        CascadeEngine.SettleOutcome outcome;
        if (job.future != null) {
            if (!job.future.isDone()) {
                return null; // still solving — wait for a later tick
            }
            try {
                outcome = job.future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return failToInline(world, job, e);
            } catch (ExecutionException e) {
                return failToInline(world, job, e.getCause());
            }
        } else {
            outcome = job.inlineResult;
        }

        StructureGraph graph = structureManager.getGraph(world);
        if (graph == null) {
            jobs.remove(world.getUID());
            return new CascadeEngine.SettleOutcome(List.of(), false);
        }

        ScopeVerdict verdict = classifyScope(graph, job);
        if (verdict == ScopeVerdict.INVALIDATED) {
            asyncSettleConflicts++;
            if (deferWhile.getAsBoolean()) {
                // The conflict came from strux's own in-progress work (an active
                // blast carving the crater). Striking toward the inline fallback
                // here would run a keep-sized sync settle on the main thread — the
                // spike this feature exists to remove. Park instead: retire the
                // job without a strike; the resume manager resubmits once the
                // blast is done and the answer can finally stick.
                jobs.remove(world.getUID());
                return null;
            }
            job.strikes++;
            if (job.strikes >= MAX_STRIKES) {
                logger.warning(String.format(
                        "Async settle in world '%s' conflicted %d times over a scope of %d nodes; "
                                + "running one inline synchronous settle under the settle budget.",
                        world.getName(), job.strikes, job.scope.size()));
                jobs.remove(world.getUID());
                return structureManager.resumeCascade(world, job.scope);
            }
            // A block inside the scope changed the graph's TOPOLOGY mid-solve (added,
            // removed, re-grounded, reinforced, or repaired) — the answer is stale and
            // could over-collapse. Re-snapshot and re-submit; the newer state wins.
            startSolve(world, graph, job.scope, job);
            return null;
        }

        if (verdict == ScopeVerdict.DAMAGE_WORSENED) {
            // Damage only WORSENED on in-scope nodes (no topology change) — the classic
            // case being fire scorch charring blocks the settle already covers. Cascade
            // collapse is monotonic in damage: weakening a node never rescues a collapse
            // that already happened, so the solved collapse set is a SUBSET of the true
            // set for the worsened graph. Applying it is safe-but-conservative — every
            // returned block genuinely falls; any EXTRA collapse the added damage now
            // causes is picked up by the next settle the ongoing fire damage schedules.
            // This is the whole point: fire between blasts no longer burns strikes toward
            // a main-thread inline settle. (Repair — damage decreasing — is classified
            // INVALIDATED above, since a solved result could then over-collapse.)
            asyncSettleDamageSkips++;
        }

        // Match: apply the collapse to the live graph (the solve ran on a snapshot,
        // so the live graph is untouched), then hand the ordered collapse back for
        // the budgeted world apply.
        asyncSettleSolves++;
        structureManager.applyCollapsedToGraph(world, outcome.collapsed());
        jobs.remove(world.getUID());
        taskTimings.record(
                TaskTimings.ASYNC_SETTLE,
                System.nanoTime() - start,
                outcome.collapsed().size());
        return outcome;
    }

    /** Surface a worker failure at SEVERE and settle this job inline — never drop it. */
    private CascadeEngine.SettleOutcome failToInline(World world, Job job, Throwable cause) {
        logger.log(
                Level.SEVERE,
                "Async settle worker failed in world '" + world.getName()
                        + "'; falling back to an inline synchronous settle so the cascade is not dropped.",
                cause);
        jobs.remove(world.getUID());
        return structureManager.resumeCascade(world, job.scope);
    }

    /**
     * How an in-scope change since the snapshot relates to the solved answer.
     *
     * <ul>
     *   <li>{@code CLEAN} — nothing in the scope moved; the answer applies verbatim.
     *   <li>{@code DAMAGE_WORSENED} — the only in-scope changes were damage getting
     *       <em>worse</em>. The solved collapse is a conservative subset of the true
     *       collapse (cascade collapse is monotonic in damage), so it is still safe to
     *       apply without a re-solve. See the apply site for the full argument.
     *   <li>{@code INVALIDATED} — a topology change (block added/removed, re-grounded,
     *       reinforced, or <em>repaired</em>) that could make the solved answer
     *       over-collapse. Must strike toward a re-solve; the newer state wins.
     * </ul>
     */
    private enum ScopeVerdict {
        CLEAN,
        DAMAGE_WORSENED,
        INVALIDATED
    }

    /**
     * Classify what changed inside the solved scope since the snapshot. The live
     * {@code modCount} is the fast path (unchanged ⇒ nothing changed anywhere);
     * otherwise a per-scope recheck restricts the verdict to the solved region so an
     * edit OUTSIDE the scope never forces a re-solve. Any single INVALIDATED node
     * dominates the whole verdict.
     */
    private ScopeVerdict classifyScope(StructureGraph live, Job job) {
        if (live.modCount() == job.snapModCount) {
            return ScopeVerdict.CLEAN;
        }
        boolean sawDamageWorsening = false;
        for (NodePos pos : job.scope) {
            Node liveNode = live.getNode(pos);
            NodeState ref = job.refState.get(pos);
            boolean liveHas = liveNode != null;
            boolean refHas = ref != null;
            if (liveHas != refHas) {
                return ScopeVerdict.INVALIDATED; // a block was added or removed in the scope
            }
            if (!liveHas) {
                continue; // absent at both snapshot and now — nothing to compare
            }
            if (liveNode.isGrounded() != ref.grounded() || liveNode.reinforcement() != ref.reinforcement()) {
                return ScopeVerdict.INVALIDATED; // re-grounded or reinforced — could over-collapse
            }
            double damageDelta = liveNode.damage() - ref.damage();
            if (damageDelta < 0.0) {
                return ScopeVerdict.INVALIDATED; // repaired — a solved collapse could over-collapse
            }
            if (damageDelta > 0.0) {
                sawDamageWorsening = true; // charred (e.g. fire scorch) — conservative, keep scanning
            }
        }
        return sawDamageWorsening ? ScopeVerdict.DAMAGE_WORSENED : ScopeVerdict.CLEAN;
    }

    /** Drop all in-flight jobs (plugin disable / world unload). */
    public void clear() {
        jobs.clear();
    }
}
