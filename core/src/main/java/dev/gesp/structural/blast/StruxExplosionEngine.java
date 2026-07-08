package dev.gesp.structural.blast;

import dev.gesp.structural.api.CollapseReason;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.metrics.StruxMetrics;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.StressSolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns an explosion into structural consequences, in two honest phases:
 *
 * <pre>
 *   Phase 1 — direct blast: for every node in range, compute a blast intensity
 *             from power, distance falloff, cover (occlusion) and the node's
 *             blast resistance.
 *               intensity ≥ destructionThreshold → DESTROY  (the crater)
 *               otherwise                         → addDamage (a crack, persistent)
 *
 *   Phase 2 — gravity: hand the now-holed, weakened graph to CascadeEngine.settle,
 *             which collapses whatever can no longer hold itself up.
 * </pre>
 *
 * <p>Blast energy and gravity load stay separate quantities: the blast writes
 * persistent {@code damage}; the solver still computes load-stress from scratch.
 * A damaged-but-standing block simply has less capacity, so it may fail under
 * the load it already carried — and a later explosion adds more damage.
 *
 * <p><b>Resumable scan.</b> The phase-1 sphere scan is the dominant O(radius³)
 * cost of a big blast. It is therefore exposed as a {@link BlastSession} that an
 * adapter can advance a few thousand candidate positions per tick instead of all
 * at once. The whole-blast {@link #process(StructureGraph, BlastContext,
 * BlastCallback)} entry point is simply {@code begin(...)} followed by
 * {@code advance(Integer.MAX_VALUE)} until done — so the atomic path IS the
 * chunked path at an infinite budget. This is what makes the two byte-identical:
 * phase 1 is a pure per-node function (distance / occlusion / power / blast
 * resistance) and the graph is NEVER mutated during the scan — destroyed removals
 * and the gravity settle run only after the whole scan finishes — so slicing the
 * scan across ticks cannot change the outcome.
 *
 * <p>Pure physics, no game dependencies — testable against {@code :core} alone.
 */
public final class StruxExplosionEngine {

    private final PhysicsConfig config;
    private final StressSolver solver;

    /** Optional work-counter; null on the production path. See {@link StruxMetrics}. */
    private StruxMetrics metrics;

    /**
     * Optional blast-geometry capture sink. While re-simulating a recorded blast, the
     * scanner reports each in-range candidate's intensity/outcome, its occlusion ray,
     * and the falloff shells to this sink. Defaults to {@link BlastDebugCapture#NONE}
     * so the live path never pays for it, and the scan stays byte-identical (capture is
     * pure reporting of values the scan already computes). A re-sim-only cost.
     */
    private BlastDebugCapture blastDebug = BlastDebugCapture.NONE;

    public StruxExplosionEngine() {
        this(new PhysicsConfig());
    }

    public StruxExplosionEngine(PhysicsConfig config) {
        this.config = config;
        this.solver = new StressSolver(config);
    }

    /**
     * Attach (or detach, with {@code null}) a work-counter, propagating it to the
     * underlying solver. Optional; only tests/benchmarks need this. Returns
     * {@code this} for chaining.
     */
    public StruxExplosionEngine setMetrics(StruxMetrics metrics) {
        this.metrics = metrics;
        solver.setMetrics(metrics);
        return this;
    }

    /**
     * Attach (or detach, with {@code null}) a blast-geometry capture sink for the next
     * {@link #begin}/{@link #process}. Only the re-sim path needs this; the live server
     * leaves it {@link BlastDebugCapture#NONE}. Returns {@code this} for chaining.
     */
    public StruxExplosionEngine setBlastDebugCapture(BlastDebugCapture blastDebug) {
        this.blastDebug = blastDebug == null ? BlastDebugCapture.NONE : blastDebug;
        return this;
    }

    public BlastResult process(StructureGraph graph, BlastContext ctx) {
        return process(graph, ctx, BlastCallback.NONE);
    }

    /**
     * Solve a whole explosion in one call. Implemented as {@link #begin} followed
     * by {@link BlastSession#advance advance(Integer.MAX_VALUE)} until the session
     * reports it is done — so the atomic path is literally the chunked path run at
     * an unbounded budget, and the two can never diverge.
     */
    public BlastResult process(StructureGraph graph, BlastContext ctx, BlastCallback cb) {
        BlastSession session = begin(graph, ctx, cb);
        while (session.advance(Integer.MAX_VALUE)) {
            // drain the whole scan + settle in one (or more) infinite-budget steps
        }
        return session.result();
    }

    /**
     * Start a resumable explosion. The returned {@link BlastSession} has done no
     * work yet (beyond the cancel hook); call {@link BlastSession#advance} to
     * consume scan steps. Returns a session that is already {@link
     * BlastSession#isDone() done} (and whose {@link BlastSession#result() result}
     * is empty) if a pre-hook cancels the blast.
     */
    public BlastSession begin(StructureGraph graph, BlastContext ctx, BlastCallback cb) {
        return new BlastSession(graph, ctx, cb, false);
    }

    /**
     * Like {@link #begin} but the session PARKS instead of running its
     * overload stress queries inline: when the settle needs one, {@link
     * BlastSession#pendingOverloadQuery()} becomes non-null and {@link
     * BlastSession#advance} does no work until the caller computes the batch
     * (typically on a worker thread, against a {@link
     * StructureGraph#copySubgraph(Set) snapshot} of the query's scope — the
     * query reads nothing outside it, so the answer is bit-identical) and
     * hands it back via {@link BlastSession#supplyOverloadBatch}. This is the
     * adapters' anti-stall hook: the overload query is the one settle
     * operation whose cost scales with structure size and cannot be split,
     * so it is the one operation a game tick must not run inline.
     */
    public BlastSession beginWithExternalOverloadQueries(StructureGraph graph, BlastContext ctx, BlastCallback cb) {
        return new BlastSession(graph, ctx, cb, true);
    }

