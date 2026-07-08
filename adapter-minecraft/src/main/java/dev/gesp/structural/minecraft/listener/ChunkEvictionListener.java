package dev.gesp.structural.minecraft.listener;

import dev.gesp.structural.minecraft.manager.ChunkEvictionManager;
import org.bukkit.Chunk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

/**
 * Bridges Bukkit chunk load/unload events to {@link ChunkEvictionManager}, which owns the
 * component-granularity eviction bookkeeping (SCALING.md §5). Registered only when
 * {@code memory.eviction.enabled} is true.
 */
public final class ChunkEvictionListener implements Listener {

    private final ChunkEvictionManager evictionManager;

    public ChunkEvictionListener(ChunkEvictionManager evictionManager) {
        this.evictionManager = evictionManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        evictionManager.onChunkLoad(event.getWorld(), chunk.getX(), chunk.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        evictionManager.onChunkUnload(event.getWorld(), chunk.getX(), chunk.getZ());
    }
}
