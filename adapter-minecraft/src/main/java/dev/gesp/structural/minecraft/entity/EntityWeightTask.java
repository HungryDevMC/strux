package dev.gesp.structural.minecraft.entity;

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
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Adds entity weight to structural calculations: standing weight (periodic) and
 * fall impact (event-driven).
 *
 * <pre>
 *   STANDING WEIGHT:
 *   ────────────────
 *   An entity standing on a stressed/damaged block adds temporary load. If
 *   (currentStress + entityMass) > effectiveMaxLoad → collapse.
 *
 *   FALL IMPACT:
 *   ────────────
 *   Entity lands from height → kinetic energy spike: E = ½mv²
 *   where v = √(2 × g × fallDistance). Higher falls = quadratically more impact.
 *   A jump from height onto a cracked floor can trigger collapse; walking = safe.
 *
 *   Only blocks above stress/damage thresholds are checked — healthy blocks
 *   ignore entity weight entirely, so normal gameplay isn't affected.
 * </pre>
 */
public class EntityWeightTask extends BukkitRunnable implements Listener {

    /** Minecraft gravity in blocks/second² (approximate). */
    private static final double GRAVITY = 20.0;

    private final Plugin plugin;
    private final StructureManager structureManager;
    private final EntityMassRegistry massRegistry;
    private final PhysicsConfig physicsConfig;
    private final DelayedCollapseManager delayedCollapseManager;
    private final CascadeResumeManager cascadeResumeManager;
    private final CollapseGuard guard;
    private final EntityWeightConfig config;
    private final TaskTimings taskTimings;

    // FREEZE + FAST PATH: a node only becomes "weak" (over the stress/damage
    // threshold) when the structure changes, so the set of weak nodes is cached
    // per world and rebuilt only when that world's revision bumps — the same
    // revision signal StressVisualizer freezes on. When the set is empty there is
    // nothing an entity could overload, so the whole entity scan is skipped: a
    // healthy world does ZERO per-entity work per pass. The freeze-cache
    // correctness argument lives once on RevisionCachedNodeView.
    private final RevisionCachedNodeView weakView;

    // Number of entities examined in the most recent standing-weight pass.
    // Observable so a healthy world can be asserted to do zero entity work.
    private long lastPassEntityWork;

    public EntityWeightTask(
            Plugin plugin,
            StructureManager structureManager,
            EntityMassRegistry massRegistry,
            PhysicsConfig physicsConfig,
            DelayedCollapseManager delayedCollapseManager,
            CascadeResumeManager cascadeResumeManager,
            CollapseGuard guard,
            EntityWeightConfig config,
            TaskTimings taskTimings) {
        this.plugin = plugin;
        this.structureManager = structureManager;
        this.massRegistry = massRegistry;
        this.physicsConfig = physicsConfig;
        this.delayedCollapseManager = delayedCollapseManager;
        this.cascadeResumeManager = cascadeResumeManager;
        this.guard = guard;
        this.config = config;
        this.taskTimings = taskTimings;
        this.weakView = new RevisionCachedNodeView(structureManager, node -> !node.isGrounded() && isWeak(node));
    }

