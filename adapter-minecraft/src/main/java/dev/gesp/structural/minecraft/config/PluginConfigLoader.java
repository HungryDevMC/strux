package dev.gesp.structural.minecraft.config;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.minecraft.container.ContainerWeightTask;
import dev.gesp.structural.minecraft.entity.EntityMassRegistry;
import dev.gesp.structural.minecraft.entity.EntityWeightTask;
import dev.gesp.structural.minecraft.material.MaterialRegistry;
import dev.gesp.structural.minecraft.recording.MetricsConfig;
import dev.gesp.structural.minecraft.recording.RecordingConfig;
import dev.gesp.structural.minecraft.weather.WeatherLoadTask;
import dev.gesp.structural.model.MaterialSpec;
import java.util.Locale;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

/**
 * Reads the plugin's {@link FileConfiguration} and produces the populated config
 * objects the plugin wires up. Extracted from {@code StructuralIntegrityPlugin}
 * so that config parsing is a single, independently testable responsibility (SRP)
 * and {@code onEnable} reads as wiring only.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                     PLUGIN CONFIG LOADER                          │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  config.yml  ──▶  PluginConfigLoader  ──▶  PhysicsConfig            │
 *   │                                            PersistenceConfig        │
 *   │                                            EffectsConfig            │
 *   │                                            RegionConfig             │
 *   │                                            RecordingConfig          │
 *   │                                            MetricsConfig            │
 *   │                                            EntityWeightConfig       │
 *   │                                            WeatherConfig            │
 *   │                                                                     │
 *   │  Pure read: identical keys, defaults, and fallbacks as before.     │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public final class PluginConfigLoader {

    private final FileConfiguration config;
    private final Logger logger;

    public PluginConfigLoader(FileConfiguration config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    /**
     * Load physics settings from config.yml
     */
    public PhysicsConfig loadPhysicsConfig() {
        PhysicsConfig physics = new PhysicsConfig();
        physics.setMomentMultiplier(config.getDouble("moment-multiplier", 1.0));
        physics.setBeamMomentReduction(config.getDouble("beam-moment-reduction", 1.0));
        // On by default in the Minecraft adapter: a cantilever's bending capacity scales with
        // the square of its section depth (S = b·d²/6), so thick beams/decks are realistically
        // stronger than thin ones. Strictly safer than off — a 1-block-thick beam is unchanged;
        // only multi-thick builds get stronger. The core keeps this off for library embedders.
        physics.setBendingDepthEnabled(config.getBoolean("bending-depth-enabled", true));
        physics.setMaxCascadeSteps(config.getInt("max-cascade-steps", 50));
        physics.setVisualUpdateTicks(config.getInt("visual-update-ticks", 10));
        physics.setPreCollapseShake(config.getBoolean("pre-collapse-shake", true));
        physics.setDebugLogging(config.getBoolean("debug-logging", false));

        physics.setBlastRadiusPerPower(config.getDouble("blast-radius-per-power", 1.5));
        physics.setDestructionThreshold(config.getDouble("blast-destruction-threshold", 2.0));
        physics.setDamageScale(config.getDouble("blast-damage-scale", 0.5));
        physics.setOcclusionAttenuation(config.getDouble("blast-occlusion-attenuation", 0.25));
        physics.setDebrisImpactScale(config.getDouble("debris-impact-scale", 0.3));
        physics.setMinImpactDrop(config.getInt("min-impact-drop", 2));

        // Kinetic impact (projectile / ram)
        physics.setImpactPenetrationCost(config.getDouble("impact.penetration-cost", 4.0));
        physics.setImpactDamageScale(config.getDouble("impact.damage-scale", 1.0));
        physics.setImpactMaxPenetration(config.getInt("impact.max-penetration", 6));

        // Fire (heat) degradation rates
        physics.setFireDamagePerTick(config.getDouble("fire.damage-per-tick", 0.0006));
        physics.setFireRadiantFactor(config.getDouble("fire.radiant-factor", 0.25));

        // Temperature-based strength (off by default — see config.yml)
        physics.setTemperatureStrengthEnabled(config.getBoolean("temperature-strength.enabled", false));
        physics.setComfortTemperatureC(config.getDouble("temperature-strength.comfort-temperature-c", 20.0));
        physics.setThermalShockOnsetC(config.getDouble("temperature-strength.shock-onset-c", 150.0));
        physics.setThermalShockSpanC(config.getDouble("temperature-strength.shock-span-c", 500.0));

        // Crack visuals (presentation-only distress thresholds)
        physics.setCrackHairlineThreshold(config.getDouble("effects.crack-hairline-threshold", 0.60));
        physics.setCrackCrackedThreshold(config.getDouble("effects.crack-cracked-threshold", 0.78));
        physics.setCrackCrumblingThreshold(config.getDouble("effects.crack-crumbling-threshold", 0.90));

        // Rubble physics
        physics.setRubbleEnabled(config.getBoolean("rubble-enabled", false));
        physics.setRubbleFallDamageFactor(config.getDouble("rubble-fall-damage-factor", 0.5));
        physics.setRubbleBaseChance(config.getDouble("rubble-base-chance", 1.0));
        physics.setRubbleMinChance(config.getDouble("rubble-min-chance", 0.1));

        return physics;
    }

    /**
     * Load effects settings from config.yml
     */
    public EffectsConfig loadEffectsConfig() {
        EffectsConfig effects = new EffectsConfig();

        // Collapse timing
        effects.setCollapseDelayTicks(config.getInt("effects.collapse-delay-ticks", 25));
        effects.setExplosionCollapseDelayTicks(config.getInt("effects.explosion-collapse-delay-ticks", 4));
        effects.setMaxCollapsesPerTick(config.getInt("effects.max-collapses-per-tick", 25));
        effects.setTpsFloor(config.getDouble("effects.tps-floor", 18.0));
        effects.setMinCollapsesPerTick(config.getInt("effects.min-collapses-per-tick", 4));
        effects.setMaxCollapseEffectsPerTick(config.getInt("effects.max-collapse-effects-per-tick", 8));

        // Screen shake
        effects.setScreenShakeEnabled(config.getBoolean("effects.screen-shake-enabled", true));
        effects.setScreenShakeThreshold(config.getInt("effects.screen-shake-threshold", 15));
        effects.setScreenShakeRadius(config.getDouble("effects.screen-shake-radius", 32.0));

        // Dust clouds
        effects.setDustCloudsEnabled(config.getBoolean("effects.dust-clouds-enabled", true));
        effects.setDustMultiplier(config.getDouble("effects.dust-multiplier", 1.0));
        effects.setDustWaveEnabled(config.getBoolean("effects.dust-wave-enabled", true));
        effects.setDustWaveMaxRadius(config.getDouble("effects.dust-wave-max-radius", 15.0));

        // Stress particles
        effects.setStressCautionThreshold(config.getDouble("effects.stress-caution-threshold", 0.50));
        effects.setStressDangerThreshold(config.getDouble("effects.stress-danger-threshold", 0.80));
        effects.setStressCriticalThreshold(config.getDouble("effects.stress-critical-threshold", 0.95));
        effects.setStressParticleSize((float) config.getDouble("effects.stress-particle-size", 0.7));
        effects.setEscalatingStressAudioEnabled(config.getBoolean("effects.escalating-stress-audio", true));
        effects.setStressAudioVolume((float) config.getDouble("effects.stress-audio-volume", 0.6));

        // Cracking warnings
        effects.setCrackingWarningsEnabled(config.getBoolean("effects.cracking-warnings-enabled", true));
        // Clamp to >= 1: DelayedCollapseManager.run() does `elapsed % interval`, so a
        // configured 0 would throw ArithmeticException every tick a pending block is still
        // in its warning window — aborting that tick's collapses and spamming the log.
        effects.setCrackingWarningInterval(Math.max(1, config.getInt("effects.cracking-warning-interval", 5)));
        effects.setMaxCrackingWarningsPerTick(config.getInt("effects.max-cracking-warnings-per-tick", 6));

        // Damage cracks
        effects.setCracksEnabled(config.getBoolean("effects.cracks-enabled", true));
        effects.setMinVisibleDamage(config.getDouble("effects.min-visible-damage", 0.05));
        effects.setDamageViewDistance(config.getDouble("effects.damage-view-distance", 32.0));
        effects.setStressCracksEnabled(config.getBoolean("effects.stress-cracks-enabled", true));
        effects.setImpactFeedbackEnabled(config.getBoolean("effects.impact-feedback", true));

        // Explosion effects
        effects.setExplosionNotifyRadius(config.getDouble("effects.explosion-notify-radius", 64.0));
        effects.setMaxDebrisPerExplosion(config.getInt("effects.max-debris-per-explosion", 200));

        // Actionbar warnings
        effects.setCriticalStressWarningThreshold(config.getDouble("effects.critical-stress-warning-threshold", 0.90));

        // Live stress summary (off by default so existing servers aren't surprised)
        effects.setStressSummaryEnabled(config.getBoolean("effects.stress-summary-enabled", false));
        effects.setStressSummaryIntervalTicks(config.getInt("effects.stress-summary-interval-ticks", 10));
        // Near-miss notification
        effects.setNearMissNotificationEnabled(config.getBoolean("effects.near-miss-notification-enabled", true));
        effects.setNearMissThreshold(config.getDouble("effects.near-miss-threshold", 0.98));

        // Rubble
        effects.setRubbleEnabled(config.getBoolean("effects.rubble-enabled", false));
        effects.setReturnCollapsedBlocks(config.getBoolean("effects.return-collapsed-blocks", false));
        effects.setRubbleGroundOffset(config.getInt("effects.rubble-ground-offset", 0));

        // Collapse notifications (chat)
        effects.setBigCollapseBroadcastEnabled(config.getBoolean("effects.big-collapse-broadcast-enabled", true));
        effects.setBigCollapseBroadcastThreshold(config.getInt("effects.big-collapse-broadcast-threshold", 15));
        effects.setFirstCollapseHintEnabled(config.getBoolean("effects.first-collapse-hint-enabled", true));
        // Undermining presentation (dig-under-a-wall backfill)
        effects.setUndermineBackfillRubble(config.getBoolean("effects.undermine.backfill-rubble", true));
        effects.setMaxRubblePerCollapse(config.getInt(
                "effects.undermine.max-rubble-per-collapse", config.getInt("effects.max-debris-per-explosion", 200)));

        return effects;
    }

    /**
     * Load foundation/terrain-grounding settings from config.yml.
     *
     * <p>Defaults preserve legacy behaviour: depth grounding is OFF and no
     * foundation block is set, so an existing install grounds builds exactly as
     * before (bedrock + world floor only). Admins opt in by enabling depth
     * grounding and/or naming a foundation block.
     */
    public FoundationConfig loadFoundationConfig() {
        FoundationConfig foundation = new FoundationConfig();
        foundation.setDepthGroundingEnabled(config.getBoolean("foundation.depth-grounding-enabled", false));
        foundation.setMinDepth(config.getInt("foundation.min-depth", 4));

        String blockName = config.getString("foundation.foundation-block", "");
        if (blockName != null && !blockName.isBlank()) {
            Material block = Material.matchMaterial(blockName.trim());
            if (block != null) {
                foundation.setFoundationBlock(block);
            } else {
                logger.warning("foundation.foundation-block: unknown block '" + blockName + "' - no anchor block set.");
            }
        }
        return foundation;
    }

    /**
     * Load region/protection settings from config.yml
     */
    public RegionConfig loadRegionConfig() {
        RegionConfig region = new RegionConfig();
        region.setEnabled(config.getBoolean("regions.enabled", true));
        region.setDisabledWorlds(config.getStringList("regions.disabled-worlds"));
        region.setRespectWorldGuard(config.getBoolean("regions.respect-worldguard", true));
        region.setCoreProtectLogging(config.getBoolean("logging.coreprotect", true));

        region.setWarZoneEnabled(config.getBoolean("regions.war-zone.enabled", false));
        region.setWarZoneProviders(config.getStringList("regions.war-zone.providers"));
        region.setWarZoneAllowWilderness(config.getBoolean("regions.war-zone.allow-wilderness", false));

        return region;
    }

    /**
     * Load persistence settings from config.yml
     */
    public PersistenceConfig loadPersistenceConfig() {
        PersistenceConfig persistence = new PersistenceConfig();
        persistence.setEnabled(config.getBoolean("persistence.enabled", true));

        String typeStr = config.getString("persistence.type", "file").toUpperCase(Locale.ROOT);
        try {
            persistence.setType(PersistenceConfig.StorageType.valueOf(typeStr));
        } catch (IllegalArgumentException e) {
            logger.warning("Unknown persistence type '" + typeStr + "', using FILE");
            persistence.setType(PersistenceConfig.StorageType.FILE);
        }

        persistence.setAutoSaveIntervalSeconds(config.getInt("persistence.auto-save-interval", 300));
        persistence.setApiUrl(config.getString("persistence.api.url", "http://localhost:8080"));
        persistence.setApiKey(config.getString("persistence.api.api-key", ""));
        persistence.setApiTimeoutSeconds(config.getInt("persistence.api.timeout", 30));

        return persistence;
    }

    /**
     * Load component memory-eviction settings from config.yml. Disabled by default — new
     * machinery that admins opt into for long-run memory bounding (SCALING.md §5).
     */
    public MemoryEvictionConfig loadMemoryEvictionConfig() {
        MemoryEvictionConfig eviction = new MemoryEvictionConfig();
        eviction.setEnabled(config.getBoolean("memory.eviction.enabled", false));
        eviction.setGraceTicks(config.getInt("memory.eviction.grace-ticks", 200));
        return eviction;
    }

    /**
     * Load recording settings from config.yml.
     */
    public RecordingConfig loadRecordingConfig() {
        RecordingConfig recording = new RecordingConfig();
        // Default false (matches the bundled config.yml AND the field default):
        // configs written before the recording section existed must not start a
        // boot session that blocks host plugins' scoped recordings.
        recording.setAutoRecord(config.getBoolean("recording.auto-record", false));
        recording.setBufferSize(config.getInt("recording.buffer-size", 100));
        recording.setMaxSessions(config.getInt("recording.max-sessions", 20));
        recording.setIncludeStressUpdates(config.getBoolean("recording.include-stress-updates", false));
        recording.setMaxEventsPerTick(config.getInt("recording.max-events-per-tick", 50));
        recording.setAsyncWrite(config.getBoolean("recording.async-write", true));
        recording.setCaptureStress(config.getBoolean("recording.capture-stress", false));
        recording.setBinaryFormat(config.getBoolean("recording.binary-format", true));

        return recording;
    }

    /**
     * Load metrics overlay settings from config.yml
     */
    public MetricsConfig loadMetricsConfig() {
        MetricsConfig metrics = new MetricsConfig();
        metrics.setEnabled(config.getBoolean("metrics-overlay.enabled", true));
        metrics.setUpdateIntervalTicks(config.getInt("metrics-overlay.update-interval-ticks", 5));

        return metrics;
    }

    /**
     * Load entity weight configuration from config.yml.
     */
    public EntityWeightTask.EntityWeightConfig loadEntityWeightConfig() {
        return new EntityWeightTask.EntityWeightConfig(
                config.getBoolean("entity-weight.enabled", true),
                config.getInt("entity-weight.scan-interval-ticks", 10),
                config.getDouble("entity-weight.stress-threshold", 0.7),
                config.getDouble("entity-weight.damage-threshold", 0.5),
                config.getBoolean("entity-weight.standing.enabled", true),
                config.getBoolean("entity-weight.fall-impact.enabled", true),
                config.getDouble("entity-weight.fall-impact.energy-scale", 1.0),
                config.getDouble("entity-weight.fall-impact.min-fall-distance", 2.0),
                config.getDouble("entity-weight.fall-impact.force-impact-distance", 15.0));
    }

    /**
     * Load heavy-container weight configuration from config.yml.
     */
    public ContainerWeightTask.ContainerWeightConfig loadContainerWeightConfig() {
        return new ContainerWeightTask.ContainerWeightConfig(
                config.getBoolean("container-weight.enabled", false),
                config.getInt("container-weight.scan-interval-ticks", 20),
                config.getDouble("container-weight.base-mass", 1.0),
                config.getDouble("container-weight.content-weight", 8.0),
                config.getDouble("container-weight.stress-threshold", 0.7),
                config.getDouble("container-weight.damage-threshold", 0.5));
    }

    /**
     * Load weather configuration from config.yml.
     */
    public WeatherLoadTask.WeatherConfig loadWeatherConfig() {
        return new WeatherLoadTask.WeatherConfig(
                config.getBoolean("weather.enabled", true),
                config.getInt("weather.scan-interval-ticks", 40),
                config.getBoolean("weather.require-sky-access", true),
                config.getBoolean("weather.rain.enabled", true),
                config.getDouble("weather.rain.capacity-multiplier", 0.95),
                config.getBoolean("weather.thunder.enabled", true),
                config.getDouble("weather.thunder.capacity-multiplier", 0.88),
                config.getBoolean("weather.thunder.stress-spikes.enabled", true),
                config.getDouble("weather.thunder.stress-spikes.chance", 0.02),
                config.getDouble("weather.thunder.stress-spikes.amount", 0.15),
                config.getBoolean("weather.snow.enabled", true),
                config.getDouble("weather.snow.load-per-scan", 0.005),
                config.getDouble("weather.snow.max-load", 0.3),
                config.getDouble("weather.snow.decay-per-scan", 0.002),
                config.getDouble("weather.tick-budget-ms", 10.0));
    }

    /**
     * Apply admin material overrides from the {@code materials:} config section
     * on top of the built-in defaults. Each entry may set any of mass,
     * max-load, blast-resistance, fire-resistance; unset axes keep their
     * default. The special key {@code default} tunes unregistered blocks.
     */
    public void loadMaterialOverrides(MaterialRegistry registry) {
        ConfigurationSection section = config.getConfigurationSection("materials");
        if (section == null) {
            return;
        }
        int applied = 0;
        for (String key : section.getKeys(false)) {
            ConfigurationSection mat = section.getConfigurationSection(key);
            if (mat == null) {
                continue;
            }
            Double mass = mat.isSet("mass") ? mat.getDouble("mass") : null;
            Double maxLoad = mat.isSet("max-load") ? mat.getDouble("max-load") : null;
            Double blast = mat.isSet("blast-resistance") ? mat.getDouble("blast-resistance") : null;
            Double fire = mat.isSet("fire-resistance") ? mat.getDouble("fire-resistance") : null;
            try {
                if (key.equalsIgnoreCase("default")) {
                    MaterialSpec base = registry.getDefault();
                    registry.setDefault(new MaterialSpec(
                            mass != null ? mass : base.mass(),
                            maxLoad != null ? maxLoad : base.maxLoad(),
                            blast != null ? blast : base.blastResistance(),
                            fire != null ? fire : base.fireResistance()));
                    applied++;
                    continue;
                }
                Material material = Material.matchMaterial(key);
                if (material == null) {
                    logger.warning("materials: unknown block '" + key + "' - skipped.");
                    continue;
                }
                registry.applyOverride(material, mass, maxLoad, blast, fire);
                applied++;
            } catch (IllegalArgumentException e) {
                logger.warning("materials: invalid values for '" + key + "' (" + e.getMessage() + ") - skipped.");
            }
        }
        if (applied > 0) {
            logger.info("Applied " + applied + " material override(s) from config.");
        }
    }

    /**
     * Load entity mass overrides from config.yml into the registry.
     */
    public void loadEntityMassConfig(EntityMassRegistry registry) {
        ConfigurationSection section = config.getConfigurationSection("entity-weight.standing.mass");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            if (key.equalsIgnoreCase("default")) {
                registry.setDefaultMass(section.getDouble(key));
                continue;
            }
            try {
                EntityType type = EntityType.valueOf(key.toUpperCase(Locale.ROOT));
                registry.setMass(type, section.getDouble(key));
            } catch (IllegalArgumentException e) {
                logger.warning("entity-weight.standing.mass: unknown entity '" + key + "' - skipped.");
            }
        }
    }
}
