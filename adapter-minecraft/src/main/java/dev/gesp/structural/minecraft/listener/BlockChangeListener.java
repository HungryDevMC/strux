package dev.gesp.structural.minecraft.listener;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.protect.CollapseGuard;
import dev.gesp.structural.minecraft.visual.CollapseEffects;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Enderman;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.plugin.Plugin;

/**
 * Catches block changes that bypass the normal break/place events and routes them
 * through the structure graph.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                    BLOCK CHANGE LISTENER                           │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Handles mechanics that modify blocks without BlockBreak/Place:   │
 *   │                                                                     │
 *   │  • BlockBurnEvent       - Fire destroys a block                    │
 *   │  • BlockFromToEvent     - Liquid flow replaces blocks              │
 *   │  • BlockFormEvent       - Cobblestone gen, ice/snow/concrete form  │
 *   │  • BlockFadeEvent       - Ice melts, snow disappears               │
 *   │  • BlockPhysicsEvent    - Sand/gravel starts falling               │
 *   │  • StructureGrowEvent   - Tree/mushroom growth adds blocks         │
 *   │  • EntityChangeBlockEvent - Enderman pickup, falling block landing │
 *   │                                                                     │
 *   │  PERFORMANCE:                                                       │
 *   │  • Early bailout if block not tracked or physics disabled          │
 *   │  • These events are infrequent compared to player actions          │
 *   │  • Cascade runs synchronously (same as block break)                │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class BlockChangeListener implements Listener {

    private final Plugin plugin;
    private final StructureManager structureManager;
    private final PhysicsConfig physicsConfig;
    private final CollapseEffects effects;
    private final CollapseGuard guard;
    private final CascadeResultHandler cascades;

    public BlockChangeListener(
            Plugin plugin,
            StructureManager structureManager,
            PhysicsConfig physicsConfig,
            DelayedCollapseManager delayedCollapseManager,
            CascadeResumeManager cascadeResumeManager,
            CollapseEffects effects,
            CollapseGuard guard) {
        this.plugin = plugin;
        this.structureManager = structureManager;
        this.physicsConfig = physicsConfig;
        this.effects = effects;
        this.guard = guard;
        this.cascades = new CascadeResultHandler(
                structureManager, delayedCollapseManager, cascadeResumeManager, guard, effects);
    }

    /**
     * Fire destroys a block — treat as removal and run cascade.
     * This complements FireScorchTask which handles gradual heat damage.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        Block block = event.getBlock();

        // Fast path: not tracked → nothing to do
        if (!structureManager.isTracked(block)) {
            return;
        }

        // Physics disabled here
        if (!guard.physicsAllowed(block.getLocation())) {
            return;
        }

        // The block is about to be destroyed by fire — run cascade
        processBlockRemoval(block, "fire");
    }

    /**
     * Liquid flow can destroy blocks (water washing away torches, etc.) or flow
     * into a tracked structure. We care about the destination block being replaced.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLiquidFlow(BlockFromToEvent event) {
        Block toBlock = event.getToBlock();

        // Fast path: destination not tracked → nothing structural happening
        if (!structureManager.isTracked(toBlock)) {
            return;
        }

        // Liquid flowing INTO a tracked block means that block is being replaced
        // Only care if the destination is a solid block (not air being filled)
        if (toBlock.getType().isAir() || !toBlock.getType().isSolid()) {
            return;
        }

        // Physics disabled here
        if (!guard.physicsAllowed(toBlock.getLocation())) {
            return;
        }

        // The solid block is about to be replaced by liquid — run cascade
        processBlockRemoval(toBlock, "liquid");
    }

    /**
     * Block forms naturally: cobblestone from lava+water, ice from water freezing,
     * snow layers, concrete hardening. Register if near a tracked structure.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        Block block = event.getBlock();
        BlockState newState = event.getNewState();
        World world = block.getWorld();

        // Only care about solid blocks forming
        if (!newState.getType().isSolid()) {
            return;
        }

        StructureGraph graph = structureManager.getGraph(world);
        if (graph == null || graph.isEmpty()) {
            return;
        }

        NodePos pos = StructureManager.toBlockPos(block.getLocation());

        // Case 1: Block is already tracked (e.g., water in a tracked position freezing to ice)
        if (graph.hasBlock(pos)) {
            // Material is changing — the old node remains with the new material
            // This is handled by DamageVisualizer's material-change detection
            return;
        }

        // Case 2: New block forming near a structure — register it
        if (!hasTrackedNeighbor(graph, pos)) {
            return;
        }

        // Physics disabled here
        if (!guard.physicsAllowed(block.getLocation())) {
            return;
        }

        // Schedule registration 1 tick later so the block state is applied
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> registerPlacedBlock(block, world), 1L);
    }

    /**
     * Block fades: ice melts, snow disappears, fire burns out.
     * Treat as removal if the block was tracked.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent event) {
        Block block = event.getBlock();

        // Fast path: not tracked → nothing to do
        if (!structureManager.isTracked(block)) {
            return;
        }

        // Only care if it's becoming air (actually disappearing)
        if (!event.getNewState().getType().isAir()) {
            return;
        }

        // Physics disabled here
        if (!guard.physicsAllowed(block.getLocation())) {
            return;
        }

        // Block is fading away — run cascade
        processBlockRemoval(block, "fade");
    }

    /**
     * Block physics: detects when gravity-affected blocks (sand, gravel, concrete powder)
     * start falling. The block turns to AIR and becomes a FallingBlock entity.
     * We need to remove it from the graph when it starts falling.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        Material type = block.getType();

        // Only care about gravity-affected blocks
        if (!isGravityBlock(type)) {
            return;
        }

        // Fast path: not tracked → nothing to do
        if (!structureManager.isTracked(block)) {
            return;
        }

        // Check if the block below is air (block will fall)
        Block below = block.getRelative(0, -1, 0);
        if (!below.getType().isAir() && below.getType() != Material.WATER && below.getType() != Material.LAVA) {
            // Block won't fall — has support
            return;
        }

        // Physics disabled here
        if (!guard.physicsAllowed(block.getLocation())) {
            return;
        }

        // Block is about to start falling — treat as a removal and cascade.
        // Schedule 1 tick later to let vanilla physics happen first. Route through
        // the SAME processBlockRemoval path every sibling event (burn, fade, enderman)
        // uses, so whatever rested on this gravity block floats/re-solves through the
        // budgeted, instrumented cascade — not just a silent graph.removeBlock that
        // strands its dependents.
        World world = block.getWorld();
        NodePos pos = StructureManager.toBlockPos(block.getLocation());
        plugin.getServer()
                .getScheduler()
                .runTaskLater(
                        plugin,
                        () -> {
                            // Verify the block actually fell (is now air)
                            if (block.getType().isAir()) {
                                StructureGraph graph = structureManager.getGraph(world);
                                if (graph != null && graph.hasBlock(pos)) {
                                    processBlockRemoval(block, "gravity");
                                }
                            }
                        },
                        1L);
    }

    /**
     * Check if this material is affected by gravity (falls when unsupported).
     */
    private boolean isGravityBlock(Material type) {
        return type == Material.SAND
                || type == Material.RED_SAND
                || type == Material.GRAVEL
                || type == Material.ANVIL
                || type == Material.CHIPPED_ANVIL
                || type == Material.DAMAGED_ANVIL
                || type.name().endsWith("_CONCRETE_POWDER")
                || type == Material.DRAGON_EGG
                || type.name().contains("SCAFFOLDING")
                || type == Material.POINTED_DRIPSTONE;
    }

    /**
     * Tree/mushroom growth adds blocks. Register the new blocks and check for overload.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        World world = event.getWorld();
        StructureGraph graph = structureManager.getGraph(world);

        // Fast path: no structure in this world
        if (graph == null || graph.isEmpty()) {
            return;
        }

        // Check if any of the new blocks are near tracked blocks
        // (growth only matters if it interacts with a structure)
        List<BlockState> relevantBlocks = new ArrayList<>();
        for (BlockState state : event.getBlocks()) {
            Location loc = state.getLocation();

            // Physics disabled here
            if (!guard.physicsAllowed(loc)) {
                continue;
            }

            // Check if this position or any adjacent position is tracked
            NodePos pos = StructureManager.toBlockPos(loc);
            if (graph.hasBlock(pos) || hasTrackedNeighbor(graph, pos)) {
                relevantBlocks.add(state);
            }
        }

        if (relevantBlocks.isEmpty()) {
            return;
        }

        // Register the new blocks and check for overload
        for (BlockState state : relevantBlocks) {
            // Only solid blocks are structural
            if (!state.getType().isSolid()) {
                continue;
            }

            Block block = state.getBlock();

            // Register the block at this position
            structureManager.onBlockPlaced(block, cascades.immediateCollapseCallback(world, state.getLocation()));
        }

        structureManager.markDirty(world);
    }

    /**
     * Entity changes a block:
     * - Enderman picks up a block → treat as removal
     * - Enderman places a block → treat as placement
     * - FallingBlock lands → treat as placement (vanilla sand/gravel)
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Block block = event.getBlock();
        Material newType = event.getTo();
        World world = block.getWorld();

        // Physics disabled here
        if (!guard.physicsAllowed(block.getLocation())) {
            return;
        }

        // Case 1: Block is being removed (replaced with AIR)
        if (newType.isAir()) {
            // Only care if the block is tracked
            if (!structureManager.isTracked(block)) {
                return;
            }

            String cause = event.getEntity() instanceof Enderman ? "enderman" : "entity";
            processBlockRemoval(block, cause);
            return;
        }

        // Case 2: Block is being placed (FallingBlock landing, Enderman placing)
        if (newType.isSolid()) {
            // FallingBlock landing or Enderman placing
            // We need to register this block if it's near a tracked structure

            StructureGraph graph = structureManager.getGraph(world);
            if (graph == null || graph.isEmpty()) {
                return;
            }

            NodePos pos = StructureManager.toBlockPos(block.getLocation());

            // Only register if near an existing structure
            if (!graph.hasBlock(pos) && !hasTrackedNeighbor(graph, pos)) {
                return;
            }

            // Schedule registration 1 tick later so the block is actually placed
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> registerPlacedBlock(block, world), 1L);
        }
    }

    /**
     * Check if any of the 6 adjacent positions has a tracked block.
     */
    private boolean hasTrackedNeighbor(StructureGraph graph, NodePos pos) {
        return graph.hasBlock(new NodePos(pos.x() + 1, pos.y(), pos.z()))
                || graph.hasBlock(new NodePos(pos.x() - 1, pos.y(), pos.z()))
                || graph.hasBlock(new NodePos(pos.x(), pos.y() + 1, pos.z()))
                || graph.hasBlock(new NodePos(pos.x(), pos.y() - 1, pos.z()))
                || graph.hasBlock(new NodePos(pos.x(), pos.y(), pos.z() + 1))
                || graph.hasBlock(new NodePos(pos.x(), pos.y(), pos.z() - 1));
    }

    /**
     * Register a newly placed block and check for overload.
     */
    private void registerPlacedBlock(Block block, World world) {
        if (!block.getType().isSolid()) {
            return;
        }

        structureManager.onBlockPlaced(block, cascades.immediateCollapseCallback(world, block.getLocation()));

        structureManager.markDirty(world);
    }

    /**
     * Process a block removal: run cascade and handle collapses.
     */
    private void processBlockRemoval(Block block, String cause) {
        World world = block.getWorld();
        Location breakLocation = block.getLocation();

        List<CollapsedNode> floatingCollapsed = new ArrayList<>();
        List<CollapsedNode> overloadedCollapsed = new ArrayList<>();
        Map<NodePos, Material> materialCache = new HashMap<>();

        CascadeResult result = structureManager.onBlockBroken(
                block,
                cascades.binningCallback(world, floatingCollapsed, overloadedCollapsed, materialCache, true, null));

        // Process floating blocks immediately
        int floatingRemoved =
                cascades.removeFloatingImmediately(world, floatingCollapsed).size();

        if (floatingRemoved > 0) {
            effects.playCascadeComplete(world, breakLocation, floatingRemoved);
        }

        // Schedule overloaded blocks for delayed collapse
        cascades.scheduleOverloaded(world, breakLocation, overloadedCollapsed, materialCache);

        // Handle truncated cascades
        cascades.resumeIfTruncated(world, result);

        structureManager.markDirty(world);
    }
}
