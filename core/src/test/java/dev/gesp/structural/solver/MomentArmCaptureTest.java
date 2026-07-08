package dev.gesp.structural.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.api.DebugCapture;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The optional {@link DebugCapture#onMomentArm} hook must report a detected cantilever's
 * geometry (members, mass, reach, beam flag, section depth, anchor) with known values,
 * fire only on the final pass when {@link DebugCapture#wantsMomentArms()} is on, and stay
 * a no-op otherwise — all without changing the moment the solver computes.
 */
@DisplayName("StressSolver moment-arm DebugCapture: cantilever geometry captured with known values")
class MomentArmCaptureTest {

    private static final MaterialSpec UNIT = new MaterialSpec(1.0, 1000.0);

    /** Records every {@link DebugCapture#onMomentArm} emit. */
    private static final class ArmRecorder implements DebugCapture {
        record Arm(
                List<NodePos> members, double totalMass, int reach, boolean isBeam, int sectionDepth, NodePos anchor) {}

        final List<Arm> arms = new ArrayList<>();
        int passes;
        boolean wantArms = true;
        boolean wantCapture = true;
        boolean finalPass;

        @Override
        public boolean wantsDebugCapture() {
            return wantCapture;
        }

        @Override
        public boolean wantsMomentArms() {
            return wantArms;
        }

        @Override
        public void beginPass(boolean finalPass) {
            this.finalPass = finalPass;
            passes++;
        }

        @Override
        public void onMomentArm(
                List<NodePos> members, double totalMass, int reach, boolean isBeam, int sectionDepth, NodePos anchor) {
            arms.add(new Arm(new ArrayList<>(members), totalMass, reach, isBeam, sectionDepth, anchor));
        }
    }

    /**
     * An L: a grounded column at x=0 (ground y0, blocks y1..y3) with a horizontal arm
     * sticking out at y=3 (blocks at x=1 and x=2). The arm hangs off the column tip
     * (0,3,0) and has no other support — a true cantilever.
     */
    private StructureGraph lCantilever() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= 3; y++) {
            g.addBlock(new NodePos(0, y, 0), UNIT, false);
        }
        g.addBlock(new NodePos(1, 3, 0), UNIT, false);
        g.addBlock(new NodePos(2, 3, 0), UNIT, false);
        return g;
    }

    private void solve(StructureGraph g, DebugCapture sink) {
        new StressSolver().setDebugCapture(sink).setFinalPass(true).solve(g, new HashSet<>(g.getAllPositions()));
    }

    @Test
    @DisplayName("L-cantilever: the arm's members, mass, reach, beam flag, depth and anchor are captured")
    void capturesCantileverGeometry() {
        StructureGraph g = lCantilever();
        ArmRecorder rec = new ArmRecorder();
        solve(g, rec);

        assertEquals(1, rec.passes, "one solve, one pass");
        assertTrue(rec.finalPass, "marked as the final settle pass");
        assertFalse(rec.arms.isEmpty(), "the horizontal arm is a true cantilever → captured");

        // The arm hangs off the column tip (0,3,0); its members are the two reaching
        // blocks at x=1 and x=2.
        ArmRecorder.Arm arm = rec.arms.stream()
                .filter(a -> a.anchor().equals(new NodePos(0, 3, 0)))
                .findFirst()
                .orElse(null);
        assertNotNull(arm, "an arm anchored at the column tip is reported");
        assertEquals(
                List.of(new NodePos(1, 3, 0), new NodePos(2, 3, 0)),
                arm.members(),
                "members are the two reaching blocks, canonical order");
        assertEquals(2.0, arm.totalMass(), 1e-9, "two UNIT blocks of mass 1 each");
        assertEquals(2, arm.reach(), "the arm is two blocks long");
        assertFalse(arm.isBeam(), "the arm has only the anchor as support → a cantilever, not a beam");
        assertEquals(1, arm.sectionDepth(), "the arm is one block thick vertically");
        assertEquals(new NodePos(0, 3, 0), arm.anchor(), "anchored at the column tip");
    }

    @Test
    @DisplayName("Arm capture is gated off when wantsMomentArms() is false, and off on a non-final pass")
    void armCaptureGated() {
        StructureGraph g = lCantilever();

        // wantsMomentArms() off → no arms even though debug capture is on.
        ArmRecorder noArms = new ArmRecorder();
        noArms.wantArms = false;
        solve(g, noArms);
        assertTrue(noArms.arms.isEmpty(), "wantsMomentArms() off → no arm emits");

        // Non-final pass → no arms (the bound: one set per event, on the settle pass).
        ArmRecorder nonFinal = new ArmRecorder();
        new StressSolver().setDebugCapture(nonFinal).setFinalPass(false).solve(g, new HashSet<>(g.getAllPositions()));
        assertTrue(nonFinal.arms.isEmpty(), "a non-final pass captures no arms");
    }

    @Test
    @DisplayName("Capturing arms does not change the moment stress the solver computes")
    void captureIsSideEffectFree() {
        StructureGraph withCapture = lCantilever();
        ArmRecorder rec = new ArmRecorder();
        solve(withCapture, rec);
        double capturedMoment = withCapture.getNode(new NodePos(0, 3, 0)).momentStress();

        StructureGraph noCapture = lCantilever();
        new StressSolver().setFinalPass(true).solve(noCapture, new HashSet<>(noCapture.getAllPositions()));
        double plainMoment = noCapture.getNode(new NodePos(0, 3, 0)).momentStress();

        assertEquals(plainMoment, capturedMoment, 1e-12, "the anchor's moment stress is identical with capture on");
        assertTrue(capturedMoment > 0.0, "the cantilever really does load the anchor in moment");
    }
}
