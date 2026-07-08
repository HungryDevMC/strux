package dev.gesp.structural.thermal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.ThermalClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link ThermalStrength} against the published Eurocode strength-reduction
 * anchor points and the documented shock behaviour. These are real engineering
 * numbers, so the test reads like the code tables (and would catch a silent edit
 * to either).
 */
@DisplayName("ThermalStrength: real strength-vs-temperature curves")
class ThermalStrengthTest {

    private static final double EPS = 1e-9;

    private static MaterialSpec of(ThermalClass cls) {
        return new MaterialSpec(2.0, 100.0, 1.0, 1.0, cls);
    }

    private static final MaterialSpec STEEL = of(ThermalClass.STEEL);
    private static final MaterialSpec MASONRY = of(ThermalClass.MASONRY);
    private static final MaterialSpec WOOD = of(ThermalClass.WOOD);
    private static final MaterialSpec INERT = of(ThermalClass.INERT);

    // ── steel: Eurocode-3 k_y,θ ──────────────────────────────────────────

    @Test
    @DisplayName("Steel holds full strength through the comfort band and up to 400°C")
    void steelComfortBand() {
        assertEquals(1.0, ThermalStrength.capacityFactor(STEEL, 20.0), EPS);
        assertEquals(1.0, ThermalStrength.capacityFactor(STEEL, 300.0), EPS, "1.0 at 300°C (still on the plateau)");
        assertEquals(1.0, ThermalStrength.capacityFactor(STEEL, 400.0), EPS, "still 1.0 at the 400°C knee");
    }

    @Test
    @DisplayName("Steel follows the Eurocode-3 reduction factors at the anchor temperatures")
    void steelAnchorPoints() {
        assertEquals(0.78, ThermalStrength.capacityFactor(STEEL, 500.0), EPS);
        assertEquals(0.47, ThermalStrength.capacityFactor(STEEL, 600.0), EPS);
        assertEquals(0.23, ThermalStrength.capacityFactor(STEEL, 700.0), EPS);
        assertEquals(0.11, ThermalStrength.capacityFactor(STEEL, 800.0), EPS);
        assertEquals(0.0, ThermalStrength.capacityFactor(STEEL, 1200.0), EPS, "≈0 by 1200°C");
    }

    @Test
    @DisplayName("Steel interpolates linearly between anchors (550°C is halfway 500→600)")
    void steelInterpolates() {
        double expected = (0.78 + 0.47) / 2.0;
        assertEquals(expected, ThermalStrength.capacityFactor(STEEL, 550.0), EPS);
    }

    // ── masonry: Eurocode-2 k_c,θ ────────────────────────────────────────

    @Test
    @DisplayName("Masonry follows the Eurocode-2 concrete reduction factors")
    void masonryAnchorPoints() {
        assertEquals(1.0, ThermalStrength.capacityFactor(MASONRY, 100.0), EPS);
        assertEquals(0.85, ThermalStrength.capacityFactor(MASONRY, 300.0), EPS);
        assertEquals(0.60, ThermalStrength.capacityFactor(MASONRY, 500.0), EPS);
        assertEquals(0.45, ThermalStrength.capacityFactor(MASONRY, 600.0), EPS);
        assertEquals(0.15, ThermalStrength.capacityFactor(MASONRY, 800.0), EPS);
        assertEquals(0.0, ThermalStrength.capacityFactor(MASONRY, 1100.0), EPS, "≈0 by 1100°C");
    }

    // ── wood: char front ─────────────────────────────────────────────────

    @Test
    @DisplayName("Wood holds to 100°C, halves by 200°C, and is gone (charred) by 300°C")
    void woodCharFront() {
        assertEquals(1.0, ThermalStrength.capacityFactor(WOOD, 100.0), EPS);
        assertEquals(0.5, ThermalStrength.capacityFactor(WOOD, 200.0), EPS);
        assertEquals(0.0, ThermalStrength.capacityFactor(WOOD, 300.0), EPS, "~0 at 300°C (charred)");
        assertEquals(0.0, ThermalStrength.capacityFactor(WOOD, 500.0), EPS, "stays 0 above the table");
    }

    // ── inert + clamping ─────────────────────────────────────────────────

    @Test
    @DisplayName("Inert material is never softened by temperature (flag-off / temperature-blind behaviour)")
    void inertNeverSoftens() {
        assertEquals(1.0, ThermalStrength.capacityFactor(INERT, 20.0), EPS);
        assertEquals(1.0, ThermalStrength.capacityFactor(INERT, 800.0), EPS);
        assertEquals(1.0, ThermalStrength.capacityFactor(INERT, 5000.0), EPS);
    }

    @Test
    @DisplayName("Sub-comfort (cold) and extreme temperatures clamp flat to the table ends")
    void clampsOutsideTable() {
        assertEquals(1.0, ThermalStrength.capacityFactor(STEEL, -40.0), EPS, "below first anchor → factor[0]");
        assertEquals(0.0, ThermalStrength.capacityFactor(STEEL, 9999.0), EPS, "above last anchor → factor[last]");
    }

    // ── thermal shock ────────────────────────────────────────────────────

    @Test
    @DisplayName("Shock: a drop below the onset cracks nothing")
    void shockBelowOnsetIsZero() {
        assertEquals(0.0, ThermalStrength.shockDamage(MASONRY, 100.0, 150.0, 500.0), EPS);
        assertEquals(0.0, ThermalStrength.shockDamage(MASONRY, 150.0, 150.0, 500.0), EPS, "exactly at onset → 0");
    }

    @Test
    @DisplayName("Shock grows with the size of the temperature drop")
    void shockGrowsWithDeltaT() {
        double small = ThermalStrength.shockDamage(MASONRY, 300.0, 150.0, 500.0);
        double big = ThermalStrength.shockDamage(MASONRY, 600.0, 150.0, 500.0);
        assertTrue(small > 0.0, "a drop past the onset cracks masonry");
        assertTrue(big > small, "a bigger drop cracks it harder: " + big + " vs " + small);
    }

    @Test
    @DisplayName("Shock saturates at full sensitivity once the drop exceeds onset+span")
    void shockSaturatesAtSensitivity() {
        // onset 150 + span 500 = 650; a 1000°C drop is well past saturation.
        double masonry = ThermalStrength.shockDamage(MASONRY, 1000.0, 150.0, 500.0);
        assertEquals(ThermalClass.MASONRY.shockSensitivity(), masonry, EPS, "full masonry sensitivity (1.0)");
    }

    @Test
    @DisplayName("Shock scales by per-material sensitivity: brittle masonry ≫ ductile steel/wood, inert immune")
    void shockScalesBySensitivity() {
        double dt = 800.0; // well into saturation for all
        double masonry = ThermalStrength.shockDamage(MASONRY, dt, 150.0, 500.0);
        double wood = ThermalStrength.shockDamage(WOOD, dt, 150.0, 500.0);
        double steel = ThermalStrength.shockDamage(STEEL, dt, 150.0, 500.0);
        double inert = ThermalStrength.shockDamage(INERT, dt, 150.0, 500.0);

        assertEquals(1.0, masonry, EPS, "brittle fired masonry shatters");
        assertTrue(masonry > wood, "masonry cracks harder than wood");
        assertTrue(wood > steel, "wood cracks harder than ductile steel");
        assertTrue(steel > 0.0, "ductile steel still takes a little shock");
        assertEquals(0.0, inert, EPS, "inert material is immune to shock");
    }
}
