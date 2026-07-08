package dev.gesp.structural.model;

/**
 * The physical properties of a material.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────┐
 *   │  MATERIAL SPEC = How heavy + How strong                    │
 *   ├─────────────────────────────────────────────────────────────┤
 *   │                                                             │
 *   │  mass = How heavy this block is                            │
 *   │         (heavier blocks push down more)                    │
 *   │                                                             │
 *   │  maxLoad = How much weight this block can hold             │
 *   │            before it breaks                                │
 *   │                                                             │
 *   │  Example:                                                  │
 *   │    - mass = 1.5 (fairly light)                             │
 *   │    - maxLoad = 20.0 (can hold 20 units before breaking)    │
 *   │                                                             │
 *   │         [block]  [block]  [block]                          │
 *   │            │        │        │                             │
 *   │            └────────┼────────┘                             │
 *   │                     ▼                                      │
 *   │              ┌───────────┐                                 │
 *   │              │  (1.5,20) │ ← total weight on this = 4.5    │
 *   │              │ max: 20.0 │   (3 blocks × 1.5 each)         │
 *   │              └───────────┘   still safe! (4.5 < 20)        │
 *   │                                                             │
 *   └─────────────────────────────────────────────────────────────┘
 * </pre>
 */
public record MaterialSpec(
        double mass, double maxLoad, double blastResistance, double fireResistance, ThermalClass thermalClass) {

    /**
     * Special spec for ground/foundation blocks.
     * Zero mass (doesn't add weight) and infinite load capacity.
     */
    public static final MaterialSpec GROUND = new MaterialSpec(0.0, Double.MAX_VALUE);

    /**
     * Convenience constructor with default blast and fire resistance of 1.0 and
     * an {@link ThermalClass#INERT} thermal class (no temperature softening).
     */
    public MaterialSpec(double mass, double maxLoad) {
        this(mass, maxLoad, 1.0, 1.0, ThermalClass.INERT);
    }

    /**
     * Convenience constructor with an explicit blast resistance and a default
     * fire resistance of 1.0.
     *
     * <p>Blast resistance scales how much of an explosion's intensity a block
     * shrugs off: higher = tougher (bunkers), below 1.0 = fragile (glass).
     */
    public MaterialSpec(double mass, double maxLoad, double blastResistance) {
        this(mass, maxLoad, blastResistance, 1.0, ThermalClass.INERT);
    }

    /**
     * Convenience constructor with explicit blast and fire resistance and a
     * default {@link ThermalClass#INERT} thermal class. This is the legacy
     * four-axis form every persistence/recording call site uses; adding the
     * thermal axis here keeps those untouched (they read a block with no
     * temperature softening, exactly as before).
     */
    public MaterialSpec(double mass, double maxLoad, double blastResistance, double fireResistance) {
        this(mass, maxLoad, blastResistance, fireResistance, ThermalClass.INERT);
    }

    public MaterialSpec {
        if (mass < 0) {
            throw new IllegalArgumentException("Mass cannot be negative: " + mass);
        }
        if (maxLoad <= 0) {
            throw new IllegalArgumentException("Max load must be positive: " + maxLoad);
        }
        if (blastResistance <= 0) {
            throw new IllegalArgumentException("Blast resistance must be positive: " + blastResistance);
        }
        if (fireResistance <= 0) {
            throw new IllegalArgumentException("Fire resistance must be positive: " + fireResistance);
        }
        if (thermalClass == null) {
            throw new IllegalArgumentException("Thermal class cannot be null");
        }
    }

    /**
     * Return a copy of this spec with a different {@link ThermalClass}, keeping
     * every other axis. Lets the material registry tag a class onto a spec built
     * with the legacy constructors without re-listing mass/load/blast/fire.
     */
    public MaterialSpec withThermalClass(ThermalClass newThermalClass) {
        return new MaterialSpec(mass, maxLoad, blastResistance, fireResistance, newThermalClass);
    }

    /**
     * Is this a ground/foundation spec? (infinite load capacity)
     */
    public boolean isGround() {
        return maxLoad == Double.MAX_VALUE;
    }
}
