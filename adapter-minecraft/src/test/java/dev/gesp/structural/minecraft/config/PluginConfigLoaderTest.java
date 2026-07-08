package dev.gesp.structural.minecraft.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.minecraft.entity.EntityMassRegistry;
import dev.gesp.structural.minecraft.entity.EntityWeightTask;
import dev.gesp.structural.minecraft.material.MaterialRegistry;
import dev.gesp.structural.minecraft.recording.MetricsConfig;
import dev.gesp.structural.minecraft.recording.RecordingConfig;
import dev.gesp.structural.minecraft.weather.WeatherLoadTask;
import dev.gesp.structural.model.MaterialSpec;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

/**
 * Equivalence guard for {@link PluginConfigLoader}.
 *
 * <p>The {@code loadXConfig} bodies were lifted verbatim out of
 * {@code StructuralIntegrityPlugin}. This test pins every parsed field twice:
 * once against a YAML that sets each key to a known NON-default value (proving
 * the right key is read into the right field), and once against an EMPTY YAML
 * (proving the code fallback/default is unchanged). Together those assert the
 * refactor changed no key, default, or fallback.
 */
class PluginConfigLoaderTest {

    private static final Logger LOGGER = Logger.getLogger(PluginConfigLoaderTest.class.getName());
    private static final double EPS = 1e-9;

    private static YamlConfiguration empty() {
        return new YamlConfiguration();
    }

    private static YamlConfiguration yaml(String body) {
        YamlConfiguration cfg = new YamlConfiguration();
        try {
            cfg.loadFromString(body);
        } catch (InvalidConfigurationException e) {
            throw new RuntimeException(e);
        }
        return cfg;
    }

    // ---------------------------------------------------------------- locale safety

    @Test
    void memoryEvictionDefaultsToDisabled() {
        MemoryEvictionConfig m = new PluginConfigLoader(empty(), LOGGER).loadMemoryEvictionConfig();
        assertFalse(m.isEnabled(), "eviction ships off");
        assertEquals(200, m.getGraceTicks(), "default grace is 200 ticks");
    }

    @Test
    void memoryEvictionReadsConfiguredValues() {
        MemoryEvictionConfig m = new PluginConfigLoader(
                        yaml("memory:\n  eviction:\n    enabled: true\n    grace-ticks: 40\n"), LOGGER)
                .loadMemoryEvictionConfig();
        assertTrue(m.isEnabled());
        assertEquals(40, m.getGraceTicks());
    }

    @Test
    void persistenceTypeParsesIndependentlyOfTheJvmLocale() {
        Locale saved = Locale.getDefault();
        try {
            // Turkish maps 'i'->'İ' under default-locale toUpperCase, so "api" would become
            // "APİ" and StorageType.valueOf would throw, silently downgrading API to FILE.
            Locale.setDefault(Locale.forLanguageTag("tr"));
            PersistenceConfig p =
                    new PluginConfigLoader(yaml("persistence:\n  type: api\n"), LOGGER).loadPersistenceConfig();
            assertEquals(
                    PersistenceConfig.StorageType.API,
                    p.getType(),
                    "persistence.type: api stays API even on a Turkish-locale JVM");
        } finally {
            Locale.setDefault(saved);
        }
    }

    // ---------------------------------------------------------------- physics

    @Test
    void physicsDefaults() {
        PhysicsConfig p = new PluginConfigLoader(empty(), LOGGER).loadPhysicsConfig();
        assertEquals(1.0, p.getMomentMultiplier(), EPS);
        assertEquals(1.0, p.getBeamMomentReduction(), EPS);
        assertTrue(p.isBendingDepthEnabled(), "the Minecraft adapter enables depth² bending by default");
        assertEquals(50, p.getMaxCascadeSteps());
        assertEquals(10, p.getVisualUpdateTicks());
        assertTrue(p.isPreCollapseShake());
        assertFalse(p.isDebugLogging());
        assertEquals(1.5, p.getBlastRadiusPerPower(), EPS);
        assertEquals(2.0, p.getDestructionThreshold(), EPS);
        assertEquals(0.5, p.getDamageScale(), EPS);
        assertEquals(0.25, p.getOcclusionAttenuation(), EPS);
        assertEquals(0.3, p.getDebrisImpactScale(), EPS);
        assertEquals(2, p.getMinImpactDrop());
        assertEquals(4.0, p.getImpactPenetrationCost(), EPS);
        assertEquals(1.0, p.getImpactDamageScale(), EPS);
        assertEquals(6, p.getImpactMaxPenetration());
        assertEquals(0.0006, p.getFireDamagePerTick(), EPS);
        assertEquals(0.25, p.getFireRadiantFactor(), EPS);
        assertEquals(0.60, p.getCrackHairlineThreshold(), EPS);
        assertEquals(0.78, p.getCrackCrackedThreshold(), EPS);
        assertEquals(0.90, p.getCrackCrumblingThreshold(), EPS);
        assertFalse(p.isRubbleEnabled());
        assertEquals(0.5, p.getRubbleFallDamageFactor(), EPS);
        assertEquals(1.0, p.getRubbleBaseChance(), EPS);
        assertEquals(0.1, p.getRubbleMinChance(), EPS);
    }

