package dev.gesp.structural.minecraft.listener;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.FallingBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Cosmetic-only collapse debris.
 *
 * <p>Spawns falling blocks purely for visual drama, then cancels them when they
 * try to land — so they never place a block or drop an item, and leave no
 * untracked rubble. They have ZERO effect on the structure graph; the real
 * physics (including debris <em>impact</em> loading) already ran in the engine.
 */
public class DebrisVisuals implements Listener {

    private final NamespacedKey key;

    public DebrisVisuals(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "strux_debris");
    }

    /** Spawn a purely visual tumbling block at the given block location. */
    public void spawn(World world, Location loc, BlockData data) {
        if (data == null || !data.getMaterial().isBlock() || data.getMaterial().isAir()) {
            return;
        }
        FallingBlock fb = world.spawnFallingBlock(loc.clone().add(0.5, 0.0, 0.5), data);
        fb.setDropItem(false);
        fb.setCancelDrop(true);
        fb.setHurtEntities(false);
        fb.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
    }

    @EventHandler
    public void onLand(EntityChangeBlockEvent event) {
        if (event.getEntity() instanceof FallingBlock fb
                && fb.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) {
            event.setCancelled(true); // never place a block
            fb.remove(); // vanish cleanly — no rubble
        }
    }
}
