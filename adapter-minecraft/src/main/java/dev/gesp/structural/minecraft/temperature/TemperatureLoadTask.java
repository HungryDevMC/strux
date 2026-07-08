package dev.gesp.structural.minecraft.temperature;

import dev.gesp.structural.api.CollapseReason;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.cache.RevisionCachedNodeView;
import dev.gesp.structural.minecraft.fire.FireScorchTask;
import dev.gesp.structural.minecraft.listener.BudgetedTask;
import dev.gesp.structural.minecraft.listener.CascadeResumeManager;
import dev.gesp.structural.minecraft.listener.DelayedCollapseManager;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.perf.TaskTimings;
import dev.gesp.structural.minecraft.protect.CollapseGuard;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import dev.gesp.structural.thermal.ThermalStrength;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

/**
 * Temperature-based strength: a tracked block near lava/fire loses load capacity
 * (it is hot and weaker), and a block heated then suddenly cooled cracks (thermal
 * shock). The world-driven half of the core temperature model — the curves and
 * the shock formula live in {@code ThermalStrength}; this task only reads each
 * block's temperature from the world and feeds it in.
 *
 * <pre>
 *   SOFTENING (transient): each scan, set the block's temperatureCapacityFactor
 *   from its current °C. A steel beam beside lava carries ~half its cool load.
 *
 *   THERMAL SHOCK (persistent): track each block's recent PEAK °C; when it drops
 *   suddenly (water/rain hits it, lava removed), apply a one-shot Node.damage
 *   scaled by the material's brittleness — heat a stone wall, douse it, it
 *   shatters.
 * </pre>
 *
 * <p><b>Budgeted weak-set fast path</b> (mirrors WeatherLoadTask / EntityWeightTask):
 * only STRESSED nodes are even considered, cached per world and rebuilt on a
 * revision bump. A healthy temperate world does ZERO per-block work.
 *
 * <p><b>Rule A — no double effect with fire.</b> A block that fire is ALREADY
 * actively damaging (it sits on/next to a tracked flame) is skipped for thermal
 * softening: fire owns the heat-weakening of those blocks via persistent char
 * damage, so we must not also soften their capacity. Ambient/radiant softening
 * is for blocks merely NEAR heat, not on fire. (Weather stays the moisture axis;
 * temperature never touches moisture.)
 */
public class TemperatureLoadTask extends BudgetedTask {

    private final Plugin plugin;
    private final StructureManager structureManager;
    private final PhysicsConfig physicsConfig;
    private final DelayedCollapseManager delayedCollapseManager;
    private final CascadeResumeManager cascadeResumeManager;
    private final CollapseGuard guard;
    private final TemperatureProvider provider;
    private final FireScorchTask fireScorchTask;
    private final TaskTimings taskTimings;

    private final int scanIntervalTicks;
    private final int scanRadius;
    private final boolean enabled;

    /** Only stressed nodes can be tipped over by softening — skip the rest. */
    private final RevisionCachedNodeView stressedView;

    /** Recent peak temperature (°C) per tracked block, per world — the shock baseline. */
    private final Map<UUID, Map<NodePos, Double>> peakTemp = new HashMap<>();

    /**
     * Nodes whose capacity we currently hold softened (factor &lt; 1.0), per world.
     * The pass only visits the CURRENT stressed set, so a node that cooled off OR
     * dropped out of that set would otherwise keep its softened factor forever
     * (capacity stuck at e.g. half). Each pass we restore any softened node that was
     * not re-softened this pass, then this set is exactly the still-softened nodes.
     */
    private final Map<UUID, Set<NodePos>> softenedNodes = new HashMap<>();

    public TemperatureLoadTask(
            Plugin plugin,
            StructureManager structureManager,
            PhysicsConfig physicsConfig,
            DelayedCollapseManager delayedCollapseManager,
            CascadeResumeManager cascadeResumeManager,
            CollapseGuard guard,
            TemperatureProvider provider,
            FireScorchTask fireScorchTask,
            int scanIntervalTicks,
            int scanRadius,
            boolean enabled,
            double tickBudgetMs,
            TaskTimings taskTimings) {
        this.plugin = plugin;
        this.structureManager = structureManager;
        this.physicsConfig = physicsConfig;
        this.delayedCollapseManager = delayedCollapseManager;
        this.cascadeResumeManager = cascadeResumeManager;
        this.guard = guard;
        this.provider = provider;
        this.fireScorchTask = fireScorchTask;
        this.scanIntervalTicks = Math.max(1, scanIntervalTicks);
        this.scanRadius = Math.max(1, scanRadius);
        this.enabled = enabled;
        this.taskTimings = taskTimings;
        setTickBudgetMs(tickBudgetMs);
        this.stressedView = new RevisionCachedNodeView(
                structureManager, node -> !node.isGrounded() && node.distress() >= DISTRESS_THRESHOLD);
    }

