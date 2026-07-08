package dev.gesp.structural.minecraft.protect;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Owns the WorldGuard {@code strux-physics} region flag.
 *
 * <pre>
 *   Admins control physics per-region with:
 *       /rg flag spawn strux-physics deny    ← spawn is now collapse-proof
 *       /rg flag arena strux-physics allow   ← (default) physics on
 * </pre>
 *
 * <p><b>Class-loading note:</b> every method here touches WorldGuard/WorldEdit
 * types, so this class must only be referenced after confirming WorldGuard is
 * on the classpath (see {@code StructuralIntegrityPlugin#onLoad}). Keeping the
 * WG imports confined to this one class lets the rest of the plugin run on
 * servers without WorldGuard installed.
 */
final class StruxFlags {

    static final String FLAG_NAME = "strux-physics";

    /** Registered flag, or null if registration failed. */
    private static StateFlag physicsFlag;

    private StruxFlags() {}

    /**
     * Register the flag. MUST be called from {@code onLoad()} (before WorldGuard
     * enables), per WorldGuard's custom-flag contract.
     */
    static void register(Logger log) {
        FlagRegistry registry = WorldGuard.getInstance().getFlagRegistry();
        try {
            StateFlag flag = new StateFlag(FLAG_NAME, true); // default: ALLOW
            registry.register(flag);
            physicsFlag = flag;
            log.info("Registered WorldGuard flag '" + FLAG_NAME + "' (default: allow).");
        } catch (FlagConflictException e) {
            // Already registered (e.g. after a /reload, or by another plugin).
            Flag<?> existing = registry.get(FLAG_NAME);
            if (existing instanceof StateFlag sf) {
                physicsFlag = sf;
                log.info("Reusing existing WorldGuard flag '" + FLAG_NAME + "'.");
            } else {
                log.warning("Flag '" + FLAG_NAME + "' exists but is not a StateFlag; "
                        + "WorldGuard region gating disabled.");
            }
        }
    }

    /** @return true unless a region at {@code loc} sets the flag to DENY. */
    static boolean allowed(Location loc) {
        if (physicsFlag == null) {
            return true; // flag never registered → don't block anything
        }
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionQuery query = container.createQuery();
        com.sk89q.worldedit.util.Location wgLoc = BukkitAdapter.adapt(loc);
        StateFlag.State state = query.queryState(wgLoc, (RegionAssociable) null, physicsFlag);
        return state != StateFlag.State.DENY;
    }

    /**
     * Exact per-chunk verdict for the physics flag, so a bulk sweep can skip the
     * per-block query when the whole chunk shares one answer.
     *
     * <pre>
     *   no flag / no region manager / no regions in the world → ALL_ALLOWED
     *       (the flag defaults to ALLOW, so with nothing set nothing is denied)
     *   __global__ region sets the flag                        → PER_BLOCK
     *       (the global region has no geometry, so a spatial query misses it; it
     *        could deny anywhere, so we cannot claim a uniform chunk answer)
     *   no region intersects this chunk's column               → ALL_ALLOWED
     *       (a block is denied only by a region covering it; none here → none denied)
     *   a region DOES touch the chunk                           → PER_BLOCK
     *       (a region edge may cut through; ask per block for an exact answer)
     * </pre>
     *
     * This is exact: it never claims ALL_ALLOWED for a chunk a region could deny.
     */
    static ChunkVerdict chunkVerdict(World world, int chunkX, int chunkZ) {
        if (physicsFlag == null) {
            return ChunkVerdict.ALL_ALLOWED;
        }
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionManager manager = container.get(BukkitAdapter.adapt(world));
        if (manager == null || manager.size() == 0) {
            return ChunkVerdict.ALL_ALLOWED;
        }
        // A __global__ flag override applies world-wide but has no geometry, so the
        // cuboid query below would miss it. If it touches our flag, be exact: per-block.
        ProtectedRegion global = manager.getRegion(ProtectedRegion.GLOBAL_REGION);
        if (global != null && global.getFlag(physicsFlag) != null) {
            return ChunkVerdict.PER_BLOCK;
        }
        int minBlockX = chunkX << 4;
        int minBlockZ = chunkZ << 4;
        BlockVector3 min = BlockVector3.at(minBlockX, world.getMinHeight(), minBlockZ);
        BlockVector3 max = BlockVector3.at(minBlockX + 15, world.getMaxHeight(), minBlockZ + 15);
        ProtectedRegion chunkCuboid = new ProtectedCuboidRegion("__strux_chunk_probe__", min, max);
        ApplicableRegionSet applicable = manager.getApplicableRegions(chunkCuboid);
        return applicable.size() == 0 ? ChunkVerdict.ALL_ALLOWED : ChunkVerdict.PER_BLOCK;
    }
}
