package dev.gesp.structural.minecraft.listener;

import dev.gesp.structural.blast.BlastCallback;
import dev.gesp.structural.blast.BlastContext;
import dev.gesp.structural.blast.BlastResult;
import dev.gesp.structural.blast.StruxExplosionEngine;
import dev.gesp.structural.blast.StruxExplosionEngine.BlastSession;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.config.EffectsConfig;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.perf.TaskTimings;
import dev.gesp.structural.minecraft.recording.MinecraftEventRecorder;
import dev.gesp.structural.minecraft.visual.CollapseEffects;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.recording.BlastEvent;
import dev.gesp.structural.recording.EventRecorder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Settles queued explosions on the main thread under a per-tick wall-clock budget,
 * so several explosions in one tick (a TNT chain, a cannon volley) can never freeze
 * a single server tick.
 *
 * <pre>
 *   Event time (cheap):   gate + claim tracked blocks out of vanilla's list →
 *                         enqueue QueuedBlast → return.
 *   Each tick (bounded):  while (budget left) { pop oldest blast; settle it. }
 * </pre>
 *
 * <p><b>One blast at a time, chunked.</b> A single very large explosion can have an
 * O(radius³) phase-1 sphere scan that costs more than the whole tick budget on its
 * own. So a blast is no longer solved in one atomic {@code process} call: the
 * processor holds ONE active {@link BlastSession} and advances it {@code
 * max-scan-per-tick} scan steps per tick, still under the same {@code
 * tick-budget-ms} wall-clock seatbelt. A new blast becomes active only once the
 * previous one finishes (strict FIFO), so a chain still resolves in firing order —
 * a big blast just spreads its scan over a few ticks instead of freezing one.
 *
 * <p><b>The crater is streamed too.</b> When the session completes we no longer turn
 * every destroyed block to AIR in one frozen tick — that was the remaining hang for a
 * 2000-block crater. Instead {@link #finalizeBlast} hands the crater to a {@link
 * CraterApplier}, which the same run loop drains at most {@code
 * max-crater-removals-per-tick} blocks per tick (under the same wall-clock seatbelt).
 * The processor stays "busy" (so its drain task keeps running and idle ticks aren't
 * recorded) until BOTH the active session AND the crater queue are drained.
 *
 * <p><b>Stale worlds.</b> A blast settled a tick later may aim at a structure that
 * has since been fully cleared (graph gone or empty). When that happens we drop the
 * blast silently (logged at {@code FINE}); there is nothing left to crater.
 */
public class BlastProcessor extends BukkitRunnable {

    private final Plugin plugin;
    private final StructureManager structureManager;
    private final StruxExplosionEngine engine;
    private final DelayedCollapseManager delayedCollapseManager;
    private final CascadeResumeManager cascadeResumeManager;
    private final EffectsConfig config;
    private final CollapseEffects effects;
    private final Logger logger;
    private final TaskTimings taskTimings;
    private long tickBudgetNanos;

    /** Scan-step budget per tick for the single active blast session. */
    private final int maxScanPerTick;

    /** Streams each finished blast's crater into the world a few blocks per tick. */
    private final CraterApplier craterApplier;

    /** Per-tick cap on streamed crater removals ({@code blast.max-crater-removals-per-tick}). */
    private final int maxCraterRemovalsPerTick;

    /** FIFO queue of blasts awaiting a settle. Touched only on the main thread. */
    private final Deque<QueuedBlast> queue = new ArrayDeque<>();

    /** The one blast currently being scanned/settled across ticks (null = idle). */
    private ActiveBlast active;

    /**
     * Worker for the settle's overload stress queries, or {@code null} to run them
     * inline (legacy). The overload query is the one settle operation whose cost
     * scales with structure size and cannot be split — on a big arena a single
     * query held the main thread 100-800ms (the "big explosions stutter every
     * tick" report). With a worker, the session PARKS on the query, this thread
     * takes an O(scope) {@code copySubgraph} snapshot (bounded, a few ms), the
     * worker computes the batch against the snapshot (bit-identical — the query
     * reads nothing outside its scope, pinned by BlastSessionEquivalenceTest),
     * and the session resumes when the answer lands a tick or two later.
     */
    private final ExecutorService solveWorker;

    /** The in-flight off-thread overload query for the active session (null = none). */
    private Future<List<NodePos>> pendingSolve;

    public BlastProcessor(
            Plugin plugin,
            StructureManager structureManager,
            StruxExplosionEngine engine,
            DelayedCollapseManager delayedCollapseManager,
            CascadeResumeManager cascadeResumeManager,
            CraterApplier craterApplier,
            EffectsConfig config,
            CollapseEffects effects,
            Logger logger,
            double tickBudgetMs,
            int maxScanPerTick,
            int maxCraterRemovalsPerTick,
            TaskTimings taskTimings) {
        this(
                plugin,
                structureManager,
                engine,
                delayedCollapseManager,
                cascadeResumeManager,
                craterApplier,
                config,
                effects,
                logger,
                tickBudgetMs,
                maxScanPerTick,
                maxCraterRemovalsPerTick,
                taskTimings,
                null);
    }

