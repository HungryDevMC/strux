package dev.gesp.structural.minecraft.hook;

import dev.gesp.structural.minecraft.protect.ChunkVerdict;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Decides whether strux destruction is permitted at a location based on
 * <em>war state</em> — as opposed to {@code ProtectionService}, which gates on
 * static region/world rules.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                       WAR-ZONE SERVICE                             │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Turns strux from a building gimmick into a siege mechanic:        │
 *   │  walls only crumble where (and when) a war is actually happening.  │
 *   │                                                                     │
 *   │  Backed by Towny / Factions via reflection (no compile-time dep),  │
 *   │  so it degrades to "allow everywhere" when neither is installed.   │
 *   │                                                                     │
 *   │  ANDed on top of region protection inside CollapseGuard, at BOTH   │
 *   │  the trigger gate and the removal gate.                            │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public interface WarZoneService {

    /**
     * @return true if strux may run physics / destroy a block here right now,
     *         given the current war state.
     */
    boolean destructionAllowed(Location loc);

    /**
     * Whole-chunk war-state verdict in ONE consultation, so a bulk sweep need not
     * call {@link #destructionAllowed} per block. Must be exact: only answer
     * {@link ChunkVerdict#ALL_ALLOWED} / {@link ChunkVerdict#ALL_DENIED} when the
     * chunk's war state is uniform; otherwise {@link ChunkVerdict#PER_BLOCK}. War
     * state is dynamic and usually finer than a chunk, so the default is the safe
     * {@code PER_BLOCK}; the pass-through {@link #ALLOW_ALL} overrides it.
     */
    default ChunkVerdict chunkVerdict(World world, int chunkX, int chunkZ) {
        return ChunkVerdict.PER_BLOCK;
    }

    /** Short human-readable description for startup logging. */
    String describe();

    /**
     * War-zone scoping disabled: destruction is always permitted (the gate is a
     * pass-through, leaving region/world protection to do its job alone).
     */
    WarZoneService ALLOW_ALL = new WarZoneService() {
        @Override
        public boolean destructionAllowed(Location loc) {
            return true;
        }

        @Override
        public ChunkVerdict chunkVerdict(World world, int chunkX, int chunkZ) {
            return ChunkVerdict.ALL_ALLOWED;
        }

        @Override
        public String describe() {
            return "off (destruction allowed everywhere region rules permit)";
        }
    };
}
