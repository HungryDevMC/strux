package dev.gesp.structural.minecraft.listener;

import dev.gesp.structural.minecraft.config.EffectsConfig;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.perf.TaskTimings;
import dev.gesp.structural.minecraft.protect.CollapseGuard;
import dev.gesp.structural.minecraft.rubble.RubbleHandler;
import dev.gesp.structural.minecraft.visual.CollapseEffects;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.NodePos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Manages delayed/progressive block failure with batch tracking.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                   DELAYED COLLAPSE MANAGER                         │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Blocks don't fail instantly - they show distress first:           │
 *   │                                                                     │
 *   │  1. Block becomes overloaded (>100% stress)                        │
 *   │  2. Cracking sounds and particles play (1-2 seconds)               │
 *   │  3. Block finally breaks                                           │
 *   │                                                                     │
 *   │  Batches track groups of blocks from the same event, so the        │
 *   │  cascade-complete effects play when ALL blocks finish.             │
 *   │                                                                     │
 *   │       [BLOCK] 🔊 *crack*  →  [BLOCK] 💥 *CRACK*  →  💨 (gone)      │
 *   │        t=0                    t=1s                   t=1.5s        │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class DelayedCollapseManager extends BukkitRunnable {

    private final Plugin plugin;
    private final StructureManager structureManager;
    private final EffectsConfig config;
    private final CollapseEffects effects;
    private final CollapseGuard guard;
    private final TaskTimings taskTimings;

    /** Rubble handler for spawning falling blocks (may be null if disabled) */
    private RubbleHandler rubbleHandler;

    /** Blocks pending collapse: position -> collapse info */
    private final Map<PendingCollapse, CollapseInfo> pendingCollapses = new HashMap<>();

    /** Active batches: batchId -> batch info */
    private final Map<Integer, BatchInfo> batches = new HashMap<>();

    private int nextBatchId = 0;

    /** Track blocks collapsed this tick per batch for impact shake */
    private final Map<Integer, Integer> collapseImpactsThisTick = new HashMap<>();

    /** Track collapsed nodes per batch for rubble processing */
    private final Map<Integer, List<CollapsedNode>> batchCollapsedNodes = new HashMap<>();

    /** Track materials for collapsed blocks */
    private final Map<Integer, Map<NodePos, Material>> batchMaterialCache = new HashMap<>();

    private long currentTick = 0;

    /** Measures live TPS from tick gaps; drives the collapse freeze guard. */
    private final TickRateMeter tickRate = new TickRateMeter();

    public DelayedCollapseManager(
            Plugin plugin,
            StructureManager structureManager,
            EffectsConfig config,
            CollapseEffects effects,
            CollapseGuard guard,
            TaskTimings taskTimings) {
        this.plugin = plugin;
        this.structureManager = structureManager;
        this.config = config;
        this.effects = effects;
        this.guard = guard;
        this.taskTimings = taskTimings;
    }

    /**
     * Set the rubble handler for spawning falling blocks.
     */
    public void setRubbleHandler(RubbleHandler rubbleHandler) {
        this.rubbleHandler = rubbleHandler;
    }

    /**
     * Start the manager running every tick.
     */
    public void start() {
        this.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * Start a new batch and return its ID.
     *
     * @param world the world
     * @param origin the batch origin location
     * @param fastCollapse if true, use shorter delay (for explosions)
     * @return batch ID
     */
    public int startBatch(World world, Location origin, boolean fastCollapse) {
        int batchId = ++nextBatchId;
        batches.put(batchId, new BatchInfo(world, origin, 0, 0, fastCollapse, null));
        batchCollapsedNodes.put(batchId, new ArrayList<>());
        batchMaterialCache.put(batchId, new HashMap<>());
        return batchId;
    }

    /**
     * Start a batch with default (slower) collapse timing.
     */
    public int startBatch(World world, Location origin) {
        return startBatch(world, origin, false);
    }

    /**
     * Set the player who triggered a batch (for item returns).
     */
    public void setBatchPlayer(int batchId, Player player) {
        BatchInfo batch = batches.get(batchId);
        if (batch != null) {
            batches.put(
                    batchId,
                    new BatchInfo(batch.world, batch.origin, batch.total, batch.completed, batch.fastCollapse, player));
        }
    }

    /**
     * Schedule a block for delayed collapse as part of a batch (legacy method for NodePos).
     *
     * @return true if the block was added, false if already pending
     */
    public boolean scheduleCollapse(World world, NodePos pos, int batchId) {
        return scheduleCollapse(world, pos, null, batchId);
    }

    /**
     * Schedule a block for delayed collapse as part of a batch with material info.
     *
     * @return true if the block was added, false if already pending
     */
    public boolean scheduleCollapse(World world, NodePos pos, Material material, int batchId) {
        PendingCollapse key = new PendingCollapse(world, pos);
        if (pendingCollapses.containsKey(key)) {
            return false;
        }

        BatchInfo batch = batches.get(batchId);
        boolean fastCollapse = batch != null && batch.fastCollapse;

        pendingCollapses.put(key, new CollapseInfo(currentTick, batchId, fastCollapse, material));

        if (batch != null) {
            batches.put(
                    batchId,
                    new BatchInfo(
                            batch.world,
                            batch.origin,
                            batch.total + 1,
                            batch.completed,
                            batch.fastCollapse,
                            batch.player));
        }

        // Cache material for rubble processing
        if (material != null && batchId >= 0) {
            Map<NodePos, Material> cache = batchMaterialCache.get(batchId);
            if (cache != null) {
                cache.put(pos, material);
            }
        }

        return true;
    }

    /**
     * Schedule a block for delayed collapse with CollapsedNode info.
     *
     * @return true if the block was added, false if already pending
     */
    public boolean scheduleCollapse(World world, CollapsedNode collapsed, Material material, int batchId) {
        boolean added = scheduleCollapse(world, collapsed.pos(), material, batchId);
        if (added && batchId >= 0) {
            List<CollapsedNode> nodes = batchCollapsedNodes.get(batchId);
            if (nodes != null) {
                nodes.add(collapsed);
            }
        }
        return added;
    }

    /**
     * Schedule a block for delayed collapse without a batch (standalone).
     */
    public boolean scheduleCollapse(World world, NodePos pos) {
        return scheduleCollapse(world, pos, null, -1);
    }

    /**
     * Check if a block is pending collapse.
     */
    public boolean isPendingCollapse(World world, NodePos pos) {
        return pendingCollapses.containsKey(new PendingCollapse(world, pos));
    }

    /**
     * Cancel a pending collapse.
     */
    public void cancelCollapse(World world, NodePos pos) {
        pendingCollapses.remove(new PendingCollapse(world, pos));
    }

    @Override
    public void run() {
        // Perf: time the whole pass; the work count is the blocks actually applied
        // (turned to air) this tick — the visible drama rate, capped by
        // max-collapses-per-tick.
        long start = System.nanoTime();
        currentTick++;
        collapseImpactsThisTick.clear();

        // Live TPS estimate (this task runs every tick) drives the freeze guard below.
        double recentTps = tickRate.sample(start);

        Iterator<Map.Entry<PendingCollapse, CollapseInfo>> it =
                pendingCollapses.entrySet().iterator();
        int warningsThisTick = 0;
        List<CollapseEvent> collapsesThisTick = new ArrayList<>();

        while (it.hasNext()) {
            Map.Entry<PendingCollapse, CollapseInfo> entry = it.next();
            PendingCollapse pending = entry.getKey();
            CollapseInfo info = entry.getValue();
            long elapsed = currentTick - info.startTick;

            long delayTicks =
                    info.fastCollapse ? config.getExplosionCollapseDelayTicks() : config.getCollapseDelayTicks();

            // Skip world reads entirely for blocks that are neither due nor emitting
            // a warning this tick — the most common case for large pending queues.
            boolean isDue = elapsed >= delayTicks;
            boolean wantsWarning = config.isCrackingWarningsEnabled()
                    && !info.fastCollapse
                    && elapsed % config.getCrackingWarningInterval() == 0
                    && warningsThisTick < config.getMaxCrackingWarningsPerTick();
            if (!isDue && !wantsWarning) {
                continue;
            }

            World world = pending.world();

            // A world that has been unloaded must never be touched: World.getBlockAt on a
            // stale CraftWorld (its ServerLevel removed) is undefined behaviour on Paper,
            // and the entry can never resolve anyway — the PendingCollapse key uses
            // CraftWorld identity, which a reload won't match — so it would leak forever.
            // Drop it.
            if (!Bukkit.getWorlds().contains(world)) {
                it.remove();
                markBatchCompleted(info.batchId, null);
                continue;
            }

            Location loc = StructureManager.toLocation(pending.pos(), world);
            Block block = loc.getBlock();

            if (block.getType().isAir()) {
                it.remove();
                markBatchCompleted(info.batchId, null);
                continue;
            }

            // Play warning effects (throttled)
            if (wantsWarning) {
                float progress = (float) elapsed / delayTicks;
                effects.playFailureWarning(loc, block.getType(), progress);
                warningsThisTick++;
            }

            if (isDue) {
                it.remove();
                collapsesThisTick.add(
                        new CollapseEvent(world, pending.pos(), block, info.batchId, info.fastCollapse, info.material));
            }
        }

        // Sort: highest Y first (top-down collapse)
        collapsesThisTick.sort((a, b) -> Integer.compare(b.pos.y(), a.pos.y()));

        // Process collapses. The per-tick cap is TPS-adaptive (the freeze guard): under
        // load it shrinks so a giant collapse can't drag the server down — it just spreads
        // over more ticks. A separate cap bounds how many blocks play effects this tick.
        int collapsedThisTick = 0;
        int effectsThisTick = 0;
        int cap = CollapseThrottle.effectiveCap(
                recentTps, config.getTpsFloor(), config.getMaxCollapsesPerTick(), config.getMinCollapsesPerTick());
        int effectsCap = config.getMaxCollapseEffectsPerTick();
        for (CollapseEvent event : collapsesThisTick) {
            if (collapsedThisTick >= cap) {
                // Over this tick's budget — re-schedule the rest for next tick.
                long delayTicks =
                        event.fastCollapse ? config.getExplosionCollapseDelayTicks() : config.getCollapseDelayTicks();
                pendingCollapses.put(
                        new PendingCollapse(event.world, event.pos),
                        new CollapseInfo(currentTick - delayTicks, event.batchId, event.fastCollapse, event.material));
            } else {
                boolean emitEffects = effectsThisTick < effectsCap;
                collapseBlock(event.world, event.pos, event.block, event.batchId, event.material, emitEffects);
                if (emitEffects) {
                    effectsThisTick++;
                }
                collapseImpactsThisTick.merge(event.batchId, 1, Integer::sum);
                collapsedThisTick++;
            }
        }

        processImpactShake();
        processProgressiveRumble();

        // Record only ticks that applied at least one collapse; the manager ticks
        // every tick, so idle ticks would otherwise drown the real collapse cost.
        if (collapsedThisTick > 0) {
            taskTimings.record(TaskTimings.DELAYED_COLLAPSE, System.nanoTime() - start, collapsedThisTick);
        }
    }

    private void processImpactShake() {
        if (!config.isScreenShakeEnabled()) return;

        for (Map.Entry<Integer, Integer> entry : collapseImpactsThisTick.entrySet()) {
            int batchId = entry.getKey();
            int impactsThisTick = entry.getValue();

            BatchInfo batch = batches.get(batchId);
            if (batch == null || batch.total < config.getScreenShakeThreshold()) continue;

            if (impactsThisTick >= 3) {
                float intensity = Math.min(0.1f + (impactsThisTick * 0.03f), 0.5f);
                effects.shakeNearbyPlayers(batch.world, batch.origin, intensity);
            }
        }
    }

    private void processProgressiveRumble() {
        for (BatchInfo batch : batches.values()) {
            if (batch.total > 10 && batch.completed > 0 && batch.completed < batch.total) {
                if (batch.completed % 10 == 0) {
                    float progress = (float) batch.completed / batch.total;
                    effects.playProgressiveRumble(batch.world, batch.origin, progress);
                }
            }
        }
    }

    private void collapseBlock(
            World world, NodePos pos, Block block, int batchId, Material cachedMaterial, boolean emitEffects) {
        // Removal gate: if this block is protected, leave it standing but stop
        // tracking it, and still let the batch finish.
        if (!guard.claimRemoval(block)) {
            structureManager.removeBlockDirect(world, pos);
            markBatchCompleted(batchId, null);
            return;
        }

        Material material = cachedMaterial != null ? cachedMaterial : block.getType();
        Location loc = block.getLocation();

        block.setType(Material.AIR);
        structureManager.removeBlockDirect(world, pos);

        // Effects are capped per tick (emitEffects) so a huge collapse can't flood clients
        // with particles/sounds — the blocks still vanish either way.
        if (emitEffects) {
            Location center = loc.clone().add(0.5, 0.5, 0.5);
            effects.playBlockCollapse(loc, material);
            world.spawnParticle(Particle.BLOCK, center, 8, 0.4, 0.4, 0.4, 0.1, material.createBlockData());
            world.spawnParticle(Particle.CLOUD, center, 4, 0.3, 0.2, 0.3, 0.02);
        }

        markBatchCompleted(batchId, pos);
    }

    private void markBatchCompleted(int batchId, NodePos completedPos) {
        if (batchId < 0) return;

        BatchInfo batch = batches.get(batchId);
        if (batch == null) return;

        int newCompleted = batch.completed + 1;
        if (newCompleted >= batch.total) {
            // Batch complete - process rubble and cleanup
            List<CollapsedNode> collapsedNodes = batchCollapsedNodes.remove(batchId);
            Map<NodePos, Material> materialCache = batchMaterialCache.remove(batchId);
            batches.remove(batchId);

            // Process rubble for the entire batch
            if (rubbleHandler != null && collapsedNodes != null && !collapsedNodes.isEmpty()) {
                int groundLevel = rubbleHandler.estimateAverageGroundLevel(batch.world, collapsedNodes);
                NodePos voidColumn =
                        new NodePos(batch.origin.getBlockX(), batch.origin.getBlockY(), batch.origin.getBlockZ());
                rubbleHandler.processCollapse(
                        batch.world,
                        collapsedNodes,
                        groundLevel,
                        batch.player,
                        (x, y, z) -> {
                            if (materialCache != null) {
                                return materialCache.getOrDefault(new NodePos(x, y, z), Material.AIR);
                            }
                            return Material.AIR;
                        },
                        voidColumn);
            }

            NodePos originPos =
                    new NodePos(batch.origin.getBlockX(), batch.origin.getBlockY(), batch.origin.getBlockZ());
            structureManager.triggerCascadeCheck(batch.world, originPos);
            effects.playCascadeComplete(batch.world, batch.origin, batch.total);
        } else {
            batches.put(
                    batchId,
                    new BatchInfo(
                            batch.world, batch.origin, batch.total, newCompleted, batch.fastCollapse, batch.player));
        }
    }

    /**
     * Stop the manager.
     */
    public void stop() {
        try {
            this.cancel();
        } catch (IllegalStateException ignored) {
        }
        // Flush — don't drop — pending world removals before clearing. The core cascade
        // already removed these nodes from the graph; only the WORLD setType(AIR) is
        // still pending, applied over later ticks. If we just clear here and the plugin
        // then saves the graph, the saved graph lacks the nodes while the world still has
        // the blocks: after restart they are physically present but untracked, so they
        // float forever and never collapse. Applying them now keeps world and graph in
        // sync. No effects/particles — shutdown is silent and fast.
        flushPendingRemovals();
        pendingCollapses.clear();
        batches.clear();
        batchCollapsedNodes.clear();
        batchMaterialCache.clear();
    }

    /**
     * Apply every still-pending world block removal synchronously. A protected block is
     * left standing (its region forbids removal) but still untracked, matching {@link
     * #collapseBlock}'s removal gate.
     */
    private void flushPendingRemovals() {
        for (PendingCollapse pending : pendingCollapses.keySet()) {
            World world = pending.world();
            NodePos pos = pending.pos();
            Block block = StructureManager.toLocation(pos, world).getBlock();
            if (guard.claimRemoval(block)) {
                block.setType(Material.AIR);
            }
            structureManager.removeBlockDirect(world, pos);
        }
    }

    private record PendingCollapse(World world, NodePos pos) {}

    private record CollapseInfo(long startTick, int batchId, boolean fastCollapse, Material material) {}

    private record BatchInfo(
            World world, Location origin, int total, int completed, boolean fastCollapse, Player player) {}

    private record CollapseEvent(
            World world, NodePos pos, Block block, int batchId, boolean fastCollapse, Material material) {}
}