    /**
     * Variant with an overload-query worker (see {@link #solveWorker}); {@code null}
     * runs the queries inline on the main thread (legacy behavior, used by tests).
     */
    public BlastProcessor(
            Plugin plugin,
            StructureManager structureManager,
            StruxExplosionEngine engine,
            DelayedCollapseManager delayedCollapseManager,
            CascadeResumeManager cascadeResumeManager,
            CraterApplier craterApplier,
            EffectsConfig config,
            CollapseEffects effects,
            Logger logger,
            double tickBudgetMs,
            int maxScanPerTick,
            int maxCraterRemovalsPerTick,
            TaskTimings taskTimings,
            ExecutorService solveWorker) {
        this.solveWorker = solveWorker;
        this.plugin = plugin;
        this.structureManager = structureManager;
        this.engine = engine;
        this.delayedCollapseManager = delayedCollapseManager;
        this.cascadeResumeManager = cascadeResumeManager;
        this.craterApplier = craterApplier;
        this.config = config;
        this.effects = effects;
        this.logger = logger;
        this.taskTimings = taskTimings;
        this.maxScanPerTick = Math.max(1, maxScanPerTick);
        this.maxCraterRemovalsPerTick = Math.max(1, maxCraterRemovalsPerTick);
        setTickBudgetMs(tickBudgetMs);
    }

    /**
     * Set the per-tick wall-clock budget. Clamped non-negative; the {@link #run}
     * loop always processes at least one blast before checking the deadline, so even
     * a zero budget drains exactly one blast per tick (never a deadlock). Visible for
     * testing the budget behaviour.
     */
    public void setTickBudgetMs(double tickBudgetMs) {
        this.tickBudgetNanos = (long) (Math.max(0.0, tickBudgetMs) * 1_000_000.0);
    }

    /** Begin draining the queue once per tick. */
    public void start() {
        runTaskTimer(plugin, 1L, 1L);
    }

    /** Stop draining, drop anything still queued, and abandon the active session. */
    public void stop() {
        try {
            cancel();
        } catch (IllegalStateException ignored) {
            // never started or already cancelled
        }
        queue.clear();
        active = null;
        craterApplier.clear();
        if (pendingSolve != null) {
            pendingSolve.cancel(true);
            pendingSolve = null;
        }
        if (solveWorker != null) {
            solveWorker.shutdownNow();
        }
    }

    /** Add a blast to the back of the queue (preserves FIFO / firing order). */
    public void enqueue(QueuedBlast blast) {
        queue.addLast(blast);
    }

    /** How many blasts are still waiting (excludes the one currently being settled). */
    public int queueSize() {
        return queue.size();
    }

    /**
     * True while there is blast work left to do: a blast mid-scan/settle across ticks,
     * OR a finished blast's crater still draining into the world a few blocks per tick.
     * For tests and diagnostics.
     */
    public boolean hasActiveBlast() {
        return active != null || craterApplier.hasPending();
    }

