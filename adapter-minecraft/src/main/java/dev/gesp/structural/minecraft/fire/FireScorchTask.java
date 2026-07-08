package dev.gesp.structural.minecraft.fire;

import dev.gesp.structural.api.CollapseReason;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.fire.FireModel;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.listener.BudgetedTask;
import dev.gesp.structural.minecraft.listener.CascadeResumeManager;
import dev.gesp.structural.minecraft.listener.DelayedCollapseManager;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.perf.TaskTimings;
import dev.gesp.structural.minecraft.protect.CollapseGuard;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.recording.EventRecorder;
import dev.gesp.structural.recording.FireDamageEvent;
import dev.gesp.structural.solver.CascadeEngine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.plugin.Plugin;

/**
 * Routes sustained fire through the strux {@link FireModel}: burning weakens a
 * structure over time, the slow-burn counterpart to the instantaneous blast and
 * impact.
 *
 * <pre>
 *   • A flammable block touched by fire chars (direct burn) — fast.
 *   • Any block next to fire/lava heats up (radiant) — slow, but enough that a
 *     long fire can cook even a metal frame down.
 *   • Damage is divided by the material's fireResistance, so wood goes fast and
 *     stone barely notices.
 * </pre>
 *
 * <p>Rather than scan every tracked block every tick, we listen for fire being
 * created ({@link BlockIgniteEvent}, which also fires on spread) and keep a small
 * set of active heat sources near tracked structures. Each scan only touches
 * those, applies damage to their neighbours, and — when a block actually crosses
 * into overload — settles the structure so the weakened part comes down. Gated by
 * the collapse guard (war zones only) and a config toggle.
 *
 * <p><b>Barren burnout:</b> a flame with nothing flammable next to it has no fuel
 * — physically it should gutter out, not burn forever against bare stone. Each
 * scan, a fuel-less flame accumulates barren time and is extinguished once it
 * crosses {@code fire.barren-burnout-ticks}; touching anything flammable resets
 * the clock, and lava is exempt (it IS its own fuel). So a sustained, fuelled
 * siege fire still cooks a wall down, but the eternal flame on a stone keep dies.
 */
public class FireScorchTask extends BudgetedTask implements Listener {

    private final Plugin plugin;
    private final StructureManager structureManager;
    private final FireModel fireModel;
    private final PhysicsConfig physicsConfig;
    private final DelayedCollapseManager delayedCollapseManager;
    private final CascadeResumeManager cascadeResumeManager;
    private final CollapseGuard guard;
    private final int scanIntervalTicks;
    private final int barrenBurnoutTicks;
    private final boolean enabled;
    private final TaskTimings taskTimings;

    /**
     * Active heat-source positions (the fire/lava block) near tracked structures,
     * per world, each mapped to how long it has been BARREN (ticks spent with no
     * flammable neighbour — the fuse for burnout).
     */
    private final Map<World, Map<NodePos, Integer>> activeFires = new HashMap<>();

    /** Safety cap so a forest fire can't blow the set up unbounded. */
    private static final int MAX_FIRES_PER_WORLD = 4096;

    public FireScorchTask(
            Plugin plugin,
            StructureManager structureManager,
            FireModel fireModel,
            PhysicsConfig physicsConfig,
            DelayedCollapseManager delayedCollapseManager,
            CascadeResumeManager cascadeResumeManager,
            CollapseGuard guard,
            int scanIntervalTicks,
            int barrenBurnoutTicks,
            boolean enabled,
            double tickBudgetMs,
            TaskTimings taskTimings) {
        this.plugin = plugin;
        this.structureManager = structureManager;
        this.fireModel = fireModel;
        this.physicsConfig = physicsConfig;
        this.delayedCollapseManager = delayedCollapseManager;
        this.cascadeResumeManager = cascadeResumeManager;
        this.guard = guard;
        this.scanIntervalTicks = Math.max(1, scanIntervalTicks);
        this.barrenBurnoutTicks = barrenBurnoutTicks;
        this.enabled = enabled;
        this.taskTimings = taskTimings;
        setTickBudgetMs(tickBudgetMs);
    }

