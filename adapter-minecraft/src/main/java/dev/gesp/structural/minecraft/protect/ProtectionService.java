package dev.gesp.structural.minecraft.protect;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * Decides whether strux physics may act at a given location.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                     PROTECTION SERVICE                             │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Server owners do NOT want a griefer to collapse spawn.            │
 *   │  This is the gate that protects regions and worlds.               │
 *   │                                                                     │
 *   │  A location is "allowed" only if:                                  │
 *   │    • the world is not in the disabled-worlds list, AND             │
 *   │    • no region here denies the strux-physics flag (WorldGuard).    │
 *   │                                                                     │
 *   │  It is consulted in TWO ways:                                      │
 *   │    1. as a TRIGGER gate  - don't even start a cascade here         │
 *   │    2. as a REMOVAL gate  - never destroy a block here, even if a   │
 *   │                            cascade started elsewhere reaches it    │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public interface ProtectionService {

    /**
     * @return true if strux may run physics / destroy a block at this location.
     */
    boolean physicsAllowed(Location loc);

    /**
     * Decide physics permission for a whole chunk column in ONE consultation, so a
     * bulk sweep need not call {@link #physicsAllowed} per block. Implementations
     * MUST be exact: only answer {@link ChunkVerdict#ALL_ALLOWED} /
     * {@link ChunkVerdict#ALL_DENIED} when the chunk's answer is genuinely uniform,
     * and {@link ChunkVerdict#PER_BLOCK} otherwise (the caller then falls back to a
     * per-block check). The default is conservative — {@code PER_BLOCK}.
     *
     * @param world  the world
     * @param chunkX chunk X (block x &gt;&gt; 4)
     * @param chunkZ chunk Z (block z &gt;&gt; 4)
     */
    default ChunkVerdict chunkVerdict(World world, int chunkX, int chunkZ) {
        return ChunkVerdict.PER_BLOCK;
    }

    /**
     * @return a short human-readable description of what this service enforces
     *         (used in startup logging).
     */
    String describe();
}
