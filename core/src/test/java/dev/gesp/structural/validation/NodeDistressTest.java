package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.crack.CrackModel;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code Node.distress()} as THE canonical distress scalar —
 * {@code max(stressPercent, damage)}, clamped to [0,1], zero for ground — and
 * checks that {@link CrackModel} buckets the very same number it would for the
 * same node (no second, divergent distress formula in core).
 */
@DisplayName("Node.distress(): canonical max(stressPercent, damage)")
class NodeDistressTest {

    /** maxLoad 100 so a verticalStress of N is N% stress on an undamaged node. */
    private static Node node(double stress, double damage) {
        Node n = new Node(new NodePos(0, 1, 0), new MaterialSpec(2.0, 100.0), false);
        // Set damage first; effectiveMaxLoad shrinks, so derive the stress to hit
        // the requested stressPercent against the *damaged* capacity.
        n.addDamage(damage);
        double capacity = 100.0 * (1.0 - damage);
        n.setVerticalStress(stress * capacity);
        return n;
    }

    @Test
    @DisplayName("distress is the larger of stressPercent and damage")
    void distressTable() {
        // 0.8 stress / 0.3 damage -> 0.8
        assertEquals(0.8, node(0.8, 0.3).distress(), 1e-9);
        // 0.2 stress / 0.9 damage -> 0.9
        assertEquals(0.9, node(0.2, 0.9).distress(), 1e-9);
        // equal -> that value
        assertEquals(0.5, node(0.5, 0.5).distress(), 1e-9);
    }

    @Test
    @DisplayName("a grounded / zero node has zero distress")
    void groundedAndZeroAreZero() {
        assertEquals(0.0, Node.ground(new NodePos(0, 0, 0)).distress(), 1e-9);
        assertEquals(0.0, node(0.0, 0.0).distress(), 1e-9);
    }

    @Test
    @DisplayName("overloaded stress clamps distress to 1.0")
    void overloadClampsToOne() {
        Node n = new Node(new NodePos(0, 1, 0), new MaterialSpec(2.0, 100.0), false);
        n.setVerticalStress(500.0); // 500% of capacity
        assertEquals(1.0, n.distress(), 1e-9);
    }

    @Test
    @DisplayName("CrackModel reads the shared Node.distress() — same number, same bucket")
    void crackModelDelegatesToNodeDistress() {
        CrackModel cracks = new CrackModel(new PhysicsConfig());
        Node[] nodes = {node(0.8, 0.3), node(0.2, 0.9), node(0.5, 0.5), node(0.0, 0.0)};
        for (Node n : nodes) {
            assertEquals(n.distress(), CrackModel.distress(n), 1e-12, "CrackModel.distress must equal Node.distress");
            assertEquals(
                    cracks.crackLevel(n),
                    new CrackModel(new PhysicsConfig()).crackLevel(n),
                    "crackLevel must be a pure function of the shared distress");
        }
    }
}
