package dev.gesp.structural.minecraft.listener;

import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.perf.TaskTimings;
import dev.gesp.structural.minecraft.recording.MinecraftEventRecorder;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.recording.CascadeEvent;
import dev.gesp.structural.recording.CascadeStep;
import dev.gesp.structural.recording.EventRecorder;
import dev.gesp.structural.solver.CascadeEngine;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Finishes cascades that the step cap cut short, over the following ticks.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                    CASCADE RESUME MANAGER                          │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  maxCascadeSteps is a per-EVENT work budget. A giant collapse hits  │
 *   │  it on the break tick and stops mid-fall, leaving overloaded or     │
 *   │  floating blocks that nothing else ever cleans (they hang in the    │
 *   │  air). This task resumes the truncated cascade:                     │
 *   │                                                                     │
 *   │  1. Break path reports CascadeResult.truncated() → enqueue(world).  │
 *   │  2. Each tick: run ONE more capped settle pass on that world.       │
 *   │  3. New collapses ride the SAME delayed-collapse path (world        │
 *   │     removal + effects + rubble) and are recorded as a follow-up     │
 *   │     CASCADE event so the recording stays honest.                    │
 *   │  4. Still truncated? keep going next tick. Settled? drop the job.   │
 *   │                                                                     │
 *   │  Each tick's work is still capped, so one structure collapses over  │
 *   │  several ticks instead of freezing a tick or stranding chunks. A    │
 *   │  hard tick bound guarantees the loop always terminates.             │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class CascadeResumeManager extends BukkitRunnable {

    private final Plugin plugin;
    private final StructureManager structureManager;
    private final DelayedCollapseManager delayedCollapseManager;
    private final Logger logger;
    private final TaskTimings taskTimings;

    /**
     * Upper bound on follow-up ticks per world job. The cap makes each tick
     * collapse at most {@code maxCascadeSteps} blocks, so this also bounds the
     * total a single resume can clear; if a job somehow keeps finding work past
     * this, we stop and WARN rather than tick forever.
     */
    private final int maxResumeTicks;

    /**
     * When non-null (config {@code cascade.async-settle: true}), each tick's settle
     * runs off the main thread over a graph snapshot instead of inline. Null keeps
     * the byte-identical synchronous path.
     */
    private final AsyncSettleCoordinator asyncSettle;

    /** Worlds with a truncated cascade still being finished: worldId → in-flight job. */
    private final Map<UUID, ResumeJob> pending = new ConcurrentHashMap<>();

    private final Map<UUID, World> worlds = new ConcurrentHashMap<>();

    /**
     * One world's in-flight resume: how many ticks it has spent, plus the live
     * disturbed region to settle next. The scope is the previous pass's
     * {@code remainingScope} (or the originating cascade's affected region on the
     * first resume), so each tick re-settles only the collapsing structure rather
     * than re-deriving the whole graph. An empty scope means "whole graph" — the
     * back-compat fallback for callers that have no region to offer.
     */
    private static final class ResumeJob {
        int ticksSpent;
        Set<NodePos> scope;

        ResumeJob(Set<NodePos> scope) {
            this.scope = scope;
        }
    }

    public CascadeResumeManager(
            Plugin plugin,
            StructureManager structureManager,
            DelayedCollapseManager delayedCollapseManager,
            Logger logger,
            int maxResumeTicks,
            TaskTimings taskTimings) {
        this(plugin, structureManager, delayedCollapseManager, logger, maxResumeTicks, taskTimings, null);
    }

    public CascadeResumeManager(
            Plugin plugin,
            StructureManager structureManager,
            DelayedCollapseManager delayedCollapseManager,
            Logger logger,
            int maxResumeTicks,
            TaskTimings taskTimings,
            AsyncSettleCoordinator asyncSettle) {
        this.plugin = plugin;
        this.structureManager = structureManager;
        this.delayedCollapseManager = delayedCollapseManager;
        this.logger = logger;
        this.maxResumeTicks = Math.max(1, maxResumeTicks);
        this.taskTimings = taskTimings;
        this.asyncSettle = asyncSettle;
    }

    /** Begin draining truncated cascades once per tick. */
    public void start() {
        runTaskTimer(plugin, 1L, 1L);
    }

    /** Stop and forget any in-flight resume jobs. */
    public void stop() {
        try {
            cancel();
        } catch (IllegalStateException ignored) {
            // never started or already cancelled
        }
        pending.clear();
        worlds.clear();
    }

    /**
     * Mark a world's cascade as truncated so the next ticks finish it, with no
     * known disturbed region (whole-graph fallback). Prefer
     * {@link #enqueue(World, Set)} so the resume stays scoped.
     */
    public void enqueue(World world) {
        enqueue(world, Set.of());
    }

    /**
     * Mark a world's cascade as truncated so the next ticks finish it, settling
     * only {@code scope} (the originating cascade's affected region). Called by
     * the break/impact paths when {@code truncated()} is true. Idempotent: a world
     * already resuming keeps its in-flight job and unions the new region in, so a
     * second disturbance before the first drains is not lost.
     */
    public void enqueue(World world, Set<NodePos> scope) {
        if (world == null) {
            return;
        }
        worlds.put(world.getUID(), world);
        Set<NodePos> seed = scope == null ? Set.of() : scope;
        pending.compute(world.getUID(), (id, existing) -> {
            if (existing == null) {
                return new ResumeJob(new HashSet<>(seed));
            }
            // Union the new region in — unless either side is the whole-graph
            // fallback (empty), in which case the conservative whole-graph wins.
            if (!existing.scope.isEmpty() && !seed.isEmpty()) {
                existing.scope.addAll(seed);
            } else {
                existing.scope = new HashSet<>();
            }
            return existing;
        });
    }

    /** How many worlds still have a cascade being finished — for tests/diagnostics. */
    public int pendingWorlds() {
        return pending.size();
    }

    @Override
    public void run() {
        // Perf: time the whole resume pass; the work count is the cascade steps
        // (blocks) actually settled this tick across all resuming worlds.
        long start = System.nanoTime();
        int stepsRun = 0;
        Iterator<Map.Entry<UUID, ResumeJob>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ResumeJob> entry = it.next();
            World world = worlds.get(entry.getKey());
            if (world == null) {
                it.remove();
                continue;
            }

            ResumeJob job = entry.getValue();
            if (job.ticksSpent >= maxResumeTicks) {
                logger.warning(String.format(
                        "Cascade resume in world '%s' hit the %d-tick bound and was stopped; "
                                + "some blocks may remain mid-collapse. Raise cascade.max-resume-ticks if this is legitimate.",
                        world.getName(), maxResumeTicks));
                it.remove();
                worlds.remove(entry.getKey());
                continue;
            }

            CascadeEngine.SettleOutcome outcome;
            if (asyncSettle != null) {
                // Off-thread settle: submit on the first sight of this job, then park
                // across ticks until the worker answers. drainCompleted() returns null
                // while the solve is still running (or was discarded for a re-solve).
                if (!asyncSettle.inFlight(world)) {
                    asyncSettle.submit(world, job.scope);
                    continue; // parked waiting on the worker is not a settle round
                }
                outcome = asyncSettle.drainCompleted(world);
                if (outcome == null) {
                    continue; // still solving/re-solving — no productive work this tick
                }
                stepsRun += outcome.collapsed().size();
                applyCollapsed(world, outcome.collapsed());
                if (outcome.truncated() || !outcome.collapsed().isEmpty()) {
                    if (!outcome.remainingScope().isEmpty()) {
                        job.scope = new HashSet<>(outcome.remainingScope());
                    }
                    job.ticksSpent++;
                    // Re-submit the next round THIS tick so the worker solves it before
                    // the next drain — one settle round per tick, matching the sync path
                    // (without this the submit/drain split doubles the ticks a resume
                    // spends and trips the tick bound on a big collapse).
                    asyncSettle.submit(world, job.scope);
                } else {
                    it.remove();
                    worlds.remove(entry.getKey());
                }
                continue;
            }

            outcome = structureManager.resumeCascade(world, job.scope);
            stepsRun += outcome.collapsed().size();
            applyCollapsed(world, outcome.collapsed());

            if (outcome.truncated() || !outcome.collapsed().isEmpty()) {
                // Still cut short, or it just collapsed something this tick (which
                // may have exposed more) — keep finishing next tick. Carry the live
                // disturbed region forward so the next pass stays scoped; if this
                // pass didn't truncate (remainingScope empty) but still collapsed,
                // re-settle the same region once more to catch newly exposed work.
                if (!outcome.remainingScope().isEmpty()) {
                    job.scope = new HashSet<>(outcome.remainingScope());
                }
                job.ticksSpent++;
            } else {
                // Nothing collapsed and not truncated: the structure is settled.
                it.remove();
                worlds.remove(entry.getKey());
            }
        }
        // Record only ticks that resumed real steps; the manager ticks every tick,
        // so idle ticks (no pending jobs, or a job that found nothing) are skipped.
        if (stepsRun > 0) {
            taskTimings.record(TaskTimings.CASCADE_RESUME, System.nanoTime() - start, stepsRun);
        }
    }

    /**
     * Route a follow-up settle's collapses through the SAME world-removal +
     * effects + rubble path the original cascade used (the delayed-collapse
     * batch), then record them so the recording is an honest account of what
     * actually fell after the cap.
     */
    private void applyCollapsed(World world, List<CollapsedNode> collapsed) {
        if (collapsed.isEmpty()) {
            return;
        }

        Location origin = StructureManager.toLocation(collapsed.get(0).pos(), world);
        // Resumed cascades use fast collapse: the original event (block break, blast,
        // impact) already started the drama — the continuation shouldn't add extra delay.
        int batchId = delayedCollapseManager.startBatch(world, origin, true);
        for (CollapsedNode node : collapsed) {
            // Capture the world material BEFORE the delayed collapse turns it to
            // air; the settle only edited the graph, so the world block is still
            // present right now.
            Location loc = StructureManager.toLocation(node.pos(), world);
            Material material = loc.getBlock().getType();
            delayedCollapseManager.scheduleCollapse(world, node, material, batchId);
        }

        recordResume(world, collapsed);
    }

    /**
     * Record follow-up collapses as a CASCADE event (reason {@code BREAK_RESUME}).
     *
     * <p>Why a separate CASCADE event and not appended to the original
     * BLOCK_BREAK: the BLOCK_BREAK event was already written on the break tick
     * (its {@code collapsed} list is that tick's capped slice) and the recorder
     * is an append-only event log — there is no rewrite. CASCADE events are
     * informational on replay (the replayer re-derives the collapse itself), so
     * appending them changes nothing about determinism; they exist purely so the
     * recorded log honestly shows the blocks that fell on later ticks instead of
     * silently applying them.
     */
    private void recordResume(World world, List<CollapsedNode> collapsed) {
        EventRecorder recorder = structureManager.getEventRecorder();
        if (!recorder.isRecording() || !(recorder instanceof MinecraftEventRecorder mcRecorder)) {
            return;
        }
        List<CascadeStep> steps = new ArrayList<>(collapsed.size());
        int stepNumber = 0;
        for (CollapsedNode node : collapsed) {
            stepNumber++;
            Material material =
                    StructureManager.toLocation(node.pos(), world).getBlock().getType();
            steps.add(new CascadeStep(node.pos(), material.name(), stepNumber, "OVERLOADED"));
        }
        NodePos trigger = collapsed.get(0).pos();
        recorder.record(new CascadeEvent(
                System.currentTimeMillis(), mcRecorder.nextSequenceId(), trigger, "BREAK_RESUME", steps));
    }
}