    @Test
    void physicsKnownValues() {
        PhysicsConfig p = new PluginConfigLoader(
                        yaml(
                                """
                moment-multiplier: 2.5
                beam-moment-reduction: 0.4
                bending-depth-enabled: false
                max-cascade-steps: 77
                visual-update-ticks: 3
                pre-collapse-shake: false
                debug-logging: true
                blast-radius-per-power: 9.0
                blast-destruction-threshold: 8.0
                blast-damage-scale: 7.0
                blast-occlusion-attenuation: 0.75
                debris-impact-scale: 0.9
                min-impact-drop: 5
                impact:
                  penetration-cost: 1.25
                  damage-scale: 3.5
                  max-penetration: 11
                fire:
                  damage-per-tick: 0.01
                  radiant-factor: 0.66
                effects:
                  crack-hairline-threshold: 0.11
                  crack-cracked-threshold: 0.22
                  crack-crumbling-threshold: 0.33
                rubble-enabled: true
                rubble-fall-damage-factor: 0.8
                rubble-base-chance: 0.6
                rubble-min-chance: 0.05
                """),
                        LOGGER)
                .loadPhysicsConfig();
        assertEquals(2.5, p.getMomentMultiplier(), EPS);
        assertEquals(0.4, p.getBeamMomentReduction(), EPS);
        assertFalse(p.isBendingDepthEnabled(), "bending-depth-enabled: false is honoured");
        assertEquals(77, p.getMaxCascadeSteps());
        assertEquals(3, p.getVisualUpdateTicks());
        assertFalse(p.isPreCollapseShake());
        assertTrue(p.isDebugLogging());
        assertEquals(9.0, p.getBlastRadiusPerPower(), EPS);
        assertEquals(8.0, p.getDestructionThreshold(), EPS);
        assertEquals(7.0, p.getDamageScale(), EPS);
        assertEquals(0.75, p.getOcclusionAttenuation(), EPS);
        assertEquals(0.9, p.getDebrisImpactScale(), EPS);
        assertEquals(5, p.getMinImpactDrop());
        assertEquals(1.25, p.getImpactPenetrationCost(), EPS);
        assertEquals(3.5, p.getImpactDamageScale(), EPS);
        assertEquals(11, p.getImpactMaxPenetration());
        assertEquals(0.01, p.getFireDamagePerTick(), EPS);
        assertEquals(0.66, p.getFireRadiantFactor(), EPS);
        assertEquals(0.11, p.getCrackHairlineThreshold(), EPS);
        assertEquals(0.22, p.getCrackCrackedThreshold(), EPS);
        assertEquals(0.33, p.getCrackCrumblingThreshold(), EPS);
        assertTrue(p.isRubbleEnabled());
        assertEquals(0.8, p.getRubbleFallDamageFactor(), EPS);
        assertEquals(0.6, p.getRubbleBaseChance(), EPS);
        assertEquals(0.05, p.getRubbleMinChance(), EPS);
    }

    // ---------------------------------------------------------------- effects

