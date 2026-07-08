package dev.gesp.structural.minecraft.protect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.minecraft.protect.CoreProtectRestoreDetector.RestoreScanConfig;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Parses the {@code logging.restore-scan} block, proving both explicit values and defaults. */
@DisplayName("RestoreScanConfig.fromConfig")
class RestoreScanConfigTest {

    private static YamlConfiguration yaml(String body) {
        YamlConfiguration cfg = new YamlConfiguration();
        try {
            cfg.loadFromString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return cfg;
    }

    @Test
    @DisplayName("reads explicit values from the logging.restore-scan block")
    void readsExplicitValues() {
        YamlConfiguration cfg = yaml(
                """
                logging:
                  restore-scan:
                    interval-ticks: 40
                    max-lookups-per-run: 25
                    defer-during-cascade: false
                """);

        RestoreScanConfig config = RestoreScanConfig.fromConfig(cfg);

        assertEquals(40, config.intervalTicks());
        assertEquals(25, config.maxLookupsPerRun());
        assertFalse(config.deferDuringCascade());
    }

    @Test
    @DisplayName("falls back to defaults (100 / 50 / true) when the keys are absent")
    void fallsBackToDefaultsWhenAbsent() {
        RestoreScanConfig config = RestoreScanConfig.fromConfig(yaml("logging:\n  coreprotect: true\n"));

        assertEquals(100, config.intervalTicks(), "interval-ticks default");
        assertEquals(50, config.maxLookupsPerRun(), "max-lookups-per-run default");
        assertTrue(config.deferDuringCascade(), "defer-during-cascade default");
    }

    @Test
    @DisplayName("an entirely empty config still yields the defaults")
    void emptyConfigYieldsDefaults() {
        RestoreScanConfig config = RestoreScanConfig.fromConfig(new YamlConfiguration());

        assertEquals(100, config.intervalTicks());
        assertEquals(50, config.maxLookupsPerRun());
        assertTrue(config.deferDuringCascade());
    }

    @Test
    @DisplayName("bundled config.yml ships the documented defaults")
    void bundledConfigShipsDefaults() {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new InputStreamReader(
                RestoreScanConfigTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));

        RestoreScanConfig config = RestoreScanConfig.fromConfig(cfg);

        assertEquals(100, config.intervalTicks(), "shipped interval-ticks");
        assertEquals(50, config.maxLookupsPerRun(), "shipped max-lookups-per-run");
        assertTrue(config.deferDuringCascade(), "shipped defer-during-cascade");
    }
}
