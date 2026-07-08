package dev.gesp.structural.minecraft.container;

import dev.gesp.structural.api.CollapseReason;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.cache.RevisionCachedNodeView;
import dev.gesp.structural.minecraft.listener.CascadeResumeManager;
import dev.gesp.structural.minecraft.listener.DelayedCollapseManager;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.perf.TaskTimings;
import dev.gesp.structural.minecraft.protect.CollapseGuard;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Adds the weight of a full storage container to the block it rests on.
 *
 * <pre>
 *   CONTAINER WEIGHT:
 *   ─────────────────
 *   A chest, barrel, or shulker box full of loot is heavy. The block it sits on
 *   carries that weight as TEMPORARY load — exactly like an entity standing on a
 *   floor. If (currentStress + containerLoad) > effectiveMaxLoad → collapse.
 *
 *   containerLoad = baseMass + fillFraction × contentWeight
 *   where fillFraction = filledSlots / totalSlots (an empty chest ≈ baseMass,
 *   a packed double chest ≈ baseMass + contentWeight).
 *
 *   Only blocks above stress/damage thresholds are checked — the weak set is the
 *   SAME revision-cached set EntityWeightTask uses, so a healthy world does ZERO
 *   container scanning (no block lookups, no getState() calls). The cost of a pass
 *   is bounded by the number of weak nodes, not by the number of containers in the
 *   world: we look at the block directly ABOVE each weak node and only then ask
 *   whether it is a container.
 * </pre>
 */
public class ContainerWeightTask extends BukkitRunnable {

    private final Plugin plugin;
    private final StructureManager structureManager;
    private final PhysicsConfig physicsConfig;
    private final DelayedCollapseManager delayedCollapseManager;
    private final CascadeResumeManager cascadeResumeManager;
    private final CollapseGuard guard;
    private final ContainerWeightConfig config;
    private final TaskTimings taskTimings;

    // FAST PATH: a node only becomes "weak" when the structure changes, so the set
    // of weak nodes is cached per world and rebuilt only on a revision bump — the
    // same signal EntityWeightTask freezes on. When the set is empty there is
    // nothing a container could overload, so the whole scan is skipped: a healthy
    // world does ZERO per-container work per pass. The freeze-cache correctness
    // argument lives once on RevisionCachedNodeView.
    private final RevisionCachedNodeView weakView;

    // Weak support nodes examined in the most recent pass. Observable so a healthy
    // world can be asserted to do zero container work.
    private long lastPassContainerWork;

    public ContainerWeightTask(
            Plugin plugin,
            StructureManager structureManager,
            PhysicsConfig physicsConfig,
            DelayedCollapseManager delayedCollapseManager,
            CascadeResumeManager cascadeResumeManager,
            CollapseGuard guard,
            ContainerWeightConfig config,
            TaskTimings taskTimings) {
        this.plugin = plugin;
        this.structureManager = structureManager;
        this.physicsConfig = physicsConfig;
        this.delayedCollapseManager = delayedCollapseManager;
        this.cascadeResumeManager = cascadeResumeManager;
        this.guard = guard;
        this.config = config;
        this.taskTimings = taskTimings;
        this.weakView = new RevisionCachedNodeView(structureManager, node -> !node.isGrounded() && isWeak(node));
    }

    /** Begin the periodic container-weight scan (no-op if disabled). */
    public void start() {
        if (config.enabled()) {
            runTaskTimer(plugin, config.scanIntervalTicks(), config.scanIntervalTicks());
        }
    }

    /** Stop the periodic scan. */
    public void stop() {
        try {
            cancel();
        } catch (IllegalStateException ignored) {
            // never started or already cancelled
        }
    }

    @Override
    public void run() {
        lastPassContainerWork = 0;
        if (!config.enabled()) {
            return;
        }

        long start = System.nanoTime();
        int checked = 0;
        for (World world : plugin.getServer().getWorlds()) {
            StructureGraph graph = structureManager.getGraph(world);
            if (graph == null || graph.isEmpty()) {
                continue;
            }
            checked += processWorld(world, graph);
        }
        taskTimings.record(TaskTimings.CONTAINER_WEIGHT, System.nanoTime() - start, checked);
    }

