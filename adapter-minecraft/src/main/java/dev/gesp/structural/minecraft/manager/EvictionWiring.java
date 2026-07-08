package dev.gesp.structural.minecraft.manager;

import dev.gesp.structural.minecraft.config.MemoryEvictionConfig;
import dev.gesp.structural.minecraft.listener.ChunkEvictionListener;
import org.bukkit.plugin.Plugin;

/**
 * One-call wiring for component memory eviction, kept out of the plugin's giant
 * {@code onEnable} so it is unit-testable in isolation. Builds the
 * {@link ChunkEvictionManager}, installs it as the manager's residency guard, and registers
 * the {@link ChunkEvictionListener} — but only when the feature is enabled.
 */
public final class EvictionWiring {

    private EvictionWiring() {}

    /**
     * Install eviction if {@code config} enables it; otherwise a no-op.
     *
     * @return the live {@link ChunkEvictionManager}, or {@code null} when disabled
     */
    public static ChunkEvictionManager install(
            Plugin plugin, StructureManager structureManager, MemoryEvictionConfig config) {
        if (!config.isEnabled()) {
            return null;
        }
        ChunkEvictionManager evictionManager =
                new ChunkEvictionManager(structureManager, new ComponentEvictor(), config, plugin);
        structureManager.setResidencyGuard(evictionManager::ensureResident);
        plugin.getServer().getPluginManager().registerEvents(new ChunkEvictionListener(evictionManager), plugin);
        plugin.getLogger().info("Memory eviction enabled (" + config + ")");
        return evictionManager;
    }
}
