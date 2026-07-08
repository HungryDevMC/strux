package dev.gesp.structural.persistence;

import dev.gesp.structural.model.ThermalClass;

/**
 * Serializable representation of a block for persistence.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                         BLOCK DATA (DTO)                           │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  This is what gets SAVED to disk or sent to an API.                │
 *   │                                                                     │
 *   │  Unlike Node (which has computed stress values),                   │
 *   │  BlockData only stores the ESSENTIAL information:                  │
 *   │                                                                     │
 *   │    • Position (x, y, z)                                            │
 *   │    • Material properties (mass, maxLoad, blast/fire resistance)    │
 *   │    • Is it a ground block?                                         │
 *   │    • Reinforcement multiplier (player-applied capacity boost)      │
 *   │    • Accumulated damage (siege scars must survive a restart)       │
 *   │                                                                     │
 *   │  Stress is NOT saved because it is recalculated when blocks are    │
 *   │  loaded back into the game. Damage and reinforcement ARE saved:    │
 *   │  both are persistent physical state, not derived values.           │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public record BlockData(
        int x,
        int y,
        int z,
        double mass,
        double maxLoad,
        boolean grounded,
        double reinforcement,
        double damage,
        double blastResistance,
        double fireResistance,
        String materialId,
        ThermalClass thermalClass) {

    /**
     * Normalize legacy/missing fields. Files written before a field existed
     * have no value for it, so deserializers supply 0.0 — map non-positive
     * multipliers to their neutral default and clamp damage to [0, 1].
     * {@code materialId} (the adapter's block-state string, used only for replay
     * rendering) may be {@code null} on older files and is left as-is.
     * {@code thermalClass} is {@code null} on files written before the thermal
     * axis existed; it defaults to {@link ThermalClass#INERT} (no temperature
     * softening), exactly the strength a legacy block had.
     */
    public BlockData {
        if (reinforcement <= 0.0) {
            reinforcement = 1.0;
        }
        damage = Math.max(0.0, Math.min(1.0, damage));
        if (blastResistance <= 0.0) {
            blastResistance = 1.0;
        }
        if (fireResistance <= 0.0) {
            fireResistance = 1.0;
        }
        if (thermalClass == null) {
            thermalClass = ThermalClass.INERT;
        }
    }

    /**
     * Full physical state with a material id but no thermal class — the legacy
     * recording/replay shape. Defaults {@code thermalClass} to
     * {@link ThermalClass#INERT}.
     */
    public BlockData(
            int x,
            int y,
            int z,
            double mass,
            double maxLoad,
            boolean grounded,
            double reinforcement,
            double damage,
            double blastResistance,
            double fireResistance,
            String materialId) {
        this(
                x,
                y,
                z,
                mass,
                maxLoad,
                grounded,
                reinforcement,
                damage,
                blastResistance,
                fireResistance,
                materialId,
                ThermalClass.INERT);
    }

    /**
     * Full physical state (incl. thermal class) without a material id — the core
     * converter has no block-state string, only an adapter does. The replay
     * renderer falls back to a default texture when {@code materialId} is
     * {@code null}.
     */
    public BlockData(
            int x,
            int y,
            int z,
            double mass,
            double maxLoad,
            boolean grounded,
            double reinforcement,
            double damage,
            double blastResistance,
            double fireResistance,
            ThermalClass thermalClass) {
        this(
                x,
                y,
                z,
                mass,
                maxLoad,
                grounded,
                reinforcement,
                damage,
                blastResistance,
                fireResistance,
                null,
                thermalClass);
    }

    /**
     * Create pristine BlockData with default resistances (legacy v2 shape).
     */
    public BlockData(int x, int y, int z, double mass, double maxLoad, boolean grounded, double reinforcement) {
        this(x, y, z, mass, maxLoad, grounded, reinforcement, 0.0, 1.0, 1.0, (String) null);
    }

    /**
     * Create unreinforced, pristine BlockData (the common case).
     */
    public BlockData(int x, int y, int z, double mass, double maxLoad, boolean grounded) {
        this(x, y, z, mass, maxLoad, grounded, 1.0);
    }

    /**
     * Create BlockData for a ground block at the given position.
     */
    public static BlockData ground(int x, int y, int z) {
        return new BlockData(x, y, z, 0.0, Double.MAX_VALUE, true, 1.0);
    }
}
