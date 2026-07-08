package dev.gesp.structural.minecraft.rubble;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.minecraft.config.EffectsConfig;
import dev.gesp.structural.minecraft.material.MaterialRegistry;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.rubble.RubbleCalculator;
import dev.gesp.structural.rubble.RubbleCalculator.RubbleCandidate;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/**
 * Handles rubble spawning and collapsed block returns for the Minecraft adapter.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                      RUBBLE HANDLER                                │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  When blocks collapse, this handler can:                           │
 *   │                                                                     │
 *   │  1. RUBBLE MODE (rubble-enabled=true)                              │
 *   │     Some blocks fall as FallingBlock entities instead of           │
 *   │     disappearing. Survival based on material toughness + height.   │
 *   │                                                                     │
 *   │       [STONE] collapses at y=70                                    │
 *   │         ↓                                                          │
 *   │       Survival chance calculated (e.g. 40%)                        │
 *   │         ↓                                                          │
 *   │       If lucky: FallingBlock spawns, falls to ground               │
 *   │       If not: block just disappears (shattered)                    │
 *   │                                                                     │
 *   │  2. RETURN MODE (return-collapsed-blocks=true)                     │
 *   │     Collapsed blocks are given back to the player as items.        │
 *   │     Useful for "fair" servers where players shouldn't lose         │
 *   │     materials due to physics.                                      │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class RubbleHandler {

    private final Plugin plugin;
    private final PhysicsConfig physicsConfig;
    private final EffectsConfig effectsConfig;
    private final MaterialRegistry materialRegistry;
    private final RubbleCalculator rubbleCalculator;

    public RubbleHandler(
            Plugin plugin,
            PhysicsConfig physicsConfig,
            EffectsConfig effectsConfig,
            MaterialRegistry materialRegistry) {
        this.plugin = plugin;
        this.physicsConfig = physicsConfig;
        this.effectsConfig = effectsConfig;
        this.materialRegistry = materialRegistry;
        this.rubbleCalculator = new RubbleCalculator(physicsConfig);
    }

    /**
     * Process collapsed blocks for rubble and item returns.
     *
     * @param world the world where collapse happened
     * @param collapsedNodes the nodes that collapsed
     * @param groundLevel the Y level of the ground (for fall calculations)
     * @param triggerPlayer the player who triggered the collapse (may be null)
     * @param materialLookup function to get Minecraft Material from position
     * @return list of blocks that became rubble (for visual effects)
     */
    public List<CollapsedNode> processCollapse(
            World world,
            List<CollapsedNode> collapsedNodes,
            int groundLevel,
            Player triggerPlayer,
            MaterialLookup materialLookup) {
        return processCollapse(world, collapsedNodes, groundLevel, triggerPlayer, materialLookup, null);
    }

    /**
     * Process collapsed blocks for rubble and item returns, biasing rubble toward
     * a dug-out void column so a dig-under-a-wall collapse reads as undermining.
     *
     * <p>Identical to {@link #processCollapse(World, List, int, Player, MaterialLookup)}
     * except for the {@code voidColumn}: when {@code effects.undermine.backfill-rubble}
     * is on and a column is given, each rubble entity is nudged sideways toward that
     * column so the tunnel partially fills instead of rubble dropping straight down.
     * Pure presentation — which blocks collapse is unchanged. Rubble spawns are capped
     * by {@code effects.undermine.max-rubble-per-collapse} so a big undermine can't
     * spawn hundreds of entities.
     *
     * @param voidColumn the broken block's column (the dug void) to bias rubble
     *     toward, or {@code null} for the plain straight-down scatter
     */
    public List<CollapsedNode> processCollapse(
            World world,
            List<CollapsedNode> collapsedNodes,
            int groundLevel,
            Player triggerPlayer,
            MaterialLookup materialLookup,
            NodePos voidColumn) {

        List<CollapsedNode> rubbleSpawned = new ArrayList<>();

        // Calculate which blocks become rubble
        List<RubbleCandidate> rubbleCandidates = rubbleCalculator.calculateRubble(collapsedNodes, groundLevel);

        // Spawn falling blocks for rubble
        if (effectsConfig.isRubbleEnabled()) {
            boolean backfill = effectsConfig.isUndermineBackfillRubble() && voidColumn != null;
            int cap = effectsConfig.getMaxRubblePerCollapse();
            for (RubbleCandidate candidate : rubbleCandidates) {
                if (rubbleSpawned.size() >= cap) {
                    break; // entity-spawn budget reached; drop the rest silently
                }
                Material material = materialLookup.getMaterial(candidate.x(), candidate.y(), candidate.z());

                if (material != null && material != Material.AIR) {
                    spawnRubble(world, candidate, material, backfill ? voidColumn : null);
                    rubbleSpawned.add(candidate.node());
                }
            }
        }

        // Return blocks to player as items
        if (effectsConfig.isReturnCollapsedBlocks() && triggerPlayer != null) {
            returnBlocksToPlayer(collapsedNodes, triggerPlayer, materialLookup);
        }

        return rubbleSpawned;
    }

    /**
     * Spawn a falling block entity for rubble, optionally drifting toward a void column.
     */
    private void spawnRubble(World world, RubbleCandidate candidate, Material material, NodePos voidColumn) {
        Location spawnLoc = new Location(world, candidate.x() + 0.5, candidate.y() + 0.5, candidate.z() + 0.5);

        try {
            FallingBlock fallingBlock = world.spawnFallingBlock(spawnLoc, material.createBlockData());

            // Add some random velocity for natural-looking scatter
            double spreadX = (Math.random() - 0.5) * 0.3;
            double spreadZ = (Math.random() - 0.5) * 0.3;
            double downVel = -0.1 - (Math.random() * 0.2);

            // Undermine backfill: bias the horizontal drift toward the dug void so
            // the rubble slides into the tunnel rather than landing straight down.
            if (voidColumn != null) {
                Vector toVoid = backfillNudge(candidate, voidColumn);
                spreadX += toVoid.getX();
                spreadZ += toVoid.getZ();
            }

            fallingBlock.setVelocity(new Vector(spreadX, downVel, spreadZ));

            // Rubble settings. Rubble that can't settle is discarded, never dropped
            // as an item: a collapse is destruction, not a loot source, and stray
            // item entities clutter the world (and a gamemode's inventories).
            fallingBlock.setDropItem(false);
            fallingBlock.setHurtEntities(true); // Damage entities it lands on
            fallingBlock.setCancelDrop(true);

        } catch (Exception e) {
            // Some materials can't be falling blocks, just skip
            plugin.getLogger().fine("Could not spawn rubble for " + material + ": " + e.getMessage());
        }
    }

    /**
     * A small, unit-length-scaled horizontal velocity pointing from a rubble
     * candidate toward the dug void column, so the falling block drifts into the
     * tunnel. Returns a zero vector for rubble already in the void column (nothing
     * to backfill there). The magnitude is a fixed gentle {@value #BACKFILL_NUDGE}
     * regardless of how far the rubble is from the void — a settle, not a launch —
     * so the direction is normalised by the distance.
     *
     * <p>Package-private and free of any randomness so it can be asserted exactly
     * in tests (the live spawn path adds cosmetic random scatter on top).
     */
    Vector backfillNudge(RubbleCandidate candidate, NodePos voidColumn) {
        double dx = (voidColumn.x() + 0.5) - (candidate.x() + 0.5);
        double dz = (voidColumn.z() + 0.5) - (candidate.z() + 0.5);
        double dist = Math.sqrt((dx * dx) + (dz * dz));
        if (dist < 1.0e-6) {
            return new Vector(0, 0, 0);
        }
        return new Vector((dx / dist) * BACKFILL_NUDGE, 0, (dz / dist) * BACKFILL_NUDGE);
    }

    /** Horizontal speed (blocks/tick) of the undermine backfill drift. */
    static final double BACKFILL_NUDGE = 0.15;

    /**
     * Give collapsed blocks back to the player as items.
     */
    private void returnBlocksToPlayer(
            List<CollapsedNode> collapsedNodes, Player player, MaterialLookup materialLookup) {

        for (CollapsedNode node : collapsedNodes) {
            Material material = materialLookup.getMaterial(
                    node.pos().x(), node.pos().y(), node.pos().z());

            if (material != null && material != Material.AIR && material.isItem()) {
                ItemStack item = new ItemStack(material, 1);

                // Try to add to inventory, drop at feet if full
                if (!player.getInventory().addItem(item).isEmpty()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), item);
                }
            }
        }
    }

    /**
     * Estimate ground level for a location (finds first solid block below).
     */
    public int estimateGroundLevel(World world, int x, int startY, int z) {
        int minY = world.getMinHeight();
        for (int y = startY - 1; y >= minY; y--) {
            Material mat = world.getBlockAt(x, y, z).getType();
            if (mat.isSolid()) {
                return y + 1; // Ground level is one above the solid block
            }
        }
        return minY + effectsConfig.getRubbleGroundOffset();
    }

    /**
     * Estimate average ground level for a collapse area.
     */
    public int estimateAverageGroundLevel(World world, List<CollapsedNode> nodes) {
        if (nodes.isEmpty()) {
            return world.getMinHeight() + effectsConfig.getRubbleGroundOffset();
        }

        // Sample a few positions to find average ground
        int samples = Math.min(5, nodes.size());
        int totalGround = 0;

        for (int i = 0; i < samples; i++) {
            CollapsedNode node = nodes.get(i * nodes.size() / samples);
            totalGround += estimateGroundLevel(
                    world, node.pos().x(), node.pos().y(), node.pos().z());
        }

        return totalGround / samples;
    }

    /**
     * Functional interface for looking up Minecraft Material from coordinates.
     * This allows the handler to work without direct block access (for delayed collapses).
     */
    @FunctionalInterface
    public interface MaterialLookup {
        Material getMaterial(int x, int y, int z);
    }
}
