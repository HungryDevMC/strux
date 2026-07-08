package dev.gesp.structural.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tunnel-roof cratering: when a tunnel is blown out under a soil overburden, the
 * ground above should cave into a crater rather than hang in the air. This pins
 * the tuning lever — dropping soil {@code max-load} so the roof's weight overloads
 * its (now soil-only) supports and cascades into the void.
 *
 * <p>Note: a tunnel roof is a two-sided <em>beam</em>, and the solver's beam
 * fast-path zeroes a beam's moment unconditionally — so {@code moment-multiplier}
 * and {@code beam-moment-reduction} do NOT crater it. The craterer is vertical
 * load accumulating on the walls once the soil's {@code max-load} is low enough.
 */
@DisplayName("Tunnel roof craters when soil max-load is dropped")
class TunnelRoofCollapseTest {

    /**
     * A wide tunnel (single z-slice): stone floor on ground, dirt side-walls, a dirt
     * roof + overburden spanning the void. The roof over the void is held only by the
     * lateral chain to the walls — a classic beam.
     */
    private StructureGraph tunnel(MaterialSpec dirt) {
        StructureGraph g = new StructureGraph();
        int xLo = 0;
        int xHi = 16;
        int voidLo = 3;
        int voidHi = 13; // tunnel void spans x=3..13 at y=1..2
        for (int x = xLo; x <= xHi; x++) {
            g.addGroundBlock(new NodePos(x, 0, 0)); // bedrock floor
        }
        for (int x = xLo; x <= xHi; x++) {
            // y=1..2: soil banks outside the void, air inside it. Soil banks (not
            // stone) so the roof's weight lands on soil — the case max-load governs.
            boolean inVoid = x >= voidLo && x <= voidHi;
            if (!inVoid) {
                g.addBlock(new NodePos(x, 1, 0), dirt, false);
                g.addBlock(new NodePos(x, 2, 0), dirt, false);
            }
        }
        // Roof + a deep overburden: dirt, spanning the whole width including over the
        // void. The deeper the column, the more weight accumulates on the soil banks —
        // which is exactly what a low max-load can no longer hold.
        for (int y = 3; y <= 11; y++) {
            for (int x = xLo; x <= xHi; x++) {
                g.addBlock(new NodePos(x, y, 0), dirt, false);
            }
        }
        return g;
    }

    private ScenarioOutcome collapse(MaterialSpec dirt, double beamReduction) {
        StructureGraph g = tunnel(dirt);
        PhysicsConfig cfg = new PhysicsConfig();
        cfg.setBeamMomentReduction(beamReduction);
        // Blow a hole in the middle of the roof, as a sapper charge would from inside.
        return Scenario.on(g, cfg).blast(new NodePos(8, 3, 0), 3.0);
    }

    @Test
    @DisplayName("dropping soil max-load craters the roof; moment knobs do not")
    void soilMaxLoadDropCratersTheRoof() {
        ScenarioOutcome stiff = collapse(new MaterialSpec(2.0, 20.0), 1.0); // current default soil
        ScenarioOutcome soft = collapse(new MaterialSpec(2.0, 6.0), 1.0); // soil max-load dropped
        ScenarioOutcome softBeam = collapse(new MaterialSpec(2.0, 6.0), 0.5); // + beam knob (should add little)

        System.out.println("=== tunnel-roof blast removedCount ===");
        System.out.println(
                "  default soil (maxLoad 20, beam 1.0): " + stiff.removedCount() + " / " + stiff.initialSize());
        System.out.println("  soft soil   (maxLoad  6, beam 1.0): " + soft.removedCount() + " / " + soft.initialSize());
        System.out.println(
                "  soft+beam   (maxLoad  6, beam 0.5): " + softBeam.removedCount() + " / " + softBeam.initialSize());

        assertTrue(
                soft.removedCount() > stiff.removedCount(),
                "dropping soil max-load must crater more of the roof than the stiff default");
        assertEquals(
                soft.removedCount(),
                softBeam.removedCount(),
                "beam-moment-reduction must NOT change a two-sided roof — the lever is max-load, not the beam knob");
    }
}
