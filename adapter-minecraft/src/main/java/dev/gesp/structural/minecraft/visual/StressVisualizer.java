package dev.gesp.structural.minecraft.visual;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.cache.RevisionCachedNodeView;
import dev.gesp.structural.minecraft.config.EffectsConfig;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.perf.TaskTimings;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import java.util.Set;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Visualizes structural stress through particles and sounds.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                     STRESS VISUALIZER                              │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Shows players how stressed blocks are:                            │
 *   │                                                                     │
 *   │    0-50%  stress: No particles (safe)                              │
 *   │   50-80%  stress: Yellow dust (caution)                            │
 *   │   80-95%  stress: Orange dust + smoke (danger)                     │
 *   │   95%+    stress: Red dust + flames + creaking (critical!)         │
 *   │                                                                     │
 *   │                                                                     │
 *   │       [BLOCK] 🟢  ← 30% stress, looks fine                         │
 *   │       [BLOCK] 🟡  ← 65% stress, starting to strain                 │
 *   │       [BLOCK] 🟠  ← 85% stress, danger zone                        │
 *   │       [BLOCK] 🔴  ← 98% stress, about to collapse!                 │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class StressVisualizer extends BukkitRunnable {

    private final StructureManager structureManager;
    private final Plugin plugin;
    private final PreCollapseShake preCollapseShake;
    private final EffectsConfig config;
    private final TaskTimings taskTimings;

    // FREEZE + BATCH: stress only changes when a structure changes, so the set of
    // "worth-visualizing" nodes (>= caution) is cached per world and rebuilt only
    // when that world's revision bumps. A static world then costs O(stressed nodes)
    // per tick instead of O(all tracked nodes). The freeze-cache correctness
    // argument lives once on RevisionCachedNodeView.
    private final RevisionCachedNodeView stressedView;

    // Chance per visualizer tick to (re)start a wobble on a danger-or-worse
    // block. Deduped while already shaking, so this mainly paces how often the
    // wobble restarts once a previous one finishes.
    private static final double SHAKE_CHANCE = 0.5;

    // Colors for stress levels (not configurable - would be confusing)
    private static final Color COLOR_CAUTION = Color.YELLOW;
    private static final Color COLOR_DANGER = Color.ORANGE;
    private static final Color COLOR_CRITICAL = Color.RED;

    // Particle counts (kept low to reduce client lag on large structures)
    private static final int PARTICLES_CAUTION = 1;
    private static final int PARTICLES_DANGER = 2;
    private static final int PARTICLES_CRITICAL = 3;

    // Only emit FX a player can actually perceive — same 48-block gate as PreCollapseShake.
    private static final double VIEW_DISTANCE_SQ = 48.0 * 48.0;

    public StressVisualizer(
            StructureManager structureManager,
            Plugin plugin,
            PreCollapseShake preCollapseShake,
            EffectsConfig config,
            TaskTimings taskTimings) {
        this.structureManager = structureManager;
        this.plugin = plugin;
        this.preCollapseShake = preCollapseShake;
        this.config = config;
        this.taskTimings = taskTimings;
        this.stressedView = new RevisionCachedNodeView(
                structureManager,
                node -> !node.isGrounded() && node.stressPercent() >= config.getStressCautionThreshold());
    }

    /**
     * Start the visualizer running every interval ticks.
     *
     * @param intervalTicks how often to update (20 ticks = 1 second)
     */
    public void start(long intervalTicks) {
        this.runTaskTimer(plugin, 20L, intervalTicks); // Delay 1 second, then repeat
    }

    @Override
    public void run() {
        // Perf: time the whole pass and count the work it actually did. On a static
        // world this is the cached stressed-set size (the freeze+batch payoff); on a
        // pass that had to rescan a changed world it is the full rebuild scan size
        // (graph.size()) — we charge whichever happened so a reading is honest about
        // a rebuild spike, not the cheap steady state it usually shows.
        long start = System.nanoTime();
        int work = 0;
        for (World world : plugin.getServer().getWorlds()) {
            StructureGraph graph = structureManager.getGraph(world);
            if (graph == null) {
                continue;
            }

            // Only iterate the stressed nodes. Rebuild that set (a full scan) once
            // per world change; otherwise reuse the cached set — frozen worlds do
            // no per-tick scanning.
            Set<NodePos> stressed = stressedView.nodes(world, graph);
            // Charge the rebuild scan if one happened this pass for this world,
            // otherwise the cached set actually visited below.
            int rebuildScan = stressedView.lastScanned();
            work += rebuildScan >= 0 ? rebuildScan : stressed.size();

            for (NodePos pos : stressed) {
                Node node = graph.getNode(pos);
                if (node == null) {
                    continue; // removed since the scan — skip
                }
                double stress = node.stressPercent();
                if (stress < config.getStressCautionThreshold()) {
                    continue; // dropped below caution — skip (will be pruned on next rebuild)
                }
                if (!shouldEmit(world, pos)) {
                    continue; // chunk unloaded or no player near — don't force-load chunks or
                    // spam particles/sounds nobody can perceive
                }
                Location loc = new Location(world, pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5);
                visualizeStress(world, pos, loc, stress);
            }
        }
        taskTimings.record(TaskTimings.STRESS_VISUALIZER, System.nanoTime() - start, work);
    }

    /**
     * Should this node emit stress FX this pass? Skips nodes whose chunk is unloaded — a
     * structure restored from persistence far from any player would otherwise force a
     * synchronous chunk load (then unload) twice a second just to play particles — and
     * nodes no player is within {@link #VIEW_DISTANCE_SQ} of, so a stressed structure with
     * nobody around costs nothing. Package-private for testing the gate directly.
     */
    boolean shouldEmit(World world, NodePos pos) {
        if (!world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
            return false;
        }
        // Block corner is close enough at a 48-block radius (the half-block centre offset
        // is immaterial to a perceptual gate, and keeps the distance free of arithmetic).
        Location node = new Location(world, pos.x(), pos.y(), pos.z());
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(node) <= VIEW_DISTANCE_SQ) {
                return true;
            }
        }
        return false;
    }

    /**
     * Spawn particles based on stress level.
     */
    private void visualizeStress(World world, NodePos pos, Location loc, double stress) {
        if (world == null) return;

        Block block = loc.getBlock();
        Material material = block.getType();

        if (stress >= config.getStressCriticalThreshold()) {
            // Critical: Red particles + flames
            spawnDustParticles(loc, COLOR_CRITICAL, PARTICLES_CRITICAL);
            world.spawnParticle(Particle.FLAME, loc, 1, 0.2, 0.2, 0.2, 0.01);

        } else if (stress >= config.getStressDangerThreshold()) {
            // Danger: Orange particles + smoke
            spawnDustParticles(loc, COLOR_DANGER, PARTICLES_DANGER);
            world.spawnParticle(Particle.SMOKE, loc, 1, 0.15, 0.15, 0.15, 0.01);

        } else if (stress >= config.getStressCautionThreshold()) {
            // Caution: Yellow particles only
            spawnDustParticles(loc, COLOR_CAUTION, PARTICLES_CAUTION);
        }

        // Ambient stress audio. Above the danger threshold a block "talks" as it
        // nears failure: the closer to 100%, the more often it sounds and the
        // more alarming the sound (soft creak → groan → sharp crack).
        if (stress >= config.getStressDangerThreshold()) {
            double danger = config.getStressDangerThreshold();
            double severity = clamp01((stress - danger) / Math.max(1e-6, 1.0 - danger));
            if (config.isEscalatingStressAudioEnabled()) {
                // Chance and intensity both ramp with severity.
                if (Math.random() < 0.05 + 0.30 * severity) {
                    playStressSound(world, loc, material, severity);
                }
            } else {
                // Legacy flat behavior: occasional fixed danger/critical sound.
                boolean critical = stress >= config.getStressCriticalThreshold();
                if (Math.random() < (critical ? 0.15 : 0.05)) {
                    playStressSound(world, loc, material, critical ? 1.0 : 0.0);
                }
            }
        }

        // Telegraph imminent failure: rock the block on its base. Starts as a
        // faint tremble in the danger zone and escalates toward 100%. Deduped
        // and view-distance gated inside PreCollapseShake.
        // Skip wobble on cracked blocks - the overlay shows an uncracked block
        // which looks jarring when the real block has visible damage cracks.
        if (preCollapseShake != null && stress >= config.getStressDangerThreshold() && Math.random() < SHAKE_CHANCE) {
            StructureGraph graph = structureManager.getGraph(world);
            Node node = graph != null ? graph.getNode(pos) : null;
            double damage = node != null ? node.damage() : 0.0;
            if (damage < config.getMinVisibleDamage()) { // below the crack-overlay floor → show shake instead
                preCollapseShake.shake(world, pos, stress);
            }
        }
    }

    /**
     * Play a material-appropriate stress sound whose tier, volume and pitch
     * ramp with {@code severity} (0 = just entered the danger zone, 1 = at the
     * brink of failure). Three tiers per material family: a soft settling
     * sound, a louder strain, and a sharp crack as it gives way.
     */
    private void playStressSound(World world, Location loc, Material material, double severity) {
        String name = material.name();
        int tier = severity >= 0.75 ? 2 : (severity >= 0.40 ? 1 : 0); // 0 soft, 1 strain, 2 crack

        // Louder and lower-pitched (more ominous) the closer to failure, scaled
        // by the configured stress-audio volume multiplier.
        float volume = (float) (0.15 + 0.35 * severity) * config.getStressAudioVolume();
        float pitch = (float) (1.1 - 0.6 * severity);

        Sound sound;
        if (name.contains("LOG")
                || name.contains("WOOD")
                || name.contains("PLANK")
                || name.contains("FENCE")
                || name.contains("DOOR")) {
            // Wood: faint step → creak → snap.
            sound = tier == 2 ? Sound.BLOCK_WOOD_BREAK : (tier == 1 ? Sound.BLOCK_WOOD_HIT : Sound.BLOCK_LADDER_STEP);
        } else if (name.contains("IRON")
                || name.contains("GOLD")
                || name.contains("COPPER")
                || name.contains("NETHERITE")
                || name.contains("CHAIN")
                || name.contains("ANVIL")) {
            // Metal: chain rattle → groan → clang.
            sound = tier == 2 ? Sound.BLOCK_ANVIL_LAND : (tier == 1 ? Sound.BLOCK_CHAIN_BREAK : Sound.BLOCK_CHAIN_STEP);
            pitch = (float) (0.8 - 0.5 * severity);
        } else if (name.contains("GLASS")) {
            // Glass: high-pitched stress that sharpens into a crack.
            sound = tier == 2 ? Sound.BLOCK_GLASS_BREAK : Sound.BLOCK_GLASS_STEP;
            pitch = (float) (2.0 - 0.3 * severity);
        } else if (name.contains("STONE")
                || name.contains("BRICK")
                || name.contains("COBBLE")
                || name.contains("DEEPSLATE")
                || name.contains("CONCRETE")) {
            // Stone: gravel grind → stone grind → stone crack.
            sound = tier == 2
                    ? Sound.BLOCK_STONE_BREAK
                    : (tier == 1 ? Sound.BLOCK_STONE_STEP : Sound.BLOCK_GRAVEL_STEP);
        } else if (name.contains("DIRT")
                || name.contains("SAND")
                || name.contains("GRAVEL")
                || name.contains("CLAY")
                || name.contains("MUD")) {
            sound = tier == 2 ? Sound.BLOCK_GRAVEL_BREAK : Sound.BLOCK_SAND_STEP;
        } else {
            sound = tier == 2 ? Sound.BLOCK_STONE_BREAK : Sound.BLOCK_STONE_STEP;
        }

        world.playSound(loc, sound, volume, Math.max(0.5f, pitch));
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /**
     * Spawn colored dust particles.
     */
    private void spawnDustParticles(Location loc, Color color, int count) {
        World world = loc.getWorld();
        if (world == null) return;

        Particle.DustOptions dustOptions = new Particle.DustOptions(color, config.getStressParticleSize());
        world.spawnParticle(
                Particle.DUST,
                loc,
                count,
                0.3,
                0.3,
                0.3, // Spread
                0, // Speed
                dustOptions);
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
