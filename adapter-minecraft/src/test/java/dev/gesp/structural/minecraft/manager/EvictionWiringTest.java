package dev.gesp.structural.minecraft.manager;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.gesp.structural.minecraft.config.MemoryEvictionConfig;
import dev.gesp.structural.minecraft.material.MaterialRegistry;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/** {@link EvictionWiring#install} builds the subsystem only when the feature is enabled. */
@DisplayName("EvictionWiring installs eviction only when enabled")
final class EvictionWiringTest {

    private Plugin plugin;
    private StructureManager manager;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        manager = new StructureManager(new MaterialRegistry());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void disabledConfig_installsNothing() {
        MemoryEvictionConfig config = new MemoryEvictionConfig(); // enabled=false by default
        assertNull(EvictionWiring.install(plugin, manager, config));
    }

    @Test
    void enabledConfig_installsManager() {
        MemoryEvictionConfig config = new MemoryEvictionConfig();
        config.setEnabled(true);
        assertNotNull(EvictionWiring.install(plugin, manager, config), "returns the live manager");
    }
}
