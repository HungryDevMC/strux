package dev.gesp.structural.blast;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the "how many point-blank power-4 kegs to break one block" numbers that siege's
 * keep tuning is documented against — the regression guard that was missing when the
 * blast {@code damageScale} silently ran at 0.5 (every wall/foundation twice as tough as
 * its docs claimed).
 *
 * <p>Blast math: {@code intensity = power × falloff(0)=1 × occlusion=1 / blastResistance};
 * if {@code intensity ≥ destructionThreshold} (2.0) the block craters at once, otherwise it
 * accrues {@code intensity × damageScale} persistent damage and is removed once that reaches
 * 1.0. So at the keg's own cell (falloff 1.0, no occlusion):
 *
 * <pre>
 *   kegs-to-break = ceil( 1.0 / (power/blastResistance × damageScale) )
 * </pre>
 *
 * With the DEPLOYED config (power 4, the strux loader's {@code blast-damage-scale = 0.5}):
 * foundation (br 9) → 5 kegs; boosted stone-brick wall (br 6) → 3 kegs; boosted deepslate-brick
 * wall (br 9) → 5 kegs. None of these one-shot (max intensity 0.67 ≪ 2.0). These match
 * KeepBuilder's FOUNDATION_SPEC (blastResistance 9, pinned by TerrainBlastResistanceTest) and
 * its javadoc. This test is the guard that would have caught the silent damageScale assumption:
 * if the deployed damageScale changes, these counts change and this test fails.
 */
@DisplayName("Blast keg-counts: foundation vs walls match siege's documented tuning")
class FoundationKegCountTest {

    private static final double KEG_POWER = 4.0;
    /** The strux loader's deployed default for blast-damage-scale. */
    private static final double DEPLOYED_DAMAGE_SCALE = 0.5;

    private static final NodePos GROUND = new NodePos(0, 0, 0);
    private static final NodePos BLOCK = new NodePos(0, 1, 0);

    /** siege FOUNDATION_SPEC: blastResistance 9, huge maxLoad so it never load-collapses. */
    private static final MaterialSpec FOUNDATION = new MaterialSpec(3.0, 1000.0, 9.0);
    /** Boosted stone bricks (base 2.0 × siege's 3× = 6.0). */
    private static final MaterialSpec STONE_BRICK_WALL = new MaterialSpec(3.0, 1000.0, 6.0);
    /** Boosted deepslate bricks (base 3.0 × 3× = 9.0). */
    private static final MaterialSpec DEEPSLATE_WALL = new MaterialSpec(3.6, 1000.0, 9.0);

    private static PhysicsConfig config(double damageScale) {
        PhysicsConfig c = new PhysicsConfig();
        c.setDamageScale(damageScale);
        return c;
    }

    private static BlastContext pointBlank() {
        // Centre the blast on the block itself (falloff factor 1.0) with no occlusion —
        // the "keg stacked directly on it" case the tuning is sized for.
        return BlastContext.builder()
                .center(BLOCK)
                .power(KEG_POWER)
                .occlusion(BlastOcclusion.NONE)
                .build();
    }

    /** Fire point-blank kegs at one block until it is removed; return the keg count. */
    private static int kegsToBreak(MaterialSpec spec, double damageScale) {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(GROUND);
        g.addBlock(BLOCK, spec, false);
        StruxExplosionEngine engine = new StruxExplosionEngine(config(damageScale));
        int kegs = 0;
        while (g.hasBlock(BLOCK)) {
            engine.process(g, pointBlank());
            kegs++;
            assertTrue(kegs <= 100, "block never broke after " + kegs + " kegs (br=" + spec.blastResistance() + ")");
        }
        return kegs;
    }

    @Test
    @DisplayName("deployed damageScale 0.5: foundation ~5 kegs, stone wall ~3, deepslate wall ~5")
    void deployedKegCounts() {
        assertEquals(
                5,
                kegsToBreak(FOUNDATION, DEPLOYED_DAMAGE_SCALE),
                "foundation (br 9) breaks in ~5 point-blank kegs — the '4-5 TNT' design target");
        assertEquals(
                3,
                kegsToBreak(STONE_BRICK_WALL, DEPLOYED_DAMAGE_SCALE),
                "boosted stone-brick wall (br 6) breaks in 3 kegs");
        assertEquals(
                5,
                kegsToBreak(DEEPSLATE_WALL, DEPLOYED_DAMAGE_SCALE),
                "boosted deepslate wall (br 9) breaks in 5 kegs");
    }

    @Test
    @DisplayName("no single keg one-shots a foundation or wall (intensity stays under the crater threshold)")
    void noSingleKegCraters() {
        // One keg leaves every block standing but cracked — proves these are damage-accrual
        // breaks, not instant craters (intensity 0.44–0.67 < destructionThreshold 2.0).
        for (MaterialSpec spec : new MaterialSpec[] {FOUNDATION, STONE_BRICK_WALL, DEEPSLATE_WALL}) {
            StructureGraph g = new StructureGraph();
            g.addGroundBlock(GROUND);
            g.addBlock(BLOCK, spec, false);
            BlastResult r = new StruxExplosionEngine(config(DEPLOYED_DAMAGE_SCALE)).process(g, pointBlank());
            assertTrue(g.hasBlock(BLOCK), "one keg never craters br=" + spec.blastResistance());
            assertFalse(r.destroyed().contains(BLOCK), "br=" + spec.blastResistance() + " not in the crater set");
            double dmg = r.damaged().getOrDefault(BLOCK, 0.0);
            assertTrue(dmg > 0.0 && dmg < 1.0, "one keg chips it (0<dmg<1), got " + dmg);
        }
    }

    @Test
    @DisplayName("damageScale is the knob: the keg count is inverse-linear in it")
    void damageScaleScalesKegCountInversely() {
        // The relationship that made the silent 0.5-vs-1.0 assumption matter: a block is
        // tougher (more kegs) at a lower damageScale. At the deployed 0.5 the br-9 foundation
        // is ~5 kegs; doubling damageScale to 1.0 roughly halves that.
        int atHalf = kegsToBreak(FOUNDATION, 0.5); // deployed
        int atFull = kegsToBreak(FOUNDATION, 1.0);
        assertEquals(5, atHalf, "br-9 foundation at the deployed damageScale 0.5");
        assertEquals(3, atFull, "br-9 foundation at damageScale 1.0 (≈ half the kegs, rounded up)");
        assertTrue(atHalf > atFull, "a lower damageScale always means more kegs to break");
    }
}