    @Test
    void effectsDefaults() {
        EffectsConfig e = new PluginConfigLoader(empty(), LOGGER).loadEffectsConfig();
        assertEquals(25, e.getCollapseDelayTicks());
        assertEquals(4, e.getExplosionCollapseDelayTicks());
        assertEquals(25, e.getMaxCollapsesPerTick());
        assertTrue(e.isScreenShakeEnabled());
        assertEquals(15, e.getScreenShakeThreshold());
        assertEquals(32.0, e.getScreenShakeRadius(), EPS);
        assertTrue(e.isDustCloudsEnabled());
        assertEquals(1.0, e.getDustMultiplier(), EPS);
        assertTrue(e.isDustWaveEnabled());
        assertEquals(15.0, e.getDustWaveMaxRadius(), EPS);
        assertEquals(0.50, e.getStressCautionThreshold(), EPS);
        assertEquals(0.80, e.getStressDangerThreshold(), EPS);
        assertEquals(0.95, e.getStressCriticalThreshold(), EPS);
        assertEquals(0.7f, e.getStressParticleSize(), EPS);
        assertTrue(e.isEscalatingStressAudioEnabled());
        assertTrue(e.isCrackingWarningsEnabled());
        assertEquals(5, e.getCrackingWarningInterval());
        assertEquals(6, e.getMaxCrackingWarningsPerTick());
        assertTrue(e.isCracksEnabled());
        assertEquals(0.05, e.getMinVisibleDamage(), EPS);
        assertEquals(32.0, e.getDamageViewDistance(), EPS);
        assertTrue(e.isStressCracksEnabled());
        assertTrue(e.isImpactFeedbackEnabled());
        assertEquals(64.0, e.getExplosionNotifyRadius(), EPS);
        assertEquals(200, e.getMaxDebrisPerExplosion());
        assertEquals(0.90, e.getCriticalStressWarningThreshold(), EPS);
        assertTrue(e.isNearMissNotificationEnabled());
        assertEquals(0.98, e.getNearMissThreshold(), EPS);
        assertFalse(e.isRubbleEnabled());
        assertFalse(e.isReturnCollapsedBlocks());
        assertEquals(0, e.getRubbleGroundOffset());
        assertTrue(e.isBigCollapseBroadcastEnabled());
        assertEquals(15, e.getBigCollapseBroadcastThreshold());
        assertTrue(e.isFirstCollapseHintEnabled());
        assertTrue(e.isUndermineBackfillRubble());
        // No undermine cap set → falls back to the explosion debris budget (200).
        assertEquals(200, e.getMaxRubblePerCollapse());
    }

    @Test
    void crackingWarningIntervalClampedToOne() {
        // 0 would make DelayedCollapseManager.run() do `elapsed % 0` and throw every tick;
        // the loader clamps it to 1 (a warning every tick) so the divisor is never zero.
        EffectsConfig e =
                new PluginConfigLoader(yaml("effects:\n  cracking-warning-interval: 0\n"), LOGGER).loadEffectsConfig();
        assertEquals(1, e.getCrackingWarningInterval(), "interval 0 is clamped to 1 (no divide-by-zero)");
    }

