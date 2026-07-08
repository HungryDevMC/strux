package dev.gesp.structural.blast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.api.CollapseReason;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reason-tagged blast collapse hook: every cascade collapse a blast triggers
 * is reported with WHY it fell, so a consumer (replay analysis, distinct effects)
 * can tell a block that lost its support (FLOATING) apart from one crushed by
 * redistributed load (OVERLOADED). The reasonless one-arg hook still fires for
 * every collapse, so an old callback that ignores the reason is unchanged.
 */
@DisplayName("Blast: reason-tagged cascade collapses")
class BlastCollapseReasonTest {

    private static final MaterialSpec STRONG = new MaterialSpec(1.0, 1000.0); // won't self-overload

    private final StruxExplosionEngine engine = new StruxExplosionEngine();

    private static BlastContext blast(NodePos center, double power) {
        return BlastContext.builder()
                .center(center)
                .power(power)
                .occlusion(BlastOcclusion.NONE)
                .build();
    }

    /** Captures the reason tagged on each two-arg collapse, plus every one-arg collapse. */
    private static final class ReasonCapture implements BlastCallback {
        final List<CollapseReason> reasons = new ArrayList<>();
        final List<NodePos> allCollapses = new ArrayList<>();

        @Override
        public void onCollapse(NodePos pos) {
            // Fires for EVERY collapse: directly for damage-shattered blocks, and
            // via the two-arg default for the reason-tagged ones.
            allCollapses.add(pos);
        }

        @Override
        public void onCollapse(NodePos pos, CollapseReason reason) {
            reasons.add(reason);
            allCollapses.add(pos); // not delegating to the one-arg override — count once
        }
    }

    @Test
    @DisplayName("knocking out a cantilever's only pillar reports the far arm as FLOATING")
    void floatingCollapsesAreTaggedFloating() {
        // A tall pillar carrying a horizontal arm. The arm reaches far enough from
        // the blast to survive the shockwave, but its ONLY path to ground is down
        // the pillar — blow the pillar base out and the whole arm floats. STRONG
        // material so nothing self-overloads: every collapse here is pure floating.
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= 6; y++) {
            g.addBlock(new NodePos(0, y, 0), STRONG, false);
        }
        for (int x = 1; x <= 5; x++) {
            g.addBlock(new NodePos(x, 6, 0), STRONG, false);
        }

        ReasonCapture cap = new ReasonCapture();
        BlastResult r = engine.process(g, blast(new NodePos(0, 1, 0), 3.0), cap);

        assertFalse(r.collapsed().isEmpty(), "losing the pillar base must float the arm");
        assertEquals(
                r.collapsed().size(),
                cap.reasons.size(),
                "pure floating: every collapse is reason-tagged, none shatter");
        Set<CollapseReason> seen = EnumSet.copyOf(cap.reasons);
        assertTrue(seen.contains(CollapseReason.FLOATING), "lost-support collapses are FLOATING");
        assertFalse(seen.contains(CollapseReason.OVERLOADED), "nothing here is stress-driven");
    }

    @Test
    @DisplayName("a roof whose supports are crushed by redistributed load reports OVERLOADED")
    void overloadCollapsesAreTaggedOverloaded() {
        // A wide tunnel with soft soil walls: blowing the roof open drops its span's
        // weight onto the soil banks, which overload and crush — a stress collapse.
        MaterialSpec softSoil = new MaterialSpec(2.0, 6.0);
        StructureGraph g = tunnel(softSoil);

        ReasonCapture cap = new ReasonCapture();
        engine.process(g, blast(new NodePos(8, 3, 0), 3.0), cap);

        assertTrue(
                cap.reasons.contains(CollapseReason.OVERLOADED),
                "the soil walls fail under the redistributed roof load — an OVERLOADED collapse");
    }

    @Test
    @DisplayName("the reasonless one-arg hook sees every collapse the result reports")
    void oneArgHookSeesEveryCollapse() {
        MaterialSpec softSoil = new MaterialSpec(2.0, 6.0);
        StructureGraph g = tunnel(softSoil);

        ReasonCapture cap = new ReasonCapture();
        BlastResult r = engine.process(g, blast(new NodePos(8, 3, 0), 3.0), cap);

        assertEquals(
                r.collapsed().size(),
                cap.allCollapses.size(),
                "one collapse callback per collapsed block, no more and no fewer");
    }

    /** A wide tunnel: bedrock floor, soil side-walls, a deep soil roof spanning the void. */
    private StructureGraph tunnel(MaterialSpec dirt) {
        StructureGraph g = new StructureGraph();
        int xHi = 16;
        int voidLo = 3;
        int voidHi = 13;
        for (int x = 0; x <= xHi; x++) {
            g.addGroundBlock(new NodePos(x, 0, 0));
        }
        for (int x = 0; x <= xHi; x++) {
            boolean inVoid = x >= voidLo && x <= voidHi;
            if (!inVoid) {
                g.addBlock(new NodePos(x, 1, 0), dirt, false);
                g.addBlock(new NodePos(x, 2, 0), dirt, false);
            }
        }
        for (int y = 3; y <= 11; y++) {
            for (int x = 0; x <= xHi; x++) {
                g.addBlock(new NodePos(x, y, 0), dirt, false);
            }
        }
        return g;
    }
}
