package dev.gesp.structural.minecraft.listener;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.impact.ImpactCallback;
import dev.gesp.structural.impact.ImpactContext;
import dev.gesp.structural.impact.ImpactEngine;
import dev.gesp.structural.impact.ImpactResult;
import dev.gesp.structural.minecraft.config.EffectsConfig;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.perf.TaskTimings;
import dev.gesp.structural.minecraft.protect.CollapseGuard;
import dev.gesp.structural.minecraft.recording.MinecraftEventRecorder;
import dev.gesp.structural.minecraft.visual.CollapseEffects;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.recording.EventRecorder;
import dev.gesp.structural.recording.ImpactEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;

/**
 * Settles queued projectile impacts on the main thread under a per-tick
 * wall-clock budget, so a volley against a huge structure can never freeze a
 * single server tick.
 *
 * <pre>
 *   Event time (cheap):   compute energy → enqueue QueuedImpact → return.
 *   Each tick (bounded):  while (budget left) { pop oldest impact; settle it. }
 * </pre>
 *
 * <p>A single {@link ImpactEngine#process} call is still atomic — the core API is
 * synchronous and one impact runs to completion. The budget only bounds HOW MANY
 * impacts are processed per tick; the budget is re-checked between queue items, so
 * the rest wait for the next tick. Order is strict FIFO, so a volley resolves in
 * the order the arrows landed.
 *
 * <p><b>Stale targets.</b> An impact processed a tick later may aim at a block that
 * has since collapsed or been broken. If the target node is no longer in the graph
 * we drop the impact silently (logged at {@code FINE}); the world has already moved
 * on and re-deriving a target would be guesswork.
 */
public class ImpactProcessor extends BudgetedDrainTask<QueuedImpact> {

    private final Plugin plugin;
    private final StructureManager structureManager;
    private final ImpactEngine engine;
    private final DelayedCollapseManager delayedCollapseManager;
    private final CascadeResumeManager cascadeResumeManager;
    private final DebrisVisuals debris;
    private final EffectsConfig config;
    private final CollapseEffects effects;
    private final CollapseGuard guard;
    private final Logger logger;
    private final TaskTimings taskTimings;

    /** FIFO queue of impacts awaiting a settle. Touched only on the main thread. */
    private final Deque<QueuedImpact> queue = new ArrayDeque<>();

    /**
     * This tick's drain deadline ({@code System.nanoTime()} value), shared with the
     * core settle as its cooperative pause signal. One {@link ImpactEngine#process}
     * used to be atomic — a structural hit on a huge graph froze the server for the
     * whole cascade (the siege 25s+ watchdog stalls). Now the settle pauses at the
     * deadline and reports {@code truncated()}, and the resume manager finishes the
     * collapse over later ticks. Touched only on the main thread.
     */
    private long passDeadline = Long.MAX_VALUE;

    public ImpactProcessor(
            Plugin plugin,
            StructureManager structureManager,
            ImpactEngine engine,
            DelayedCollapseManager delayedCollapseManager,
            CascadeResumeManager cascadeResumeManager,
            DebrisVisuals debris,
            EffectsConfig config,
            CollapseEffects effects,
            CollapseGuard guard,
            Logger logger,
            double tickBudgetMs,
            TaskTimings taskTimings) {
        this.plugin = plugin;
        this.structureManager = structureManager;
        this.engine = engine;
        this.delayedCollapseManager = delayedCollapseManager;
        this.cascadeResumeManager = cascadeResumeManager;
        this.debris = debris;
        this.config = config;
        this.effects = effects;
        this.guard = guard;
        this.logger = logger;
        this.taskTimings = taskTimings;
        setTickBudgetMs(tickBudgetMs);
    }

    /** Begin draining the queue once per tick. */
    public void start() {
        runTaskTimer(plugin, 1L, 1L);
    }

    /** Stop draining and drop anything still queued. */
    public void stop() {
        try {
            cancel();
        } catch (IllegalStateException ignored) {
            // never started or already cancelled
        }
        queue.clear();
    }

    /** Add an impact to the back of the queue (preserves FIFO / landing order). */
    public void enqueue(QueuedImpact impact) {
        queue.addLast(impact);
    }