    @Test
    void effectsKnownValues() {
        EffectsConfig e = new PluginConfigLoader(
                        yaml(
                                """
                effects:
                  collapse-delay-ticks: 1
                  explosion-collapse-delay-ticks: 2
                  max-collapses-per-tick: 3
                  screen-shake-enabled: false
                  screen-shake-threshold: 99
                  screen-shake-radius: 12.5
                  dust-clouds-enabled: false
                  dust-multiplier: 4.0
                  dust-wave-enabled: false
                  dust-wave-max-radius: 5.0
                  stress-caution-threshold: 0.11
                  stress-danger-threshold: 0.22
                  stress-critical-threshold: 0.33
                  stress-particle-size: 1.5
                  escalating-stress-audio: false
                  cracking-warnings-enabled: false
                  cracking-warning-interval: 9
                  max-cracking-warnings-per-tick: 8
                  cracks-enabled: false
                  min-visible-damage: 0.42
                  damage-view-distance: 10.0
                  stress-cracks-enabled: false
                  impact-feedback: false
                  explosion-notify-radius: 7.0
                  max-debris-per-explosion: 13
                  critical-stress-warning-threshold: 0.41
                  near-miss-notification-enabled: false
                  near-miss-threshold: 1.25
                  rubble-enabled: true
                  return-collapsed-blocks: true
                  rubble-ground-offset: 6
                  big-collapse-broadcast-enabled: false
                  big-collapse-broadcast-threshold: 42
                  first-collapse-hint-enabled: false
                  undermine:
                    backfill-rubble: false
                """),
                        LOGGER)
                .loadEffectsConfig();
        assertEquals(1, e.getCollapseDelayTicks());
        assertEquals(2, e.getExplosionCollapseDelayTicks());
        assertEquals(3, e.getMaxCollapsesPerTick());
        assertFalse(e.isScreenShakeEnabled());
        assertEquals(99, e.getScreenShakeThreshold());
        assertEquals(12.5, e.getScreenShakeRadius(), EPS);
        assertFalse(e.isDustCloudsEnabled());
        assertEquals(4.0, e.getDustMultiplier(), EPS);
        assertFalse(e.isDustWaveEnabled());
        assertEquals(5.0, e.getDustWaveMaxRadius(), EPS);
        assertEquals(0.11, e.getStressCautionThreshold(), EPS);
        assertEquals(0.22, e.getStressDangerThreshold(), EPS);
        assertEquals(0.33, e.getStressCriticalThreshold(), EPS);
        assertEquals(1.5f, e.getStressParticleSize(), EPS);
        assertFalse(e.isEscalatingStressAudioEnabled());
        assertFalse(e.isCrackingWarningsEnabled());
        assertEquals(9, e.getCrackingWarningInterval());
        assertEquals(8, e.getMaxCrackingWarningsPerTick());
        assertFalse(e.isCracksEnabled());
        assertEquals(0.42, e.getMinVisibleDamage(), EPS);
        assertEquals(10.0, e.getDamageViewDistance(), EPS);
        assertFalse(e.isStressCracksEnabled());
        assertFalse(e.isImpactFeedbackEnabled());
        assertEquals(7.0, e.getExplosionNotifyRadius(), EPS);
        assertEquals(13, e.getMaxDebrisPerExplosion());
        assertEquals(0.41, e.getCriticalStressWarningThreshold(), EPS);
        assertFalse(e.isNearMissNotificationEnabled());
        assertEquals(1.25, e.getNearMissThreshold(), EPS);
        assertTrue(e.isRubbleEnabled());
        assertTrue(e.isReturnCollapsedBlocks());
        assertEquals(6, e.getRubbleGroundOffset());
        assertFalse(e.isBigCollapseBroadcastEnabled());
        assertEquals(42, e.getBigCollapseBroadcastThreshold());
        assertFalse(e.isFirstCollapseHintEnabled());
        assertFalse(e.isUndermineBackfillRubble());
        // The undermine cap is unset here, so it falls back to max-debris-per-explosion (13).
        assertEquals(13, e.getMaxRubblePerCollapse());
    }

    @Test
    void undermineCapOverridesDebrisFallback() {
        // An explicit undermine cap wins over the max-debris-per-explosion fallback.
        EffectsConfig e = new PluginConfigLoader(
                        yaml(
                                """
                effects:
                  max-debris-per-explosion: 13
                  undermine:
                    backfill-rubble: true
                    max-rubble-per-collapse: 42
                """),
                        LOGGER)
                .loadEffectsConfig();
        assertTrue(e.isUndermineBackfillRubble());
        assertEquals(42, e.getMaxRubblePerCollapse());
    }

    // ----------------------------------------------------------------- region

    @Test
    void regionDefaults() {
        RegionConfig r = new PluginConfigLoader(empty(), LOGGER).loadRegionConfig();
        assertTrue(r.isEnabled());
        assertTrue(r.getDisabledWorlds().isEmpty());
        assertTrue(r.isRespectWorldGuard());
        assertTrue(r.isCoreProtectLogging());
        assertFalse(r.isWarZoneEnabled());
        assertTrue(r.getWarZoneProviders().isEmpty());
        assertFalse(r.isWarZoneAllowWilderness());
    }

    @Test
    void regionKnownValues() {
        RegionConfig r = new PluginConfigLoader(
                        yaml(
                                """
                regions:
                  enabled: false
                  disabled-worlds:
                    - Nether
                    - End
                  respect-worldguard: false
                  war-zone:
                    enabled: true
                    providers:
                      - Towny
                    allow-wilderness: true
                logging:
                  coreprotect: false
                """),
                        LOGGER)
                .loadRegionConfig();
        assertFalse(r.isEnabled());
        // setDisabledWorlds lower-cases names for case-insensitive matching.
        assertEquals(Set.of("nether", "end"), r.getDisabledWorlds());
        assertFalse(r.isRespectWorldGuard());
        assertFalse(r.isCoreProtectLogging());
        assertTrue(r.isWarZoneEnabled());
        assertEquals(Set.of("towny"), r.getWarZoneProviders());
        assertTrue(r.isWarZoneAllowWilderness());
    }

