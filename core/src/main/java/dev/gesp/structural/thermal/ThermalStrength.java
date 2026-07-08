package dev.gesp.structural.thermal;

import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.ThermalClass;

/**
 * Turns a block's CURRENT temperature into a load-capacity multiplier, using
 * REAL engineering strength-vs-temperature curves. Hot structures get weaker —
 * the same honest physics as everything else in strux, just keyed to heat.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │            capacityFactor(spec, tempC) → multiplier in [0, 1]        │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │   1.0 ┤━━━━━━━━━━━┓            (full strength in the comfort band)   │
 *   │       │           ┗━┓                                                │
 *   │       │             ┗━━┓        steel: holds to 400°C, then sags    │
 *   │   0.5 ┤                ┗━━━┓                                         │
 *   │       │                    ┗━━━━┓                                    │
 *   │   0.0 ┤                         ┗━━━━━━━  (≈0 near failure temp)     │
 *   │       └────┬─────┬─────┬─────┬─────┬─────                            │
 *   │           200   400   600   800  1000  °C                           │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p><b>Sources (the curves are not invented — they are tabulated codes):</b>
 *
 * <ul>
 *   <li><b>Steel</b> — Eurocode 3 (EN 1993-1-2), yield-strength reduction factor
 *       k_y,θ: 1.0 up to 400 °C, 0.78 @500, 0.47 @600, 0.23 @700, 0.11 @800,
 *       0.06 @900, 0.04 @1000, 0.02 @1100, 0.0 @1200.
 *   <li><b>Masonry</b> (concrete / stone / brick / fired ceramic) — Eurocode 2
 *       (EN 1992-1-2) siliceous-concrete strength-reduction factor k_c,θ: ~1.0
 *       to 100 °C, 0.85 @300, 0.60 @500, 0.45 @600, 0.30 @700, 0.15 @800,
 *       0.08 @900, 0.04 @1000, 0.0 @1100.
 *   <li><b>Wood</b> — char-front model: full to 100 °C, ~0.5 by 200 °C (it is
 *       drying and pyrolysing), and structurally gone (charred) by ~300 °C.
 * </ul>
 *
 * <p>Each curve is piecewise-linear between those published anchor points — an
 * honest interpolation of measured data, not a fudge factor. Below the first
 * anchor the factor is a flat 1.0; above the last it is a flat 0.0 (clamped).
 *
 * <p><b>This is a strength model, not a thermal-stress simulation.</b> We reduce
 * the load CAPACITY a hot block has; we do not compute the internal stresses
 * that differential expansion induces. That is a deliberate omission — see the
 * temperature wiki page. The shock term below is the one nod to transient
 * thermal damage, and it too is a calibrated capacity-loss, not a PDE.
 *
 * <p>Pure and deterministic: no game types, no RNG, no clock. Identical inputs
 * always give an identical {@code double}, so it is safe on the hot path and in
 * snapshots.
 */
public final class ThermalStrength {

    private ThermalStrength() {}

    // Eurocode-3 steel k_y,θ anchor points (°C → factor). Ascending in °C.
    private static final double[] STEEL_TEMP = {20, 400, 500, 600, 700, 800, 900, 1000, 1100, 1200};
    private static final double[] STEEL_FACTOR = {1.0, 1.0, 0.78, 0.47, 0.23, 0.11, 0.06, 0.04, 0.02, 0.0};

    // Eurocode-2 concrete/masonry k_c,θ anchor points.
    private static final double[] MASONRY_TEMP = {20, 100, 300, 500, 600, 700, 800, 900, 1000, 1100};
    private static final double[] MASONRY_FACTOR = {1.0, 1.0, 0.85, 0.60, 0.45, 0.30, 0.15, 0.08, 0.04, 0.0};

    // Wood char-front anchor points.
    private static final double[] WOOD_TEMP = {20, 100, 200, 300};
    private static final double[] WOOD_FACTOR = {1.0, 1.0, 0.5, 0.0};

    /**
     * Load-capacity multiplier for a block of this material at temperature
     * {@code tempC} (degrees Celsius). 1.0 means full strength; values approach 0
     * as the material nears its failure temperature.
     *
     * <p>An {@link ThermalClass#INERT} material always returns 1.0 — that is how
     * the feature stays invisible when disabled (the adapter never tags a thermal
     * class) and how genuinely temperature-blind blocks behave.
     */
    public static double capacityFactor(MaterialSpec spec, double tempC) {
        return switch (spec.thermalClass()) {
            case STEEL -> interpolate(STEEL_TEMP, STEEL_FACTOR, tempC);
            case MASONRY -> interpolate(MASONRY_TEMP, MASONRY_FACTOR, tempC);
            case WOOD -> interpolate(WOOD_TEMP, WOOD_FACTOR, tempC);
            case INERT -> 1.0;
        };
    }

    /**
     * Persistent damage a heated block takes when it is suddenly cooled — thermal
     * shock. Heat a wall with fire, then douse it with water (or rain), and a
     * brittle fired block cracks; a ductile metal one barely flinches.
     *
     * <pre>
     *   shockDamage = clamp01( (ΔT − onset) / span ) × shockSensitivity
     * </pre>
     *
     * <p>{@code deltaT} is the size of the sudden temperature DROP (peak minus the
     * new temperature) in °C. A drop smaller than {@code onsetDeltaC} cracks
     * nothing — the material has time to equalise. Past it, the cracking grows
     * linearly with the drop, saturating at {@code spanC} above the onset, and is
     * scaled by the material's {@link ThermalClass#shockSensitivity()} so brittle
     * masonry shatters and ductile metal/wood barely cares. The result is in
     * [0, 1] — a persistent {@code Node.damage} amount.
     *
     * <p>Deterministic and monotonic in {@code deltaT}; an {@code INERT} or any
     * sensitivity-0 material never cracks (returns 0).
     *
     * @param spec        the material
     * @param deltaT      the sudden temperature drop in °C (peak − current)
     * @param onsetDeltaC the smallest drop that cracks anything (below it → 0)
     * @param spanC       the drop, above onset, at which cracking saturates
     */
    public static double shockDamage(MaterialSpec spec, double deltaT, double onsetDeltaC, double spanC) {
        double sensitivity = spec.thermalClass().shockSensitivity();
        if (sensitivity <= 0.0 || deltaT <= onsetDeltaC || spanC <= 0.0) {
            return 0.0;
        }
        double fraction = Math.min(1.0, (deltaT - onsetDeltaC) / spanC);
        return fraction * sensitivity;
    }

    /**
     * Piecewise-linear interpolation of {@code factor[]} over the ascending
     * {@code temp[]} anchor points, clamped flat outside the table (factor[0]
     * below the first anchor, factor[last] above the last).
     */
    private static double interpolate(double[] temp, double[] factor, double tempC) {
        if (tempC <= temp[0]) {
            return factor[0];
        }
        int last = temp.length - 1;
        if (tempC >= temp[last]) {
            return factor[last];
        }
        for (int i = 1; i <= last; i++) {
            if (tempC <= temp[i]) {
                double span = temp[i] - temp[i - 1];
                double t = (tempC - temp[i - 1]) / span;
                return factor[i - 1] + t * (factor[i] - factor[i - 1]);
            }
        }
        return factor[last]; // unreachable (clamped above), keeps the compiler happy
    }
}