    /** How many impacts are still waiting — for tests and diagnostics. */
    public int queueSize() {
        return queue.size();
    }

    /** FIFO source for the shared drain loop: the oldest queued impact, or null when empty. */
    @Override
    protected QueuedImpact poll() {
        return queue.pollFirst();
    }

    /** Whether the impact queue currently has nothing to settle. */
    @Override
    protected boolean isEmpty() {
        return queue.isEmpty();
    }

    @Override
    public void run() {
        long start = System.nanoTime();
        // The shared base owns the "process at least one, re-check the deadline
        // between items" drain loop; we just supply the queue and per-impact work.
        // The same deadline doubles as the core settle's pause signal (see
        // passDeadline), so a single impact can't blow past the tick budget either.
        passDeadline = start + tickBudgetNanos();
        int settled = drain(passDeadline);
        // Record only ticks that actually settled an impact; idle ticks (empty
        // queue) would otherwise flood the window with near-zero samples and bury
        // the real per-impact cost.
        if (settled > 0) {
            taskTimings.record(TaskTimings.IMPACT_QUEUE, System.nanoTime() - start, settled);
        }
    }

    /** Settle one queued impact, mirroring the synchronous behaviour the event handler used to run inline. */
    @Override
    protected void process(QueuedImpact impact) {
        World world = impact.world();
        StructureGraph graph = structureManager.getGraph(world);
        NodePos origin = impact.origin();
        // Stale-world guard: the target may have collapsed/broken since the hit.
        if (graph == null || !graph.hasBlock(origin)) {
            logger.fine(() -> String.format(
                    "impact dropped: target (%d,%d,%d) no longer tracked when its turn came",
                    origin.x(), origin.y(), origin.z()));
            return;
        }

        // Read the struck block's damage BEFORE the hit so we can record what THIS
        // hit dealt (a per-hit delta), not the block's running total. The block is
        // guaranteed present here (stale-world guard above).
        double damageBefore = graph.getNode(origin).damage();

        // Capture the struck material BEFORE settling: a penetrating hit removes the
        // block, so reading it afterwards would give AIR. Used only for the per-hit
        // feedback burst below.
        Material struckMaterial =
                StructureManager.toLocation(origin, world).getBlock().getType();

        ImpactResult result = engine.process(
                graph,
                ImpactContext.builder()
                        .origin(origin)
                        .direction(impact.dirX(), impact.dirY(), impact.dirZ())
                        .energy(impact.energy())
                        .build(),
                ImpactCallback.NONE,
                () -> System.nanoTime() >= passDeadline);
        // The impact mutated the graph (penetration + cracks) — bump the revision
        // so grade/visualizer caches refresh even when nothing fully collapses.
        structureManager.markDirty(world);

        int[] debrisBudget = {config.getMaxDebrisPerExplosion()};
        for (NodePos pos : result.penetrated()) {
            applyRemovalSingle(world, pos, debrisBudget);
        }

        // The core ImpactEngine already ran the full gravity settle over the
        // affected scope IN-CORE (it removes the penetrated blocks, seeds the
        // floating BFS from the severed stubs, and collapses everything that lost
        // support), so result.collapsed() is the complete in-scope collapse. The
        // block-break path needs a follow-up refreshGroundAndCollapseInScope because
        // IT removes the broken block adapter-side and never ran a core settle; the
        // impact path did, so a second scope-wide settle here was pure duplicate work
        // — on a structural hit that's a whole-building re-solve twice in one tick,
        // the projectile server-lag report. If the core settle truncated, the scoped
        // resume below finishes the rest across ticks.
        List<NodePos> allCollapsed = result.collapsed();

        // If the settle hit the step cap, hand the world to the resume manager so
        // the collapse continues on subsequent ticks (same path as block-break).
        if (result.truncated()) {
            cascadeResumeManager.enqueue(world, result.affectedScope());
        }

        int batchId = -1;
        if (!allCollapsed.isEmpty()) {
            batchId = delayedCollapseManager.startBatch(world, impact.hitLocation(), true);
        }
        for (NodePos pos : allCollapsed) {
            delayedCollapseManager.scheduleCollapse(world, pos, batchId);
        }

        recordImpact(impact, origin, result, allCollapsed, damageBefore);

        // Per-hit feedback: if this hit actually bit the wall — it cracked the struck
        // block (per-hit delta > 0) or punched through at least one block — show one
        // small burst at the hit point so a volley reads as many little bites. The
        // penetrated blocks already get playBlockCollapse where they are removed; this
        // burst is the single hit-point bite, not a second per-block effect.
        boolean dealtDamage = damageThisHit(origin, result, damageBefore) > 0.0;
        if (dealtDamage || !result.penetrated().isEmpty()) {
            effects.playImpactHit(impact.hitLocation(), struckMaterial);
        }

        if (result.penetrated().size() + allCollapsed.size() > 0) {
            logger.info(String.format(
                    "impact energy=%.1f at (%d,%d,%d): punched=%d collapsed=%d cracked=%d",
                    impact.energy(),
                    origin.x(),
                    origin.y(),
                    origin.z(),
                    result.penetrated().size(),
                    allCollapsed.size(),
                    result.damaged().size()));
        }
    }