    /**
     * Compute the overload batch for an externally-parked query — the exact
     * computation the session would have run inline. Thread-safe BY ISOLATION:
     * call it with a {@link StructureGraph#copySubgraph(Set) snapshot} of the
     * scope, never the live graph, and it may run on any thread (it builds a
     * fresh solver; nothing is shared).
     */
    public List<NodePos> computeOverloadBatch(StructureGraph snapshot, Set<NodePos> solveScope) {
        return new StressSolver(config).findOverloadedBatch(snapshot, solveScope);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PHASE HELPERS — shared by the (single) session state machine
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Build the affected scope from the phase-1 outcome: destroyed + damaged +
     * their dependent subgraphs. This bounds every subsequent settle operation to
     * the blast-affected region.
     *
     * <p>All disturbed blocks seed ONE multi-seed dependent-subgraph BFS rather
     * than one BFS per block: the union is identical (dependency is a per-edge
     * rule), but a block reachable from several seeds is visited once, not once
     * per seed — what was N overlapping upward traversals is now a single shared
     * one.
     */
    private static Set<NodePos> buildAffectedScope(
            StructureGraph graph, List<NodePos> destroyed, Map<NodePos, Double> damaged) {
        Set<NodePos> seeds = new HashSet<>(destroyed);
        seeds.addAll(damaged.keySet());
        return graph.getDependentSubgraph(seeds);
    }

    // The settle's phase logic now lives in BlastSession as a resumable state
    // machine (crater removal → floating → fully-damaged → overload), so a giant
    // collapse can be spread across advances exactly like the scan. See
    // BlastSession.settle(int).

    // ─────────────────────────────────────────────────────────────────────

    /**
     * Add the surviving neighbors of {@code pos} to {@code scope} before
     * {@code pos} is removed. This keeps the scoped floating check complete: a
     * removal that orphans a region (cuts its only path to ground) is detected
     * because at least one severed stub of the orphaned region lands in scope,
     * and {@link StructureGraph#findFloatingInScope} explores its whole
     * component from there. Without this, a region disconnected by a Phase-1/3/4
     * removal whose boundary was not already in scope survives the settle and
     * floats forever.
     */
    private static void severNeighborsIntoScope(
            StructureGraph graph, NodePos pos, Set<NodePos> scope, Set<NodePos> reportedScope) {
        for (NodePos neighbor : graph.getNeighbors(pos)) {
            Node node = graph.getNode(neighbor);
            if (node != null && !node.isGrounded()) {
                scope.add(neighbor);
                // The reported scope is never pruned, so it keeps every severed stub
                // the settle expanded into — that is exactly the maximal region the
                // adapter needs for a scoped ground-refresh.
                reportedScope.add(neighbor);
            }
        }
    }

    /**
     * Sort a HashSet of collapse candidates into the canonical, run-stable
     * order ({@link NodePos#CANONICAL_ORDER}) so the blast settle is
     * reproducible across JVM runs, not just deterministic in its final set.
     */
    private static List<NodePos> canonical(Set<NodePos> positions) {
        List<NodePos> ordered = new ArrayList<>(positions);
        ordered.sort(NodePos.CANONICAL_ORDER);
        return ordered;
    }

    private static double distance(NodePos a, NodePos b) {
        return Math.sqrt(distanceSquared(a, b));
    }

    /**
     * Squared Euclidean distance — the cheap in-range cull. The cube scan rejects
     * ~56% of its 6,859 candidates as out-of-range; comparing {@code dx²+dy²+dz²}
     * against {@code radius²} reaches that verdict without any {@link Math#sqrt},
     * so the (more expensive) real distance is computed only for the in-range
     * survivors that actually need it for falloff / occlusion. Bit-identical: the
     * survivors get the exact same sqrt value, just computed later and fewer times.
     */
    private static double distanceSquared(NodePos a, NodePos b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Largest integer offset {@code n} with {@code n² ≤ remainingSq} — i.e. the
     * inclusive sphere half-span left after subtracting the already-fixed axes'
     * squared offsets. Returns {@code -1} when {@code remainingSq < 0} so a loop of
     * the form {@code for (d = -sphereBound; d <= sphereBound; d++)} runs zero times
     * (lower {@code 1} > upper {@code -1}) — the "this slice is outside the sphere"
     * case. This is what lets the scan visit only in-sphere cells, never corners.
     */
    private static int sphereBound(double remainingSq) {
        if (remainingSq < 0.0) {
            return -1;
        }
        return (int) Math.floor(Math.sqrt(remainingSq));
    }

    /** 1.0 = unobstructed; lower as solid blocks sit between center and target. */
    private double occlusionFactor(StructureGraph graph, BlastContext ctx, NodePos target) {
        if (ctx.occlusion() == BlastOcclusion.NONE) {
            return 1.0;
        }
        int cover = countCover(graph, ctx.center(), target);
        return Math.max(0.0, 1.0 - cover * config.getOcclusionAttenuation());
    }

    /**
     * Count the solid blocks the line of sight from {@code from} (the blast center)
     * to {@code to} (the target) actually passes through, strictly between the two.
     *
     * <p>This is an integer <b>3D DDA</b> (Amanatides–Woo) voxel traversal: it walks
     * the ray voxel-by-voxel, at each step advancing along whichever axis reaches its
     * next voxel boundary first, so it visits <em>exactly</em> the voxels the segment
     * crosses — once each, in order — with no division, rounding, allocation or dedup
     * per step. The ray runs center-to-center, so the first boundary on a moving axis
     * is half a voxel away ({@code tMax = 0.5/|d|}) and successive boundaries a full
     * voxel apart ({@code tDelta = 1/|d|}).
     *
     * <p>This is true line-of-sight, unlike the previous coarse 2×/block point
     * sampling, so it can count a different set of cover blocks — intensities (and
     * therefore which blocks crater vs. crack) shift accordingly.
     */
    private int countCover(StructureGraph graph, NodePos from, NodePos to) {
        int x = from.x();
        int y = from.y();
        int z = from.z();
        int toX = to.x();
        int toY = to.y();
        int toZ = to.z();

        int dx = toX - x;
        int dy = toY - y;
        int dz = toZ - z;
        if (dx == 0 && dy == 0 && dz == 0) {
            return 0; // target is the center — nothing in between
        }

        int stepX = Integer.signum(dx);
        int stepY = Integer.signum(dy);
        int stepZ = Integer.signum(dz);

        // Parametric distance (t ∈ [0,1], 0 = center voxel, 1 = target voxel) to the
        // next voxel boundary on each axis, and the step between boundaries. A
        // stationary axis never crosses a boundary → +∞.
        double tMaxX = dx == 0 ? Double.POSITIVE_INFINITY : 0.5 / Math.abs(dx);
        double tMaxY = dy == 0 ? Double.POSITIVE_INFINITY : 0.5 / Math.abs(dy);
        double tMaxZ = dz == 0 ? Double.POSITIVE_INFINITY : 0.5 / Math.abs(dz);
        double tDeltaX = dx == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dx);
        double tDeltaY = dy == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dy);
        double tDeltaZ = dz == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dz);

