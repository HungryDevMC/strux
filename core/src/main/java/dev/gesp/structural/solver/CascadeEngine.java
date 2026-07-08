package dev.gesp.structural.solver;

import dev.gesp.structural.api.CollapseReason;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.metrics.StruxMetrics;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Drives cascade collapse when blocks are removed.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                      HOW CASCADE WORKS                             │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  1. Block is removed (trigger)                                     │
 *   │                                                                     │
 *   │  2. Check for FLOATING blocks (not connected to ground)            │
 *   │     → These collapse immediately, no stress check needed           │
 *   │                                                                     │
 *   │  3. Recalculate stress for affected blocks                         │
 *   │                                                                     │
 *   │  4. Find the most stressed block                                   │
 *   │                                                                     │
 *   │  5. If stress > 100%, that block collapses                         │
 *   │     → Go back to step 2                                            │
 *   │                                                                     │
 *   │  6. If no block is over 100%, cascade is done                      │
 *   │                                                                     │
 *   │                                                                     │
 *   │  EXAMPLE:                                                          │
 *   │                                                                     │
 *   │       [E]                                                          │
 *   │        │                                                           │
 *   │       [D]         Player breaks [B]:                               │
 *   │        │          1. C, D, E are now floating → collapse           │
 *   │       [C]         2. A recalculates stress                         │
 *   │        │          3. A is fine → cascade done                      │
 *   │       [B] ← break                                                  │
 *   │        │          Result: C, D, E collapsed                        │
 *   │       [A]                                                          │
 *   │        │                                                           │
 *   │      [GND]                                                         │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class CascadeEngine {

    private final StressSolver solver;
    private final PhysicsConfig config;

    /** Optional work-counter; null on the production path. See {@link StruxMetrics}. */
    private StruxMetrics metrics;

    // ── Cross-call resume state for the two interruptible units ─────────────
    // A budget-paused settle reports truncated() with a scope the caller feeds
    // back next tick. To make the two heavy units (the affectedRegion closure and
    // the findOverloadedBatch level scan) actually interruptible — not just paused
    // BETWEEN units — we park their live cursor here, keyed by the scope the caller
    // will hand back. A resume whose scope matches picks the cursor up where it
    // stopped; a non-matching scope (a different disturbance, or a caller that
    // unioned more region in) drops the stale cursor and recomputes from scratch
    // (correct, just no resume saving). One engine drives one manager's cascades.
    private Set<NodePos> pausedScopeKey;
    private StructureGraph.ScopeClosureCursor pendingClosure;
    private StressSolver.BatchScanCursor pendingBatch;

    // A budget pause that lands INSIDE a collapse loop (an overload batch or a
    // floating drain) parks the un-collapsed remainder here, keyed by the same
    // scope as the cursors above. On resume this EXACT list is replayed —
    // before any re-query — so the pause never changes WHICH blocks collapse.
    // Without this the resume re-derives the batch from the now-mutated graph
    // (members removed, arms stranded, debris applied), and since the adapter's
    // pause is a wall-clock deadline, server load could change the physics
    // outcome. `pendingFloatingOwed` records that the parked remainder was an
    // overload batch whose post-batch floating drain has not run yet, so the
    // resume runs it after the batch remainder (matching the unpaused order).
    private List<NodePos> pendingBatchRemainder;
    private boolean pendingFloatingOwed;
    private List<NodePos> pendingFloatingRemainder;
    // The initial floating drain is cap-EXEMPT (a cut column always falls
    // completely); the post-batch drain is cap-bound. A parked floating remainder
    // remembers which it came from so its resume replay keeps the same cap rule.
    private boolean pendingFloatingCapExempt;

    private void clearPending() {
        pausedScopeKey = null;
        pendingClosure = null;
        pendingBatch = null;
        pendingBatchRemainder = null;
        pendingFloatingOwed = false;
        pendingFloatingRemainder = null;
        pendingFloatingCapExempt = false;
    }

    /**
     * Create a cascade engine with default config.
     */
    public CascadeEngine() {
        this(new PhysicsConfig());
    }

    /**
     * Create a cascade engine with custom physics config.
     */
    public CascadeEngine(PhysicsConfig config) {
        this.config = config;
        this.solver = new StressSolver(config);
    }

    /**
     * Create a cascade engine with custom solver (for testing).
     */
    public CascadeEngine(StressSolver solver, PhysicsConfig config) {
        this.solver = solver;
        this.config = config;
    }

    /**
     * Attach (or detach, with {@code null}) a work-counter, propagating it to the
     * underlying solver so solver passes are counted too. Optional; only
     * tests/benchmarks need this. Returns {@code this} for chaining.
     */
    public CascadeEngine setMetrics(StruxMetrics metrics) {
        this.metrics = metrics;
        solver.setMetrics(metrics);
        return this;
    }

    /**
     * Process a block removal and return what collapsed.
     *
     * @param graph      the structure
     * @param triggerPos the block being removed (player broke it)
     * @param callback   receives events during cascade
     * @return what collapsed and how many steps it took
     */
    public CascadeResult cascade(StructureGraph graph, NodePos triggerPos, SolverCallback callback) {
        return cascade(graph, triggerPos, callback, NEVER_PAUSE);
    }

    /**
     * Like {@link #cascade(StructureGraph, NodePos, SolverCallback)} but with a
     * cooperative budget — see
     * {@link #settleResult(StructureGraph, Set, SolverCallback, BooleanSupplier)}.
     * When the budget pauses the settle, the result reports {@code truncated()}
     * with the live {@code remainingScope()} for a cross-tick resume.
     */
    public CascadeResult cascade(
            StructureGraph graph, NodePos triggerPos, SolverCallback callback, BooleanSupplier pause) {
        // Capture trigger node info BEFORE removal - callers need this to schedule
        // world-side collapse for internal triggers (entity weight, fire, etc.).
        Node triggerNode = graph.getNode(triggerPos);
        CollapsedNode triggerCollapsed = triggerNode != null
                ? CollapsedNode.from(triggerNode)
                : new CollapsedNode(triggerPos, new MaterialSpec(1.0, 10.0));

        // Fast path: if nothing depends on this block (no blocks above or at same
        // level with structures), skip cascade entirely. This makes breaking flat
        // terrain instant.
        Set<NodePos> dependents = graph.getDependentSubgraph(triggerPos);
        if (dependents.size() <= 1) {
            // Only the trigger itself - nothing to cascade
            if (graph.removeBlock(triggerPos) != null && metrics != null) {
                metrics.blocksRemoved++;
            }
            // Fire callback for the trigger so callers can handle it (e.g., EntityWeightTask
            // needs to schedule world-side collapse). The result is empty because nothing
            // else collapsed - the caller handles the trigger separately.
            callback.onCascadeStep(triggerCollapsed, 0, CollapseReason.TRIGGER);
            callback.onCascadeComplete(List.of());
            return new CascadeResult(List.of(), 0);
        }

        // Seed the settle with the disturbance: everything that depended on
        // the trigger, plus the trigger's neighbors (they receive the
        // redistributed load) — captured BEFORE removal, while the trigger
        // still has edges. settle() widens this to the structural closure —
        // the smallest region the solver can be EXACT on — so the work stays
        // bounded by structure size, not terrain size.
        Set<NodePos> scope = new HashSet<>(dependents);
        scope.addAll(graph.getNeighbors(triggerPos));

        // Remove the trigger block first
        if (graph.removeBlock(triggerPos) != null && metrics != null) {
            metrics.blocksRemoved++;
        }
        dependents.remove(triggerPos);
        scope.remove(triggerPos);

        // Fire callback for the trigger (step 0) so callers can handle it
        // (e.g., EntityWeightTask needs to schedule world-side collapse).
        // Step 0 because it happens before the settle; settle steps start at 1.
        callback.onCascadeStep(triggerCollapsed, 0, CollapseReason.TRIGGER);

        SettleOutcome outcome = settleResult(graph, scope, callback, pause);
        List<CollapsedNode> collapsed = outcome.collapsed();
        callback.onCascadeComplete(collapsed);
        return new CascadeResult(collapsed, collapsed.size(), outcome.truncated(), outcome.remainingScope());
    }

    /**
     * Settle a graph that has ALREADY been disturbed — blocks removed and/or
     * damaged by the caller — until it is stable again.
     *
     * <pre>
     *   1. Collapse anything now floating (no path to ground).
     *   2. Progressively collapse the most-overloaded block (farthest first),
     *      re-checking for newly-floating blocks after each one.
     *   3. Repeat until nothing is floating or overloaded (or the cascade cap
     *      is hit).
     * </pre>
     *
     * <p>This is the shared engine behind both {@link #cascade} (single trigger)
     * and explosions (many blocks removed/weakened up front). It fires
     * {@code onStressUpdated} / {@code onCascadeStep}, but NOT
     * {@code onCascadeComplete} — the caller owns "done".
     *
     * @return nodes that collapsed (with material info), in order
     */
    public List<CollapsedNode> settle(StructureGraph graph, SolverCallback callback) {
        // Whole-graph settle: scope is every position. Used by explosions, which
        // disturb the graph broadly up front.
        return settle(graph, new HashSet<>(graph.getAllPositions()), callback);
    }

    /**
     * What a {@link #settleResult settle} did: the nodes that collapsed and
     * whether the step cap cut the settle short while overloads still remained.
     *
     * <p>{@code truncated} is the resume signal: when {@code true}, the graph is
     * left mid-collapse (blocks may still be overloaded or newly floating), so
     * the caller should settle again on a later tick until a pass reports no
     * collapse and no truncation. Pure floating collapse is exempt from the cap,
     * so a settle that only had floaters to drop never reports truncation.
     *
     * <p>{@code remainingScope} is the live affected region the pass was still
     * working on when it stopped — the seed a resume should settle next, instead
     * of re-deriving the whole graph. It is non-empty only when {@code truncated}
     * (a clean finish leaves nothing to resume), so feeding it back keeps each
     * resume tick's solver work bounded by the disturbed structure, not the whole
     * (terrain-sized) graph. See {@code StructureManager.resumeCascade}.
     */
    public record SettleOutcome(List<CollapsedNode> collapsed, boolean truncated, Set<NodePos> remainingScope) {
        public SettleOutcome {
            collapsed = List.copyOf(collapsed);
            remainingScope = Set.copyOf(remainingScope);
        }

        /** A settle that left nothing to resume (no surviving disturbed region). */
        public SettleOutcome(List<CollapsedNode> collapsed, boolean truncated) {
            this(collapsed, truncated, Set.of());
        }
    }

    /**
     * Whole-graph {@link #settleResult} (scope is every position). Used by
     * explosions, which disturb the graph broadly up front.
     */
    public SettleOutcome settleResult(StructureGraph graph, SolverCallback callback) {
        return settleResult(graph, new HashSet<>(graph.getAllPositions()), callback);
    }

    /**
     * Whole-graph {@link #settleResult} with a cooperative budget — see
     * {@link #settleResult(StructureGraph, Set, SolverCallback, BooleanSupplier)}.
     */
    public SettleOutcome settleResult(StructureGraph graph, SolverCallback callback, BooleanSupplier pause) {
        return settleResult(graph, new HashSet<>(graph.getAllPositions()), callback, pause);
    }

    /**
     * Settle, re-solving only the region the disturbance can affect. The
     * caller passes the disturbed SEED positions; this widens them to their
     * structural closure ({@link StructureGraph#affectedRegion}) — the
     * smallest set the stress solver is exact on — so the result matches a
     * whole-graph settle while skipping inert terrain and untouched
     * structures. The working scope then shrinks as blocks collapse and grows
     * if falling debris reaches another structure.
     */
    public List<CollapsedNode> settle(StructureGraph graph, Set<NodePos> scope, SolverCallback callback) {
        return settleResult(graph, scope, callback).collapsed();
    }

    /**
     * Like {@link #settle(StructureGraph, Set, SolverCallback)} but also reports
     * whether the step cap cut the settle short — see {@link SettleOutcome}. This
     * is the entry point adapters use to drive cross-tick resumption: keep
     * calling it until a pass collapses nothing and reports {@code !truncated()}.
     */
    public SettleOutcome settleResult(StructureGraph graph, Set<NodePos> scope, SolverCallback callback) {
        return settleResult(graph, scope, callback, NEVER_PAUSE);
    }

    /** The default budget: never pause — settle runs to the step cap or stability. */
    private static final BooleanSupplier NEVER_PAUSE = () -> false;

    /**
     * Like {@link #settleResult(StructureGraph, Set, SolverCallback)} but
     * cooperatively pausable: {@code pause} is consulted between units of work
     * (after each collapse, after each scope expansion round) and, when it
     * returns {@code true}, the settle stops where it is and reports
     * {@code truncated} with the live scope as {@code remainingScope} — exactly
     * the state a later call (next tick) resumes from.
     *
     * <p><b>Guaranteed progress.</b> The supplier is only consulted AFTER real
     * progress: at least one collapsed block, one scope-expansion round, or a
     * completed stability proof. So a caller that passes an already-expired
     * deadline still advances the cascade every call — a settle can be slowed
     * arbitrarily, but never starved into an infinite resume loop.
     *
     * <p>This is the adapters' anti-freeze hook: a per-tick wall-clock deadline
     * goes in, and a too-big cascade becomes a multi-tick (delayed) collapse
     * instead of a frozen server. Individual solver/floating queries remain
     * atomic — the pause lands between them, not inside — so the collapse
     * order and final stable state are identical to an unbudgeted settle.
     */
    public SettleOutcome settleResult(
            StructureGraph graph, Set<NodePos> scope, SolverCallback callback, BooleanSupplier pause) {
        // ── Resume dispatch ──
        // If a prior pass parked a cursor for exactly this scope, pick it up. A batch
        // scan was parked AFTER the closure completed, so its scope is already closed —
        // skip the closure entirely and resume the scan. A closure cursor resumes the
        // BFS. Anything else is a fresh disturbance: drop any stale cursor.
        StressSolver.BatchScanCursor resumeBatch = null;
        StructureGraph.ScopeClosureCursor closureCursor = null;
        List<NodePos> resumeBatchRemainder = null;
        List<NodePos> resumeFloatingRemainder = null;
        boolean resumeFloatingOwed = false;
        boolean resumeFloatingCapExempt = false;
        if (pausedScopeKey != null && pausedScopeKey.equals(scope)) {
            resumeBatch = pendingBatch;
            closureCursor = pendingClosure;
            resumeBatchRemainder = pendingBatchRemainder;
            resumeFloatingRemainder = pendingFloatingRemainder;
            resumeFloatingOwed = pendingFloatingOwed;
            resumeFloatingCapExempt = pendingFloatingCapExempt;
        }
        // Everything is captured in locals now; start from a clean slate so a fresh
        // pause this call re-parks exactly what it interrupts.
        clearPending();

        // Close the seeds: the solver's distances, load shares and moment
        // arms are only exact when every evaluated node's upstream cone and
        // support paths are visible. Without the closure, load that belongs
        // to unseen neighbors piles onto seen ones (phantom overloads) or
        // goes missing (missed ones). The closure itself is interruptible: on a
        // keep-sized disturbance its BFS alone can cost hundreds of ms, so a
        // budget pause parks it and reports truncated with the SEED set — the
        // resume continues the SAME closure, set-equal to the uninterrupted one.
        if (resumeBatch == null && resumeBatchRemainder == null && resumeFloatingRemainder == null) {
            if (closureCursor == null) {
                closureCursor = new StructureGraph.ScopeClosureCursor(scope);
            }
            Set<NodePos> region = graph.affectedRegion(closureCursor, pause);
            if (!closureCursor.isComplete()) {
                pausedScopeKey = new HashSet<>(scope);
                pendingClosure = closureCursor;
                pendingBatch = null;
                // Nothing collapsed yet; hand back the SEEDS so the resume rebuilds the
                // identical closure. remainingScope is the original seeds, not the
                // partial region (re-seeding context columns would grow it wrongly).
                return new SettleOutcome(List.of(), true, new HashSet<>(scope));
            }
            clearPending();
            scope = region;
        }
        // else: scope is already the closed region the parked batch scan was working.

        List<CollapsedNode> collapsed = new ArrayList<>();
        // The running step count lives in a one-cell holder so {@link #collapseList}
        // can advance it in place across every collapse site (the initial floaters,
        // the overload batch, the post-batch floating drain, and the resume replay).
        int[] stepNumber = {0};
        // Truncation = the step cap stopped solver work while overloads still
        // remained, NOT a clean stable finish. We set this only when the
        // overload loop is forced to stop with collapse work still pending.
        boolean truncated = false;
        // The budget pause tripped: stop where we are and report truncated so the
        // caller resumes next tick. Checked only AFTER real progress (see the
        // pausable settleResult overload's javadoc).
        boolean paused = false;
        // Boundary-guard expansion width, doubling per guard round (see below).
        int expandRings = 1;

        // Maintain distance-from-ground for the scope INCREMENTALLY across the
        // whole settle: build once, then repair cheaply as collapses delete
        // blocks, instead of re-running a full O(N) BFS inside every
        // findOverloadedBatch. The repaired map is byte-identical to that BFS
        // (GroundDistanceIndexTest), so the collapse outcome is unchanged.
        ScopeIndex scopeIndex = new ScopeIndex(graph, scope);

        // ── Resume replay ──
        // A prior pass that budget-paused INSIDE a collapse loop parked its
        // un-collapsed remainder. Replay THAT EXACT LIST — before any re-query — so a
        // paused settle collapses the same blocks, in the same order, an unpaused one
        // would. Re-deriving the batch from the now-mutated graph here is the
        // pacing-contract bug this guards against: a removed member can change what
        // findOverloadedBatch returns, and the adapter's pause is a wall-clock
        // deadline, so re-querying could let SERVER LOAD change which blocks die.
        //
        // The replay is still budget-paced (it drives the SAME collapseList, which
        // re-parks the tail on a pause), so each pass stays bounded — it just replays
        // the parked remainder rather than re-querying it. "Unconditional" means
        // "not re-derived from the graph", not "collapsed all at once".
        if (resumeBatchRemainder != null) {
            paused = collapseList(
                    graph,
                    scope,
                    scopeIndex,
                    callback,
                    collapsed,
                    resumeBatchRemainder,
                    true,
                    false,
                    stepNumber,
                    pause);
            // Run the batch's owed post-batch floating drain only once the WHOLE batch
            // remainder is collapsed (if the replay paused partway, collapseList re-parked
            // the rest and kept the owed flag, so we resume the batch first). The drain's
            // findFloatingInScope is identical to the unpaused path's post-batch drain —
            // same graph, same scope — so the order matches.
            if (!paused && resumeFloatingOwed) {
                List<NodePos> owedFloaters = canonical(graph.findFloatingInScope(scope));
                paused = collapseList(
                        graph, scope, scopeIndex, callback, collapsed, owedFloaters, false, false, stepNumber, pause);
            }
        } else if (resumeFloatingRemainder != null) {
            paused = collapseList(
                    graph,
                    scope,
                    scopeIndex,
                    callback,
                    collapsed,
                    resumeFloatingRemainder,
                    false,
                    resumeFloatingCapExempt,
                    stepNumber,
                    pause);
        }

        // Floating detection scoped to the affected component. Since scope is
        // a connected component, paths to ground within scope are equivalent
        // to paths in the full graph. Collapse in canonical order so that
        // debris impact (which depends on which block falls first) is
        // reproducible across runs instead of following HashSet iteration.
        //
        // Skipped when resuming a parked batch scan OR a parked collapse remainder:
        // the initial floaters were already drained on the call that parked, and
        // re-running the query could collapse a block and invalidate the parked plan.
        if (resumeBatch == null && resumeBatchRemainder == null && resumeFloatingRemainder == null) {
            // The initial floating drain is cap-EXEMPT: a cut column always falls
            // completely (CascadeCapTest.floatingCollapseIsExemptFromCap).
            List<NodePos> initialFloaters = canonical(graph.findFloatingInScope(scope));
            paused = collapseList(
                    graph, scope, scopeIndex, callback, collapsed, initialFloaters, false, true, stepNumber, pause);
        }

        // Progressive stress-based collapse. Far-from-support fails first, so
        // load can't propagate through a block that should already be breaking.
        // The trivially-stable optimization in the solver filters out unaffected
        // blocks, keeping performance bounded by affected structure size.
        //
        // OPTIMIZATION: Use batch collapse to process all overloaded blocks at
        // the same distance level in one pass, reducing solve invocations.
        while (!paused && stepNumber[0] < config.getMaxCascadeSteps()) {
            // The level scan is itself interruptible: on a keep-sized scope a single
            // clean stability proof can cost hundreds of ms. Resume a parked scan if
            // this call inherited one (its plan already holds the distances, so skip
            // the ScopeIndex rebuild); otherwise start a fresh scan.
            StressSolver.BatchScanCursor batchCursor =
                    resumeBatch != null ? resumeBatch : new StressSolver.BatchScanCursor();
            resumeBatch = null; // consumed — later scans this call start fresh
            List<NodePos> overloadedBatch = solver.findOverloadedBatch(
                    graph, scope, batchCursor.isStarted() ? null : scopeIndex.distances(), pause, batchCursor);
            if (!batchCursor.isComplete()) {
                // The scan paused mid-stability-proof (no collapse this round, so the
                // graph is unchanged). Park it and report truncated with the closed
                // scope, so the resume picks the scan up at its next level.
                pausedScopeKey = new HashSet<>(scope);
                pendingBatch = batchCursor;
                pendingClosure = null;
                paused = true;
                break;
            }
            if (callback.wantsStressUpdates()) {
                callback.onStressUpdated(buildStressMap(graph, scope));
            }
            if (callback.wantsStressDeltas()) {
                callback.onStressDelta(buildStressMap(graph, scope));
            }

            if (overloadedBatch.isEmpty()) {
                break; // stable
            }

            // BOUNDARY GUARD: stress at the scope edge is approximate — the
            // solver's distance BFS cannot leave the scope, so load that
            // really belongs to an unseen neighbor piles onto the nodes it
            // CAN see (and load that should arrive via unseen paths goes
            // missing). Never collapse on approximate numbers: if a candidate
            // has a neighbor outside the scope, widen the scope around it and
            // re-solve first. Load conservation migrates the misplaced excess
            // to its true owner; the expansion strictly grows the scope, so
            // this terminates (worst case: the connected component — exactly
            // the brute-force solve the scoped path must agree with).
            Set<NodePos> expansion = new HashSet<>();
            for (NodePos candidate : overloadedBatch) {
                for (NodePos neighbor : graph.getNeighbors(candidate)) {
                    if (graph.hasBlock(neighbor) && !scope.contains(neighbor)) {
                        expansion.add(neighbor);
                    }
                }
            }
            if (!expansion.isEmpty()) {
                // Expand by `expandRings` neighbor layers, DOUBLING on each
                // consecutive guard round. The first round adds one ring — exactly
                // the old behavior, so a cascade that needs one small widening pays
                // nothing extra. But the old guard expanded ONE ring per full
                // scope-wide re-solve, so a structure leaning on a large marginal
                // region (the 124k-block siege terrain) paid a re-solve per block
                // layer — the quadratic behind the 10s+ watchdog stalls. Doubling
                // makes covering distance L cost O(log L) re-solves with at most a
                // ~2x scope overshoot, while never eagerly flooding the whole
                // component the way a one-jump closure would (that pessimized every
                // small cascade near big terrain by two orders of magnitude).
                // Collapse decisions are unchanged either way: the loop still only
                // collapses once no candidate touches an out-of-scope neighbor, so
                // stress is exact wherever it is acted on.
                Set<NodePos> frontier = expansion;
                for (int ring = 0; ring < expandRings && !frontier.isEmpty(); ring++) {
                    Set<NodePos> next = new HashSet<>();
                    for (NodePos pos : frontier) {
                        if (scopeIndex.add(pos)) {
                            for (NodePos anc : graph.getSupportAncestors(pos)) {
                                scopeIndex.add(anc);
                            }
                            for (NodePos nb : graph.getNeighbors(pos)) {
                                if (graph.hasBlock(nb) && !scope.contains(nb)) {
                                    next.add(nb);
                                }
                            }
                        }
                    }
                    frontier = next;
                }
                // Double only across CONSECUTIVE guard rounds (the crawl case);
                // any round that reaches a collapse resets the width below. A
                // persistent width would make a LATE one-ring need jump dozens of
                // rings and flood the scope with inert terrain, slowing every
                // subsequent solve pass for the rest of the settle.
                expandRings = Math.min(expandRings * 2, 1 << 16);
                // An expansion round IS progress (the scope grew strictly toward
                // the finite component), so the budget may pause here. Without
                // this check a structure leaning on a huge marginal region (e.g.
                // 100k+ registered terrain blocks) re-solves the ever-growing
                // scope layer by layer in ONE call — the quadratic freeze the
                // budget exists to prevent.
                if (pause.getAsBoolean()) {
                    paused = true;
                    break;
                }
                continue; // re-solve with the wider view before collapsing anything
            }

            // The scope is closed around this batch (no expansion needed), so the
            // guard crawl — if there was one — is over; the next expansion episode
            // starts narrow again.
            expandRings = 1;

            // Collapse all overloaded blocks at this level, in canonical order
            // (the batch comes from a HashSet-backed solver pass). If the budget
            // pauses mid-batch, collapseList parks the un-collapsed remainder so the
            // resume finishes it before re-querying — never re-deriving the batch
            // from the mutated graph.
            overloadedBatch.sort(NodePos.CANONICAL_ORDER);
            paused = collapseList(
                    graph, scope, scopeIndex, callback, collapsed, overloadedBatch, true, false, stepNumber, pause);
            if (paused) {
                break;
            }

            // Check for floating after batch collapse (canonical order). A mid-drain
            // pause parks the floating remainder the same way, so the resume replays
            // it rather than re-snapshotting the mutated graph.
            List<NodePos> postFloaters = canonical(graph.findFloatingInScope(scope));
            paused = collapseList(
                    graph, scope, scopeIndex, callback, collapsed, postFloaters, false, false, stepNumber, pause);
        }

        // Truncation = the step cap stopped us with collapse work still pending.
        // The loop exits either by the cap (stepNumber hit maxCascadeSteps) or by
        // a stable break (overloadedBatch was empty). Only the former can leave
        // work behind — and only if work genuinely remains, so a settle that
        // happened to end exactly on the cap with a stable graph is NOT
        // truncated. Pure floating collapse drains in the uncapped initial pass,
        // so it leaves nothing here and never reports truncation. This extra
        // scan runs only when the cap was reached (the rare, already-truncated
        // path), so the common, well-behaved cascade pays nothing for it.
        if (paused) {
            // Budget pause: work very likely remains, and proving otherwise costs a
            // full solver + floating re-query — exactly the work we just ran out of
            // budget for. Report truncated unconditionally; the resume pass that
            // finds nothing left simply finishes cleanly.
            truncated = true;
        } else if (stepNumber[0] >= config.getMaxCascadeSteps()) {
            boolean workRemains = !solver.findOverloadedBatch(graph, scope, scopeIndex.distances())
                            .isEmpty()
                    || !graph.findFloatingInScope(scope).isEmpty();
            truncated = workRemains;
        }

        // Carry the live affected region forward only when there is more to do.
        // `scope` here is the same set the ScopeIndex kept current as blocks
        // collapsed (removed) and the boundary guard expanded it, so it is exactly
        // the disturbed region a resume should re-settle — not the whole graph.
        return new SettleOutcome(collapsed, truncated, truncated ? new HashSet<>(scope) : Set.of());
    }

    /**
     * Collapse an ordered list of nodes (an overload batch or a floating snapshot),
     * advancing {@code stepNumber} in place and firing {@code onCascadeStep} for each.
     *
     * <p>This is the single collapse site behind every phase of the settle, so the
     * budget's mid-loop behavior is defined in exactly one place: when {@code pause}
     * trips after a collapse and un-collapsed members remain, the remainder is PARKED
     * (keyed by {@code scope}) for a resume replay (before any re-query), and the method
     * returns {@code true}. That is what keeps a budget pause from changing WHICH
     * blocks collapse — the resume finishes this exact list instead of re-deriving a
     * (possibly different) batch from the graph the earlier collapses already mutated.
     *
     * <p>An {@code overloaded} list parks as {@link #pendingBatchRemainder} with
     * {@link #pendingFloatingOwed} set (its post-batch floating drain still owes a
     * run); a floating list parks as {@link #pendingFloatingRemainder}. The step cap
     * is honored (unless {@code capExempt}) but is NOT a pause — hitting it returns
     * {@code false} and parks nothing (the deterministic cap path re-queries on
     * resume, unchanged).
     *
     * @param capExempt when {@code true} the step cap is ignored — used for the
     *     initial floating drain, where a cut column always falls completely
     *     (a parked remainder of an exempt drain remembers this for its replay).
     * @return {@code true} if the budget paused this list (caller stops the settle)
     */
    private boolean collapseList(
            StructureGraph graph,
            Set<NodePos> scope,
            ScopeIndex scopeIndex,
            SolverCallback callback,
            List<CollapsedNode> collapsed,
            List<NodePos> list,
            boolean overloaded,
            boolean capExempt,
            int[] stepNumber,
            BooleanSupplier pause) {
        CollapseReason reason = overloaded ? CollapseReason.OVERLOADED : CollapseReason.FLOATING;
        for (int i = 0; i < list.size(); i++) {
            if (!capExempt && stepNumber[0] >= config.getMaxCascadeSteps()) {
                return false; // step cap (deterministic) — not a budget pause, park nothing
            }
            stepNumber[0]++;
            CollapsedNode collapsedNode = collapse(graph, list.get(i), scopeIndex, overloaded);
            collapsed.add(collapsedNode);
            callback.onCascadeStep(collapsedNode, stepNumber[0], reason);
            if (pause.getAsBoolean()) {
                if (i + 1 < list.size()) {
                    // Park the un-collapsed remainder for a resume replay (before any re-query).
                    pausedScopeKey = new HashSet<>(scope);
                    List<NodePos> remainder = new ArrayList<>(list.subList(i + 1, list.size()));
                    if (overloaded) {
                        pendingBatchRemainder = remainder;
                        pendingFloatingOwed = true;
                        pendingFloatingRemainder = null;
                    } else {
                        pendingFloatingRemainder = remainder;
                        pendingFloatingCapExempt = capExempt;
                        pendingBatchRemainder = null;
                        pendingFloatingOwed = false;
                    }
                    pendingClosure = null;
                    pendingBatch = null;
                }
                // else: paused exactly at the list's end — a clean re-query boundary,
                // identical to the unpaused path looping back to the next scan.
                return true;
            }
        }
        return false;
    }

    /**
     * Remove a collapsing node and, if debris loading is enabled, drop its mass
     * onto the first standing block beneath it as impact damage. That extra
     * damage can overload the block below on the next solve, so collapses
     * pancake downward — a tall section coming down can drive the lower section
     * past its capacity.
     *
     * @return the collapsed node with its material info preserved
     */
    private CollapsedNode collapse(StructureGraph graph, NodePos pos, ScopeIndex scope, boolean overloaded) {
        // Capture node info BEFORE removal. An overloaded collapse also captures how
        // loaded the node was when it failed (stressAtCollapse); a floating one leaves
        // it 0.0 because it fell for lack of support, not load.
        Node node = graph.getNode(pos);
        CollapsedNode collapsedNode;
        if (node == null) {
            collapsedNode = new CollapsedNode(pos, new MaterialSpec(1.0, 10.0));
        } else if (overloaded) {
            collapsedNode = CollapsedNode.fromOverloaded(node);
        } else {
            collapsedNode = CollapsedNode.from(node);
        }

        double scale = config.getDebrisImpactScale();
        double fallerMass = node != null ? node.mass() : 0.0;

        // Expand scope to include neighbors BEFORE removal (they may be
        // affected) — together with their support paths, or the solver can't
        // compute their distances and silently skips them.
        for (NodePos neighbor : graph.getNeighbors(pos)) {
            if (graph.hasBlock(neighbor) && scope.add(neighbor)) {
                for (NodePos anc : graph.getSupportAncestors(neighbor)) {
                    scope.add(anc);
                }
            }
        }

        graph.removeBlock(pos);
        scope.removeCollapsed(pos); // it's gone — keep the scope (and index) down to live nodes
        if (metrics != null) {
            metrics.blocksRemoved++;
        }
        if (scale > 0 && fallerMass > 0) {
            applyDebrisImpact(graph, pos, fallerMass, scale, scope);
        }

        return collapsedNode;
    }

    /** Drop debris straight down from {@code from}, damaging the first block it hits. */
    private void applyDebrisImpact(StructureGraph graph, NodePos from, double mass, double scale, ScopeIndex scope) {
        int minDrop = config.getMinImpactDrop();
        int floor = from.y() - 128; // bound the downward search
        for (int y = from.y() - 1; y >= floor; y--) {
            NodePos targetPos = new NodePos(from.x(), y, from.z());
            Node target = graph.getNode(targetPos);
            if (target == null) {
                continue; // empty space — debris keeps falling
            }
            if (target.isGrounded()) {
                return; // the earth absorbs it
            }
            int drop = from.y() - y;
            if (drop < minDrop) {
                return; // too short a fall to matter
            }
            // Normalize the impact energy (mass × drop) by the target's EFFECTIVE
            // capacity, not its pristine maxLoad: an already-cracked or reinforced
            // block must take debris damage against the strength it actually has left
            // (this is the same capacity every overload check uses). An undamaged,
            // unreinforced block is unaffected (effectiveMaxLoad == maxLoad there).
            double capacity = target.effectiveMaxLoad();
            double damage = capacity > 0 ? (mass * drop * scale) / capacity : 1.0;
            target.addDamage(Math.min(1.0, damage));
            // If debris crossed into a DIFFERENT structure, pull that damaged
            // block into scope so it re-solves — with its support path, or the
            // solver can't compute its distance and skips it. We DON'T pull
            // the entire component (for large connected terrain that would be
            // O(terrain_size)); if the block fails, its dependents cascade
            // naturally.
            if (scope.add(targetPos)) {
                for (NodePos anc : graph.getSupportAncestors(targetPos)) {
                    scope.add(anc);
                }
            }
            return; // debris stops at the first thing it lands on
        }
    }

    /**
     * Convenience method: cascade with no callback.
     */
    public CascadeResult cascade(StructureGraph graph, NodePos triggerPos) {
        return cascade(graph, triggerPos, SolverCallback.NONE);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HELPER METHODS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Sort a set of positions into the canonical, run-stable order
     * ({@link NodePos#CANONICAL_ORDER}). Used wherever a {@code HashSet} of
     * collapse candidates is about to be processed in a way that has side
     * effects (debris impact), so the result does not depend on hash iteration.
     */
    private static List<NodePos> canonical(Set<NodePos> positions) {
        List<NodePos> ordered = new ArrayList<>(positions);
        ordered.sort(NodePos.CANONICAL_ORDER);
        return ordered;
    }

    /**
     * Build a map of position → stress percent for the blocks in {@code scope}.
     *
     * <p>Scope is the only region the settle re-solves, so it is the only region
     * whose stress changed since the last update — building over the whole graph
     * (every {@code getAllNodes()}) would re-report thousands of unchanged terrain
     * blocks every settle step. This feeds the {@code onStressUpdated} FX callback
     * (e.g. shake/crack visuals), not the physics, so narrowing it changes nothing
     * the solver reads.
     */
    private Map<NodePos, Double> buildStressMap(StructureGraph graph, Set<NodePos> scope) {
        Map<NodePos, Double> map = new HashMap<>();
        for (NodePos pos : scope) {
            Node node = graph.getNode(pos);
            if (node != null) {
                map.put(pos, node.stressPercent());
            }
        }
        return map;
    }

    /**
     * The scope set PLUS a {@link GroundDistanceIndex} kept in lock-step with it, so
     * the progressive solver reads distance-from-ground from one incrementally-repaired
     * map instead of re-running a full BFS every pass.
     *
     * <pre>
     *   collapse() deletes a block        → removeCollapsed(): drop it from scope and
     *                                        repair the index decrementally (cheap).
     *   boundary guard / debris grow scope → add(): the index has no incremental ADD,
     *                                        so mark it stale; the next distances()
     *                                        rebuilds it over the (now larger) scope.
     * </pre>
     *
     * <p>The index's adjacency is restricted to the scope set, and its distances are
     * byte-identical to {@code StressSolver.calculateDistances(graph, scope)} for the
     * same node set (both BFS within the set) — pinned by {@code GroundDistanceIndexTest}
     * — so feeding it to the solver does not change any collapse decision. In the common
     * cascade the collapsing block's neighbours are already inside the closure, so
     * scope rarely grows and the index stays incremental across the whole settle.
     */
    private final class ScopeIndex {

        private final StructureGraph graph;
        private final Set<NodePos> scope;
        private GroundDistanceIndex index; // rebuilt lazily; null until first distances()
        private boolean stale = true; // scope grew (or never built) ⇒ rebuild on next read

        ScopeIndex(StructureGraph graph, Set<NodePos> scope) {
            this.graph = graph;
            this.scope = scope;
        }

        /** Add a node to the scope; if it is genuinely new, mark the index stale. */
        boolean add(NodePos pos) {
            if (scope.add(pos)) {
                stale = true;
                return true;
            }
            return false;
        }

        /** A collapsed block left the graph: drop it from scope and repair the index. */
        void removeCollapsed(NodePos pos) {
            scope.remove(pos);
            // If we're going to rebuild anyway (scope grew this round), the repair is
            // wasted — skip it. Otherwise repair decrementally, the cheap path.
            if (!stale && index != null) {
                index.remove(List.of(pos));
            }
        }

        /** The live distance-from-ground map for the current scope, rebuilt if stale. */
        Object2IntOpenHashMap<NodePos> distances() {
            if (stale || index == null) {
                Set<NodePos> grounded = new HashSet<>();
                for (NodePos pos : scope) {
                    Node node = graph.getNode(pos);
                    if (node != null && node.isGrounded()) {
                        grounded.add(pos);
                    }
                }
                index = new GroundDistanceIndex(new HashSet<>(scope), grounded, graph::neighborsOf);
                stale = false;
            }
            return index.distances();
        }
    }
}
