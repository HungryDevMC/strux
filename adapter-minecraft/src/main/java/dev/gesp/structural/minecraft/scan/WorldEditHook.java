package dev.gesp.structural.minecraft.scan;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.World;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Reads a player's WorldEdit selection.
 *
 * <p>WorldEdit is a soft dependency: this class touches WorldEdit's API, so it
 * must only ever be referenced AFTER confirming WorldEdit is installed
 * (see {@link StruxCommand}). The JVM loads it lazily on first use, so on a
 * server without WorldEdit its classes are never resolved.
 */
final class WorldEditHook {

    private WorldEditHook() {}

    /**
     * @return {@code {minX,minY,minZ,maxX,maxY,maxZ}} of the player's current
     *         WorldEdit selection, or {@code null} if there's no complete
     *         selection.
     */
    static int[] selectionBounds(Player player) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("WorldEdit");
        if (!(plugin instanceof WorldEditPlugin worldEdit)) {
            return null;
        }
        LocalSession session = worldEdit.getSession(player);
        World world = session.getSelectionWorld();
        if (world == null) {
            return null;
        }
        try {
            Region region = session.getSelection(world);
            BlockVector3 min = region.getMinimumPoint();
            BlockVector3 max = region.getMaximumPoint();
            return new int[] {min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ()};
        } catch (IncompleteRegionException e) {
            return null; // corners not both set
        }
    }
}