    /** Begin the periodic scorch scan (no-op if disabled). */
    public void start() {
        if (enabled) {
            runTaskTimer(plugin, scanIntervalTicks, scanIntervalTicks);
        }
    }

    /**
     * Manually register a fire at the given location for tracking. Use this when
     * programmatically placing fire (e.g. in demo scenarios) since {@link
     * BlockIgniteEvent} only fires for player-lit or naturally-spreading fire.
     *
     * @param loc the location of the fire block (must be FIRE, SOUL_FIRE, or LAVA)
     */
    public void registerFire(Location loc) {
        if (!enabled) {
            return;
        }
        Block fire = loc.getBlock();
        if (!isHeatSource(fire.getType())) {
            return;
        }
        StructureGraph graph = structureManager.getGraph(fire.getWorld());
        if (graph == null || graph.isEmpty() || !touchesTracked(graph, fire)) {
            return;
        }
        Map<NodePos, Integer> fires = activeFires.computeIfAbsent(fire.getWorld(), w -> new HashMap<>());
        if (fires.size() < MAX_FIRES_PER_WORLD) {
            fires.putIfAbsent(StructureManager.toBlockPos(fire.getLocation()), 0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        if (!enabled) {
            return;
        }
        Block fire = event.getBlock();
        StructureGraph graph = structureManager.getGraph(fire.getWorld());
        if (graph == null || graph.isEmpty() || !touchesTracked(graph, fire)) {
            return;
        }
        Map<NodePos, Integer> fires = activeFires.computeIfAbsent(fire.getWorld(), w -> new HashMap<>());
        if (fires.size() < MAX_FIRES_PER_WORLD) {
            fires.putIfAbsent(StructureManager.toBlockPos(fire.getLocation()), 0);
        }
    }

    /**
     * Vanilla fire just consumed a block. If it was one we track, the structure
     * has lost a member — drop it from the graph and settle, so whatever it was
     * holding up comes down. Without this, a burnt support vanishes the vanilla
     * way and the structure never reacts (graph desync, no collapse).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        if (!enabled) {
            return;
        }
        Block block = event.getBlock();
        StructureGraph graph = structureManager.getGraph(block.getWorld());
        if (graph == null) {
            return;
        }
        NodePos pos = StructureManager.toBlockPos(block.getLocation());
        if (!graph.hasBlock(pos) || !guard.physicsAllowed(block.getLocation())) {
            return;
        }
        // cascade() syncs the graph (removes the burnt node) and settles
        // everything that depended on it — including chains hanging below,
        // which a hand-rolled dependents-only scope would miss.
        List<NodePos> collapsed = new ArrayList<>();
        var result = new CascadeEngine(physicsConfig).cascade(graph, pos, collector(collapsed));
        structureManager.markDirty(block.getWorld());
        scheduleCollapses(block.getWorld(), collapsed);
        resumeIfTruncated(block.getWorld(), result.truncated(), result.remainingScope());
    }

    /**
     * Hand a cut-short fire collapse to the resume manager so it finishes across the
     * next ticks under budget — never a synchronous freeze, and never a
     * silently-dropped collapse leaving the graph mid-fall. Shared by both fire
     * collapse paths ({@link #onBlockBurn} and {@link #settleAndSchedule}). The
     * scope keeps the resume bounded to the disturbed region.
     */
    private void resumeIfTruncated(World world, boolean truncated, Set<NodePos> scope) {
        if (truncated) {
            cascadeResumeManager.enqueue(world, scope);
        }
    }

    @Override
    public void run() {
        // Seatbelt: one scorch pass can't freeze a tick. We stop starting new
        // worlds/fires once this pass has spent its wall-clock budget; whatever
        // is left simply waits for the next scan (a slower burn, never a freeze).
        long start = System.nanoTime();
        long deadline = start + tickBudgetNanos();
        // Perf: count the fires actually visited this pass. Most ticks scan zero
        // fires (the set is empty), which is exactly the cheap idle cost we want to
        // see distinguished from a real forest-fire pass.
        int firesScanned = 0;
        for (Map.Entry<World, Map<NodePos, Integer>> entry : activeFires.entrySet()) {
            World world = entry.getKey();
            Map<NodePos, Integer> fires = entry.getValue();
            if (fires.isEmpty()) {
                continue;
            }
            StructureGraph graph = structureManager.getGraph(world);
            if (graph == null || graph.isEmpty()) {
                fires.clear();
                continue;
            }
            firesScanned += scorchWorld(world, graph, fires, deadline);
            if (System.nanoTime() >= deadline) {
                break; // remaining worlds wait for the next scan
            }
        }
        taskTimings.record(TaskTimings.FIRE_SCORCH, System.nanoTime() - start, firesScanned);
    }

    /** @return how many fires this world's scan visited (the pass's work count). */
    private int scorchWorld(World world, StructureGraph graph, Map<NodePos, Integer> fires, long deadline) {
        Set<NodePos> affectedPositions = new HashSet<>();
        boolean damaged = false;
        int firesVisited = 0;
        Iterator<Map.Entry<NodePos, Integer>> it = fires.entrySet().iterator();
        while (it.hasNext()) {
            // Over budget: settle whatever cooked through so far and leave the rest
            // of this world's fires untouched for the next pass.
            if (System.nanoTime() >= deadline) {
                break;
            }
            Map.Entry<NodePos, Integer> fire = it.next();
            firesVisited++;
            NodePos firePos = fire.getKey();
            Block fireBlock = world.getBlockAt(firePos.x(), firePos.y(), firePos.z());
            if (!isHeatSource(fireBlock.getType())) {
                it.remove(); // burned out or extinguished (water, rain) — stop tracking it
                continue;
            }
            boolean flame = isFlame(fireBlock.getType());

            // Barren burnout: a flame with no fuel beside it gutters out.
            if (flame && barrenBurnoutTicks > 0) {
                if (hasFlammableNeighbour(fireBlock)) {
                    fire.setValue(0); // fed — reset the fuse
                } else {
                    int barren = fire.getValue() + scanIntervalTicks;
                    if (barren >= barrenBurnoutTicks) {
                        extinguish(world, fireBlock);
                        it.remove();
                        continue;
                    }
                    fire.setValue(barren);
                }
            }

            for (NodePos pos : graph.getAdjacentPositions(firePos)) {
                Node node = graph.getNode(pos);
                if (node == null || node.isGrounded()) {
                    continue;
                }
                Location loc = StructureManager.toLocation(pos, world);
                if (!guard.physicsAllowed(loc)) {
                    continue; // outside a war zone — leave it be
                }
                boolean burning = flame && loc.getBlock().getType().isFlammable();
                double amount = burning
                        ? fireModel.burnDamage(node.spec(), scanIntervalTicks)
                        : fireModel.radiantDamage(node.spec(), scanIntervalTicks);
                if (amount <= 0.0) {
                    continue;
                }
                double damageBefore = node.damage();
                graph.applyDamage(pos, amount); // via graph so modCount moves (async settle conflict check)
                damaged = true;
                emitScorch(world, pos, burning); // visible feedback that the block is heating
                // Only pay for a full settle once a block has actually crossed the
                // line — slow charring just accumulates silently until then.
                boolean destroyed = node.isDestroyed() || node.stressPercent() > 1.0;
                if (destroyed) {
                    affectedPositions.add(pos);
                }

                // Record fire damage event
                EventRecorder recorder = structureManager.getEventRecorder();
                if (recorder.isRecording()
                        && recorder
                                instanceof dev.gesp.structural.minecraft.recording.MinecraftEventRecorder mcRecorder) {
                    recorder.record(new FireDamageEvent(
                            System.currentTimeMillis(),
                            mcRecorder.nextSequenceId(),
                            pos,
                            node.spec().toString(),
                            amount,
                            damageBefore + amount,
                            destroyed,
                            List.of())); // collapsed list filled in separately
                }
            }
        }
        if (!affectedPositions.isEmpty()) {
            // Build scope from all affected positions and their dependents
            Set<NodePos> scope = new HashSet<>();
            for (NodePos pos : affectedPositions) {
                scope.addAll(graph.getDependentSubgraph(pos));
            }
            settleAndSchedule(world, graph, scope);
        }
        if (damaged) {
            structureManager.markDirty(world); // heat weakened blocks → refresh grade/visualizer
        }
        return firesVisited;
    }

    /** Re-solve the weakened structure, collapsing whatever can no longer stand, and animate it. */
    private void settleAndSchedule(World world, StructureGraph graph, Set<NodePos> scope) {
        List<NodePos> collapsed = new ArrayList<>();
        var outcome = new CascadeEngine(physicsConfig).settleResult(graph, scope, collector(collapsed));
        scheduleCollapses(world, collapsed);
        resumeIfTruncated(world, outcome.truncated(), outcome.remainingScope());
    }

    /** Callback that just records which positions collapsed. */
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

    /** Hand the collapsed positions to the delayed-collapse animation as one batch. */
    private void scheduleCollapses(World world, List<NodePos> collapsed) {
        if (collapsed.isEmpty()) {
            return;
        }
        Location origin = StructureManager.toLocation(collapsed.get(0), world);
        int batchId = delayedCollapseManager.startBatch(world, origin, true);
        for (NodePos pos : collapsed) {
            delayedCollapseManager.scheduleCollapse(world, pos, batchId);
        }
    }

    /** A wisp of smoke (plus embers on a directly-burning block) so heating is visible before cracks show. */
    private void emitScorch(World world, NodePos pos, boolean burning) {
        double x = pos.x() + 0.5;
        double y = pos.y() + 0.5;
        double z = pos.z() + 0.5;
        world.spawnParticle(Particle.SMOKE, x, y, z, 3, 0.25, 0.25, 0.25, 0.01);
        if (burning) {
            world.spawnParticle(Particle.LAVA, x, y, z, 1, 0.2, 0.2, 0.2, 0.0);
        }
    }

    /** Is anything beside this fire actually burnable — i.e. does the flame have fuel? */
    private static boolean hasFlammableNeighbour(Block fire) {
        for (BlockFace face : SCAN_FACES) {
            if (fire.getRelative(face).getType().isFlammable()) {
                return true;
            }
        }
        return false;
    }

    /** Put the flame out: it spent its whole burnout fuse with nothing to eat. */
    private void extinguish(World world, Block fireBlock) {
        fireBlock.setType(Material.AIR);
        double x = fireBlock.getX() + 0.5;
        double y = fireBlock.getY() + 0.5;
        double z = fireBlock.getZ() + 0.5;
        world.spawnParticle(Particle.SMOKE, x, y, z, 12, 0.2, 0.2, 0.2, 0.02);
        world.playSound(fireBlock.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 0.5f, 1.0f);
    }

    private static final BlockFace[] SCAN_FACES = {
        BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private boolean touchesTracked(StructureGraph graph, Block fire) {
        NodePos firePos = StructureManager.toBlockPos(fire.getLocation());
        for (NodePos neighbour : graph.getAdjacentPositions(firePos)) {
            if (graph.hasBlock(neighbour)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Is there an active fire/lava heat source ADJACENT to (or on) this position
     * in the given world — i.e. is fire already actively damaging this block?
     * Used by the temperature task to honour rule A: a block that fire is already
     * weakening (persistent char damage) must NOT also have its capacity softened
     * by the ambient/radiant temperature model, so the two never double-count on
     * the same block. Reads only the tracked active-fire set this task already
     * maintains — no extra world scan.
     */
    public boolean isFireActiveAt(World world, NodePos pos) {
        Map<NodePos, Integer> fires = activeFires.get(world);
        if (fires == null || fires.isEmpty()) {
            return false;
        }
        if (fires.containsKey(pos)) {
            return true;
        }
        for (BlockFace face : SCAN_FACES) {
            NodePos adj = new NodePos(pos.x() + face.getModX(), pos.y() + face.getModY(), pos.z() + face.getModZ());
            if (fires.containsKey(adj)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHeatSource(Material m) {
        return m == Material.FIRE || m == Material.SOUL_FIRE || m == Material.LAVA;
    }

    private static boolean isFlame(Material m) {
        return m == Material.FIRE || m == Material.SOUL_FIRE;
    }

    /** Stop the scan. */
    public void stop() {
        try {
            cancel();
        } catch (IllegalStateException ignored) {
            // never started or already cancelled
        }
        activeFires.clear();
    }
}