    // ------------------------------------------------------------- foundation

    @Test
    void foundationDefaults() {
        FoundationConfig f = new PluginConfigLoader(empty(), LOGGER).loadFoundationConfig();
        // Defaults MUST preserve legacy behaviour: depth grounding off, no block.
        assertFalse(f.isDepthGroundingEnabled());
        assertEquals(4, f.getMinDepth());
        assertFalse(f.hasFoundationBlock());
    }

    @Test
    void foundationKnownValues() {
        FoundationConfig f = new PluginConfigLoader(
                        yaml(
                                """
                foundation:
                  depth-grounding-enabled: true
                  min-depth: 6
                  foundation-block: BRICKS
                """),
                        LOGGER)
                .loadFoundationConfig();
        assertTrue(f.isDepthGroundingEnabled());
        assertEquals(6, f.getMinDepth());
        assertTrue(f.hasFoundationBlock());
        assertEquals(Material.BRICKS, f.getFoundationBlock());
        assertTrue(f.isFoundationBlock(Material.BRICKS));
        assertFalse(f.isFoundationBlock(Material.STONE));
    }

    @Test
    void foundationUnknownBlockIsIgnored() {
        FoundationConfig f = new PluginConfigLoader(
                        yaml(
                                """
                foundation:
                  foundation-block: NOT_A_REAL_BLOCK
                """),
                        LOGGER)
                .loadFoundationConfig();
        // Unknown material name -> no anchor block set (logged a warning).
        assertFalse(f.hasFoundationBlock());
    }

    @Test
    void foundationBlankBlockIsNoBlock() {
        FoundationConfig f = new PluginConfigLoader(
                        yaml(
                                """
                foundation:
                  foundation-block: ""
                """),
                        LOGGER)
                .loadFoundationConfig();
        assertFalse(f.hasFoundationBlock());
    }

    @Test
    void foundationMinDepthClampedToAtLeastOne() {
        FoundationConfig f = new PluginConfigLoader(
                        yaml("""
                foundation:
                  min-depth: 0
                """),
                        LOGGER)
                .loadFoundationConfig();
        // A depth of 0 would ground everything; the setter clamps it up to 1.
        assertEquals(1, f.getMinDepth());
    }

    // ------------------------------------------------------------ persistence

    @Test
    void persistenceDefaults() {
        PersistenceConfig p = new PluginConfigLoader(empty(), LOGGER).loadPersistenceConfig();
        assertTrue(p.isEnabled());
        assertEquals(PersistenceConfig.StorageType.FILE, p.getType());
        assertEquals(300, p.getAutoSaveIntervalSeconds());
        assertEquals("http://localhost:8080", p.getApiUrl());
        assertEquals("", p.getApiKey());
        assertEquals(30, p.getApiTimeoutSeconds());
    }

    @Test
    void persistenceKnownValues() {
        PersistenceConfig p = new PluginConfigLoader(
                        yaml(
                                """
                persistence:
                  enabled: false
                  type: api
                  auto-save-interval: 60
                  api:
                    url: http://example.test:9000
                    api-key: secret-key
                    timeout: 5
                """),
                        LOGGER)
                .loadPersistenceConfig();
        assertFalse(p.isEnabled());
        assertEquals(PersistenceConfig.StorageType.API, p.getType());
        assertEquals(60, p.getAutoSaveIntervalSeconds());
        assertEquals("http://example.test:9000", p.getApiUrl());
        assertEquals("secret-key", p.getApiKey());
        assertEquals(5, p.getApiTimeoutSeconds());
    }

    @Test
    void persistenceUnknownTypeFallsBackToFile() {
        PersistenceConfig p =
                new PluginConfigLoader(yaml("persistence:\n  type: bogus\n"), LOGGER).loadPersistenceConfig();
        assertEquals(PersistenceConfig.StorageType.FILE, p.getType());
    }

    // -------------------------------------------------------------- recording

