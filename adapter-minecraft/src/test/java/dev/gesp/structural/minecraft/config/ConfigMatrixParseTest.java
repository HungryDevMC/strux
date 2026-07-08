package dev.gesp.structural.minecraft.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.minecraft.protect.CoreProtectRestoreDetector.RestoreScanConfig;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * LAYER 1 — parse / defaults / fallback matrix.
 *
 * <p>The existing {@link PluginConfigLoaderTest} pins (a) every key's default and (b) a
 * known non-default value. This class adds the third leg the equivalence guard never
 * covered: <b>(c) hostile input</b>. For a representative key from every config object
 * (plus each of today's additions) it drives the <i>real</i> {@link PluginConfigLoader}
 * path three ways:
 *
 * <ol>
 *   <li><b>absent</b> — the runtime value equals the documented default;
 *   <li><b>valid</b> — a non-default value is reflected;
 *   <li><b>hostile</b> — a wrong-type value (a string where a number is expected) or an
 *       out-of-range value falls back to the default / is clamped, and <b>never throws</b>
 *       — a parse exception here would abort plugin enable.
 * </ol>
 *
 * <p>Wrong-type input matters because {@code YamlConfiguration.getInt/getDouble} silently
 * return the supplied default when the stored node is not a number; this test proves the
 * loader relies on that (rather than {@code Integer.parseInt}-style code that would throw)
 * for every numeric key it exercises.
 */
class ConfigMatrixParseTest {

    private static final Logger LOGGER = Logger.getLogger(ConfigMatrixParseTest.class.getName());
    private static final double EPS = 1e-9;

    private static YamlConfiguration yaml(String body) {
        YamlConfiguration cfg = new YamlConfiguration();
        try {
            cfg.loadFromString(body);
        } catch (InvalidConfigurationException e) {
            throw new RuntimeException(e);
        }
        return cfg;
    }

    private static PluginConfigLoader loader(String body) {
        return new PluginConfigLoader(body.isEmpty() ? new YamlConfiguration() : yaml(body), LOGGER);
    }

    /**
     * One key's full absent/valid/hostile row.
     *
     * @param extract pulls the parsed runtime value out of a loaded config object
     * @param absentDefault expected value when the key is missing (the documented default)
     * @param validYaml YAML that sets the key to {@code validExpected}
     * @param hostileYaml YAML with a wrong-type or out-of-range value for the key
     * @param hostileExpected value after hostile input (== default for a type mismatch, or
     *     the clamped value for an out-of-range number)
     */
    private record Row(
            String name,
            Function<PluginConfigLoader, Object> extract,
            Object absentDefault,
            String validYaml,
            Object validExpected,
            String hostileYaml,
            Object hostileExpected) {}

    private static void assertValue(String label, Object expected, Object actual) {
        if (expected instanceof Double d) {
            assertEquals(d, ((Number) actual).doubleValue(), EPS, label);
        } else {
            assertEquals(expected, actual, label);
        }
    }

    private List<Row> rows() {
        return List.of(
                // Top-level physics — double + int + boolean coverage.
                new Row(
                        "moment-multiplier",
                        l -> l.loadPhysicsConfig().getMomentMultiplier(),
                        1.0,
                        "moment-multiplier: 2.5",
                        2.5,
                        "moment-multiplier: not-a-number",
                        1.0),
                new Row(
                        "max-cascade-steps",
                        l -> l.loadPhysicsConfig().getMaxCascadeSteps(),
                        50,
                        "max-cascade-steps: 77",
                        77,
                        "max-cascade-steps: banana",
                        50),
                new Row(
                        "bending-depth-enabled",
                        l -> l.loadPhysicsConfig().isBendingDepthEnabled(),
                        true,
                        "bending-depth-enabled: false",
                        false,
                        // getBoolean returns the default for a non-boolean node — a sane, non-throwing fallback.
                        "bending-depth-enabled: maybe",
                        true),
                // effects.min-visible-damage — the corrected 0.05 default.
                new Row(
                        "effects.min-visible-damage",
                        l -> l.loadEffectsConfig().getMinVisibleDamage(),
                        0.05,
                        "effects:\n  min-visible-damage: 0.4",
                        0.4,
                        "effects:\n  min-visible-damage: wide-open",
                        0.05),
                // effects.cracking-warning-interval — clamped to >= 1 (divide-by-zero guard).
                new Row(
                        "effects.cracking-warning-interval (clamp>=1)",
                        l -> l.loadEffectsConfig().getCrackingWarningInterval(),
                        5,
                        "effects:\n  cracking-warning-interval: 8",
                        8,
                        "effects:\n  cracking-warning-interval: 0",
                        1),
                // container-weight.enabled — the FIXED default (false, matching config.yml + docs).
                new Row(
                        "container-weight.enabled (fixed default=false)",
                        l -> l.loadContainerWeightConfig().enabled(),
                        false,
                        "container-weight:\n  enabled: true",
                        true,
                        "container-weight:\n  enabled: nope",
                        false),
                new Row(
                        "container-weight.content-weight",
                        l -> l.loadContainerWeightConfig().contentWeight(),
                        8.0,
                        "container-weight:\n  content-weight: 3.5",
                        3.5,
                        "container-weight:\n  content-weight: heavy",
                        8.0),
                new Row(
                        "entity-weight.enabled",
                        l -> l.loadEntityWeightConfig().enabled(),
                        true,
                        "entity-weight:\n  enabled: false",
                        false,
                        "entity-weight:\n  enabled: sure",
                        true),
                // entity-weight.fall-impact.force-impact-distance — today's addition.
                new Row(
                        "entity-weight.fall-impact.force-impact-distance",
                        l -> l.loadEntityWeightConfig().forceImpactDistance(),
                        15.0,
                        "entity-weight:\n  fall-impact:\n    force-impact-distance: 6.0",
                        6.0,
                        "entity-weight:\n  fall-impact:\n    force-impact-distance: far",
                        15.0),
                new Row(
                        "memory.eviction.enabled",
                        l -> l.loadMemoryEvictionConfig().isEnabled(),
                        false,
                        "memory:\n  eviction:\n    enabled: true",
                        true,
                        "memory:\n  eviction:\n    enabled: on-please",
                        false),
                new Row(
                        "memory.eviction.grace-ticks",
                        l -> l.loadMemoryEvictionConfig().getGraceTicks(),
                        200,
                        "memory:\n  eviction:\n    grace-ticks: 40",
                        40,
                        "memory:\n  eviction:\n    grace-ticks: soon",
                        200),
                new Row(
                        "persistence.auto-save-interval",
                        l -> l.loadPersistenceConfig().getAutoSaveIntervalSeconds(),
                        300,
                        "persistence:\n  auto-save-interval: 60",
                        60,
                        "persistence:\n  auto-save-interval: often",
                        300),
                // recording.buffer-size — clamped to >= 10 by the config object's setter.
                new Row(
                        "recording.buffer-size (clamp>=10)",
                        l -> l.loadRecordingConfig().getBufferSize(),
                        100,
                        "recording:\n  buffer-size: 250",
                        250,
                        "recording:\n  buffer-size: 3",
                        10),
                new Row(
                        "weather.rain.capacity-multiplier",
                        l -> l.loadWeatherConfig().rainCapacityMultiplier(),
                        0.95,
                        "weather:\n  rain:\n    capacity-multiplier: 0.5",
                        0.5,
                        "weather:\n  rain:\n    capacity-multiplier: soggy",
                        0.95),
                new Row(
                        "regions.enabled",
                        l -> l.loadRegionConfig().isEnabled(),
                        true,
                        "regions:\n  enabled: false",
                        false,
                        "regions:\n  enabled: yeah",
                        true));
    }

    @TestFactory
    List<DynamicTest> absentValidHostileMatrix() {
        return rows().stream()
                .map(row -> DynamicTest.dynamicTest(row.name(), () -> {
                    // (a) absent -> documented default
                    assertValue(
                            row.name() + " [absent -> default]",
                            row.absentDefault(),
                            row.extract().apply(loader("")));
                    // (b) valid non-default -> reflected
                    assertValue(
                            row.name() + " [valid -> reflected]",
                            row.validExpected(),
                            row.extract().apply(loader(row.validYaml())));
                    // (c) hostile -> fallback/clamp, never an exception
                    Object hostile = row.extract().apply(loader(row.hostileYaml()));
                    assertValue(row.name() + " [hostile -> fallback/clamp]", row.hostileExpected(), hostile);
                }))
                .toList();
    }

    // ---- today's additions read outside PluginConfigLoader: restore-scan via fromConfig ----
    // Default + valid-value coverage lives in RestoreScanConfigTest; this adds the hostile leg.

    @Test
    void restoreScanSurvivesHostileValues() {
        // Wrong-type numbers fall through to the defaults instead of aborting the detector wiring.
        RestoreScanConfig c = RestoreScanConfig.fromConfig(
                yaml(
                        "logging:\n  restore-scan:\n    interval-ticks: soon\n    max-lookups-per-run: lots\n    defer-during-cascade: whenever\n"));
        assertNotNull(c);
        assertEquals(100, c.intervalTicks(), "hostile interval falls back to default");
        assertEquals(50, c.maxLookupsPerRun(), "hostile lookup cap falls back to default");
        assertTrue(c.deferDuringCascade(), "getBoolean returns the default (true) for a non-boolean node");
    }
}
