package dev.gesp.structural.minecraft.protect;

import java.util.Locale;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Shared gating logic: the master switch and the per-world disable list.
 *
 * <p>Subclasses only implement {@link #regionAllows(Location)} for the
 * region-level check (e.g. WorldGuard); everything else is handled here so
 * per-world toggles work even when no region plugin is installed.
 */
public abstract class AbstractProtectionService implements ProtectionService {

    private final boolean enabled;
    private final Set<String> disabledWorlds;

    /**
     * @param enabled        master switch ({@code regions.enabled}). When false,
     *                       no gating is applied and physics runs everywhere.
     * @param disabledWorlds world names (any case) where physics never runs.
     */
    protected AbstractProtectionService(boolean enabled, Set<String> disabledWorlds) {
        this.enabled = enabled;
        this.disabledWorlds = Set.copyOf(disabledWorlds);
    }

    @Override
    public final boolean physicsAllowed(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return true;
        }
        if (!enabled) {
            return true;
        }
        if (disabledWorlds.contains(loc.getWorld().getName().toLowerCase(Locale.ROOT))) {
            return false;
        }
        return regionAllows(loc);
    }

    @Override
    public final ChunkVerdict chunkVerdict(World world, int chunkX, int chunkZ) {
        if (world == null) {
            return ChunkVerdict.ALL_ALLOWED;
        }
        if (!enabled) {
            return ChunkVerdict.ALL_ALLOWED;
        }
        if (disabledWorlds.contains(world.getName().toLowerCase(Locale.ROOT))) {
            return ChunkVerdict.ALL_DENIED;
        }
        return regionChunkVerdict(world, chunkX, chunkZ);
    }

    /** Whether the world has any per-world disables configured. */
    protected boolean hasWorldRules() {
        return enabled && !disabledWorlds.isEmpty();
    }

    /**
     * Region-level decision. Implementations should return true when no region
     * here denies physics (the safe default).
     */
    protected abstract boolean regionAllows(Location loc);

    /**
     * Region-level chunk decision: the master switch and per-world list are already
     * handled by {@link #chunkVerdict}, so this only weighs region rules. Default is
     * conservative ({@code ALL_ALLOWED} only when there are no regions to consider);
     * subclasses with a region engine override for an exact per-chunk answer.
     */
    protected ChunkVerdict regionChunkVerdict(World world, int chunkX, int chunkZ) {
        return ChunkVerdict.ALL_ALLOWED;
    }
}
