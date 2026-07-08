package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.crack.CrackLevel;
import dev.gesp.structural.crack.CrackModel;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.StressSolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Presentation-only cracking: a node looks cracked from its distress —
 * {@code max(stressPercent, damage)} — bucketed into levels. No physics: the
 * model never decides when a block fails, only how cracked it looks.
 */
@DisplayName("CrackModel: distress = max(stress, damage), bucketed")
class CrackModelTest {

    private final CrackModel cracks = new CrackModel(new PhysicsConfig());

    private static Node loose(double damage) {
        Node n = new Node(new NodePos(0, 1, 0), new MaterialSpec(2.0, 100.0), false);
        n.addDamage(damage);
        return n;
    }

    @Test
    @DisplayName("distress takes the worse of accumulated damage and current stress")
    void distressIsTheWorseOfTwo() {
        Node n = loose(0.4); // capacity 100 × (1 − 0.4) = 60
        // No stress set yet → distress is just the damage.
        assertEquals(0.4, CrackModel.distress(n), 1e-9);

        // Pile on stress above the damage → stress wins (42 / 60 = 0.7).
        n.setVerticalStress(42.0);
        assertEquals(0.7, CrackModel.distress(n), 1e-9);

        // Drop the load; the larger damage now wins.
        n.setVerticalStress(0.0);
        n.addDamage(0.5); // damage now 0.9
        assertEquals(0.9, CrackModel.distress(n), 1e-9);

        // An overloaded node clamps to a full 1.0 of distress.
        n.setVerticalStress(500.0);
        assertEquals(1.0, CrackModel.distress(n), 1e-9, "overloaded stress clamps to 1.0");
    }

    @Test
    @DisplayName("Ground is never cracked")
    void groundNeverCracks() {
        Node ground = Node.ground(new NodePos(0, 0, 0));
        assertEquals(0.0, CrackModel.distress(ground), 1e-9);
        assertEquals(CrackLevel.NONE, cracks.crackLevel(ground));
    }

    @Test
    @DisplayName("Default thresholds bucket distress into the right level")
    void defaultBucketing() {
        // Defaults: hairline 0.60, cracked 0.78, crumbling 0.90.
        assertEquals(CrackLevel.NONE, cracks.crackLevel(loose(0.50)));
        assertEquals(CrackLevel.HAIRLINE, cracks.crackLevel(loose(0.60)));
        assertEquals(CrackLevel.HAIRLINE, cracks.crackLevel(loose(0.77)));
        assertEquals(CrackLevel.CRACKED, cracks.crackLevel(loose(0.80)));
        assertEquals(CrackLevel.CRUMBLING, cracks.crackLevel(loose(0.95)));
    }

    @Test
    @DisplayName("Thresholds are configurable")
    void thresholdsAreConfigurable() {
        PhysicsConfig sensitive = new PhysicsConfig();
        sensitive.setCrackHairlineThreshold(0.2);
        sensitive.setCrackCrackedThreshold(0.4);
        sensitive.setCrackCrumblingThreshold(0.6);
        CrackModel cm = new CrackModel(sensitive);

        assertEquals(CrackLevel.HAIRLINE, cm.crackLevel(loose(0.25)));
        assertEquals(CrackLevel.CRACKED, cm.crackLevel(loose(0.45)));
        assertEquals(CrackLevel.CRUMBLING, cm.crackLevel(loose(0.65)));
    }

    @Test
    @DisplayName("Cracks intensify under load with no damage — a working wall shows it")
    void stressAloneCracks() {
        // A tall column: the base carries the most load and should crack hardest,
        // purely from stress — no blast or impact damage anywhere.
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= 20; y++) {
            g.addBlock(new NodePos(0, y, 0), new MaterialSpec(3.0, 70.0), false);
        }
        new StressSolver().solveAll(g);

        CrackLevel base = cracks.crackLevel(g.getNode(new NodePos(0, 1, 0)));
        CrackLevel top = cracks.crackLevel(g.getNode(new NodePos(0, 20, 0)));

        assertTrue(base.isVisible(), "the heavily-loaded base should show cracks from stress alone");
        assertEquals(CrackLevel.NONE, top, "the top carries almost nothing and stays clean");
    }

    @Test
    @DisplayName("Crack levels carry an overlay progress for continuous renderers")
    void overlayProgressOrdering() {
        assertEquals(0.0, CrackLevel.NONE.overlayProgress(), 1e-9);
        assertTrue(CrackLevel.HAIRLINE.overlayProgress() < CrackLevel.CRACKED.overlayProgress());
        assertTrue(CrackLevel.CRACKED.overlayProgress() < CrackLevel.CRUMBLING.overlayProgress());
    }
}