    @Test
    void recordingDefaults() {
        RecordingConfig r = new PluginConfigLoader(empty(), LOGGER).loadRecordingConfig();
        assertFalse(r.isAutoRecord());
        assertEquals(100, r.getBufferSize());
        assertEquals(20, r.getMaxSessions());
        assertFalse(r.isIncludeStressUpdates());
        assertEquals(50, r.getMaxEventsPerTick());
        assertTrue(r.isAsyncWrite());
    }

    @Test
    void recordingKnownValues() {
        RecordingConfig r = new PluginConfigLoader(
                        yaml(
                                """
                recording:
                  auto-record: true
                  buffer-size: 77
                  max-sessions: 3
                  include-stress-updates: true
                  max-events-per-tick: 9
                  async-write: false
                """),
                        LOGGER)
                .loadRecordingConfig();
        assertTrue(r.isAutoRecord());
        // RecordingConfig.setBufferSize clamps to >= 10; 77 is above the floor so
        // it proves the buffer-size key flows through unchanged.
        assertEquals(77, r.getBufferSize());
        assertEquals(3, r.getMaxSessions());
        assertTrue(r.isIncludeStressUpdates());
        assertEquals(9, r.getMaxEventsPerTick());
        assertFalse(r.isAsyncWrite());
    }

    // ---------------------------------------------------------------- metrics

    @Test
    void metricsDefaults() {
        MetricsConfig m = new PluginConfigLoader(empty(), LOGGER).loadMetricsConfig();
        assertTrue(m.isEnabled());
        assertEquals(5, m.getUpdateIntervalTicks());
    }

    @Test
    void metricsKnownValues() {
        MetricsConfig m = new PluginConfigLoader(
                        yaml(
                                """
                metrics-overlay:
                  enabled: false
                  update-interval-ticks: 17
                """),
                        LOGGER)
                .loadMetricsConfig();
        assertFalse(m.isEnabled());
        assertEquals(17, m.getUpdateIntervalTicks());
    }

    // ---------------------------------------------------------- entity weight

    @Test
    void entityWeightDefaults() {
        EntityWeightTask.EntityWeightConfig c = new PluginConfigLoader(empty(), LOGGER).loadEntityWeightConfig();
        assertTrue(c.enabled());
        assertEquals(10, c.scanIntervalTicks());
        assertEquals(0.7, c.stressThreshold(), EPS);
        assertEquals(0.5, c.damageThreshold(), EPS);
        assertTrue(c.standingEnabled());
        assertTrue(c.fallImpactEnabled());
        assertEquals(1.0, c.energyScale(), EPS);
        assertEquals(2.0, c.minFallDistance(), EPS);
    }

    @Test
    void entityWeightKnownValues() {
        EntityWeightTask.EntityWeightConfig c = new PluginConfigLoader(
                        yaml(
                                """
                entity-weight:
                  enabled: false
                  scan-interval-ticks: 33
                  stress-threshold: 0.21
                  damage-threshold: 0.12
                  standing:
                    enabled: false
                  fall-impact:
                    enabled: false
                    energy-scale: 2.5
                    min-fall-distance: 4.5
                """),
                        LOGGER)
                .loadEntityWeightConfig();
        assertFalse(c.enabled());
        assertEquals(33, c.scanIntervalTicks());
        assertEquals(0.21, c.stressThreshold(), EPS);
        assertEquals(0.12, c.damageThreshold(), EPS);
        assertFalse(c.standingEnabled());
        assertFalse(c.fallImpactEnabled());
        assertEquals(2.5, c.energyScale(), EPS);
        assertEquals(4.5, c.minFallDistance(), EPS);
    }

    // ---------------------------------------------------------------- weather

    @Test
    void weatherDefaults() {
        WeatherLoadTask.WeatherConfig w = new PluginConfigLoader(empty(), LOGGER).loadWeatherConfig();
        assertTrue(w.enabled());
        assertEquals(40, w.scanIntervalTicks());
        assertTrue(w.requireSkyAccess());
        assertTrue(w.rainEnabled());
        assertEquals(0.95, w.rainCapacityMultiplier(), EPS);
        assertTrue(w.thunderEnabled());
        assertEquals(0.88, w.thunderCapacityMultiplier(), EPS);
        assertTrue(w.thunderSpikesEnabled());
        assertEquals(0.02, w.thunderSpikeChance(), EPS);
        assertEquals(0.15, w.thunderSpikeAmount(), EPS);
        assertTrue(w.snowEnabled());
        assertEquals(0.005, w.snowLoadPerScan(), EPS);
        assertEquals(0.3, w.snowMaxLoad(), EPS);
        assertEquals(0.002, w.snowDecayPerScan(), EPS);
        assertEquals(10.0, w.tickBudgetMs(), EPS);
    }

