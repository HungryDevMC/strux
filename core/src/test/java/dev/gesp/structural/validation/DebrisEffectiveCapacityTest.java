package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Debris impact damage is normalized by the target's EFFECTIVE capacity
 * ({@code maxLoad × reinforcement × (1 − damage)}), not its pristine {@code maxLoad}
 * — the same capacity every overload check uses. So an already-cracked lower block
 * takes proportionally MORE debris damage (it has less strength left), which is what
 * makes a pancake accelerate through weakened floors. A pristine, unreinforced block
 * is unaffected by the change.
 *
 * <pre>
 *   [F] heavy faller, mass 10, drops 4 → impact energy 10×4×0.5 = 20
 *    ┊
 *   [T] target, maxLoad 50, own weight ~2
 *  [GND]
 *
 *   pristine T:        debris dmg = 20 / 50          = 0.40 → survives
 *   T pre-cracked 0.5: debris dmg = 20 / (50×0.5)=25 = 0.80 → 0.5+0.8 ≥ 1 → collapses
 * </pre>
 *
 * The faller energy (20) sits between {@code maxLoad×(1−d)²}=12.5 and
 * {@code maxLoad×(1−d)}=25, the exact band where dividing by EFFECTIVE vs RAW capacity
 * flips the outcome — so this test fails if the divisor ever regresses to raw maxLoad.
 */
@DisplayName("Debris impact damage scales with EFFECTIVE (remaining) capacity, not pristine maxLoad")
class DebrisEffectiveCapacityTest {

    private static final NodePos GROUND = new NodePos(0, 0, 0);
    private static final NodePos T = new NodePos(0, 1, 0); // target
    private static final NodePos F = new NodePos(0, 5, 0); // heavy floating faller, drop 4

    private StructureGraph scene() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(GROUND);
        g.addBlock(T, new MaterialSpec(2.0, 50.0), false); // own weight 2 ≪ cap, stands alone
        g.addBlock(F, new MaterialSpec(10.0, 1000.0), false); // unsupported → floats → falls on T
        return g;
    }

    private static CascadeEngine engine() {
        PhysicsConfig c = new PhysicsConfig();
        c.setDebrisImpactScale(0.5);
        c.setMinImpactDrop(2);
        return new CascadeEngine(c);
    }

    @Test
    @DisplayName("a PRISTINE target survives the falling debris (0.40 < 1.0)")
    void pristineTargetSurvivesDebris() {
        StructureGraph g = scene();
        engine().settle(g, SolverCallback.NONE);
        assertFalse(g.hasBlock(F), "the unsupported faller drops");
        assertTrue(g.hasBlock(T), "a full-strength target shrugs off this much debris");
    }

    @Test
    @DisplayName("the SAME debris collapses an already-cracked target (effective capacity halved)")
    void preCrackedTargetCollapsesFromSameDebris() {
        StructureGraph g = scene();
        g.getNode(T).addDamage(0.5); // T is at half strength before anything falls
        engine().settle(g, SolverCallback.NONE);
        assertFalse(g.hasBlock(F), "the faller drops");
        assertFalse(
                g.hasBlock(T),
                "a half-cracked target has half the capacity, so the same debris (÷25, not ÷50)"
                        + " tips it past failure — proving the divisor is EFFECTIVE capacity");
    }

    @Test
    @DisplayName("a REINFORCED target is tougher against debris too (effective capacity raised)")
    void reinforcedTargetIsTougher() {
        // Reinforce a weaker target so its EFFECTIVE capacity matches the pristine case;
        // it must then survive debris that would break the un-reinforced version.
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(GROUND);
        g.addBlock(T, new MaterialSpec(2.0, 25.0), false); // half the cap...
        g.getNode(T).setReinforcement(2.0); // ...×2 reinforcement → effective 50, like pristine
        g.addBlock(F, new MaterialSpec(10.0, 1000.0), false);

        engine().settle(g, SolverCallback.NONE);
        assertTrue(g.hasBlock(T), "reinforcement raises effective capacity, so debris (÷50) is shrugged off");
    }
}
