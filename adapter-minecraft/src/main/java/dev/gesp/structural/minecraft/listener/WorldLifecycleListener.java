package dev.gesp.structural.minecraft.listener;

import dev.gesp.structural.minecraft.manager.StructureManager;
import java.util.List;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.Plugin;

/**
 * Loads saved structures for worlds that come up <b>after</b> {@code onEnable}.
 *
 * <p>The boot load only runs once, over the worlds present at enable. A world loaded
 * later (Multiverse {@code /mv load}, dynamic world creation, lazy-loading world
 * managers) would otherwise never have its saved structures read from disk — every
 * tracked build there silently loses physics, and worse: the first block a player
 * places creates a fresh near-empty graph, and the next auto-save or shutdown save
 * writes <em>that</em> over the world's good structure file. Permanent data loss.
 *
 * <p>This listener runs the SAME detached-load-then-publish path the boot load uses,
 * which also marks the world load-pending so a save during the in-flight window can
 * never overwrite the good disk data.
 */
public final class WorldLifecycleListener implements Listener {

    private final Plugin plugin;
    private final StructureManager structureManager;

    public WorldLifecycleListener(Plugin plugin, StructureManager structureManager) {
        this.plugin = plugin;
        this.structureManager = structureManager;
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        World world = event.getWorld();
        // Already tracked (a boot world, or one already loaded) — don't reload from disk
        // over live in-memory state. A genuinely new world has no graph yet.
        if (structureManager.getGraph(world) != null) {
            return;
        }
        // No-op when persistence is disabled (loadAllWorldsAsync returns immediately).
        structureManager.loadAllWorldsAsync(plugin, List.of(world));
    }
}
