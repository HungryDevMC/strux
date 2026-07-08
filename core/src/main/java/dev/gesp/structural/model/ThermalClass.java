package dev.gesp.structural.model;

/**
 * How a material's STRENGTH responds to temperature — the family whose real
 * strength-vs-temperature curve it follows.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │              HOT MATERIALS GET WEAKER (real engineering)            │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Steel beams sag in a fire. Concrete spalls. Wood chars away.       │
 *   │  Each material loses load capacity at a DIFFERENT rate as it heats  │
 *   │  up, and engineers have measured those curves:                     │
 *   │                                                                     │
 *   │    STEEL    holds full strength to ~400 °C, then drops fast         │
 *   │    MASONRY  (concrete/stone/brick) holds to ~100 °C, drops slower   │
 *   │    WOOD     holds to ~100 °C, then chars and is gone by ~300 °C     │
 *   │    INERT    a material we don't model thermally (always full)       │
 *   │                                                                     │
 *   │  The actual reduction numbers live in ThermalStrength. This enum    │
 *   │  just says WHICH curve a block follows.                             │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>Each class also carries a {@code shockSensitivity}: how badly the material
 * cracks when it is heated and then suddenly cooled (thermal shock). Brittle
 * fired ceramics and stone crack hard; ductile metal and fibrous wood barely
 * care. This is a single honest 0..1 multiplier per family, not a fake knob.
 */
public enum ThermalClass {

    /**
     * Steel / structural metal. Follows Eurocode-3 yield-strength reduction
     * factors k_y,θ: full strength to ~400 °C, then a steep loss. Ductile, so it
     * shrugs off thermal shock (low sensitivity).
     */
    STEEL(0.15),

    /**
     * Concrete, stone, brick, fired ceramic — anything masonry-like. Follows
     * Eurocode-2 concrete strength-reduction factors. Brittle: heat-then-douse
     * cracks it hard (high shock sensitivity).
     */
    MASONRY(1.0),

    /**
     * Wood and other fibrous combustibles. Holds to ~100 °C, then chars and is
     * structurally gone by ~300 °C. Fibrous, so only mildly shock-sensitive.
     */
    WOOD(0.25),

    /**
     * A material we deliberately do not soften with temperature (ground, or a
     * block whose thermal behaviour is out of scope). Always full strength, no
     * shock — this is the backward-compatible default so existing specs are
     * untouched.
     */
    INERT(0.0);

    private final double shockSensitivity;

    ThermalClass(double shockSensitivity) {
        this.shockSensitivity = shockSensitivity;
    }

    /**
     * How sharply heat-then-sudden-cooling cracks this material, in [0, 1].
     * 0 = immune (ductile/inert), 1 = maximally brittle (fired masonry).
     */
    public double shockSensitivity() {
        return shockSensitivity;
    }
}