        // Exactly |dx|+|dy|+|dz| boundary crossings reach the target voxel; this also
        // bounds the loop so a floating-point tie can never spin it forever.
        int maxSteps = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
        int cover = 0;
        for (int step = 0; step < maxSteps; step++) {
            // Advance to the next voxel boundary. On an EXACT tie (a 45° ray hits a
            // voxel corner — common here since rays run center-to-center, not a
            // measure-zero edge case), step EVERY tied axis in one diagonal move so we
            // pass through the corner instead of grazing the corner-touching voxels to
            // either side. Stepping a single axis on ties over-counted cover (a 4-block
            // diagonal read as 7 voxels of material, not 3) and broke mirror symmetry
            // (the X-first tie-break shielded a target from one diagonal block but not
            // its mirror image). Each iteration still consumes >= 1 crossing, so the
            // |dx|+|dy|+|dz| bound still terminates.
            double tMin = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
            if (tMaxX == tMin) {
                x += stepX;
                tMaxX += tDeltaX;
            }
            if (tMaxY == tMin) {
                y += stepY;
                tMaxY += tDeltaY;
            }
            if (tMaxZ == tMin) {
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
            if (x == toX && y == toY && z == toZ) {
                break; // reached the target voxel — it is not "between"
            }
            if (graph.hasBlock(new NodePos(x, y, z))) {
                cover++;
            }
        }
        return cover;
    }