    @Override
    public void run() {
        long start = System.nanoTime();
        long deadline = start + tickBudgetNanos;
        // Forward progress is guaranteed: each loop pass either advances the active
        // session by a scan chunk or starts a new one, and we always do at least one
        // pass before checking the deadline — so even a zero budget never deadlocks.
        int settled = 0;
        boolean didWork = false;
        while (true) {
            if (active == null) {
                if (queue.isEmpty()) {
                    break;
                }
                active = beginNext(queue.pollFirst());
                if (active == null) {
                    // Stale/empty graph: blast dropped. Re-check the budget like any
                    // other unit of work so a backlog of stale blasts can't freeze a tick.
                    didWork = true;
                    if (System.nanoTime() >= deadline) {
                        break;
                    }
                    continue;
                }
            }

            // Parked on an off-thread overload query? Submit it once (snapshot on
            // THIS thread — O(scope), bounded; the worker only ever sees the copy),
            // then wait for the answer across ticks without advancing the session.
            // Advance the active session by one scan chunk, answering FAST overload
            // queries within the same pass (the worker turns a small blast's query
            // around in microseconds, so its pacing is unchanged from the inline
            // days). Only a genuinely heavy query — one the worker cannot finish
            // within the short grace — parks the blast across ticks.
            boolean parkedOnHeavyQuery = false;
            // One SHARED grace budget per tick: chained fast queries all fit inside
            // it (each resolves in microseconds), while a heavy query exhausts it
            // once and parks — waits can never stack past ~25ms in a single tick.
            long graceDeadline = System.nanoTime() + GRACE_NANOS;
            while (true) {
                active.session.advance(maxScanPerTick);
                didWork = true;
                Set<NodePos> pendingQuery = active.session.pendingOverloadQuery();
                if (pendingQuery == null) {
                    break; // no query needed — normal scan/settle pacing
                }
                if (pendingSolve == null) {
                    StructureGraph graph = structureManager.getGraph(active.blast.world());
                    if (graph == null) {
                        // World gone mid-settle: answer "nothing overloaded" so the
                        // session finalizes and the stale blast drains normally.
                        active.session.supplyOverloadBatch(List.of());
                        continue;
                    }
                    StructureGraph snapshot = graph.copySubgraph(pendingQuery);
                    pendingSolve = solveWorker.submit(() -> engine.computeOverloadBatch(snapshot, pendingQuery));
                }
                List<NodePos> batch = takeSolve(Math.max(0, graceDeadline - System.nanoTime()) / 1_000_000L);
                if (batch == null) {
                    parkedOnHeavyQuery = true; // still solving — re-check next tick
                    break;
                }
                active.session.supplyOverloadBatch(batch);
            }
            if (active.session.isDone()) {
                finalizeBlast(active);
                active = null;
                settled++;
            }
            if (parkedOnHeavyQuery || System.nanoTime() >= deadline) {
                break;
            }
        }
        // Stream finished blasts' craters into the world a few blocks per tick, still
        // under this tick's wall-clock seatbelt. A pass turns at most
        // maxCraterRemovalsPerTick blocks to AIR; the rest waits for the next tick — so
        // a 2000-block crater forms over many ticks instead of freezing one. Always do
        // one pass even if the scan loop already hit the deadline, so the crater can
        // never get stuck behind a busy scan queue (and a zero budget still drains it).
        while (craterApplier.hasPending()) {
            int removed = craterApplier.drainUpTo(maxCraterRemovalsPerTick);
            if (removed > 0) {
                didWork = true;
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
        // Record only ticks that actually did blast work; idle ticks (empty queue +
        // no active session) would otherwise flood the window and bury the real cost.
        if (didWork) {
            taskTimings.record(TaskTimings.BLAST_QUEUE, System.nanoTime() - start, Math.max(1, settled));
        }
    }

    /**
     * Give the in-flight off-thread query a SHORT bounded window to finish, so the
     * common small blast resolves in the same tick it always did (its query is
     * microseconds — the wait returns immediately) while a genuinely heavy query
     * parks the session across ticks instead of stalling this one. Returns the
     * batch, or {@code null} while the worker is still solving.
     *
     * <p>The grace is the per-tick stall ceiling for blasts: whatever the query
     * costs, this thread waits at most ~25ms before walking away.
     */
    /** Per-tick stall ceiling for off-thread solves: the shared grace budget. */
    private static final long GRACE_NANOS = 25_000_000L;

    private List<NodePos> takeSolve(long graceMs) {
        try {
            List<NodePos> batch = pendingSolve.get(graceMs, TimeUnit.MILLISECONDS);
            pendingSolve = null;
            return batch;
        } catch (TimeoutException stillSolving) {
            return null;
        } catch (ExecutionException | InterruptedException e) {
            // Worker failed: never wedge the blast — answer "nothing overloaded";
            // the next re-query (if any work truly remains) gets a fresh attempt.
            logger.warning("off-thread overload query failed; treating as stable: " + e);
            pendingSolve = null;
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }
    }

    /**
     * Start a resumable session for one queued blast, or return {@code null} if the
     * structure is gone/empty by the time its turn comes (dropped, logged at FINE).
     */
    private ActiveBlast beginNext(QueuedBlast blast) {
        World world = blast.world();
        Location center = blast.center();
        double power = blast.power();
        StructureGraph graph = structureManager.getGraph(world);
        // Stale-world guard: the structure may have been fully cleared since the event.
        if (graph == null || graph.isEmpty()) {
            logger.fine(() -> String.format(
                    "blast dropped: structure in world '%s' gone/empty when its turn came (centre %d,%d,%d)",
                    world.getName(), center.getBlockX(), center.getBlockY(), center.getBlockZ()));
            return null;
        }
        BlastContext ctx = BlastContext.builder()
                .center(StructureManager.toBlockPos(center))
                .power(power)
                .build();
        BlastSession session = solveWorker != null
                ? engine.beginWithExternalOverloadQueries(graph, ctx, BlastCallback.NONE)
                : engine.begin(graph, ctx, BlastCallback.NONE);
        return new ActiveBlast(blast, session);
    }

    /**
     * Finish a session: queue its crater for streamed application, schedule its
     * cascade, and fire the aggregate effects + recording. The crater is no longer
     * turned to AIR inline here — it is handed to the {@link CraterApplier}, which the
     * run loop drains a few blocks per tick.
     */
    private void finalizeBlast(ActiveBlast active) {
        QueuedBlast blast = active.blast;
        World world = blast.world();
        Location center = blast.center();
        double power = blast.power();
        BlastResult result = active.session.result();
        // The blast mutated the graph (craters + cracks) — bump the world revision
        // so grade/visualizer caches refresh even when nothing fully collapses.
        structureManager.markDirty(world);

        // Hand the crater to the streaming applier with THIS blast's debris budget; it
        // carries the budget across however many ticks the crater takes to drain.
        craterApplier.enqueue(world, result.destroyed(), config.getMaxDebrisPerExplosion());

        List<NodePos> allCollapsed = new ArrayList<>(result.collapsed());

        // Scoped ground-refresh over exactly the region the core already settled —
        // not a whole-graph floating scan (O(scope), not O(terrain)).
        List<NodePos> additionalCollapsed =
                structureManager.refreshGroundAndCollapseInScope(world, result.affectedScope());
        allCollapsed.addAll(additionalCollapsed);

        // Resume the cascade across later ticks. The in-core blast settle and the
        // scoped refresh above only finish what is FLOATING (no path to ground); a
        // blast that turns a structure into a doomed cantilever leaves blocks that
        // are still grounded but OVERLOADED — and whose overloaded root lies outside
        // the crater's affected scope, so neither step brings them down. Every other
        // disturbance (break, impact, fire, …) hands such leftovers to the resume
        // manager; the blast path used to be the one that didn't, abandoning them to
        // float forever (the blast-heavy-match "stranded floaters" report). Seed the
        // resume with this blast's disturbed region so the per-tick capped overload
        // settle drains them over later ticks until the structure is a fixpoint. The
        // resume manager retires immediately if nothing more was overloaded, so an
        // under-cap blast registers no lasting work.
        if (cascadeResumeManager != null && !result.affectedScope().isEmpty()) {
            cascadeResumeManager.enqueue(world, result.affectedScope());
        }

        int batchId = -1;
        if (!allCollapsed.isEmpty()) {
            batchId = delayedCollapseManager.startBatch(world, center, true);
        }

        for (NodePos pos : allCollapsed) {
            delayedCollapseManager.scheduleCollapse(world, pos, batchId);
        }

        int totalCollapsed = allCollapsed.size();
        int destroyed = result.destroyed().size();

        notifyNearby(world, center, destroyed, totalCollapsed, result.damaged().size());

        if (destroyed > 0) {
            effects.playCascadeComplete(world, center, destroyed);
        }

        recordBlast(world, center, power, result, allCollapsed, blast.actorId());

        logger.fine(() -> String.format(
                "blast power=%.1f at (%d,%d,%d): destroyed=%d collapsed=%d (additional=%d) cracked=%d",
                power,
                center.getBlockX(),
                center.getBlockY(),
                center.getBlockZ(),
                destroyed,
                totalCollapsed,
                additionalCollapsed.size(),
                result.damaged().size()));
    }

    /** Mirror the synchronous recording the listener used to do, now that the settle ran. */
    private void recordBlast(
            World world,
            Location center,
            double power,
            BlastResult result,
            List<NodePos> allCollapsed,
            String actorId) {
        EventRecorder recorder = structureManager.getEventRecorder();
        if (recorder.isRecording() && recorder instanceof MinecraftEventRecorder mcRecorder) {
            // Damaged = blocks that survived weakened. The core already prunes any
            // block it collapsed out of result.damaged(); subtract the adapter's
            // extra ground-refresh collapses too so the recorded damaged set stays
            // disjoint from collapsed (no double-counting in the session file).
            Map<NodePos, Double> damaged = new HashMap<>(result.damaged());
            for (NodePos pos : allCollapsed) {
                damaged.remove(pos);
            }
            recorder.record(new BlastEvent(
                    System.currentTimeMillis(),
                    mcRecorder.nextSequenceId(),
                    StructureManager.toBlockPos(center),
                    power,
                    "SPHERE",
                    new ArrayList<>(result.destroyed()),
                    new ArrayList<>(allCollapsed),
                    damaged,
                    actorId));
        }
    }

    private void notifyNearby(World world, Location center, int destroyed, int collapsed, int cracked) {
        if (destroyed + collapsed + cracked == 0) {
            return;
        }
        String msg = "§e⚡ strux blast §7» §c" + destroyed + " shattered§7, §6" + collapsed + " collapsed§7, §e"
                + cracked + " cracked";
        double r2 = config.getExplosionNotifyRadius() * config.getExplosionNotifyRadius();
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(center) <= r2) {
                p.sendMessage(msg);
            }
        }
    }

    /** The one blast being scanned/settled across ticks: its queued snapshot + live session. */
    private record ActiveBlast(QueuedBlast blast, BlastSession session) {}
}
