package dev.gesp.structural.blast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.api.CollapseReason;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Two blast-engine invariants, each locked by a fix in this area:
 *
 * <ul>
 *   <li><b>Altitude invariance.</b> The phase-4 overload solve grows scope down each
 *       support column with NO y floor (it used to stop at y=0), so a structure built
 *       at or below y=0 — real coordinates since MC 1.18's -64 floor — must collapse
 *       identically to the same structure above y=0. This pins that invariant directly.
 *   <li><b>Occlusion fairness.</b> The occlusion DDA steps diagonally through voxel
 *       corners on exact ties instead of grazing one side, so a centred blast on a
 *       symmetric structure must destroy a mirror-symmetric set. (The exact cover-count
 *       change is locked separately by the {@code tower-blast-occluded} golden snapshot.)
 * </ul>
 */
@DisplayName("Blast: overload cascade works below y=0, occlusion is mirror-symmetric")
class BlastBelowGroundAndOcclusionTest {

    private final StruxExplosionEngine engine = new StruxExplosionEngine();

    private static BlastContext blast(NodePos center, double power, BlastOcclusion occlusion) {
        return BlastContext.builder()
                .center(center)
                .power(power)
                .occlusion(occlusion)
                .build();
    }

    private static final int BLAST_Y = 3; // roof base, over the void centre

    /** A wide soil tunnel whose roof, when blown open, overloads the side banks. */
    private static StructureGraph tunnel(MaterialSpec dirt, int yOffset) {
        StructureGraph g = new StructureGraph();
        int xHi = 16;
        int voidLo = 3;
        int voidHi = 13;
        for (int x = 0; x <= xHi; x++) {
            g.addGroundBlock(new NodePos(x, yOffset, 0));
        }
        for (int x = 0; x <= xHi; x++) {
            boolean inVoid = x >= voidLo && x <= voidHi;
            if (!inVoid) {
                g.addBlock(new NodePos(x, yOffset + 1, 0), dirt, false);
                g.addBlock(new NodePos(x, yOffset + 2, 0), dirt, false);
            }
        }
        for (int y = 3; y <= 11; y++) {
            for (int x = 0; x <= xHi; x++) {
                g.addBlock(new NodePos(x, yOffset + y, 0), dirt, false);
            }
        }
        return g;
    }

    private static Set<NodePos> shiftedSet(List<NodePos> positions, int unshiftY) {
        Set<NodePos> set = new HashSet<>();
        for (NodePos p : positions) {
            set.add(new NodePos(p.x(), p.y() - unshiftY, p.z()));
        }
        return set;
    }

    @Test
    @DisplayName("A structure below y=0 collapses identically to the same structure above y=0")
    void overloadCascadeIsHeightInvariant() {
        MaterialSpec softSoil = new MaterialSpec(2.0, 6.0);

        ReasonCapture atZero = new ReasonCapture();
        StructureGraph gZero = tunnel(softSoil, 0);
        var rZero = engine.process(gZero, blast(new NodePos(8, BLAST_Y, 0), 3.0, BlastOcclusion.NONE), atZero);

        // The same tunnel, dropped well below the y=0 line (ground at y=-30): the
        // column-walk floor the fix removed lived at y=0, so a build down here must
        // still collapse exactly as it does at the surface.
        int offset = -30;
        ReasonCapture below = new ReasonCapture();
        StructureGraph gBelow = tunnel(softSoil, offset);
        var rBelow =
                engine.process(gBelow, blast(new NodePos(8, BLAST_Y + offset, 0), 3.0, BlastOcclusion.NONE), below);

        // The roof load crushing the banks is an OVERLOADED collapse, and it must still
        // run below y=0 (not silently degrade to floating-only).
        assertTrue(
                below.reasons.contains(CollapseReason.OVERLOADED),
                "the overload cascade must run below y=0, reasons were " + below.reasons);
        // And the whole outcome is height-invariant: identical collapsed set once shifted.
        assertEquals(
                shiftedSet(rZero.collapsed(), 0),
                shiftedSet(rBelow.collapsed(), offset),
                "the same structure must collapse identically regardless of vertical offset");
    }

    /**
     * A solid {@code w×d×h} box on a grounded footing — symmetric under x↔(w-1-x) and
     * z↔(d-1-z), so a fair occlusion model must destroy a mirror-symmetric set.
     */
    private static StructureGraph box(int w, int d, int h) {
        StructureGraph g = new StructureGraph();
        MaterialSpec stone = new MaterialSpec(1.0, 50.0);
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                g.addGroundBlock(new NodePos(x, 0, z));
                for (int y = 1; y <= h; y++) {
                    g.addBlock(new NodePos(x, y, z), stone, false);
                }
            }
        }
        return g;
    }

    @Test
    @DisplayName("Diagonal occlusion is mirror-symmetric: a centred blast destroys a symmetric set")
    void occlusionIsMirrorSymmetric() {
        int w = 5;
        int d = 5;
        int h = 9;
        StructureGraph g = box(w, d, h);

        // Centre the blast directly above the middle column so the rays down to the
        // shielded blocks run along diagonals — the case the corner tie-break skewed.
        ReasonCapture cap = new ReasonCapture();
        var r = engine.process(g, blast(new NodePos(2, h + 3, 2), 8.0, BlastOcclusion.RAYCAST), cap);

        Set<NodePos> destroyed = new HashSet<>(r.destroyed());
        assertFalse(destroyed.isEmpty(), "a centred blast must crater the exposed top");
        for (NodePos p : destroyed) {
            NodePos mirrorX = new NodePos(w - 1 - p.x(), p.y(), p.z());
            NodePos mirrorZ = new NodePos(p.x(), p.y(), d - 1 - p.z());
            assertTrue(destroyed.contains(mirrorX), "x-mirror of destroyed " + p + " must also be destroyed");
            assertTrue(destroyed.contains(mirrorZ), "z-mirror of destroyed " + p + " must also be destroyed");
        }
    }

    /** Captures collapse reasons and destroyed blocks from a blast. */
    private static final class ReasonCapture implements BlastCallback {
        final Set<CollapseReason> reasons = new HashSet<>();
        final List<NodePos> allCollapses = new ArrayList<>();

        @Override
        public void onCollapse(NodePos pos, CollapseReason reason) {
            reasons.add(reason);
            allCollapses.add(pos);
        }
    }
}
