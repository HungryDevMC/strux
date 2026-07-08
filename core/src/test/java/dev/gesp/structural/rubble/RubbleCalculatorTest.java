package dev.gesp.structural.rubble;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.rubble.RubbleCalculator.RubbleCandidate;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rubble survival roll: tougher blocks and shorter falls survive more often.
 *
 * <p>The chance is {@code blastRes / (blastRes + fallHeight × factor)}, scaled by
 * a base multiplier and floored by a minimum. These tests pin the math (sign,
 * boundaries, floor) and the per-node roll, since the survival formula drives
 * how much debris a collapse leaves behind.
 */
@DisplayName("RubbleCalculator: survival chance + rolling")
class RubbleCalculatorTest {

    private static final double EPS = 1e-9;

    private static PhysicsConfig config() {
        PhysicsConfig c = new PhysicsConfig();
        c.setRubbleEnabled(true);
        c.setRubbleFallDamageFactor(0.5);
        c.setRubbleBaseChance(1.0);
        c.setRubbleMinChance(0.1);
        return c;
    }

    private static CollapsedNode node(int x, int y, int z, double blastResistance) {
        return new CollapsedNode(new NodePos(x, y, z), new MaterialSpec(1.0, 10.0, blastResistance));
    }

    @Test
    @DisplayName("Formula matches the documented worked examples")
    void formulaMatchesDocs() {
        RubbleCalculator calc = new RubbleCalculator(config());

        // Stone (blastRes=1.5) falling 10: 1.5 / (1.5 + 10×0.5) = 1.5/6.5
        assertEquals(1.5 / 6.5, calc.calculateSurvivalChance(1.5, 10), EPS);
        // Obsidian (blastRes=6.0) falling 10: 6.0 / (6.0 + 5.0) = 6.0/11.0
        assertEquals(6.0 / 11.0, calc.calculateSurvivalChance(6.0, 10), EPS);
    }

    @Test
    @DisplayName("Tougher blocks survive more often than weak ones at the same fall")
    void toughnessRaisesSurvival() {
        RubbleCalculator calc = new RubbleCalculator(config());
        double glass = calc.calculateSurvivalChance(0.3, 10);
        double stone = calc.calculateSurvivalChance(1.5, 10);
        double obsidian = calc.calculateSurvivalChance(6.0, 10);
        assertTrue(glass < stone, "glass should survive less than stone");
        assertTrue(stone < obsidian, "stone should survive less than obsidian");
    }

    @Test
    @DisplayName("Higher falls survive less often than short falls (correct sign)")
    void fallHeightLowersSurvival() {
        RubbleCalculator calc = new RubbleCalculator(config());
        double shortFall = calc.calculateSurvivalChance(1.5, 1);
        double longFall = calc.calculateSurvivalChance(1.5, 40);
        assertTrue(longFall < shortFall, "a longer fall must reduce survival, not raise it");
    }

    @Test
    @DisplayName("Zero fall height: survival is exactly 1.0 (nothing destroys it)")
    void zeroFallSurvivesFully() {
        RubbleCalculator calc = new RubbleCalculator(config());
        assertEquals(1.0, calc.calculateSurvivalChance(1.5, 0), EPS);
    }

    @Test
    @DisplayName("The minimum-chance floor lifts a tiny raw chance up to the floor")
    void minChanceFloorApplies() {
        PhysicsConfig c = config();
        c.setRubbleMinChance(0.25);
        RubbleCalculator calc = new RubbleCalculator(c);
        // weak block, huge fall → raw chance is well under 0.25
        double raw = 0.3 / (0.3 + 100 * 0.5);
        assertTrue(raw < 0.25, "precondition: raw chance is below the floor");
        assertEquals(0.25, calc.calculateSurvivalChance(0.3, 100), EPS);
    }

    @Test
    @DisplayName("Base-chance multiplier scales the raw survival down")
    void baseChanceScales() {
        PhysicsConfig c = config();
        c.setRubbleBaseChance(0.5);
        c.setRubbleMinChance(0.0); // remove the floor so the multiplier is visible
        RubbleCalculator calc = new RubbleCalculator(c);
        double full = (1.5 / 6.5);
        assertEquals(full * 0.5, calc.calculateSurvivalChance(1.5, 10), EPS);
    }

    @Test
    @DisplayName("Disabled rubble yields no candidates")
    void disabledYieldsNothing() {
        PhysicsConfig c = config();
        c.setRubbleEnabled(false);
        RubbleCalculator calc = new RubbleCalculator(c, alwaysSurvives());
        List<RubbleCandidate> out = calc.calculateRubble(List.of(node(0, 50, 0, 1.5)), 0);
        assertTrue(out.isEmpty(), "rubble disabled → empty list");
    }

    @Test
    @DisplayName("A roll below the survival chance keeps the block as rubble")
    void rollBelowChanceSurvives() {
        RubbleCalculator calc = new RubbleCalculator(config(), alwaysSurvives());
        List<RubbleCandidate> out = calc.calculateRubble(List.of(node(3, 50, 7, 1.5)), 10);

        assertEquals(1, out.size());
        RubbleCandidate c = out.get(0);
        assertEquals(40, c.fallHeight(), "fall height = y - groundLevel = 50 - 10");
        assertEquals(3, c.x());
        assertEquals(50, c.y());
        assertEquals(7, c.z());
    }

    @Test
    @DisplayName("A roll above the survival chance shatters the block (no rubble)")
    void rollAboveChanceShatters() {
        RubbleCalculator calc = new RubbleCalculator(config(), alwaysShatters());
        // base chance 1.0, min chance 0.1 → survival 0.1 here; a roll of ~1.0 exceeds it
        List<RubbleCandidate> out = calc.calculateRubble(List.of(node(0, 200, 0, 0.3)), 0);
        assertTrue(out.isEmpty(), "a roll above the survival chance must not produce rubble");
    }

    @Test
    @DisplayName("Fall height never goes negative for a block below ground level")
    void fallHeightClampedAtZero() {
        RubbleCalculator calc = new RubbleCalculator(config(), alwaysSurvives());
        // node y=5, groundLevel=20 → raw would be -15, must clamp to 0
        List<RubbleCandidate> out = calc.calculateRubble(List.of(node(0, 5, 0, 1.5)), 20);
        assertEquals(1, out.size());
        assertEquals(0, out.get(0).fallHeight(), "below-ground fall height clamps to 0, not negative");
    }

    /** A Random whose nextDouble is always 0.0 — below any positive chance, so the block survives. */
    private static Random alwaysSurvives() {
        return new Random() {
            @Override
            public double nextDouble() {
                return 0.0;
            }
        };
    }

    /** A Random whose nextDouble is just under 1.0 — above any sub-1.0 chance, so the block shatters. */
    private static Random alwaysShatters() {
        return new Random() {
            @Override
            public double nextDouble() {
                return 0.999999;
            }
        };
    }
}
