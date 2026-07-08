package dev.gesp.structural.minecraft.visual;

import dev.gesp.structural.minecraft.config.EffectsConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/**
 * Visual and audio effects for structural collapse.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                      COLLAPSE EFFECTS                               │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Single source of truth for all collapse-related visuals:          │
 *   │                                                                     │
 *   │  • Block breaking particles and sounds                             │
 *   │  • Cracking warnings before failure                                │
 *   │  • Cascade-complete dust clouds and rumbles                        │
 *   │  • Screen shake for nearby players                                 │
 *   │                                                                     │
 *   │       💥 ← particles                                               │
 *   │      🔊 ← sound                                                    │
 *   │      📳 ← screen shake                                             │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class CollapseEffects {

    private final EffectsConfig config;
    private final Plugin plugin;

    public CollapseEffects(EffectsConfig config, Plugin plugin) {
        this.config = config;
        this.plugin = plugin;
    }

    /**
     * Play effects when a single block collapses.
     */
    public void playBlockCollapse(Location location, Material material) {
        World world = location.getWorld();
        if (world == null) return;

        Location center = location.clone().add(0.5, 0.5, 0.5);

        // Particle effect - block breaking dust
        world.spawnParticle(Particle.BLOCK, center, 8, 0.3, 0.3, 0.3, 0.1, material.createBlockData());

        // Sound effect - block breaking
        world.playSound(center, Sound.BLOCK_STONE_BREAK, 1.0f, 0.8f);
    }

    /**
     * Play a small "bite" of feedback when a projectile impact settles and actually
     * damages a block. One soft hit sound plus a handful of the struck block's
     * particles, at the hit point — so a volley of N damaging arrows looks like N
     * little bites instead of one block silently soaking them all up.
     *
     * <p>Gated by {@code effects.impact-feedback} (default on); off means nothing
     * plays here and the physics is untouched either way. Deliberately tiny so a big
     * volley (bounded by the impact tick budget) can't spam particles or sound.
     *
     * @param location the hit location (impact point, not block-centred)
     * @param material the struck block's material, so the particles match it
     */
    public void playImpactHit(Location location, Material material) {
        if (!config.isImpactFeedbackEnabled()) return;

        World world = location.getWorld();
        if (world == null) return;

        Location center = location.clone().add(0.5, 0.5, 0.5);

        // A handful of the block's break particles — the visible "bite".
        world.spawnParticle(Particle.BLOCK, center, 6, 0.2, 0.2, 0.2, 0.05, material.createBlockData());

        // A soft, higher-pitched hit tick (quieter than a full break/collapse).
        world.playSound(center, getCrackSound(material), 0.4f, 1.4f);
    }

    /**
     * Play escalating warning effects as block approaches failure.
     *
     * @param location block location
     * @param material block material
     * @param progress 0.0 to 1.0, how close to collapse
     */
    public void playFailureWarning(Location location, Material material, float progress) {
        World world = location.getWorld();
        if (world == null) return;

        Location center = location.clone().add(0.5, 0.5, 0.5);

        // Escalating crack sounds
        float volume = 0.3f + (progress * 0.4f);
        float pitch = 1.5f - (progress * 0.5f); // gets lower/more ominous

        Sound crackSound = getCrackSound(material);
        world.playSound(center, crackSound, volume, pitch);

        // Dust/debris particles - more as failure approaches
        int particleCount = 2 + (int) (progress * 4);
        world.spawnParticle(Particle.BLOCK, center, particleCount, 0.3, 0.3, 0.3, 0.05, material.createBlockData());

        // Smoke near the end
        if (progress > 0.6f) {
            world.spawnParticle(Particle.SMOKE, center, 1, 0.2, 0.2, 0.2, 0.02);
        }
    }

    /**
     * Play cascade-complete effects scaled to cascade size.
     */
    public void playCascadeComplete(World world, Location origin, int totalCollapsed) {
        if (world == null || totalCollapsed == 0) return;

        Location center = origin.clone().add(0.5, 0.5, 0.5);

        // Scaled sounds based on cascade size
        playCascadeSounds(world, center, totalCollapsed);

        // Dust cloud effects
        if (config.isDustCloudsEnabled()) {
            playDustCloud(world, center, totalCollapsed);
        }

        // Final settling shake for big collapses — honour the configured threshold (the same
        // effects.screen-shake-threshold DelayedCollapseManager uses), not a hardcoded 30.
        if (config.isScreenShakeEnabled() && totalCollapsed >= config.getScreenShakeThreshold()) {
            shakeNearbyPlayers(world, center, 0.4f);
        }
    }

    /**
     * Play progressive rumble for ongoing collapse.
     *
     * @param world the world
     * @param origin cascade origin
     * @param progress 0.0 to 1.0, how far through the collapse
     */
    public void playProgressiveRumble(World world, Location origin, float progress) {
        float volume = 0.3f + (progress * 0.4f);
        world.playSound(origin, Sound.ENTITY_GENERIC_EXPLODE, volume * 0.5f, 0.4f + (progress * 0.2f));
    }

    /**
     * Shake nearby players with given intensity.
     *
     * @param world the world
     * @param center shake epicenter
     * @param intensity shake strength (0.1 = subtle, 0.5 = violent)
     */
    public void shakeNearbyPlayers(World world, Location center, float intensity) {
        double radiusSq = config.getScreenShakeRadius() * config.getScreenShakeRadius();

        for (Player player : world.getPlayers()) {
            double distSq = player.getLocation().distanceSquared(center);
            if (distSq <= radiusSq) {
                double distFactor = 1.0 - (Math.sqrt(distSq) / config.getScreenShakeRadius());
                float adjustedIntensity = (float) (intensity * distFactor);
                if (adjustedIntensity > 0.05f) {
                    shakePlayer(player, adjustedIntensity);
                }
            }
        }
    }

    /**
     * Get appropriate cracking sound for material type.
     */
    public Sound getCrackSound(Material material) {
        String name = material.name();

        if (name.contains("WOOD") || name.contains("LOG") || name.contains("PLANK")) {
            return Sound.BLOCK_WOOD_BREAK;
        }
        if (name.contains("GLASS")) {
            return Sound.BLOCK_GLASS_BREAK;
        }
        if (name.contains("STONE") || name.contains("BRICK") || name.contains("CONCRETE")) {
            return Sound.BLOCK_STONE_BREAK;
        }
        if (name.contains("METAL") || name.contains("IRON") || name.contains("COPPER")) {
            return Sound.BLOCK_CHAIN_BREAK;
        }

        return Sound.BLOCK_STONE_BREAK;
    }

    // ========== Private Helpers ==========

    private void playCascadeSounds(World world, Location center, int totalCollapsed) {
        if (totalCollapsed <= 3) {
            // Small collapse: simple thud
            world.playSound(center, Sound.BLOCK_ANVIL_LAND, 0.5f, 0.6f);
            world.playSound(center, Sound.BLOCK_GRAVEL_BREAK, 0.6f, 0.7f);
        } else if (totalCollapsed <= 10) {
            // Medium collapse: heavier thud + debris
            world.playSound(center, Sound.BLOCK_ANVIL_LAND, 0.8f, 0.4f);
            world.playSound(center, Sound.BLOCK_GRAVEL_BREAK, 1.0f, 0.5f);
            world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.4f, 0.5f);
        } else {
            // Large collapse: deep boom + lingering rumble
            world.playSound(center, Sound.BLOCK_ANVIL_LAND, 1.2f, 0.3f);
            world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 0.4f);
            world.playSound(center, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.4f, 0.1f);
            world.playSound(center, Sound.BLOCK_GRAVEL_BREAK, 1.2f, 0.4f);

            // Delayed rumble for very large collapses
            if (totalCollapsed > 25) {
                plugin.getServer()
                        .getScheduler()
                        .runTaskLater(
                                plugin,
                                () -> {
                                    world.playSound(center, Sound.ENTITY_WITHER_BREAK_BLOCK, 0.3f, 0.3f);
                                },
                                10L);
            }
        }
    }

    private void playDustCloud(World world, Location center, int totalCollapsed) {
        double dustMult = config.getDustMultiplier();
        double spread = Math.min(1.5 + (totalCollapsed * 0.1), 5.0);

        // Primary dust cloud
        int mainDust = (int) (Math.min(totalCollapsed * 8, 100) * dustMult);
        world.spawnParticle(Particle.CLOUD, center, mainDust, spread, spread * 0.6, spread, 0.08);

        // Ground-level dust burst
        Location groundLevel = center.clone();
        groundLevel.setY(Math.max(groundLevel.getY() - 2, world.getMinHeight()));
        world.spawnParticle(
                Particle.CLOUD, groundLevel, Math.min(totalCollapsed * 4, 50), spread * 1.5, 0.3, spread * 1.5, 0.12);

        // Rising dust column
        for (int i = 0; i < Math.min(totalCollapsed / 3, 8); i++) {
            Location rising =
                    center.clone().add((Math.random() - 0.5) * spread, i * 0.8, (Math.random() - 0.5) * spread);
            world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, rising, 3, 0.4, 0.6, 0.4, 0.02);
        }

        // Explosion puffs for larger collapses
        if (totalCollapsed > 5) {
            int explosionPuffs = Math.min(totalCollapsed / 3, 12);
            world.spawnParticle(
                    Particle.EXPLOSION, center, explosionPuffs, spread * 0.6, spread * 0.4, spread * 0.6, 0);
        }

        // Fine debris particles
        world.spawnParticle(
                Particle.ASH, center, Math.min(totalCollapsed * 10, 80), spread * 1.2, spread, spread * 1.2, 0);

        // Lingering dust waves
        if (totalCollapsed > 8) {
            playLingeringDust(world, center, spread, totalCollapsed);
        }

        // Outward spreading dust wave
        if (config.isDustWaveEnabled() && totalCollapsed > 5) {
            playDustWave(world, groundLevel, spread, totalCollapsed);
        }
    }

    private void playLingeringDust(World world, Location center, double spread, int totalCollapsed) {
        for (int delay = 5; delay <= 20; delay += 5) {
            final int d = delay;
            plugin.getServer()
                    .getScheduler()
                    .runTaskLater(
                            plugin,
                            () -> {
                                world.spawnParticle(
                                        Particle.CLOUD,
                                        center.clone().add(0, -0.5, 0),
                                        Math.min(totalCollapsed * 2, 30),
                                        spread * (1.0 + d * 0.05),
                                        0.2,
                                        spread * (1.0 + d * 0.05),
                                        0.02);
                            },
                            d);
        }

        // Very large collapses get extra dramatic billowing
        if (totalCollapsed > 20) {
            plugin.getServer()
                    .getScheduler()
                    .runTaskLater(
                            plugin,
                            () -> {
                                world.spawnParticle(
                                        Particle.CAMPFIRE_COSY_SMOKE,
                                        center,
                                        30,
                                        spread * 0.8,
                                        1.5,
                                        spread * 0.8,
                                        0.04);
                                Location groundLevel = center.clone();
                                groundLevel.setY(Math.max(groundLevel.getY() - 2, world.getMinHeight()));
                                world.spawnParticle(Particle.CLOUD, groundLevel, 40, spread * 2, 0.5, spread * 2, 0.06);
                            },
                            8L);
        }
    }

    private void playDustWave(World world, Location ground, double spread, int totalCollapsed) {
        final double maxRadius = Math.min(3.0 + (totalCollapsed * 0.3), config.getDustWaveMaxRadius());
        final int waves = Math.min(totalCollapsed / 5, 6);

        for (int wave = 0; wave < waves; wave++) {
            final int w = wave;
            final double waveRadius = (w + 1) * (maxRadius / waves);

            plugin.getServer()
                    .getScheduler()
                    .runTaskLater(
                            plugin,
                            () -> {
                                int particlesInRing = (int) (12 + waveRadius * 4);
                                for (int i = 0; i < particlesInRing; i++) {
                                    double angle = (2 * Math.PI * i) / particlesInRing;
                                    double x = Math.cos(angle) * waveRadius;
                                    double z = Math.sin(angle) * waveRadius;

                                    Location ringPoint = ground.clone().add(x, 0.5, z);

                                    world.spawnParticle(Particle.CLOUD, ringPoint, 5, 0.8, 0.4, 0.8, 0.03);
                                    world.spawnParticle(
                                            Particle.CAMPFIRE_COSY_SMOKE,
                                            ringPoint.clone().add(0, 0.3, 0),
                                            2,
                                            0.3,
                                            0.5,
                                            0.3,
                                            0.01);
                                }

                                // Central billow still rising
                                world.spawnParticle(
                                        Particle.CLOUD,
                                        ground.clone().add(0, 1 + w * 0.5, 0),
                                        15,
                                        spread * 0.5,
                                        0.8,
                                        spread * 0.5,
                                        0.02);
                            },
                            10L + (w * 4L));
        }

        // Final lingering haze
        plugin.getServer()
                .getScheduler()
                .runTaskLater(
                        plugin,
                        () -> {
                            world.spawnParticle(
                                    Particle.CLOUD,
                                    ground.clone().add(0, 1.5, 0),
                                    Math.min(totalCollapsed * 3, 60),
                                    maxRadius * 0.8,
                                    1.0,
                                    maxRadius * 0.8,
                                    0.01);
                            world.spawnParticle(
                                    Particle.ASH,
                                    ground.clone().add(0, 2, 0),
                                    Math.min(totalCollapsed * 5, 80),
                                    maxRadius,
                                    2.0,
                                    maxRadius,
                                    0);
                        },
                        10L + (waves * 4L) + 10L);
    }

    private void shakePlayer(Player player, float intensity) {
        try {
            float yaw = (float) (Math.random() * 360);
            player.sendHurtAnimation(yaw);
        } catch (NoSuchMethodError e) {
            // Fallback for older versions
            Vector nudge = new Vector(
                    (Math.random() - 0.5) * intensity * 0.15,
                    intensity * 0.03,
                    (Math.random() - 0.5) * intensity * 0.15);
            player.setVelocity(player.getVelocity().add(nudge));
        }
    }
}
