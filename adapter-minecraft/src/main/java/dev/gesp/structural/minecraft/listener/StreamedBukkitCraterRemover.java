package dev.gesp.structural.minecraft.listener;

import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.model.NodePos;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * The default crater block-writer: plain per-block {@code setType(AIR, false)}.
 *
 * <p>This is the path that runs on every server WITHOUT FastAsyncWorldEdit, and the
 * one MockBukkit exercises in tests. The {@link CraterApplier} already streams the
 * removals over several ticks under a per-tick cap, so this loop only ever turns a
 * small slice of the crater to air per pass — no need for a bulk edit here.
 *
 * <p>The {@code false} {@code applyPhysics} flag matches the old atomic path: it stops
 * Bukkit running vanilla block-update physics on each removal (strux owns the physics).
 */
public final class StreamedBukkitCraterRemover implements CraterBlockRemover {

    @Override
    public void removeToAir(World world, List<NodePos> approved) {
        for (NodePos pos : approved) {
            Location loc = StructureManager.toLocation(pos, world);
            loc.getBlock().setType(Material.AIR, false);
        }
    }

    @Override
    public String describe() {
        return "streamed Bukkit setType (no FAWE)";
    }
}
