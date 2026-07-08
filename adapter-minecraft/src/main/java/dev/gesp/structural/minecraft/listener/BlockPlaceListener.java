package dev.gesp.structural.minecraft.listener;

import dev.gesp.structural.api.CollapseReason;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.config.EffectsConfig;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.protect.CollapseGuard;
import dev.gesp.structural.minecraft.visual.ActionbarArbiter;
import dev.gesp.structural.minecraft.visual.CollapseEffects;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.recording.EventRecorder;
import java.util.ArrayList;
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
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Handles block placement and registers blocks in the structure.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                    BLOCK PLACE LISTENER                            │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  When a player places a block:                                     │
 *   │                                                                     │
 *   │  1. Register block in structure graph                              │
 *   │  2. Recalculate stress for the structure                           │
 *   │  3. If any block is overloaded → collapse it                       │
 *   │  4. Repeat until structure is stable                               │
 *   │                                                                     │
 *   │                                                                     │
 *   │  STABLE PLACEMENT:            OVERLOAD PLACEMENT:                  │
 *   │                                                                     │
 *   │       [?] ← new block              [HEAVY] ← placed                │
 *   │        │                              │                            │
 *   │       [A]                          [WEAK] ← collapses!             │
 *   │        │                              │                            │
 *   │      [GND]                          [GND]                          │
 *   │                                                                     │
 *   │     ✓ Structure stable          ⚠ Weak block collapses            │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class BlockPlaceListener implements Listener {

    private final StructureManager structureManager;
    private final EffectsConfig config;
    private final CollapseEffects effects;
    private final CollapseGuard guard;
    private final boolean preventUnstablePlacements;
    private final CollapseNotifier collapseNotifier;
    private final ActionbarArbiter arbiter;

    public BlockPlaceListener(
            StructureManager structureManager,
            EffectsConfig config,
            CollapseEffects effects,
            CollapseGuard guard,
            boolean preventUnstablePlacements,
            CollapseNotifier collapseNotifier,
            ActionbarArbiter arbiter) {
        this.structureManager = structureManager;
        this.config = config;
        this.effects = effects;
        this.guard = guard;
        this.preventUnstablePlacements = preventUnstablePlacements;
        this.collapseNotifier = collapseNotifier;
        this.arbiter = arbiter;
    }

    /**
     * If the most-stressed block in {@code stressMap} is over {@code threshold},
     * flash the player a "⚠ CRITICAL STRESS" warning — routed through the arbiter
     * so the live summary yields to it on the same tick (no flicker). Below the
     * threshold, nothing is sent. Package-private + static so the decision is
     * unit-testable without a full place event.
     *
     * @return whether a warning was sent
     */
    static boolean maybeWarnCriticalStress(
            ActionbarArbiter arbiter, Player player, Map<NodePos, Double> stressMap, double threshold) {
        double maxStress = 0.0;
        for (double stress : stressMap.values()) {
            if (stress > maxStress) {
                maxStress = stress;
            }
        }
        if (maxStress <= threshold) {
            return false;
        }
        int percent = (int) Math.round(maxStress * 100);
        return arbiter.send(
                player, ActionbarArbiter.Priority.CRITICAL_WARNING, ActionbarArbiter.criticalStressWarning(percent));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        Player player = event.getPlayer();

        // Non-solid blocks are not structural and carry no load. This filter
        // matters more than it looks: flint & steel fires a BlockPlaceEvent for
        // the FIRE block, which would otherwise be registered with the default
        // mass — igniting a stressed wall would knock it over by phantom weight.
        // Same rule as RegionScanner: only solid blocks enter the graph.
        if (block.getType().isAir() || !block.getType().isSolid()) {
            return;
        }

        // Physics off here (protected region / disabled world): leave the block
        // as vanilla and don't track it.
        if (!guard.physicsAllowed(block.getLocation())) {
            return;
        }

        if (preventUnstablePlacements) {
            if (!structureManager.wouldBeStable(block)) {
                event.setCancelled(true);
                player.sendMessage("§cThis block is too heavy! The structure would collapse.");
                return;
            }
        }

        World world = block.getWorld();

        // Capture material info BEFORE cascade (the block may collapse and become AIR)
        Material placedMaterial = block.getType();
        // Full block-state string (e.g. "minecraft:oak_planks[axis=y]") so a replay can
        // render the exact texture/orientation, not just the bare material name. Read now
        // because the placement may cascade this very block out of existence below.
        String placedBlockState = block.getBlockData().getAsString();
        MaterialSpec placedSpec = structureManager.getMaterialRegistry().getSpec(placedMaterial);
        NodePos placePos = StructureManager.toBlockPos(block);

        // Collected for the near-miss check: only OVERLOADED collapses carry a meaningful
        // stressAtCollapse, so floating ones are never near misses.
        List<CollapsedNode> overloadedCollapsed = new ArrayList<>();

        List<NodePos> collapsed = structureManager.onBlockPlaced(block, new SolverCallback() {
            @Override
            public boolean wantsStressUpdates() {
                return true; // opt in so the place path actually delivers onStressUpdated below
            }

            @Override
            public void onStressUpdated(Map<NodePos, Double> stressMap) {
                maybeWarnCriticalStress(arbiter, player, stressMap, config.getCriticalStressWarningThreshold());
            }

            @Override
            public void onCascadeStep(CollapsedNode collapsed, int stepNumber, CollapseReason reason) {
                Location loc = StructureManager.toLocation(collapsed.pos(), world);
                Block collapsingBlock = loc.getBlock();

                Material material = collapsingBlock.getType();
                // Removal gate: don't destroy blocks that fall inside a protected region.
                if (!guard.claimRemoval(collapsingBlock)) {
                    return;
                }
                collapsingBlock.setType(Material.AIR);

                if (reason == CollapseReason.OVERLOADED) {
                    overloadedCollapsed.add(collapsed);
                }

                effects.playBlockCollapse(loc, material);
            }

            @Override
            public void onCascadeComplete(List<CollapsedNode> allCollapsed) {
                if (!allCollapsed.isEmpty()) {
                    effects.playCascadeComplete(world, block.getLocation(), allCollapsed.size());
                }
            }
        });

        // Record the event (using pre-captured material, not current block state)
        EventRecorder recorder = structureManager.getEventRecorder();
        if (recorder.isRecording()
                && recorder instanceof dev.gesp.structural.minecraft.recording.MinecraftEventRecorder mcRecorder) {
            // The REAL grounding decision the place path made (a ground material or a
            // foundation block on terrain), read back off the graph — not a y==0 guess.
            // If the block collapsed during placement its node is gone, which means it
            // was certainly not an anchor, so false is correct.
            StructureGraph graph = structureManager.getGraph(world);
            Node placedNode = graph == null ? null : graph.getNode(placePos);
            boolean grounded = placedNode != null && placedNode.isGrounded();
            recorder.record(new dev.gesp.structural.recording.BlockPlaceEvent(
                    System.currentTimeMillis(),
                    mcRecorder.nextSequenceId(),
                    placePos,
                    placedBlockState,
                    placedSpec.mass(),
                    placedSpec.maxLoad(),
                    placedSpec.blastResistance(),
                    placedSpec.fireResistance(),
                    placedSpec.thermalClass(),
                    grounded,
                    new ArrayList<>(collapsed),
                    player.getUniqueId().toString()));
        }

        if (!collapsed.isEmpty()) {
            player.sendMessage("§c" + collapsed.size() + " blocks collapsed from overload!");
            // Chat feedback (big-collapse broadcast + first-collapse hint).
            collapseNotifier.onCollapse(player, collapsed.size());
        }

        NearMissNotifier.notifyIfNearMiss(player, overloadedCollapsed, config);
    }
}
