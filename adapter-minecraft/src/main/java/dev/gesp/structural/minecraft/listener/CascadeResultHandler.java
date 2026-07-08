package dev.gesp.structural.minecraft.listener;

import dev.gesp.structural.api.CollapseReason;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.protect.CollapseGuard;
import dev.gesp.structural.minecraft.visual.CollapseEffects;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.recording.StressDeltaCollector;
import dev.gesp.structural.solver.CascadeResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Shared collapse-outcome pipeline for the block listeners.
 *
 * <p>Break, liquid, fire, piston and growth cascades all funnel their collapsed nodes through the
 * same steps: bin nodes into floating vs overloaded while caching materials, remove floating blocks
 * immediately, schedule the overloaded ones for a delayed batch, resume a truncated cascade, and
 * mark the world dirty. This class owns that identical core so each listener keeps only its own
 * variations (rubble, recording, near-miss, reverse iteration) at the call site.
 */
public class CascadeResultHandler {

    private final StructureManager structureManager;
    private final DelayedCollapseManager delayedCollapseManager;
    private final CascadeResumeManager cascadeResumeManager;
    private final CollapseGuard guard;
    private final CollapseEffects effects;

    public CascadeResultHandler(
            StructureManager structureManager,
            DelayedCollapseManager delayedCollapseManager,
            CascadeResumeManager cascadeResumeManager,
            CollapseGuard guard,
            CollapseEffects effects) {
        this.structureManager = structureManager;
        this.delayedCollapseManager = delayedCollapseManager;
        this.cascadeResumeManager = cascadeResumeManager;
        this.guard = guard;
        this.effects = effects;
    }

    /**
     * A callback that bins each collapsed node into {@code floating} or {@code overloaded} by its
     * {@link CollapseReason} and records the pre-removal material in {@code materialCache}.
     *
     * @param skipTrigger drop TRIGGER frames (the driver already removed that block) — breaks and
     *     event-driven removals set this; a raw {@code CascadeEngine} pass leaves it false
     * @param stressCollector optional schema-v3 stress capture; when non-null the callback requests
     *     and forwards stress deltas, otherwise it pays nothing
     */
    public SolverCallback binningCallback(
            World world,
            List<CollapsedNode> floating,
            List<CollapsedNode> overloaded,
            Map<NodePos, Material> materialCache,
            boolean skipTrigger,
            StressDeltaCollector stressCollector) {
        return new SolverCallback() {
            @Override
            public void onStressUpdated(Map<NodePos, Double> stressMap) {}

            @Override
            public boolean wantsStressDeltas() {
                return stressCollector != null;
            }

            @Override
            public void onStressDelta(Map<NodePos, Double> loadRatios) {
                if (stressCollector != null) {
                    stressCollector.accept(loadRatios);
                }
            }

            @Override
            public void onCascadeStep(CollapsedNode collapsed, int stepNumber, CollapseReason reason) {
                if (skipTrigger && reason == CollapseReason.TRIGGER) {
                    return;
                }

                Location location = StructureManager.toLocation(collapsed.pos(), world);
                materialCache.put(collapsed.pos(), location.getBlock().getType());

                if (reason == CollapseReason.OVERLOADED) {
                    overloaded.add(collapsed);
                } else {
                    floating.add(collapsed);
                }
            }

            @Override
            public void onCascadeComplete(List<CollapsedNode> allCollapsed) {}
        };
    }

    /**
     * A callback for placement cascades that removes each collapsing block immediately (there is no
     * separate floating/overloaded split for these paths) and plays the cascade-complete effect.
     *
     * @param completeOrigin where to centre the cascade-complete effect; when null, the first
     *     collapsed node's location is used
     */
    public SolverCallback immediateCollapseCallback(World world, Location completeOrigin) {
        return new SolverCallback() {
            @Override
            public void onStressUpdated(Map<NodePos, Double> stressMap) {}

            @Override
            public void onCascadeStep(CollapsedNode collapsed, int stepNumber, CollapseReason reason) {
                Location location = StructureManager.toLocation(collapsed.pos(), world);
                Block collapsingBlock = location.getBlock();

                if (!guard.claimRemoval(collapsingBlock)) {
                    return;
                }

                Material material = collapsingBlock.getType();
                collapsingBlock.setType(Material.AIR);
                effects.playBlockCollapse(location, material);
            }

            @Override
            public void onCascadeComplete(List<CollapsedNode> allCollapsed) {
                if (allCollapsed.isEmpty()) {
                    return;
                }
                Location origin = completeOrigin != null
                        ? completeOrigin
                        : StructureManager.toLocation(allCollapsed.get(0).pos(), world);
                effects.playCascadeComplete(world, origin, allCollapsed.size());
            }
        };
    }

    /**
     * Removes the floating nodes from the world immediately (protection-gated), playing each block's
     * collapse effect. Returns the nodes actually removed (a claim can be refused by protection).
     */
    public List<CollapsedNode> removeFloatingImmediately(World world, List<CollapsedNode> floating) {
        List<CollapsedNode> removed = new ArrayList<>();
        for (CollapsedNode collapsed : floating) {
            Location location = StructureManager.toLocation(collapsed.pos(), world);
            Block collapsingBlock = location.getBlock();

            if (!guard.claimRemoval(collapsingBlock)) {
                continue;
            }

            Material material = collapsingBlock.getType();
            collapsingBlock.setType(Material.AIR);
            effects.playBlockCollapse(location, material);
            removed.add(collapsed);
        }
        return removed;
    }

    /**
     * Schedules the overloaded nodes for a delayed collapse batch, keyed at {@code origin}. Returns
     * the batch id (so a caller can attach a player for item returns), or -1 when there is nothing
     * to schedule.
     */
    public int scheduleOverloaded(
            World world, Location origin, List<CollapsedNode> overloaded, Map<NodePos, Material> materialCache) {
        if (overloaded.isEmpty()) {
            return -1;
        }
        int batchId = delayedCollapseManager.startBatch(world, origin);
        for (CollapsedNode collapsed : overloaded) {
            Material material = materialCache.getOrDefault(collapsed.pos(), Material.STONE);
            delayedCollapseManager.scheduleCollapse(world, collapsed, material, batchId);
        }
        return batchId;
    }

    /**
     * Hands a truncated cascade's remaining scope to the resume manager, which settles a little more
     * each tick until the structure is stable. No-op when the cascade completed within its step cap.
     */
    public void resumeIfTruncated(World world, CascadeResult result) {
        if (result.truncated()) {
            cascadeResumeManager.enqueue(world, result.remainingScope());
        }
    }

    public void markDirty(World world) {
        structureManager.markDirty(world);
    }
}
