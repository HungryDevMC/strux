package dev.gesp.structural.minecraft.listener;

import dev.gesp.structural.minecraft.config.EffectsConfig;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.protect.CollapseGuard;
import dev.gesp.structural.minecraft.recording.MinecraftEventRecorder;
import dev.gesp.structural.minecraft.rubble.RubbleHandler;
import dev.gesp.structural.minecraft.visual.CollapseEffects;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.recording.EventRecorder;
import dev.gesp.structural.recording.StressDeltaCollector;
import dev.gesp.structural.solver.CascadeResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

/**
 * Handles block break events and triggers cascade collapse.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                    BLOCK BREAK LISTENER                            │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  When a player breaks a block:                                     │
 *   │                                                                     │
 *   │  1. Check if block is in a tracked structure                       │
 *   │  2. Run cascade simulation                                         │
 *   │  3. For each collapsed block:                                      │
 *   │     • Remove it from Minecraft world                               │
 *   │     • Spawn rubble (if enabled)                                    │
 *   │     • Return items to player (if enabled)                          │
 *   │     • Play effects (particles, sound)                              │
 *   │                                                                     │
 *   │                                                                     │
 *   │       [D]                                                          │
 *   │        │           Player breaks [B]:                              │
 *   │       [C]          → Cascade runs                                  │
 *   │        │           → C, D collapse                                 │
 *   │       [B] ← BREAK  → Blocks fall, particles spawn                  │
 *   │        │                                                           │
 *   │       [A]                                                          │
 *   │        │                                                           │
 *   │      [GND]                                                         │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class BlockBreakListener implements Listener {

    private final StructureManager structureManager;
    private final DelayedCollapseManager delayedCollapseManager;
    private final CollapseEffects effects;
    private final CollapseGuard guard;
    private final RubbleHandler rubbleHandler;
    private final EffectsConfig effectsConfig;
    private final CollapseNotifier collapseNotifier;
    private final CascadeResultHandler cascades;

    public BlockBreakListener(
            StructureManager structureManager,
            DelayedCollapseManager delayedCollapseManager,
            CascadeResumeManager cascadeResumeManager,
            CollapseEffects effects,
            CollapseGuard guard,
            RubbleHandler rubbleHandler,
            EffectsConfig effectsConfig,
            CollapseNotifier collapseNotifier) {
        this.structureManager = structureManager;
        this.delayedCollapseManager = delayedCollapseManager;
        this.effects = effects;
        this.guard = guard;
        this.rubbleHandler = rubbleHandler;
        this.effectsConfig = effectsConfig;
        this.collapseNotifier = collapseNotifier;
        this.cascades = new CascadeResultHandler(
                structureManager, delayedCollapseManager, cascadeResumeManager, guard, effects);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();

        if (!structureManager.isTracked(block)) {
            return;
        }

        // Trigger gate: no physics in protected regions / disabled worlds.
        if (!guard.physicsAllowed(block.getLocation())) {
            return;
        }

        World world = block.getWorld();
        Location breakLocation = block.getLocation();

        List<CollapsedNode> floatingCollapsed = new ArrayList<>();
        List<CollapsedNode> overloadedCollapsed = new ArrayList<>();

        // Capture material info for rubble/return before blocks are removed
        Map<NodePos, Material> materialCache = new HashMap<>();

        // Schema-v3 stress capture: only build a collector when the active recording
        // asked for it, so a non-recording (or capture-off) break pays nothing.
        EventRecorder stressRecorder = structureManager.getEventRecorder();
        StressDeltaCollector stressCollector =
                stressRecorder instanceof MinecraftEventRecorder mcStressRecorder && mcStressRecorder.isCaptureStress()
                        ? new StressDeltaCollector()
                        : null;

        CascadeResult result = structureManager.onBlockBroken(
                block,
                cascades.binningCallback(
                        world, floatingCollapsed, overloadedCollapsed, materialCache, true, stressCollector));

        // Process floating blocks immediately
        List<CollapsedNode> immediatelyCollapsed = cascades.removeFloatingImmediately(world, floatingCollapsed);
        int floatingRemoved = immediatelyCollapsed.size();

        // Handle rubble and item returns for immediately collapsed blocks
        if (!immediatelyCollapsed.isEmpty() && rubbleHandler != null) {
            int groundLevel = rubbleHandler.estimateAverageGroundLevel(world, immediatelyCollapsed);
            NodePos voidColumn = StructureManager.toBlockPos(block);
            rubbleHandler.processCollapse(
                    world,
                    immediatelyCollapsed,
                    groundLevel,
                    event.getPlayer(),
                    (x, y, z) -> materialCache.getOrDefault(new NodePos(x, y, z), Material.AIR),
                    voidColumn);
        }

        if (floatingRemoved > 0) {
            effects.playCascadeComplete(world, breakLocation, floatingRemoved);
        }

        // Schedule overloaded blocks for delayed collapse
        int batchId = cascades.scheduleOverloaded(world, breakLocation, overloadedCollapsed, materialCache);
        if (batchId != -1) {
            // Store player reference for item returns on delayed collapses
            delayedCollapseManager.setBatchPlayer(batchId, event.getPlayer());
        }

        // Record the event
        recordBreak(block, immediatelyCollapsed, overloadedCollapsed, stressCollector, event.getPlayer());

        // The cascade hit the per-event step cap and stopped mid-collapse: the
        // graph still has overloaded or newly-floating blocks that nothing else
        // would finish. Hand the world to the resume manager, which settles a
        // little more each tick (still capped) until the structure is stable, so
        // a huge collapse finishes over the next ticks instead of stranding
        // chunks in the air.
        cascades.resumeIfTruncated(world, result);

        if (result.hadCascade()) {
            event.getPlayer().sendMessage("§c" + result.totalCollapsed() + " blocks collapsed!");
            // Chat feedback (big-collapse broadcast + first-collapse hint). For a
            // truncated cascade this is the first-tick size — the blocks that came
            // down inside this event — not the eventual resumed total.
            collapseNotifier.onCollapse(event.getPlayer(), result.totalCollapsed());
        }

        NearMissNotifier.notifyIfNearMiss(event.getPlayer(), overloadedCollapsed, effectsConfig);
    }

    /**
     * Record this break to the active recording, if one is running — the origin block,
     * the collapsed positions (immediate floaters + scheduled overloaded), the breaking
     * player, and the captured stress deltas. Mirrors the recordBlast / recordImpact
     * pattern in the blast and impact processors.
     */
    private void recordBreak(
            Block block,
            List<CollapsedNode> immediatelyCollapsed,
            List<CollapsedNode> overloadedCollapsed,
            StressDeltaCollector stressCollector,
            Player player) {
        EventRecorder recorder = structureManager.getEventRecorder();
        if (recorder.isRecording() && recorder instanceof MinecraftEventRecorder mcRecorder) {
            NodePos breakPos = StructureManager.toBlockPos(block);
            List<NodePos> collapsedPositions = new ArrayList<>();
            for (CollapsedNode collapsedNode : immediatelyCollapsed) {
                collapsedPositions.add(collapsedNode.pos());
            }
            for (CollapsedNode collapsedNode : overloadedCollapsed) {
                collapsedPositions.add(collapsedNode.pos());
            }
            recorder.record(new dev.gesp.structural.recording.BlockBreakEvent(
                    System.currentTimeMillis(),
                    mcRecorder.nextSequenceId(),
                    breakPos,
                    block.getType().name(),
                    collapsedPositions,
                    player.getUniqueId().toString(),
                    stressCollector != null ? stressCollector.build() : null));
        }
    }
}