    @Test
    void weatherKnownValues() {
        WeatherLoadTask.WeatherConfig w = new PluginConfigLoader(
                        yaml(
                                """
                weather:
                  enabled: false
                  scan-interval-ticks: 11
                  require-sky-access: false
                  rain:
                    enabled: false
                    capacity-multiplier: 0.51
                  thunder:
                    enabled: false
                    capacity-multiplier: 0.42
                    stress-spikes:
                      enabled: false
                      chance: 0.33
                      amount: 0.44
                  snow:
                    enabled: false
                    load-per-scan: 0.06
                    max-load: 0.7
                    decay-per-scan: 0.08
                  tick-budget-ms: 25.0
                """),
                        LOGGER)
                .loadWeatherConfig();
        assertFalse(w.enabled());
        assertEquals(11, w.scanIntervalTicks());
        assertFalse(w.requireSkyAccess());
        assertFalse(w.rainEnabled());
        assertEquals(0.51, w.rainCapacityMultiplier(), EPS);
        assertFalse(w.thunderEnabled());
        assertEquals(0.42, w.thunderCapacityMultiplier(), EPS);
        assertFalse(w.thunderSpikesEnabled());
        assertEquals(0.33, w.thunderSpikeChance(), EPS);
        assertEquals(0.44, w.thunderSpikeAmount(), EPS);
        assertFalse(w.snowEnabled());
        assertEquals(0.06, w.snowLoadPerScan(), EPS);
        assertEquals(0.7, w.snowMaxLoad(), EPS);
        assertEquals(0.08, w.snowDecayPerScan(), EPS);
        assertEquals(25.0, w.tickBudgetMs(), EPS);
    }

    // ------------------------------------------------------- material overrides

    @Test
    void materialOverridesAbsentSectionLeavesDefaultsUntouched() {
        MaterialRegistry registry = new MaterialRegistry();
        MaterialSpec before = registry.getDefault();
        new PluginConfigLoader(empty(), LOGGER).loadMaterialOverrides(registry);
        assertEquals(before, registry.getDefault());
    }

    @Test
    void materialOverridesApplyPerAxisAndDefault() {
        MaterialRegistry registry = new MaterialRegistry();
        MaterialSpec baseDefault = registry.getDefault();
        new PluginConfigLoader(
                        yaml(
                                """
                materials:
                  default:
                    mass: 12.0
                  STONE:
                    max-load: 999.0
                """),
                        LOGGER)
                .loadMaterialOverrides(registry);

        // default: only mass overridden, other axes preserved from base default.
        MaterialSpec def = registry.getDefault();
        assertEquals(12.0, def.mass(), EPS);
        assertEquals(baseDefault.maxLoad(), def.maxLoad(), EPS);
        assertEquals(baseDefault.blastResistance(), def.blastResistance(), EPS);
        assertEquals(baseDefault.fireResistance(), def.fireResistance(), EPS);

        // STONE: only max-load overridden.
        assertEquals(999.0, registry.getSpec(Material.STONE).maxLoad(), EPS);
    }

    // ------------------------------------------------------------ entity mass

    @Test
    void entityMassAbsentSectionLeavesDefaultsUntouched() {
        EntityMassRegistry registry = new EntityMassRegistry();
        double before = registry.getMass(EntityType.ZOMBIE);
        new PluginConfigLoader(empty(), LOGGER).loadEntityMassConfig(registry);
        assertEquals(before, registry.getMass(EntityType.ZOMBIE), EPS);
    }

    @Test
    void entityMassAppliesDefaultAndPerEntity() {
        EntityMassRegistry registry = new EntityMassRegistry();
        new PluginConfigLoader(
                        yaml(
                                """
                entity-weight:
                  standing:
                    mass:
                      default: 88.0
                      ZOMBIE: 42.0
                """),
                        LOGGER)
                .loadEntityMassConfig(registry);
        assertEquals(42.0, registry.getMass(EntityType.ZOMBIE), EPS);
        // The "default" key sets the fallback mass for unregistered entity types.
        assertEquals(88.0, registry.getDefaultMass(), EPS);
    }
}
