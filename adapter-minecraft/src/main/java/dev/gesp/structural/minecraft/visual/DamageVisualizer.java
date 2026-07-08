package dev.gesp.structural.minecraft.visual;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.crack.CrackLevel;
import dev.gesp.structural.crack.CrackModel;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.cache.RevisionCachedNodeView;
import dev.gesp.structural.minecraft.config.EffectsConfig;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.material.MaterialRegistry;
import dev.gesp.structural.minecraft.perf.TaskTimings;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Visualizes structural damage using Minecraft's native block breaking texture.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                     DAMAGE / CRACK VISUALIZER                      │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Shows players how cracked blocks are using the native Minecraft   │
 *   │  block breaking overlay texture. A block cracks for the worse of   │
 *   │  two reasons (its "distress"):                                     │
 *   │                                                                     │
 *   │    • accumulated blast/impact DAMAGE (persistent micro-fracture)   │
 *   │    • current STRESS — a heavily-loaded wall is visibly working     │
 *   │                                                                     │
 *   │  Crack propagation needs no script: during a cascade the solver    │
 *   │  re-runs and load shifts onto neighbours, so their cracks deepen   │
 *   │  on the next refresh — the cracks spread toward the failure on     │
 *   │  their own.                                                        │
 *   │                                                                     │
 *   │  Uses Player.sendBlockDamage() to render the authentic breaking    │
 *   │  animation texture that players recognize from mining.             │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class DamageVisualizer extends BukkitRunnable {

    private final StructureManager structureManager;
    private final MaterialRegistry materialRegistry;
    private final Plugin plugin;
    private final EffectsConfig config;
    private final CrackModel crackModel;
    private final TaskTimings taskTimings;

    /**
     * Positions that currently have a crack overlay on a client, per world. Lets a
     * later pass send one {@code progress = 0} packet to wipe an overlay the moment
     * the block stops being cracked (rescued, broken, or cracks disabled), instead
     * of letting it linger until the client-side animation times out (~2s).
     */
    private final Map<UUID, Set<NodePos>> overlaidByWorld = new HashMap<>();

    /**
     * FREEZE + BATCH (mirrors {@link StressVisualizer}): a block's crack distress
     * — accumulated damage, or stress at/over the hairline crack threshold — only
     * changes when its structure changes. So the set of "distressed" candidates is
     * cached per world and rebuilt (a full scan) only when that world's
     * {@code StructureManager.revision()} bumps; a static world then costs
     * O(distressed nodes) per pass instead of O(all tracked nodes). The
     * freeze-cache correctness argument lives once on {@link RevisionCachedNodeView}.
     */
    private final RevisionCachedNodeView distressedView;

    /**
     * Nodes the most recent {@link #run()} pass actually visited. Test seam for the
     * perf criterion: on a static world this equals the distressed count (~K), and
     * on a rebuild pass it equals the tracked count (N).
     */
    private int lastPassWork = 0;

    public DamageVisualizer(
            StructureManager structureManager,
            MaterialRegistry materialRegistry,
            Plugin plugin,
            EffectsConfig config,
            PhysicsConfig physicsConfig,
            TaskTimings taskTimings) {
        this.structureManager = structureManager;
        this.materialRegistry = materialRegistry;
        this.plugin = plugin;
        this.config = config;
        this.crackModel = new CrackModel(physicsConfig);
        this.taskTimings = taskTimings;
        this.distressedView = new RevisionCachedNodeView(structureManager, node -> {
            if (node.isGrounded()) {
                return false;
            }
            boolean damageCracked = node.damage() >= config.getMinVisibleDamage();
            boolean stressCracked = config.isStressCracksEnabled() && crackModel.crackLevel(node) != CrackLevel.NONE;
            return damageCracked || stressCracked;
        });
    }

    /**
     * Start the visualizer running every 1 second.
     *
     * @param intervalTicks ignored - we use fixed 20 tick (1 second) interval
     */
    public void start(long intervalTicks) {
        this.runTaskTimer(plugin, 20L, 20L); // 1 second interval
    }

    @Override
    public void run() {
        lastPassWork = 0;
        if (!config.isCracksEnabled()) {
            // Crack overlay disabled entirely (server owner's choice). Any overlay
            // we previously drew would otherwise linger client-side until it times
            // out — wipe each one and forget it so toggling cracks off is instant.
            clearAllOverlays();
            return;
        }
        // Perf: time the whole crack-refresh pass and count nodes iterated. Cracks
        // can appear ANYWHERE damage/stress is high, so this pass scans every
        // tracked node every pass — the work count is exactly that scan size, which
        // is what makes this the cheapest thing to optimize once it dominates.
        long start = System.nanoTime();
        int nodesIterated = 0;
        for (World world : plugin.getServer().getWorlds()) {
            StructureGraph graph = structureManager.getGraph(world);
            if (graph == null) {
                continue;
            }

            Collection<? extends Player> players = world.getPlayers();
            if (players.isEmpty()) {
                continue;
            }

            double viewDistSq = config.getDamageViewDistance() * config.getDamageViewDistance();
            Set<NodePos> nowOverlaid = new HashSet<>();

            // Iterate only the cached distressed candidates. The cache is rebuilt
            // (a full scan) on this pass only if the world's revision changed; a
            // static world reuses it and never scans the untouched majority. The
            // work count therefore drops from O(all tracked) to O(distressed) — the
            // whole point of this optimization, and what /strux perf now reports.
            Set<NodePos> distressed = distressedView.nodes(world, graph);
            // Mirror the old per-pass work seam: a rebuild charges the full scan (N),
            // a cache hit charges only the candidate set actually iterated (~K).
            int rebuildScan = distressedView.lastScanned();
            lastPassWork += rebuildScan >= 0 ? rebuildScan : distressed.size();

            // Collect positions where the block was replaced by an external tool
            // (CoreProtect rollback). We defer repair to avoid ConcurrentModificationException.
            List<NodePos> toRepair = new ArrayList<>();

            for (NodePos pos : distressed) {
                nodesIterated++;
                Node node = graph.getNode(pos);
                if (node == null) {
                    continue; // removed since the scan — let the clear-on-rescue pass wipe it
                }
                if (node.isGrounded()) {
                    continue;
                }

                // CoreProtect rollback / external edit detection: only applies to blocks
                // with accumulated damage. If the world block was replaced with a DIFFERENT
                // solid material, clear the stale damage. Air blocks are handled by the
                // normal BlockBreakEvent path. We only check solid replacements to avoid
                // false positives (e.g., MockBukkit worlds that don't place actual blocks).
                if (node.damage() > 0) {
                    Block worldBlock = world.getBlockAt(pos.x(), pos.y(), pos.z());
                    Material worldMaterial = worldBlock.getType();
                    if (!worldMaterial.isAir()) {
                        // Block is solid. Check if it's a DIFFERENT material (replaced block).
                        MaterialSpec worldSpec = materialRegistry.getSpec(worldMaterial);
                        if (!specsMatch(node.spec(), worldSpec)) {
                            // Material changed — this is a replaced/restored block. Clear damage.
                            toRepair.add(pos);
                        }
                    }
                }

                // A block cracks for the worse of two reasons: accumulated
                // damage (continuous, from the old visible-damage floor) and —
                // when enabled — structural distress bucketed into crack levels.
                double damage = node.damage();
                double damageProgress = damage >= config.getMinVisibleDamage() ? Math.min(damage, 0.99) : 0.0;
                double crackProgress = config.isStressCracksEnabled()
                        ? crackModel.crackLevel(node).overlayProgress()
                        : 0.0;
                double progress = Math.max(damageProgress, crackProgress);
                if (progress <= 0.0) {
                    continue; // dropped below distress — let the clear-on-rescue pass wipe it
                }

                Location loc = new Location(world, pos.x(), pos.y(), pos.z());
                int sourceId = positionToEntityId(pos);

                // Send to all nearby players every tick - block damage animation
                // times out client-side, so we need to refresh it regularly
                for (Player player : players) {
                    if (player.getLocation().distanceSquared(loc) <= viewDistSq) {
                        sendDamage(player, loc, (float) progress, sourceId);
                    }
                }
                nowOverlaid.add(pos);
            }

            // Any position we drew last pass that is no longer cracked (rescued to
            // progress 0, or broken out of the graph) gets ONE clear packet so the
            // overlay vanishes immediately instead of ageing out client-side.
            Set<NodePos> previous = overlaidByWorld.get(world.getUID());
            if (previous != null) {
                for (NodePos pos : previous) {
                    if (nowOverlaid.contains(pos)) {
                        continue;
                    }
                    Location loc = new Location(world, pos.x(), pos.y(), pos.z());
                    int sourceId = positionToEntityId(pos);
                    for (Player player : players) {
                        if (player.getLocation().distanceSquared(loc) <= viewDistSq) {
                            sendDamage(player, loc, 0.0f, sourceId);
                        }
                    }
                }
            }

            if (nowOverlaid.isEmpty()) {
                overlaidByWorld.remove(world.getUID());
            } else {
                overlaidByWorld.put(world.getUID(), nowOverlaid);
            }

            // Cleanup: repair replaced blocks. This handles CoreProtect rollback:
            // when a block is restored using Block.setType(), BlockPlaceEvent doesn't
            // fire, but we detect the material change here and clear the damage.
            for (NodePos pos : toRepair) {
                graph.repairNode(pos); // via graph so modCount moves (async settle conflict check)
            }
            if (!toRepair.isEmpty()) {
                structureManager.markDirty(world);
            }
        }
        taskTimings.record(TaskTimings.DAMAGE_VISUALIZER, System.nanoTime() - start, nodesIterated);
    }

    /** Test seam: nodes the most recent pass visited (~K on a static world, N on a rebuild). */
    int lastPassWork() {
        return lastPassWork;
    }

    /** Wipe every overlay we have drawn, in every world, and forget them all. */
    private void clearAllOverlays() {
        for (World world : plugin.getServer().getWorlds()) {
            Set<NodePos> previous = overlaidByWorld.remove(world.getUID());
            if (previous == null || previous.isEmpty()) {
                continue;
            }
            Collection<? extends Player> players = world.getPlayers();
            if (players.isEmpty()) {
                continue;
            }
            double viewDistSq = config.getDamageViewDistance() * config.getDamageViewDistance();
            for (NodePos pos : previous) {
                Location loc = new Location(world, pos.x(), pos.y(), pos.z());
                int sourceId = positionToEntityId(pos);
                for (Player player : players) {
                    if (player.getLocation().distanceSquared(loc) <= viewDistSq) {
                        sendDamage(player, loc, 0.0f, sourceId);
                    }
                }
            }
        }
    }

    /** Test seam: how many positions currently carry a tracked overlay in a world. */
    int trackedOverlayCount(World world) {
        Set<NodePos> overlaid = overlaidByWorld.get(world.getUID());
        return overlaid == null ? 0 : overlaid.size();
    }

    /**
     * Send (or clear) the crack overlay for one block to one player. A
     * {@code progress} of {@code 0.0f} wipes any overlay the same {@code sourceId}
     * created. Package-private and overridable purely as a test seam — MockBukkit's
     * {@code PlayerMock.sendBlockDamage} is a no-op, so tests cannot otherwise
     * observe which packets we emit.
     */
    void sendDamage(Player player, Location loc, float progress, int sourceId) {
        player.sendBlockDamage(loc, progress, sourceId);
    }

    /**
     * Generate a unique entity ID from a block position.
     * Uses high negative numbers to avoid collisions with real entities.
     */
    private int positionToEntityId(NodePos pos) {
        int hash = pos.x() * 73856093 ^ pos.y() * 19349663 ^ pos.z() * 83492791;
        return Integer.MIN_VALUE + (hash & 0x7FFFFFFF);
    }

    /**
     * Check if two MaterialSpecs represent the same material. Used to detect when a
     * block has been replaced by an external tool (CoreProtect rollback, WorldEdit).
     * A null worldSpec (unregistered material) is never a match.
     */
    private boolean specsMatch(MaterialSpec nodeSpec, MaterialSpec worldSpec) {
        if (worldSpec == null || nodeSpec == null) {
            return false;
        }
        // Compare by mass and maxLoad — the defining physical properties.
        // Different materials with identical physics are treated as equivalent.
        return Double.compare(nodeSpec.mass(), worldSpec.mass()) == 0
                && Double.compare(nodeSpec.maxLoad(), worldSpec.maxLoad()) == 0;
    }

    /**
     * Stop the visualizer.
     */
    public void stop() {
        try {
            this.cancel();
        } catch (IllegalStateException ignored) {
            // Already cancelled
        }
    }
}