    /** Begin the periodic weight scan (no-op if disabled). */
    public void start() {
        if (config.enabled() && config.standingEnabled()) {
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
        lastPassEntityWork = 0;
        if (!config.enabled() || !config.standingEnabled()) {
            return;
        }

        // Perf: time the whole standing-weight scan and count the entities checked —
        // a busy mob farm over a weak floor is "slow because many entities", a single
        // boss over a fragile keep is "slow per unit".
        long start = System.nanoTime();
        int entitiesChecked = 0;
        for (World world : plugin.getServer().getWorlds()) {
            StructureGraph graph = structureManager.getGraph(world);
            if (graph == null || graph.isEmpty()) {
                continue;
            }
            entitiesChecked += processWorldEntities(world, graph);
        }
        taskTimings.record(TaskTimings.ENTITY_WEIGHT, System.nanoTime() - start, entitiesChecked);
    }

    /** @return how many entities this world's scan examined (the pass's work count). */
    private int processWorldEntities(World world, StructureGraph graph) {
        // FAST PATH: with no weak nodes there is nothing an entity could overload,
        // so skip the entity scan entirely — zero per-entity work this pass. This is
        // the win: a healthy structure records zero entity work (TaskTimings) and
        // never even asks the world for its entities.
        Set<NodePos> weak = weakView.nodes(world, graph);
        if (weak.isEmpty()) {
            return 0;
        }

        int checked = 0;
        for (Entity entity : entitiesIn(world)) {
            checked++;
            lastPassEntityWork++;
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            // For mobs, isOnGround() is server-controlled and reliable.
            // For players, it's client-reported and can be stale due to network latency.
            // Allow players through if they have low vertical velocity (not jumping/falling).
            if (!living.isOnGround()) {
                boolean playerNearGround = living instanceof Player
                        && Math.abs(living.getVelocity().getY()) < 0.1;
                if (!playerNearGround) {
                    continue;
                }
            }

            // Block beneath the entity
            Location feet = living.getLocation();
            Block below = world.getBlockAt(feet.getBlockX(), feet.getBlockY() - 1, feet.getBlockZ());
            if (below.getType().isAir()) {
                continue;
            }

            NodePos pos = StructureManager.toBlockPos(below.getLocation());
            // O(1) weakness test against the cached set instead of re-deriving it.
            if (!weak.contains(pos)) {
                continue;
            }

            Node node = graph.getNode(pos);
            if (node == null || node.isGrounded()) {
                continue;
            }

            // Check protection
            if (!guard.physicsAllowed(below.getLocation())) {
                continue;
            }

            // Add temporary weight and check for collapse
            double mass = massRegistry.getMass(entity.getType());
            double combinedStress = node.stressValue() + mass;
            if (combinedStress > node.effectiveMaxLoad()) {
                triggerCollapse(world, graph, pos);
            }
        }
        return checked;
    }

    /**
     * Entity source for the standing-weight scan. Overridable so tests can prove
     * the scan is skipped (this method is never called) on a healthy world.
     */
    protected Iterable<Entity> entitiesIn(World world) {
        return world.getEntities();
    }

    /** Entities examined in the most recent standing-weight pass (0 when skipped). */
    public long lastPassEntityWork() {
        return lastPassEntityWork;
    }

    /**
     * Handle fall impact — landing from height applies kinetic energy as a stress spike.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityFall(EntityDamageEvent event) {
        if (!config.enabled() || !config.fallImpactEnabled()) {
            return;
        }
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity living)) {
            return;
        }

        double fallDistance = living.getFallDistance();
        if (fallDistance < config.minFallDistance()) {
            return;
        }

        World world = living.getWorld();
        StructureGraph graph = structureManager.getGraph(world);
        if (graph == null || graph.isEmpty()) {
            return;
        }

        // Block landed on
        Location feet = living.getLocation();
        Block below = world.getBlockAt(feet.getBlockX(), feet.getBlockY() - 1, feet.getBlockZ());
        if (below.getType().isAir()) {
            return;
        }

        NodePos pos = StructureManager.toBlockPos(below.getLocation());
        Node node = graph.getNode(pos);
        if (node == null || node.isGrounded()) {
            return;
        }

        // Only affect weak blocks — unless falling from extreme height, which can
        // break even healthy blocks (configured via force-impact-distance).
        boolean forceImpact = fallDistance >= config.forceImpactDistance();
        if (!forceImpact && !isWeak(node)) {
            return;
        }

        // Check protection
        if (!guard.physicsAllowed(below.getLocation())) {
            return;
        }

        // Kinetic energy: E = ½mv², v = √(2gh)
        double velocity = Math.sqrt(2.0 * GRAVITY * fallDistance);
        double mass = massRegistry.getMass(living.getType());
        double energy = 0.5 * mass * velocity * velocity * config.energyScale();

        // Temporary stress spike — check if it tips the block over
        double combinedStress = node.stressValue() + energy;
        if (combinedStress > node.effectiveMaxLoad()) {
            triggerCollapse(world, graph, pos);
        }
    }

    /**
     * Whether a node is "weak" enough to be affected by entity weight.
     * Thresholds gate which blocks we even check.
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

        // A huge collapse can hit the per-event step cap and stop mid-fall; hand the
        // cut-short remainder to the resume manager so the rest finishes over the next
        // ticks instead of leaving overloaded/floating blocks stranded in the air.
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

    /** Configuration record for entity weight settings. */
    public record EntityWeightConfig(
            boolean enabled,
            int scanIntervalTicks,
            double stressThreshold,
            double damageThreshold,
            boolean standingEnabled,
            boolean fallImpactEnabled,
            double energyScale,
            double minFallDistance,
            double forceImpactDistance) {

        public static EntityWeightConfig defaults() {
            return new EntityWeightConfig(
                    true, // enabled
                    10, // scanIntervalTicks
                    0.7, // stressThreshold (70%)
                    0.5, // damageThreshold (50%)
                    true, // standingEnabled
                    true, // fallImpactEnabled
                    1.0, // energyScale
                    2.0, // minFallDistance
                    15.0); // forceImpactDistance
        }
    }
}
