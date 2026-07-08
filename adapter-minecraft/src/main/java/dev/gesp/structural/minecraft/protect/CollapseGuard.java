package dev.gesp.structural.minecraft.protect;

import dev.gesp.structural.minecraft.hook.WarZoneService;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * The single chokepoint every collapse removal goes through.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                        COLLAPSE GUARD                              │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Bundles "may physics act here?" (ProtectionService) with           │
 *   │  "record what was destroyed" (CollapseLogger).                      │
 *   │                                                                     │
 *   │  Listeners call:                                                    │
 *   │    physicsAllowed(loc)  → trigger gate (don't start a cascade)      │
 *   │    claimRemoval(block)  → removal gate; logs, then says yes/no      │
 *   │                                                                     │
 *   │  claimRemoval MUST be called while the block is still solid         │
 *   │  (before setType(AIR)) so the logger captures the real block.       │
 *   │                                                                     │
 *   │  Both gates AND together two independent checks:                    │
 *   │    • ProtectionService — static region/world rules (WorldGuard)     │
 *   │    • WarZoneService    — dynamic war state (Towny/Factions)         │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public final class CollapseGuard {

    private final ProtectionService protection;
    private final WarZoneService warZone;
    private final CollapseLogger logger;

    public CollapseGuard(ProtectionService protection, WarZoneService warZone, CollapseLogger logger) {
        this.protection = protection;
        this.warZone = warZone;
        this.logger = logger;
    }

    /**
     * Trigger gate: whether strux may run physics at this location at all.
     * Requires both region rules and (if scoping is on) an active war zone.
     */
    public boolean physicsAllowed(Location loc) {
        return warZone.destructionAllowed(loc) && protection.physicsAllowed(loc);
    }

    /**
     * Trigger gate for a whole chunk column, in ONE consultation per gate instead of
     * one per block. A bulk sweep (e.g. weather over 135k blocks) asks this once per
     * chunk; only when the answer is {@link ChunkVerdict#PER_BLOCK} need it fall back
     * to {@link #physicsAllowed} per block. Combines both gates exactly via
     * {@link ChunkVerdict#and}: a chunk is uniformly allowed only when BOTH the war
     * zone and the region rules allow the whole chunk.
     */
    public ChunkVerdict physicsAllowedInChunk(World world, int chunkX, int chunkZ) {
        ChunkVerdict war = warZone.chunkVerdict(world, chunkX, chunkZ);
        if (war == ChunkVerdict.ALL_DENIED) {
            return ChunkVerdict.ALL_DENIED; // war vetoes the chunk; no need to ask regions
        }
        return war.and(protection.chunkVerdict(world, chunkX, chunkZ));
    }

    /**
     * Removal gate. Call BEFORE setting the block to AIR.
     *
     * @return true  → the caller may remove the block (it has been logged);
     *         false → the block is protected and must be left standing.
     */
    public boolean claimRemoval(Block block) {
        Location loc = block.getLocation();
        if (!warZone.destructionAllowed(loc) || !protection.physicsAllowed(loc)) {
            return false;
        }
        logger.logRemoval(loc, block.getType(), block.getBlockData());
        return true;
    }

    /**
     * Removal gate for a block whose chunk verdict is already known — the bulk-crater
     * path. A big blast groups its removals by chunk and resolves
     * {@link #physicsAllowedInChunk} ONCE per chunk; when that verdict is
     * {@link ChunkVerdict#ALL_ALLOWED} every block in the chunk is allowed, so this
     * skips the two per-block protection queries and only logs the removal. For any
     * other verdict it falls back to the exact per-block {@link #claimRemoval}.
     *
     * <p>Call BEFORE setting the block to AIR (so the logger captures the real block).
     * The result is identical to {@link #claimRemoval} — only cheaper when the chunk
     * is uniformly allowed.
     *
     * @param block          the block about to be removed (still solid)
     * @param chunkVerdict the pre-resolved verdict for this block's chunk
     * @return true  → the caller may remove the block (it has been logged);
     *         false → the block is protected and must be left standing.
     */
    public boolean claimRemoval(Block block, ChunkVerdict chunkVerdict) {
        if (chunkVerdict == ChunkVerdict.ALL_DENIED) {
            return false; // whole chunk is protected; never log, never remove
        }
        if (chunkVerdict == ChunkVerdict.ALL_ALLOWED) {
            // The chunk is uniformly allowed — both gates already said yes for every
            // block here, so skip the per-block queries and just log + allow.
            Location loc = block.getLocation();
            logger.logRemoval(loc, block.getType(), block.getBlockData());
            return true;
        }
        return claimRemoval(block); // PER_BLOCK: exact, as before
    }

    public String describeProtection() {
        return protection.describe() + "; war-zone: " + warZone.describe();
    }
}
