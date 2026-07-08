package dev.gesp.structural.minecraft.listener;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.protect.CollapseGuard;
import dev.gesp.structural.minecraft.visual.CollapseEffects;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.Node;
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
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Handles piston movements and triggers structural recalculation.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                    PISTON LISTENER                                  │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  When a piston extends or retracts:                                │
 *   │                                                                     │
 *   │  1. Calculate which blocks will move and where                     │
 *   │  2. For each moved block:                                          │
 *   │     • Treat as REMOVAL from old position → run cascade             │
 *   │     • Treat as PLACEMENT at new position → check overload          │
 *   │                                                                     │
 *   │                                                                     │
 *   │  PISTON EXTEND:                  PISTON RETRACT (STICKY):          │
 *   │                                                                     │
 *   │       [C]                               [C]                        │
 *   │        │                                 │                         │
 *   │       [B] ──▶ pushed                   [B] ◀── pulled              │
 *   │        │                                 │                         │
 *   │    [▶ PISTON]                       [◀ PISTON]                     │
 *   │                                                                     │
 *   │  If B was supporting C:             If B lands on weak floor:      │
 *   │  → C becomes floating → collapses   → floor overloads → collapses  │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class PistonListener implements Listener {

    private final Plugin plugin;
    private final StructureManager structureManager;
    private final CollapseEffects effects;
    private final CollapseGuard guard;
    private final CascadeResultHandler cascades;

    public PistonListener(
            Plugin plugin,
            StructureManager structureManager,
            DelayedCollapseManager delayedCollapseManager,
            CascadeResumeManager cascadeResumeManager,
            CollapseEffects effects,
            CollapseGuard guard) {
        this.plugin = plugin;
        this.structureManager = structureManager;
        this.effects = effects;
        this.guard = guard;
        this.cascades = new CascadeResultHandler(
                structureManager, delayedCollapseManager, cascadeResumeManager, guard, effects);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        List<Block> movedBlocks = event.getBlocks();
        if (movedBlocks.isEmpty()) {
            return;
        }

        World world = event.getBlock().getWorld();
        BlockFace direction = event.getDirection();

        // Check if any moved block is tracked
        boolean hasTracked = false;
        for (Block block : movedBlocks) {
            if (structureManager.isTracked(block)) {
                hasTracked = true;
                break;
            }
        }
        if (!hasTracked) {
            return;
        }

        // Gate: physics disabled in this region/world
        if (!guard.physicsAllowed(event.getBlock().getLocation())) {
            return;
        }

        processPistonMove(world, movedBlocks, direction);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        List<Block> movedBlocks = event.getBlocks();
        if (movedBlocks.isEmpty()) {
            return;
        }

        World world = event.getBlock().getWorld();
        BlockFace direction = event.getDirection();

        // Check if any moved block is tracked
        boolean hasTracked = false;
        for (Block block : movedBlocks) {
            if (structureManager.isTracked(block)) {
                hasTracked = true;
                break;
            }
        }
        if (!hasTracked) {
            return;
        }

        // Gate: physics disabled in this region/world
        if (!guard.physicsAllowed(event.getBlock().getLocation())) {
            return;
        }

        processPistonMove(world, movedBlocks, direction);
    }

    /**
     * Process a piston movement: blocks leave old positions and enter new positions.
     * This is scheduled 1 tick later so the world state reflects the actual move.
     */
    private void processPistonMove(World world, List<Block> movedBlocks, BlockFace direction) {
        // Capture block info before the move (old positions, materials)
        List<PistonMovement> movements = new ArrayList<>();
        for (Block block : movedBlocks) {
            if (!structureManager.isTracked(block)) {
                continue;
            }
            NodePos oldPos = StructureManager.toBlockPos(block.getLocation());
            NodePos newPos = new NodePos(
                    oldPos.x() + direction.getModX(),
                    oldPos.y() + direction.getModY(),
                    oldPos.z() + direction.getModZ());
            Material material = block.getType();
            movements.add(new PistonMovement(oldPos, newPos, material));
        }

        if (movements.isEmpty()) {
            return;
        }

        // Schedule processing 1 tick later so the world state is updated
        new BukkitRunnable() {
            @Override
            public void run() {
                processMovementsDelayed(world, movements);
            }
        }.runTaskLater(plugin, 1L);
    }

    /**
     * Process the structural implications of piston movements after blocks have moved.
     */
    private void processMovementsDelayed(World world, List<PistonMovement> movements) {
        StructureGraph graph = structureManager.getGraph(world);
        if (graph == null) {
            return;
        }

        // Phase 1: Remove blocks from old positions and run cascade
        // Process in reverse order so blocks further from piston are removed first
        // (they're listed from piston outward in the event)
        List<CollapsedNode> floatingCollapsed = new ArrayList<>();
        List<CollapsedNode> overloadedCollapsed = new ArrayList<>();
        Map<NodePos, Material> materialCache = new HashMap<>();

        for (int i = movements.size() - 1; i >= 0; i--) {
            PistonMovement movement = movements.get(i);
            NodePos oldPos = movement.oldPos();

            // The node should have been at oldPos but may already be gone
            // (e.g., if a previous cascade removed it)
            Node node = graph.getNode(oldPos);
            if (node == null) {
                continue;
            }

            // Route the removal through StructureManager's break path so the piston
            // cascade respects the settle budget (anti-freeze cap), perf sampling and
            // shared StruxMetrics — exactly like a block break. The cascade OWNS the
            // trigger removal: we must NOT pre-remove the node, or the seed subgraph
            // would be empty and the dependent column would never cascade. The world
            // block already moved away (now air at oldPos), so we skip TRIGGER steps.
            Block oldBlock = world.getBlockAt(oldPos.x(), oldPos.y(), oldPos.z());
            CascadeResult result = structureManager.onBlockBroken(
                    oldBlock,
                    cascades.binningCallback(world, floatingCollapsed, overloadedCollapsed, materialCache, true, null));

            cascades.resumeIfTruncated(world, result);
        }

        // Process floating collapses immediately
        cascades.removeFloatingImmediately(world, floatingCollapsed);

        // Schedule overloaded blocks for delayed collapse, keyed at the first overloaded block
        Location overloadedOrigin = overloadedCollapsed.isEmpty()
                ? null
                : StructureManager.toLocation(overloadedCollapsed.get(0).pos(), world);
        cascades.scheduleOverloaded(world, overloadedOrigin, overloadedCollapsed, materialCache);

        // Phase 2: Register blocks at new positions
        for (PistonMovement movement : movements) {
            NodePos newPos = movement.newPos();
            Block newBlock = world.getBlockAt(newPos.x(), newPos.y(), newPos.z());

            // Only register if the block is actually there now
            if (newBlock.getType().isAir() || !newBlock.getType().isSolid()) {
                continue;
            }

            // Check if already tracked (shouldn't be, but defensive)
            if (graph.getNode(newPos) != null) {
                continue;
            }

            // Register the block at new position - this triggers overload check
            structureManager.onBlockPlaced(newBlock, cascades.immediateCollapseCallback(world, null));
        }

        // Cascade effects
        int totalCollapsed = floatingCollapsed.size() + overloadedCollapsed.size();
        if (totalCollapsed > 0) {
            Location effectLoc = StructureManager.toLocation(movements.get(0).oldPos(), world);
            effects.playCascadeComplete(world, effectLoc, totalCollapsed);
        }

        structureManager.markDirty(world);
    }

    /** Record of a block movement: old position, new position, and material. */
    private record PistonMovement(NodePos oldPos, NodePos newPos, Material material) {}
}