    /**
     * A block must be at least this distressed — working hard under load OR already
     * cracked — to be worth heating/cooling. A pristine, lightly-loaded block has
     * so much headroom that even full thermal softening can't tip it, so skipping
     * it keeps a healthy world at zero work (the same weak-set fast path the
     * entity/weather tasks use).
     */
    private static final double DISTRESS_THRESHOLD = 0.3;

    /** Begin the periodic temperature scan (no-op if disabled). */
    public void start() {
        if (enabled && physicsConfig.isTemperatureStrengthEnabled()) {
            runTaskTimer(plugin, scanIntervalTicks, scanIntervalTicks);
        }
    }

    /** Stop the scan. */
    public void stop() {
        try {
            cancel();
        } catch (IllegalStateException ignored) {
            // never started or already cancelled
        }
        peakTemp.clear();
        softenedNodes.clear();
    }

    @Override
    public void run() {
        if (!enabled || !physicsConfig.isTemperatureStrengthEnabled()) {
            return;
        }
        long start = System.nanoTime();
        long deadline = start + tickBudgetNanos();
        int blocksVisited = 0;
        for (World world : plugin.getServer().getWorlds()) {
            StructureGraph graph = structureManager.getGraph(world);
            if (graph == null || graph.isEmpty()) {
                continue;
            }
            blocksVisited += processWorld(world, graph, deadline);
            if (System.nanoTime() >= deadline) {
                break; // remaining worlds resume next scan
            }
        }
        taskTimings.record(TaskTimings.TEMPERATURE_LOAD, System.nanoTime() - start, blocksVisited);
    }

    /** @return how many blocks this world's scan examined (the pass's work count). */
    private int processWorld(World world, StructureGraph graph, long deadline) {
        // FAST PATH: no stressed node means nothing temperature could tip over.
        Set<NodePos> stressed = stressedView.nodes(world, graph);
        if (stressed.isEmpty()) {
            return 0;
        }
        Map<NodePos, Double> peaks = peakTemp.computeIfAbsent(world.getUID(), k -> new HashMap<>());
        Set<NodePos> softened = softenedNodes.computeIfAbsent(world.getUID(), k -> new HashSet<>());

        // A node that DROPPED OUT of the stressed set is never visited below, so its
        // softened capacity would stick forever. Restore (factor → 1.0) every softened
        // node no longer stressed, before this pass re-softens whoever is still hot.
        boolean changed = false;
        Iterator<NodePos> softenedIt = softened.iterator();
        while (softenedIt.hasNext()) {
            NodePos pos = softenedIt.next();
            if (stressed.contains(pos)) {
                continue; // still in the set — the loop below will refresh its factor
            }
            Node node = graph.getNode(pos);
            if (node != null && node.temperatureCapacityFactor() != 1.0) {
                node.setTemperatureCapacityFactor(1.0);
                changed = true;
            }
            softenedIt.remove();
        }

        // Prune peak-temperature entries for positions no longer stressed (cooled off, or
        // gone from the graph). Without this the per-world map grows unboundedly until
        // plugin disable, and a node that LEFT the set while hot then re-enters later would
        // shock off its stale, long-ago peak (a phantom cooling event).
        peaks.keySet().removeIf(pos -> !stressed.contains(pos));

        int visited = 0;
        List<NodePos> overloaded = new ArrayList<>();
        for (NodePos pos : stressed) {
            if (System.nanoTime() >= deadline) {
                break; // budget spent — the rest waits for next scan
            }
            Node node = graph.getNode(pos);
            if (node == null || node.isGrounded()) {
                continue;
            }
            Location loc = StructureManager.toLocation(pos, world);
            if (!guard.physicsAllowed(loc)) {
                continue;
            }
            visited++;

            double tempC = provider.temperatureAt(world, pos, scanRadius);

            // Thermal shock first: it reads the peak BEFORE this scan's update, so a
            // cooling from the old peak is detected even as we record the new temp.
            if (applyShock(graph, node, peaks, pos, tempC)) {
                changed = true;
            }

            // Rule A: a block fire is already actively damaging is NOT also softened.
            boolean fireActive = fireScorchTask != null && fireScorchTask.isFireActiveAt(world, pos);
            double target = fireActive ? 1.0 : ThermalStrength.capacityFactor(node.spec(), tempC);
            // Heat (and cooling) is GRADUAL: ease the factor a fixed fraction toward its target
            // each scan instead of snapping. A block heats through / cools off over several
            // scans, giving a readable build-up players can react to rather than an instant flip.
            double eased = easeToward(node.temperatureCapacityFactor(), target);
            if (eased != node.temperatureCapacityFactor()) {
                node.setTemperatureCapacityFactor(eased);
                changed = true;
            }
            // Track who is currently softened so a node that later leaves the stressed
            // set (or cools off) gets its capacity restored instead of stuck softened.
            if (eased < 1.0) {
                softened.add(pos);
            } else {
                softened.remove(pos);
            }

            if (node.stressPercent() > 1.0 || node.isDestroyed()) {
                overloaded.add(pos);
            }
        }

        if (!overloaded.isEmpty()) {
            settleAndSchedule(world, graph, overloaded);
        }
        if (changed) {
            structureManager.markDirty(world);
        }
        return visited;
    }