    /** @return how many weak support nodes this world's scan examined (the pass's work count). */
    private int processWorld(World world, StructureGraph graph) {
        // FAST PATH: with no weak nodes there is nothing a container could overload,
        // so skip the scan entirely — zero per-container work this pass. A healthy
        // structure never even looks at the block above a node.
        Set<NodePos> weak = weakView.nodes(world, graph);
        if (weak.isEmpty()) {
            return 0;
        }

        int checked = 0;
        for (NodePos pos : weak) {
            checked++;
            lastPassContainerWork++;

            Node node = graph.getNode(pos);
            if (node == null || node.isGrounded()) {
                continue;
            }

            // A weak node in an unloaded chunk (persistence-loaded structure far from any
            // player) can't have its container change — skip it instead of force-loading the
            // chunk via getBlockAt, which would churn load/unload every scan.
            if (!world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
                continue;
            }

            // The container rests on the block directly ABOVE this weak support node.
            Block above = world.getBlockAt(pos.x(), pos.y() + 1, pos.z());
            BlockState state = above.getState();
            if (!(state instanceof Container container)) {
                continue;
            }

            // Check protection on the support block.
            Location supportLoc = world.getBlockAt(pos.x(), pos.y(), pos.z()).getLocation();
            if (!guard.physicsAllowed(supportLoc)) {
                continue;
            }

            double load = containerLoad(container);
            double combinedStress = node.stressValue() + load;
            if (combinedStress > node.effectiveMaxLoad()) {
                triggerCollapse(world, graph, pos);
            }
        }
        return checked;
    }

    /** Weak support nodes examined in the most recent pass (0 when skipped). */
    public long lastPassContainerWork() {
        return lastPassContainerWork;
    }

    /**
     * Transient load a container adds to its support block:
     * {@code baseMass + fillFraction × contentWeight}. An empty container is just
     * its own (light) mass; a packed one adds the full content weight.
     */
    private double containerLoad(Container container) {
        Inventory inventory = container.getInventory();
        int size = inventory.getSize();
        if (size <= 0) {
            return config.baseMass();
        }
        int filled = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && !item.getType().isAir()) {
                filled++;
            }
        }
        double fillFraction = (double) filled / size;
        return config.baseMass() + fillFraction * config.contentWeight();
    }

    /**
     * Whether a node is "weak" enough to be affected by container weight.
     * Mirrors EntityWeightTask's thresholds so the two tasks share one weak set.
     */
    private boolean isWeak(Node node) {
        return node.stressPercent() >= config.stressThreshold() || node.damage() >= config.damageThreshold();
    }

    /** Trigger a collapse starting from the given position. */
    protected void triggerCollapse(World world, StructureGraph graph, NodePos pos) {
        List<NodePos> collapsed = new ArrayList<>();
        var result = new CascadeEngine(physicsConfig).cascade(graph, pos, new SolverCallback() {
            @Override
            public void onStressUpdated(Map<NodePos, Double> stressMap) {}

            @Override
            public void onCascadeStep(CollapsedNode node, int stepNumber, CollapseReason reason) {
                collapsed.add(node.pos());
            }

            @Override
            public void onCascadeComplete(List<CollapsedNode> allCollapsed) {}
        });

        // Resume a collapse cut short by the per-event step cap so the remainder
        // finishes over the next ticks rather than stranding floating blocks.
        if (result.truncated()) {
            cascadeResumeManager.enqueue(world, result.remainingScope());
        }

        if (collapsed.isEmpty()) {
            return;
        }

        Location origin = StructureManager.toLocation(collapsed.get(0), world);
        int batchId = delayedCollapseManager.startBatch(world, origin, true);
        for (NodePos collapsePos : collapsed) {
            Block block = world.getBlockAt(collapsePos.x(), collapsePos.y(), collapsePos.z());
            Material material = block.getType();
            delayedCollapseManager.scheduleCollapse(world, collapsePos, material, batchId);
        }
        structureManager.markDirty(world);
    }

    /** Configuration record for container weight settings. */
    public record ContainerWeightConfig(
            boolean enabled,
            int scanIntervalTicks,
            double baseMass,
            double contentWeight,
            double stressThreshold,
            double damageThreshold) {

        public static ContainerWeightConfig defaults() {
            return new ContainerWeightConfig(
                    false, // enabled — disabled until integrated into stress solver
                    20, // scanIntervalTicks (once a second — storage moves slowly)
                    1.0, // baseMass (the empty container itself)
                    8.0, // contentWeight (a packed double chest of loot)
                    0.7, // stressThreshold (70%)
                    0.5); // damageThreshold (50%)
        }
    }
}
