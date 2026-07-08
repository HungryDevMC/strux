package dev.gesp.structural.minecraft.protect;

import java.util.Set;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Protection backed by the WorldGuard {@code strux-physics} region flag.
 *
 * <p>Only instantiate this when WorldGuard is confirmed present — constructing
 * it (and its query path through {@link StruxFlags}) loads WorldGuard classes.
 */
public final class WorldGuardProtection extends AbstractProtectionService {

    public WorldGuardProtection(boolean enabled, Set<String> disabledWorlds) {
        super(enabled, disabledWorlds);
    }

    @Override
    protected boolean regionAllows(Location loc) {
        return StruxFlags.allowed(loc);
    }

    @Override
    protected ChunkVerdict regionChunkVerdict(World world, int chunkX, int chunkZ) {
        return StruxFlags.chunkVerdict(world, chunkX, chunkZ);
    }

    @Override
    public String describe() {
        return "WorldGuard region flag '" + StruxFlags.FLAG_NAME + "'"
                + (hasWorldRules() ? " + per-world toggles" : "");
    }
}