    /** Fraction of the gap to its target a block's softening factor closes each scan. */
    private static final double EASE_FRACTION = 0.4;

    /** When the eased value is within this of the target, snap to it (so it actually arrives). */
    private static final double EASE_SNAP = 0.01;

    /**
     * Move {@code current} a fixed fraction of the way toward {@code target}, snapping when
     * very close. This makes thermal softening (and recovery) GRADUAL across scans instead of a
     * one-scan step — heat builds up and cools off, which the rest of the game can telegraph and
     * contest. (Thermal shock is unaffected: it reads the raw temperature, not this factor.)
     */
    static double easeToward(double current, double target) {
        double next = current + (target - current) * EASE_FRACTION;
        return Math.abs(target - next) < EASE_SNAP ? target : next;
    }

    /**
     * Track this block's peak temperature and, on a sudden drop past the
     * configured threshold, apply a one-shot persistent shock damage. Records the
     * new temperature as the peak when heating; decays the peak toward the current
     * temperature once the shock has been spent so a single cooling event cracks
     * once, not every scan.
     *
     * @return whether shock damage was applied
     */
    private boolean applyShock(StructureGraph graph, Node node, Map<NodePos, Double> peaks, NodePos pos, double tempC) {
        double peak = peaks.getOrDefault(pos, tempC);
        boolean cracked = false;
        if (tempC < peak) {
            double deltaT = peak - tempC;
            double dmg = ThermalStrength.shockDamage(
                    node.spec(), deltaT, physicsConfig.getThermalShockOnsetC(), physicsConfig.getThermalShockSpanC());
            if (dmg > 0.0) {
                graph.applyDamage(pos, dmg); // via graph so modCount moves (async settle conflict check)
                cracked = true;
            }
            // Spend the shock: the new baseline is the cooled temperature.
            peaks.put(pos, tempC);
        } else {
            // Heating (or unchanged): raise the peak.
            peaks.put(pos, tempC);
        }
        return cracked;
    }

    /** Re-solve the weakened structure, collapsing whatever can no longer stand. */
    private void settleAndSchedule(World world, StructureGraph graph, List<NodePos> seeds) {
        List<NodePos> collapsed = new ArrayList<>();
        for (NodePos seed : seeds) {
            var result = new CascadeEngine(physicsConfig).cascade(graph, seed, collector(collapsed));
            // Resume a collapse cut short by the per-event step cap so the remainder
            // settles over the next ticks rather than stranding floating blocks.
            if (result.truncated()) {
                cascadeResumeManager.enqueue(world, result.remainingScope());
            }
        }
        if (collapsed.isEmpty()) {
            return;
        }
        Location origin = StructureManager.toLocation(collapsed.get(0), world);
        int batchId = delayedCollapseManager.startBatch(world, origin, true);
        for (NodePos pos : collapsed) {
            Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
            Material material = block.getType();
            delayedCollapseManager.scheduleCollapse(world, pos, material, batchId);
        }
        structureManager.markDirty(world);
    }

    private static SolverCallback collector(List<NodePos> collapsed) {
        return new SolverCallback() {
            @Override
            public void onStressUpdated(Map<NodePos, Double> stressMap) {}

            @Override
            public void onCascadeStep(CollapsedNode node, int stepNumber, CollapseReason reason) {
                collapsed.add(node.pos());
            }

            @Override
            public void onCascadeComplete(List<CollapsedNode> allCollapsed) {}
        };
    }
}
