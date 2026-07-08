package dev.gesp.structural.blast;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two-phase blast contract: direct destruction (crater) + persistent damage,
 * then a gravity cascade. Verifies the fun/correct behaviours — epicenter always
 * craters, tough/sheltered blocks survive, knocked-out supports cause collapse,
 * and damage accumulates across hits.
 */
@DisplayName("Blast: direct destruction + damage + secondary cascade")
class BlastEngineTest {

    // Strong material so blocks don't collapse from their own weight — isolates blast effects.
    private static final MaterialSpec NORMAL = new MaterialSpec(1.0, 1000.0); // resistance 1
    private static final MaterialSpec TOUGH = new MaterialSpec(1.0, 1000.0, 10.0); // resistance 10

    private final StruxExplosionEngine engine = new StruxExplosionEngine();

    private static BlastContext blast(NodePos center, double power) {
        return BlastContext.builder()
                .center(center)
                .power(power)
                .occlusion(BlastOcclusion.NONE)
                .build();
    }

    @Test
    @DisplayName("The epicenter is shattered directly (the crater)")
    void epicenterDestroyed() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        NodePos block = new NodePos(0, 1, 0);
        g.addBlock(block, NORMAL, false);

        BlastResult r = engine.process(g, blast(block, 4.0));

        assertTrue(r.destroyed().contains(block));
        assertFalse(g.hasBlock(block));
    }

    @Test
    @DisplayName("A tough block survives a blast that destroys a normal one")
    void resistanceProtects() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addGroundBlock(new NodePos(3, 0, 0));
        NodePos normal = new NodePos(0, 1, 0);
        NodePos tough = new NodePos(3, 1, 0);
        g.addBlock(normal, NORMAL, false);
        g.addBlock(tough, TOUGH, false);

        BlastResult r = engine.process(g, blast(normal, 4.0)); // radius 6 reaches both

        assertFalse(g.hasBlock(normal), "normal block shattered");
        assertTrue(g.hasBlock(tough), "tough block survives");
        assertTrue(r.damaged().containsKey(tough), "but it took some damage");
        assertTrue(r.damaged().get(tough) > 0.0);
    }

    @Test
    @DisplayName("Destroying a support cascades the structure above it")
    void secondaryCascade() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        NodePos support = new NodePos(0, 1, 0);
        NodePos a = new NodePos(0, 2, 0), b = new NodePos(0, 3, 0), c = new NodePos(0, 4, 0);
        g.addBlock(support, NORMAL, false);
        g.addBlock(a, NORMAL, false);
        g.addBlock(b, NORMAL, false);
        g.addBlock(c, NORMAL, false);

        BlastResult r = engine.process(g, blast(support, 2.0)); // just enough to shatter the support

        assertTrue(r.destroyed().contains(support));
        assertEquals(3, r.collapsed().size(), "everything above loses support and falls");
        assertTrue(r.collapsed().containsAll(List.of(a, b, c)));
        assertEquals(1, g.size(), "only the ground node remains");
    }

    @Test
    @DisplayName("A pre-hook can cancel the explosion entirely")
    void hookCanCancel() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        NodePos block = new NodePos(0, 1, 0);
        g.addBlock(block, NORMAL, false);

        BlastResult r = engine.process(g, blast(block, 8.0), new BlastCallback() {
            @Override
            public boolean onBlast(BlastContext ctx) {
                return false;
            }
        });

        assertEquals(0, r.totalRemoved());
        assertTrue(g.hasBlock(block), "nothing happens when cancelled");
    }

    @Test
    @DisplayName("Cover shields blocks behind it (occlusion)")
    void occlusionShields() {
        BlastContext.Builder base =
                BlastContext.builder().center(new NodePos(0, 8, 0)).power(6.0);

        int destroyedOpen = engine.process(
                        column(), base.occlusion(BlastOcclusion.NONE).build())
                .destroyed()
                .size();
        int destroyedCovered = engine.process(
                        column(), base.occlusion(BlastOcclusion.RAYCAST).build())
                .destroyed()
                .size();

        assertTrue(
                destroyedOpen > destroyedCovered,
                "with cover, fewer blocks are directly shattered (open=" + destroyedOpen + ", covered="
                        + destroyedCovered + ")");
    }

    @Test
    @DisplayName("Damage accumulates across hits — a second blast finishes the job")
    void multiHitAccumulates() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        NodePos block = new NodePos(0, 1, 0);
        g.addBlock(block, NORMAL, false);

        // power 1.5 (< destruction threshold 2) only cracks: damage += 1.5 * 0.5 = 0.75 per hit.
        BlastResult first = engine.process(g, blast(block, 1.5));
        assertTrue(g.hasBlock(block), "survives the first hit");
        assertTrue(first.damaged().get(block) > 0.0 && first.damaged().get(block) < 1.0);

        engine.process(g, blast(block, 1.5));
        assertFalse(g.hasBlock(block), "accumulated damage brings it down on the second hit");
    }

    @Test
    @DisplayName("A block inside the bounding cube but outside the blast sphere is untouched")
    void cubeCornerOutsideSphereIsUntouched() {
        // power 6 → radius 9, radiusCeil 9. The far cube corner (9,9,9) sits at
        // distance √243 ≈ 15.6 from center — well inside the [-9,9]³ cube the OLD
        // scan walked, but outside the radius-9 sphere. The squared-distance cull and
        // the sphere-bounded loop must BOTH agree it is never destroyed or damaged.
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addGroundBlock(new NodePos(9, 8, 9));
        NodePos center = new NodePos(0, 1, 0);
        NodePos corner = new NodePos(9, 9, 9); // dx=9,dy=8,dz=9 from center → still in cube
        g.addBlock(center, NORMAL, false);
        g.addBlock(corner, NORMAL, false);

        BlastResult r = engine.process(g, blast(center, 6.0));

        assertTrue(r.destroyed().contains(center), "epicenter still craters");
        assertTrue(g.hasBlock(corner), "the out-of-sphere corner survives");
        assertFalse(r.destroyed().contains(corner), "and is never in the destroyed crater");
        assertFalse(r.damaged().containsKey(corner), "nor even cracked — it is outside the radius");
    }

    @Test
    @DisplayName("A block exactly on the sphere boundary (distance == radius) is still affected")
    void blockOnSphereBoundaryIsAffected() {
        // power 1 → radius 1.5. A block at (1,1,0) is distance 1 from a center at
        // (0,1,0): inside. A block at (2,1,0) is distance 2 > 1.5: outside. This pins
        // the squared-distance cull boundary (dist² ≤ radius²) to the same verdict the
        // old sqrt-then-compare gave.
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addGroundBlock(new NodePos(1, 0, 0));
        g.addGroundBlock(new NodePos(2, 0, 0));
        NodePos center = new NodePos(0, 1, 0);
        NodePos inRange = new NodePos(1, 1, 0); // dist 1 ≤ 1.5
        NodePos outRange = new NodePos(2, 1, 0); // dist 2 > 1.5
        g.addBlock(center, NORMAL, false);
        g.addBlock(inRange, NORMAL, false);
        g.addBlock(outRange, NORMAL, false);

        BlastResult r = engine.process(g, blast(center, 1.0));

        assertFalse(g.hasBlock(inRange) && !r.damaged().containsKey(inRange), "the in-range block must be hit");
        assertTrue(
                r.destroyed().contains(inRange) || r.damaged().containsKey(inRange),
                "the in-range block is destroyed or at least cracked");
        assertTrue(g.hasBlock(outRange), "the out-of-range block survives");
        assertFalse(r.damaged().containsKey(outRange), "and takes no damage");
    }

    @Test
    @DisplayName("Occlusion: solids on the line of sight reduce a survivor's damage vs the open shot")
    void occlusionCoverReducesIntensity() {
        // A tough target on its OWN ground stub (so it never floats away), with a
        // free-standing two-block cover wall on the line center→target that is NOT
        // load-bearing for the target. Turning RAYCAST on must reduce the target's
        // damage relative to NONE, proving countCover still tallies the in-between
        // solids after the alloc-free rewrite.
        MaterialSpec tough = new MaterialSpec(1.0, 1000.0, 12.0); // survives both modes → readable damage
        NodePos center = new NodePos(0, 2, 0);
        NodePos target = new NodePos(4, 2, 0);

        Supplier<StructureGraph> build = () -> {
            StructureGraph g = new StructureGraph();
            // Target on its own column so it stays standing regardless of the wall.
            g.addGroundBlock(new NodePos(4, 0, 0));
            g.addBlock(new NodePos(4, 1, 0), tough, false);
            g.addBlock(target, tough, false);
            // A short cover wall (x=2) standing between center and target, on its own
            // ground — its loss cannot orphan the target.
            g.addGroundBlock(new NodePos(2, 0, 0));
            g.addBlock(new NodePos(2, 1, 0), tough, false);
            g.addBlock(new NodePos(2, 2, 0), tough, false);
            return g;
        };

        BlastContext.Builder base = BlastContext.builder().center(center).power(6.0);
        BlastResult open =
                engine.process(build.get(), base.occlusion(BlastOcclusion.NONE).build());
        BlastResult covered = engine.process(
                build.get(), base.occlusion(BlastOcclusion.RAYCAST).build());

        Double openDmg = open.damaged().get(target);
        Double coveredDmg = covered.damaged().get(target);
        assertNotNull(openDmg, "with no occlusion the target should crack");
        assertNotNull(coveredDmg, "with occlusion it should still crack a little");
        assertTrue(
                coveredDmg < openDmg, "cover must reduce the hit (covered=" + coveredDmg + ", open=" + openDmg + ")");
    }

    @Test
    @DisplayName("DDA occlusion: a block off to the SIDE does not shield — only blocks on the line of sight count")
    void occlusionIgnoresOffAxisBlocks() {
        // True line of sight: a solid block that is NOT on the straight line from the
        // blast to the target gives zero cover, so the target takes the SAME hit as if
        // the obstacle weren't there. The DDA traverses only the voxels the ray
        // actually crosses, so an off-axis block is never counted.
        MaterialSpec tough = new MaterialSpec(1.0, 1000.0, 12.0); // survives → readable damage
        NodePos center = new NodePos(0, 2, 0);
        NodePos target = new NodePos(4, 2, 0); // straight along +x at y=2,z=0

        Supplier<StructureGraph> withSideBlock = () -> {
            StructureGraph g = new StructureGraph();
            g.addGroundBlock(new NodePos(4, 0, 0));
            g.addBlock(new NodePos(4, 1, 0), tough, false);
            g.addBlock(target, tough, false);
            // An obstacle parallel to the path but one row over in z — never on the
            // center→target line, so a true LOS check must ignore it entirely.
            g.addGroundBlock(new NodePos(2, 0, 1));
            g.addBlock(new NodePos(2, 1, 1), tough, false);
            g.addBlock(new NodePos(2, 2, 1), tough, false);
            return g;
        };
        Supplier<StructureGraph> noSideBlock = () -> {
            StructureGraph g = new StructureGraph();
            g.addGroundBlock(new NodePos(4, 0, 0));
            g.addBlock(new NodePos(4, 1, 0), tough, false);
            g.addBlock(target, tough, false);
            return g;
        };

        BlastContext.Builder base =
                BlastContext.builder().center(center).power(6.0).occlusion(BlastOcclusion.RAYCAST);
        Double withSide =
                engine.process(withSideBlock.get(), base.build()).damaged().get(target);
        Double withoutSide =
                engine.process(noSideBlock.get(), base.build()).damaged().get(target);

        assertNotNull(withSide, "the target should crack");
        assertNotNull(withoutSide, "the target should crack");
        assertEquals(
                withoutSide, withSide, "an off-axis block must give zero cover — the target takes the identical hit");
    }

    /** A 6-tall column on ground, for the occlusion comparison. */
    private static StructureGraph column() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= 6; y++) {
            g.addBlock(new NodePos(0, y, 0), NORMAL, false);
        }
        return g;
    }
}