    /** Mirror the synchronous recording the listener used to do, now that the settle ran. */
    private void recordImpact(
            QueuedImpact impact, NodePos origin, ImpactResult result, List<NodePos> allCollapsed, double damageBefore) {
        EventRecorder recorder = structureManager.getEventRecorder();
        if (recorder.isRecording() && recorder instanceof MinecraftEventRecorder mcRecorder) {
            // A penetrating impact removes MORE than the origin and cracks NON-origin
            // blocks along its path. Record the full path effect — penetrated[] plus
            // the per-block (absolute) crack levels — so replay reproduces the same
            // graph state and the pre-state never drifts. The scalar damageDealt stays
            // for legacy readers: the per-hit delta to the ORIGIN block (damageThisHit).
            recorder.record(new ImpactEvent(
                    System.currentTimeMillis(),
                    mcRecorder.nextSequenceId(),
                    origin,
                    impact.projectileType(),
                    impact.energy(),
                    damageThisHit(origin, result, damageBefore),
                    !result.penetrated().isEmpty(),
                    new ArrayList<>(allCollapsed),
                    new ArrayList<>(result.penetrated()),
                    new HashMap<>(result.damaged()),
                    impact.actorId()));
        }
    }

    /**
     * Damage THIS hit applied to the struck block, as a 0..1 delta — the value the
     * {@link ImpactEvent#damageDealt()} field documents, and the amount replay must
     * re-apply to reproduce the recording.
     *
     * <pre>
     *   Survived:   new cumulative damage − damage before the hit  (one hit's crack)
     *   Destroyed:  1 − damage before the hit  (the slice that finished it off; the
     *               engine drives a penetrated block to full damage before removing it,
     *               so the killing blow is never recorded as 0)
     * </pre>
     *
     * Earlier code summed the cumulative damage of every surviving block, so the
     * recorded value climbed hit after hit and dropped to 0 on the killing blow
     * (a destroyed block leaves {@code damaged()} for {@code penetrated()}).
     */
    private double damageThisHit(NodePos origin, ImpactResult result, double damageBefore) {
        // The struck block's damage AFTER this hit: full (1.0) if it was punched
        // through, its new cumulative crack level if it survived cracked, or unchanged
        // (→ delta 0) if the energy was spent past it without touching it.
        double after = damageBefore;
        if (result.penetrated().contains(origin)) {
            after = 1.0;
        } else {
            Double cracked = result.damaged().get(origin);
            if (cracked != null) {
                after = cracked;
            }
        }
        return after - damageBefore;
    }

    private void applyRemovalSingle(World world, NodePos pos, int[] debrisBudget) {
        Location loc = StructureManager.toLocation(pos, world);
        Block block = loc.getBlock();
        if (block.getType() == Material.AIR) {
            return;
        }
        if (!guard.claimRemoval(block)) {
            return;
        }
        BlockData data = block.getBlockData();
        block.setType(Material.AIR, false);
        effects.playBlockCollapse(loc, data.getMaterial());
        if (debrisBudget[0] > 0) {
            debris.spawn(world, loc, data);
            debrisBudget[0]--;
        }
    }
}