    /**
     * Debug-only mirror of {@link #countCover}: walks the identical 3D-DDA line of
     * sight from {@code from} to {@code to}, but COLLECTS the voxels it crosses
     * (strictly between the two) into {@code cells} and the solid-block subset into
     * {@code attenuations}, instead of just counting cover. The traversal — step rule,
     * diagonal tie-break, termination bound — is byte-for-byte the same as
     * {@code countCover}, so the recorded cells are exactly the ones that attenuated
     * the candidate. Kept apart so {@code countCover} (the hot path) stays
     * allocation-free.
     */
    private static void traceCover(
            StructureGraph graph, NodePos from, NodePos to, List<NodePos> cells, List<NodePos> attenuations) {
        int x = from.x();
        int y = from.y();
        int z = from.z();
        int toX = to.x();
        int toY = to.y();
        int toZ = to.z();

        int dx = toX - x;
        int dy = toY - y;
        int dz = toZ - z;
        if (dx == 0 && dy == 0 && dz == 0) {
            return; // target is the center — nothing in between
        }

        int stepX = Integer.signum(dx);
        int stepY = Integer.signum(dy);
        int stepZ = Integer.signum(dz);

        double tMaxX = dx == 0 ? Double.POSITIVE_INFINITY : 0.5 / Math.abs(dx);
        double tMaxY = dy == 0 ? Double.POSITIVE_INFINITY : 0.5 / Math.abs(dy);
        double tMaxZ = dz == 0 ? Double.POSITIVE_INFINITY : 0.5 / Math.abs(dz);
        double tDeltaX = dx == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dx);
        double tDeltaY = dy == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dy);
        double tDeltaZ = dz == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dz);

        int maxSteps = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
        for (int step = 0; step < maxSteps; step++) {
            double tMin = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
            if (tMaxX == tMin) {
                x += stepX;
                tMaxX += tDeltaX;
            }
            if (tMaxY == tMin) {
                y += stepY;
                tMaxY += tDeltaY;
            }
            if (tMaxZ == tMin) {
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
            if (x == toX && y == toY && z == toZ) {
                break; // reached the target voxel — it is not "between"
            }
            NodePos cell = new NodePos(x, y, z);
            cells.add(cell);
            if (graph.hasBlock(cell)) {
                attenuations.add(cell);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  BLAST SESSION — the explosion's loop state lifted to a heap object
    // ─────────────────────────────────────────────────────────────────────

    /**
     * One in-progress explosion whose phase-1 sphere scan can be advanced a bounded
     * number of candidate positions at a time, then finished with an atomic settle.
     *
     * <pre>
     *   BlastSession s = engine.begin(graph, ctx, cb);
     *   while (s.advance(4096)) { /* next tick *​/ }
     *   BlastResult r = s.result();
     * </pre>
     *
     * <p>Lifecycle: a session runs as a SCAN → SETTLE → DONE state machine, and a
     * single {@code budget} bounds the <b>work steps</b> done per {@link
     * #advance(int)} across BOTH halves. A work step is one of:
     *
     * <ul>
     *   <li><b>SCAN:</b> one candidate cube position examined (one {@code getNode} +
     *       distance check) — IN or OUT of range alike. The scan walks a cube cursor
     *       (dx outer, dy, dz inner) over the blast's bounding cube, accumulating
     *       destroyed / damaged exactly as the atomic engine does, WITHOUT mutating
     *       the graph.
     *   <li><b>SETTLE:</b> one block removed from the graph — a crater removal or a
     *       collapse (floating / fully-damaged / overload). The settle is the SAME
     *       logic as the atomic engine, just PAUSED between (and within) its batches:
     *       the canonical collapse order is preserved because a paused batch resumes
     *       at the next position in the same canonical list, and a new floating /
     *       overload query is only issued once the current batch is fully drained.
     * </ul>
     *
     * <p>When the scan finishes inside an {@code advance}, the settle continues in
     * the SAME call against whatever budget is left, so a small blast still scans
     * and settles in one call. {@code advance} returns {@code true} while any work
     * (scan or settle) remains.
     *
     * <p>Because the scan never mutates the graph, occlusion ray-casts always read
     * the pristine pre-blast graph regardless of how the scan is sliced, and the
     * {@code destroyed} list is appended in the same cube order no matter where a
     * tick boundary falls — so a chunked run is byte-identical to the atomic one.
     * The settle preserves byte-identity for the same reason: pausing a batch never
     * reorders or re-queries it, so {@code process()} = {@code begin()} +
     * {@code advance(MAX)} drives the identical destroyed / collapsed / damaged sets.
     *
     * <p>A session paused across ticks holds a reference into the live graph;
     * {@code getNode} returning {@code null} is tolerated (the position is simply
     * skipped), so a structure that shrinks between ticks does not break resume.
     */
    public final class BlastSession {

        private final StructureGraph graph;
        private final BlastContext ctx;
        private final BlastCallback cb;

        private final boolean cancelled;
        private final double radius;
        private final double radiusSq; // radius², for the sqrt-free in-range cull
        private final int radiusCeil;

        // Phase-1 accumulators (mutated only by the scan; graph untouched).
        private final List<NodePos> destroyed = new ArrayList<>();
        private final Map<NodePos, Double> damaged = new HashMap<>();

        // Cube cursor: next candidate to examine is (dx, dy, dz). dx is the outer
        // loop, dz the inner — matching the atomic engine's nested order so the
        // destroyed list is appended identically no matter where a tick splits it.
        private int dx;
        private int dy;
        private int dz;
        private boolean scanComplete;

        // ── Settle state machine (entered once the scan completes) ─────────────
        // All of the atomic settle's locals lifted to fields so the settle can pause
        // mid-batch and resume from the exact same cursor on the next advance.
        private SettlePhase settlePhase;
        private List<NodePos> collapsed;
        private Set<NodePos> affectedScope;
        private Set<NodePos> reportedScope; // never pruned; the settle's maximal extent
        private Set<NodePos> solveScope; // phase-4 stress scope (affected + support columns)

        private int craterIdx; // CRATER: next index into `destroyed`

        // FLOATING (shared by phases 2/3/4): drain a canonical floating batch fully,
        // then re-query, until none float. `floatExtraScope` mirrors removals into the
        // overload solve scope (phase 4) or is null (phases 2/3). `afterFloating` is
        // the phase to resume once the structure stops floating.
        private List<NodePos> floatBatch;
        private int floatIdx;
        private Set<NodePos> floatExtraScope;
        private SettlePhase afterFloating;

        private List<NodePos> damagedBatch; // P3: the sorted fully-damaged blocks
        private int damagedIdx;

        private List<NodePos> overloadBatch; // P4: the current overloaded batch
        private int overloadIdx;
        // P4 boundary-guard ring width, doubling per CONSECUTIVE guard round (reset to
        // 1 once a batch is closed and collapsed). See loadOverloadBatch.
        private int overloadExpandRings = 1;

        // ── External (off-thread) overload queries ──────────────────────────
        // The overload stress query is the one settle operation whose cost
        // scales with structure size and cannot be split. With
        // externalOverloadQueries the session PARKS instead of running it
        // inline: pendingOverloadScope exposes the query's exact scope, the
        // caller computes the batch wherever it likes (a worker thread, against
        // a copySubgraph snapshot — bit-identical, the query reads nothing
        // outside the scope) and feeds it back via supplyOverloadBatch.
        private final boolean externalOverloadQueries;
        private Set<NodePos> pendingOverloadScope; // non-null => parked
        private List<NodePos> suppliedOverloadBatch;

        private boolean done;
        private BlastResult result;

        private BlastSession(StructureGraph graph, BlastContext ctx, BlastCallback cb, boolean externalOverload) {
            this.graph = graph;
            this.ctx = ctx;
            this.cb = cb;
            this.externalOverloadQueries = externalOverload;
            this.cancelled = !cb.onBlast(ctx);
            this.radius = ctx.power() * config.getBlastRadiusPerPower();
            this.radiusSq = radius * radius;
            this.radiusCeil = (int) Math.ceil(radius);
            // Seed the cursor at the sphere's lower bounds for the first dx so the
            // very first cell examined is already inside the sphere (no corner).
            this.dx = -radiusCeil;
            this.dy = -sphereBound(radiusSq - (double) dx * dx);
            this.dz = -sphereBound(radiusSq - (double) dx * dx - (double) dy * dy);
            if (cancelled) {
                // A cancelled blast does no work and reports nothing.
                this.scanComplete = true;
                finish(BlastResult.empty());
                return;
            }
            // Debug-only: announce the falloff geometry once, before the scan. The
            // shells are quarter-radius bands a renderer draws as concentric spheres.
            if (blastDebug.wantsBlastCapture()) {
                List<Double> shells = new ArrayList<>(4);
                for (int s = 1; s <= 4; s++) {
                    shells.add(radius * (s / 4.0));
                }
                blastDebug.onBlastGeometry(radius, shells, config.getDestructionThreshold());
            }
        }

        /** True once the whole blast (scan + settle) is finished. */
        public boolean isDone() {
            return done;
        }

        /**
         * The scope of the overload query this session is parked on, or
         * {@code null} when it is not waiting for one. Non-null only for
         * sessions started with external overload queries; the caller answers
         * with {@link #supplyOverloadBatch}. The returned set is a defensive
         * snapshot — safe to hand to another thread.
         */
        public Set<NodePos> pendingOverloadQuery() {
            return pendingOverloadScope;
        }

        /**
         * Answer a parked overload query (see {@link #pendingOverloadQuery}).
         * The batch must be the result of {@code findOverloadedBatch} over the
         * pending scope — typically computed against a
         * {@link StructureGraph#copySubgraph(Set) snapshot} on a worker thread.
         * Must be called on the session's owner thread.
         */
        public void supplyOverloadBatch(List<NodePos> batch) {
            if (pendingOverloadScope == null) {
                throw new IllegalStateException("no overload query is pending");
            }
            this.suppliedOverloadBatch = new ArrayList<>(batch);
            this.pendingOverloadScope = null;
        }

        /**
         * True once the phase-1 sphere scan has examined the whole cube — i.e. the
         * session has moved past SCAN into the (resumable) SETTLE. Useful for tests
         * and diagnostics that want to know which half of the work an {@link
         * #advance} is doing.
         */
        public boolean isScanComplete() {
            return scanComplete;
        }

        /**
         * The final outcome. Only valid once {@link #isDone()} is true; throws
         * otherwise so a caller can never read a half-built result.
         */
        public BlastResult result() {
            if (!done) {
                throw new IllegalStateException("blast session not finished — advance() until it returns false");
            }
            return result;
        }

        /**
         * Do up to {@code budget} more <b>work steps</b> and report whether any work
         * remains. A single budget is shared across BOTH halves of the blast: a scan
         * step is one candidate cube position examined; a settle step is one block
         * removed (crater or collapse). When the scan finishes mid-call the settle
         * continues against the leftover budget in the same call — so a small blast
         * scans and settles in one {@code advance}, and an unbounded budget finishes
         * the whole blast in one call ({@code process()} = {@code begin()} +
         * {@code advance(MAX)}).
         *
         * @param budget max work steps to do this call (≥ 1; values ≤ 0 are treated
         *     as 1 so a session always makes forward progress)
         * @return {@code true} if {@link #advance} should be called again
         */
        public boolean advance(int budget) {
            if (done) {
                return false;
            }
            if (pendingOverloadScope != null) {
                // Parked on an external overload query: no work until the caller
                // supplies the batch. Work clearly remains.
                return true;
            }
            int remaining = scan(Math.max(1, budget));
            if (!scanComplete) {
                // More cube left to examine — pause here and resume next call.
                return true;
            }
            // The scan finished in (or before) this call: spend whatever budget is
            // left advancing the resumable settle. It returns true while collapse
            // work remains, so a giant collapse spreads over later advances too.
            return settle(remaining);
        }

        /**
         * Examine up to {@code budget} candidate cube positions, accumulating phase-1
         * effects. Returns the budget left over once the scan is complete (so the
         * settle can spend the remainder in the same {@code advance}); returns 0 if
         * the budget ran out before the cube was exhausted.
         */
        private int scan(int budget) {
            if (scanComplete) {
                // The scan already finished on an earlier advance whose settle paused;
                // hand the whole budget to the settle without re-walking the cube.
                return budget;
            }
            NodePos center = ctx.center();
            int examined = 0;
            // Walk only the cells inside the blast sphere, not the whole bounding
            // cube: for a fixed dx the dy span is bounded by dx²+dy² ≤ radius², and
            // for fixed dx,dy the dz span by dx²+dy²+dz² ≤ radius². Cube corners
            // (which examineCandidate would have culled anyway) are never visited or
            // allocated. The dx-outer→dy→dz-inner ascending order is unchanged, so
            // the destroyed/damaged accumulation order is byte-identical; the cursor
            // resumes mid-sphere because the bounds are recomputed from dx/dy here.
            while (dx <= radiusCeil) {
                int dyBound = sphereBound(radiusSq - (double) dx * dx);
                while (dy <= dyBound) {
                    int dzBound = sphereBound(radiusSq - (double) dx * dx - (double) dy * dy);
                    while (dz <= dzBound) {
                        if (examined >= budget) {
                            return 0;
                        }
                        examineCandidate(center, dx, dy, dz);
                        examined++;
                        dz++;
                    }
                    dy++;
                    dz = -sphereBound(radiusSq - (double) dx * dx - (double) dy * dy);
                }
                dx++;
                dy = -sphereBound(radiusSq - (double) dx * dx);
                dz = -sphereBound(radiusSq - (double) dx * dx - (double) dy * dy);
            }
            scanComplete = true;
            return budget - examined;
        }

        /** One candidate position: the pure per-node phase-1 function (no graph mutation). */
        private void examineCandidate(NodePos center, int ox, int oy, int oz) {
            NodePos pos = new NodePos(center.x() + ox, center.y() + oy, center.z() + oz);
            Node node = graph.getNode(pos);
            if (node == null || node.isGrounded()) {
                return; // the earth/bedrock is not blown up
            }
            // Cull by squared distance first — no sqrt for the ~56% out-of-range
            // candidates. Only in-range survivors pay for the real distance.
            double distSq = distanceSquared(center, pos);
            if (distSq > radiusSq) {
                return;
            }
            double dist = Math.sqrt(distSq);

            double occlusion = occlusionFactor(graph, ctx, pos);
            // A heat-softened block resists a blast less: its effective blast resistance scales
            // by its temperatureCapacityFactor (1.0 when cold → identical to before; lower when
            // hot → easier to crater). The factor is clamped > 0, so this never divides by zero.
            double effectiveBlastResistance = node.spec().blastResistance() * node.temperatureCapacityFactor();
            double intensity = ctx.power() * ctx.falloff().factor(dist / radius) * occlusion / effectiveBlastResistance;

            BlastDebugCapture.CandidateOutcome outcome;
            if (intensity >= config.getDestructionThreshold()) {
                destroyed.add(pos);
                outcome = BlastDebugCapture.CandidateOutcome.DESTROYED;
            } else if (intensity > 0) {
                node.addDamage(intensity * config.getDamageScale());
                damaged.put(pos, node.damage());
                cb.onDamaged(pos, node.damage());
                outcome = BlastDebugCapture.CandidateOutcome.DAMAGED;
            } else {
                outcome = BlastDebugCapture.CandidateOutcome.UNAFFECTED;
            }

            // Debug-only: report this candidate's geometry and (subject to fullRays)
            // its occlusion ray. Pure reads of values the scan already computed — the
            // outcome above is unchanged.
            if (blastDebug.wantsBlastCapture()) {
                blastDebug.onBlastCandidate(pos, dist, intensity, occlusion, outcome);
                boolean touched = outcome != BlastDebugCapture.CandidateOutcome.UNAFFECTED;
                if (blastDebug.wantsFullRays() || touched) {
                    emitRay(ctx, pos);
                }
            }
        }

        /**
         * Re-walk the occlusion ray to {@code target}, recording the DDA cell path and
         * the subset that held cover, and hand both to the debug sink. This re-runs the
         * exact {@link #countCover} traversal (so the cells match what attenuated the
         * candidate) but collects the walk instead of just counting — kept separate so
         * the hot {@code countCover} stays allocation-free.
         */
        private void emitRay(BlastContext ctx, NodePos target) {
            List<NodePos> cells = new ArrayList<>();
            List<NodePos> attenuations = new ArrayList<>();
            traceCover(graph, ctx.center(), target, cells, attenuations);
            blastDebug.onBlastRay(target, cells, attenuations);
        }

        /**
         * Advance the resumable settle by at most {@code budget} block removals
         * (crater + collapse). Returns {@code true} while settle work remains. This
         * is the SAME phase logic as the old atomic settle — crater → floating →
         * fully-damaged → overload — just paused between removals: the phase cursor
         * and every in-flight batch live in fields, so resuming continues from the
         * exact next position in the same canonical order. New floating / overload
         * queries are issued only once the current batch is fully drained, so the
         * graph queries (and their metric counts) match the atomic engine exactly.
         */
        private boolean settle(int budget) {
            if (settlePhase == null) {
                initSettle();
            }
            int steps = Math.max(1, budget);
            while (steps > 0 && settlePhase != SettlePhase.FINALIZE && pendingOverloadScope == null) {
                steps = runSettlePhase(steps);
            }
            if (settlePhase != SettlePhase.FINALIZE) {
                return true; // budget exhausted mid-settle — resume next advance
            }
            finalizeSettle();
            return false;
        }

        /** One-time settle setup: scope + accumulators, then enter the CRATER phase. */
        private void initSettle() {
            collapsed = new ArrayList<>();
            affectedScope = buildAffectedScope(graph, destroyed, damaged);
            // The reported scope captures the settle's MAXIMAL extent — the
            // dependent-subgraph seeds plus every severed stub the settle expands
            // into — so the adapter can run its ground-refresh over exactly this
            // region instead of rescanning the whole graph. Unlike affectedScope
            // (which is pruned as blocks fall) it only ever grows.
            reportedScope = new HashSet<>(affectedScope);
            settlePhase = SettlePhase.CRATER;
        }

        /**
         * Run the current settle phase for up to {@code steps} removals and return the
         * steps left. Each phase removes from its current batch, transitioning to the
         * next phase (or arming the shared FLOATING drain) once its batch is drained.
         */
        private int runSettlePhase(int steps) {
            switch (settlePhase) {
                case CRATER:
                    return stepCrater(steps);
                case FLOATING:
                    return stepFloating(steps);
                case DAMAGED_REMOVE:
                    return stepDamaged(steps);
                case OVERLOAD_INIT:
                    // Builds the solve scope AND runs a scope-wide stress solve —
                    // charge it like a floating re-query (see stepFloating).
                    initOverload();
                    return Math.max(0, steps - queryCharge(solveScope.size()));
                case OVERLOAD_BATCH:
                    return stepOverload(steps);
                case OVERLOAD_REQUERY:
                    // A scope-wide stress re-solve — charge it like the floating
                    // re-query so one advance can't stack many of them for free.
                    loadOverloadBatch();
                    return Math.max(0, steps - queryCharge(solveScope.size()));
                default:
                    throw new IllegalStateException("unexpected settle phase: " + settlePhase);
            }
        }

        /**
         * What a scope-wide query (floating BFS / stress solve) costs in settle
         * steps. A removal touches one block; a query walks the whole scope, so it
         * is charged proportionally (the divisor reflects that one visited node is
         * cheaper than one removal). Floored at 1 so even a tiny query registers.
         */
        private static int queryCharge(int scopeSize) {
            return Math.max(1, scopeSize / 8);
        }

        /** CRATER: remove the directly-destroyed blocks in list order, then start phase 2. */
        private int stepCrater(int steps) {
            while (steps > 0 && craterIdx < destroyed.size()) {
                NodePos pos = destroyed.get(craterIdx++);
                severNeighborsIntoScope(graph, pos, affectedScope, reportedScope);
                graph.removeBlock(pos);
                cb.onDirectDestroy(pos);
                affectedScope.remove(pos);
                steps--;
            }
            if (craterIdx >= destroyed.size()) {
                // ── Phase 2: collapse floating blocks (scoped, no extra scope) ──
                armFloating(null, SettlePhase.DAMAGED_REMOVE);
            }
            return steps;
        }

        /**
         * FLOATING drain (phases 2/3/4): collapse a canonical floating batch fully,
         * then re-query, until none float. Mirrors {@code findFloatingInScope} ▸
         * {@code canonical} ▸ remove-all, just paused per removal. {@code
         * floatExtraScope} keeps the overload solve-scope in lock-step (phase 4) or
         * is null. When the structure stops floating, jump to {@code afterFloating}.
         */
        private int stepFloating(int steps) {
            while (steps > 0) {
                if (floatBatch == null) {
                    // A floating re-query is a scope-wide BFS — real work the step
                    // counter must see, or a settle whose batches drain in a few
                    // steps stacks a scope-wide query per batch inside ONE advance:
                    // on a huge marginal region (100k+ registered terrain blocks)
                    // that froze the tick for seconds (the explosion freeze).
                    // Charge it in proportion to the scope it walked: a small blast
                    // still scans, queries and settles within one advance, while a
                    // huge scope's query ends the advance so the caller's wall-clock
                    // deadline check runs before the next one.
                    Set<NodePos> floating = graph.findFloatingInScope(affectedScope);
                    steps = Math.max(0, steps - queryCharge(affectedScope.size()));
                    if (floating.isEmpty()) {
                        settlePhase = afterFloating;
                        floatExtraScope = null;
                        afterFloating = null;
                        return steps; // structure stopped floating — hand off to the next phase
                    }
                    floatBatch = canonical(floating);
                    floatIdx = 0;
                    if (steps <= 0) {
                        return 0; // batch armed; drain resumes next advance
                    }
                }
                while (steps > 0 && floatIdx < floatBatch.size()) {
                    NodePos pos = floatBatch.get(floatIdx++);
                    graph.removeBlock(pos);
                    collapsed.add(pos);
                    cb.onCollapse(pos, CollapseReason.FLOATING);
                    affectedScope.remove(pos);
                    if (floatExtraScope != null) {
                        floatExtraScope.remove(pos);
                    }
                    steps--;
                }
                if (floatIdx >= floatBatch.size()) {
                    floatBatch = null; // batch drained — re-query on the next loop
                }
            }
            return steps;
        }

        /** Arm the shared FLOATING drain with its extra scope and resume phase. */
        private void armFloating(Set<NodePos> extraScope, SettlePhase after) {
            this.floatExtraScope = extraScope;
            this.afterFloating = after;
            this.floatBatch = null;
            this.floatIdx = 0;
            this.settlePhase = SettlePhase.FLOATING;
        }

        /**
         * Phase 3 entry: collect the fully-damaged blocks (damage ≥ 1) in canonical
         * order. This is a read-only pass (no removals), so it runs in one shot and
         * does not consume budget — matching the atomic engine's single collection.
         */
        private void collectDamaged() {
            damagedBatch = new ArrayList<>();
            for (NodePos pos : affectedScope) {
                Node node = graph.getNode(pos);
                if (node != null && !node.isGrounded() && node.damage() >= 1.0) {
                    damagedBatch.add(pos);
                }
            }
            damagedBatch.sort(NodePos.CANONICAL_ORDER);
            damagedIdx = 0;
        }

        /** DAMAGED_REMOVE: drop fully-damaged blocks, then re-run the floating drain. */
        private int stepDamaged(int steps) {
            if (damagedBatch == null) {
                collectDamaged();
            }
            while (steps > 0 && damagedIdx < damagedBatch.size()) {
                NodePos pos = damagedBatch.get(damagedIdx++);
                severNeighborsIntoScope(graph, pos, affectedScope, reportedScope);
                graph.removeBlock(pos);
                collapsed.add(pos);
                // Damaged-out blocks shattered from accumulated blast damage rather
                // than from losing support (FLOATING) or stress (OVERLOADED), so
                // there is no honest CollapseReason for them: fire the plain
                // reasonless hook. A capture sink tells this case apart by which
                // overload fires (1-arg here vs the 2-arg reason hook elsewhere).
                cb.onCollapse(pos);
                affectedScope.remove(pos);
                steps--;
            }
            if (damagedIdx >= damagedBatch.size()) {
                // Removing damaged blocks may create more floaters (scoped check),
                // after which phase 4 (overload) begins.
                armFloating(null, SettlePhase.OVERLOAD_INIT);
            }
            return steps;
        }

        /**
         * Phase 4 setup: expand the affected scope downward into vertical support
         * columns for the stress solve, then load the first overloaded batch. A
         * read-only pass — no removals, no budget consumed — exactly as the atomic
         * engine built its solve scope and first batch before its first removal.
         */
        private void initOverload() {
            solveScope = new HashSet<>(affectedScope);
            for (NodePos pos : new ArrayList<>(solveScope)) {
                // Walk the vertical support column down until it ends or grounds —
                // NO y floor. A y >= 0 bound dropped the grounded anchor for any column
                // crossing y=0 (and skipped the walk entirely for blast nodes at y <= 0),
                // leaving the solve scope with no grounded node — so calculateDistances
                // marked every block "floating" and the whole overload cascade was
                // silently skipped for bases at/below y=0 (real coordinates since MC
                // 1.18's -64 floor). Mirrors StructureGraph.hasGroundedColumnBelow;
                // termination is guaranteed by the finite graph.
                NodePos below = new NodePos(pos.x(), pos.y() - 1, pos.z());
                while (true) {
                    Node belowNode = graph.getNode(below);
                    if (belowNode == null) {
                        break;
                    }
                    solveScope.add(below);
                    if (belowNode.isGrounded()) {
                        break;
                    }
                    below = new NodePos(below.x(), below.y() - 1, below.z());
                }
            }
            // The scope is deliberately NOT closed here; the boundary guard in
            // loadOverloadBatch widens it lazily, only around genuinely-overloaded
            // edge candidates, before any collapse (see loadOverloadBatch's javadoc).
            overloadExpandRings = 1;
            loadOverloadBatch();
        }

        /**
         * Query the next overloaded batch (sorted) and route to BATCH or FINALIZE.
         *
         * <p><b>Boundary guard.</b> {@code solveScope} is dependent-subgraph +
         * support columns — NOT a structural closure, so a node at its edge still
         * has neighbours OUTSIDE it. {@code StressSolver.computeLevelStress} treats
         * any neighbour absent from the distance map (out-of-scope ⇒ {@code
         * MAX_VALUE > currentDist}) as an upstream load source and reads its
         * PERSISTENT {@code verticalStress()} field — whatever an unrelated earlier
         * solve last wrote there. That leaks stale stress into the verdict: phantom
         * overloads (spurious collapse) or stale-value misses at the edge. So we
         * never collapse on approximate edge numbers: if any candidate touches a
         * block outside the scope, widen the scope around the batch and re-query
         * FIRST. Load conservation migrates the misplaced excess to its true owner;
         * the scope grows strictly toward the finite component, so this terminates
         * (worst case: the whole component — the brute-force solve the scoped path
         * must agree with). This mirrors {@code CascadeEngine}'s boundary guard, and
         * keeps the tight blast scope where nothing leans across it while paying to
         * widen only where a real overload does. Ring width doubles across
         * consecutive guard rounds so covering a far marginal region costs
         * O(log&nbsp;L) re-queries, not one per block layer.
         */
        private void loadOverloadBatch() {
            List<NodePos> batch;
            if (externalOverloadQueries) {
                if (suppliedOverloadBatch == null) {
                    // Park: expose the query's exact scope and do nothing until the
                    // caller supplies the answer (computed off-thread against a
                    // copySubgraph snapshot — the query reads nothing outside the
                    // scope, so the answer is bit-identical to running it here).
                    // Resume re-enters via OVERLOAD_REQUERY so an OVERLOAD_INIT park
                    // does not rebuild the solve scope a second time.
                    pendingOverloadScope = Set.copyOf(solveScope);
                    settlePhase = SettlePhase.OVERLOAD_REQUERY;
                    return;
                }
                batch = suppliedOverloadBatch;
                suppliedOverloadBatch = null;
                // The answer was computed against a snapshot; the graph may have
                // lost nodes since (another blast's crater, an impact, a player).
                // Keep only positions that still exist — exactly the tolerance the
                // cross-tick batch drain already grants ("a structure that shrinks
                // between ticks does not break resume").
                batch.removeIf(pos -> graph.getNode(pos) == null);
            } else {
                batch = solver.findOverloadedBatch(graph, solveScope);
            }
            // BOUNDARY GUARD: if any candidate leans on a block outside the scope,
            // widen and re-query before collapsing anything. Re-entering
            // loadOverloadBatch re-parks the external path against the bigger scope.
            if (!batch.isEmpty() && widenScopeAcrossBatchBoundary(batch)) {
                loadOverloadBatch();
                return;
            }
            // The scope is closed around this batch, so the guard crawl (if any) is
            // over; the next expansion episode starts narrow again.
            overloadExpandRings = 1;
            overloadBatch = batch;
            if (overloadBatch.isEmpty()) {
                settlePhase = SettlePhase.FINALIZE;
                return;
            }
            overloadBatch.sort(NodePos.CANONICAL_ORDER);
            overloadIdx = 0;
            settlePhase = SettlePhase.OVERLOAD_BATCH;
        }

        /**
         * If any block in {@code batch} has a still-standing neighbour outside
         * {@code solveScope}, grow the scope by {@code overloadExpandRings} neighbour
         * layers around the batch (pulling in each added node's support column so the
         * re-solve has its grounding), double the ring width for the next consecutive
         * round, and return {@code true}. Returns {@code false} — leaving the scope
         * untouched — when the batch is already closed.
         */
        private boolean widenScopeAcrossBatchBoundary(List<NodePos> batch) {
            Set<NodePos> frontier = new HashSet<>();
            for (NodePos candidate : batch) {
                for (NodePos neighbor : graph.getNeighbors(candidate)) {
                    if (graph.hasBlock(neighbor) && !solveScope.contains(neighbor)) {
                        frontier.add(neighbor);
                    }
                }
            }
            if (frontier.isEmpty()) {
                return false;
            }
            for (int ring = 0; ring < overloadExpandRings && !frontier.isEmpty(); ring++) {
                Set<NodePos> next = new HashSet<>();
                for (NodePos pos : frontier) {
                    if (solveScope.add(pos)) {
                        for (NodePos ancestor : graph.getSupportAncestors(pos)) {
                            solveScope.add(ancestor);
                        }
                        for (NodePos nb : graph.getNeighbors(pos)) {
                            if (graph.hasBlock(nb) && !solveScope.contains(nb)) {
                                next.add(nb);
                            }
                        }
                    }
                }
                frontier = next;
            }
            overloadExpandRings = Math.min(overloadExpandRings * 2, 1 << 16);
            return true;
        }

        /**
         * OVERLOAD_BATCH: drop the current overloaded batch, then re-check floaters
         * (extra scope = solveScope) and re-query the next overloaded batch — exactly
         * the atomic phase-4 loop, paused per removal.
         */
        private int stepOverload(int steps) {
            while (steps > 0 && overloadIdx < overloadBatch.size()) {
                NodePos pos = overloadBatch.get(overloadIdx++);
                severNeighborsIntoScope(graph, pos, affectedScope, reportedScope);
                graph.removeBlock(pos);
                collapsed.add(pos);
                cb.onCollapse(pos, CollapseReason.OVERLOADED);
                solveScope.remove(pos);
                affectedScope.remove(pos);
                steps--;
            }
            if (overloadIdx >= overloadBatch.size()) {
                // Floaters created by the overload collapse fall first; once they
                // settle, re-query the next overloaded batch (OVERLOAD_INIT path).
                armFloating(solveScope, SettlePhase.OVERLOAD_REQUERY);
            }
            return steps;
        }

        /** Assemble the result, fire callbacks, and mark the session done. */
        private void finalizeSettle() {
            // The crater itself and everything that fell are part of the touched
            // region too (a scoped refresh skips ones already gone from the graph).
            reportedScope.addAll(destroyed);
            reportedScope.addAll(collapsed);

            if (metrics != null) {
                metrics.blocksRemoved += destroyed.size() + collapsed.size();
            }

            // Keep the reported sets disjoint. A block cracked in phase 1 lands in
            // `damaged`; if a later phase then collapses (or directly destroys) it, it
            // is gone — so it is no longer a "survived but weakened" survivor and must
            // not also be reported as damaged. The node's own persistent damage was
            // ALREADY applied in phase 1, so a block that merely cracked and did NOT
            // fall keeps its accumulated damage for future blasts; this prune only
            // affects what the RESULT reports, not the graph.
            for (NodePos pos : destroyed) {
                damaged.remove(pos);
            }
            for (NodePos pos : collapsed) {
                damaged.remove(pos);
            }

            BlastResult r = new BlastResult(destroyed, collapsed, damaged, Map.of(), reportedScope);
            cb.onComplete(r);
            finish(r);
        }

        private void finish(BlastResult r) {
            this.result = r;
            this.done = true;
        }
    }

    /**
     * The settle's resumable phases, walked in order. OVERLOAD_INIT / OVERLOAD_REQUERY
     * are zero-removal "between batches" transitions (build the solve scope / query the
     * next overloaded batch) handled inline; the removal-bearing phases are CRATER,
     * FLOATING, DAMAGED_REMOVE and OVERLOAD_BATCH.
     */
    private enum SettlePhase {
        CRATER,
        FLOATING,
        DAMAGED_REMOVE,
        OVERLOAD_INIT,
        OVERLOAD_BATCH,
        OVERLOAD_REQUERY,
        FINALIZE
    }
}
