package dev.gesp.structural.graph;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StructureGraph#affectedRegion} must return the full upstream cone of a
 * disturbance, INDEPENDENT of neighbour-encounter order. The bug: addGroundedColumn
 * pre-added a support-column node to the result set without enqueuing it, and because
 * the result set doubled as the BFS visited-guard, a structural node the BFS reached
 * later through that column was silently skipped — dropping a genuine load path (a
 * block leaning on the column) from the closure in one ordering but not another.
 */
@DisplayName("affectedRegion: a block leaning on a support column is always in the closure")
class AffectedRegionClosureTest {

    private static final MaterialSpec STONE = new MaterialSpec(1.0, 100.0);

    @Test
    @DisplayName("A lateral leaner on a terrain-skin's column is included regardless of BFS order")
    void leanerOnColumnIsInClosure() {
        StructureGraph g = new StructureGraph();

        // Wall at x=0: ground G0, W1, damaged W2 (the disturbed seed — damage makes it
        // structural so the BFS expands from it).
        NodePos g0 = new NodePos(0, 0, 0);
        NodePos w1 = new NodePos(0, 1, 0);
        NodePos w2 = new NodePos(0, 2, 0);
        g.addGroundBlock(g0);
        g.addBlock(w1, STONE, false);
        g.addBlock(w2, STONE, false);
        g.getNode(w2).addDamage(0.5);

        // Terrain skin S at (1,2) over column C at (1,1) over ground G1. S is
        // terrain-stable (nothing above, grounded column, no leaner), so the BFS treats
        // it as context and addGroundedColumn pre-adds C.
        NodePos g1 = new NodePos(1, 0, 0);
        NodePos c = new NodePos(1, 1, 0);
        NodePos s = new NodePos(1, 2, 0);
        g.addGroundBlock(g1);
        g.addBlock(c, STONE, false);
        g.addBlock(s, STONE, false);

        // L hangs laterally off C with no grounded column of its own — a real load path
        // that only enters the closure if C is actually expanded.
        NodePos l = new NodePos(2, 1, 0);
        g.addBlock(l, STONE, false);

        Set<NodePos> region = g.affectedRegion(Set.of(w2));

        assertTrue(region.contains(c), "the column under the terrain skin must be in the closure");
        assertTrue(
                region.contains(l),
                "the block leaning on that column is a load path and must be in the closure, got: " + region);
    }

    /**
     * The other half of the contract: while the closure must include every real
     * load path, it must NOT flood laterally through a buried terrain slab. Every
     * buried cell has a block above it (so it reads "structural" to the skin
     * test), but a pristine buried cell whose connected column is capped by inert
     * skin and leaned on by nobody routes load strictly vertically — it belongs
     * in the closure only when it is part of the disturbance's own support
     * columns. Without that barrier a tower-on-terrain closure was the whole
     * map: one settle's nodeVisits grew 13x and the 50x50 cascade benchmark ran
     * 8x slower, for a bit-identical outcome.
     */
    @Test
    @DisplayName("A buried terrain slab does not flood the closure laterally")
    void buriedSlabDoesNotFloodClosure() {
        StructureGraph g = new StructureGraph();

        // 21x21 slab: grounded floor at y=0, two buried-able terrain layers at
        // y=1 (buried under y=2) and y=2 (skin).
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                g.addGroundBlock(new NodePos(x, 0, z));
                g.addBlock(new NodePos(x, 1, z), STONE, false);
                g.addBlock(new NodePos(x, 2, z), STONE, false);
            }
        }
        // A 1x1 tower at the center, with a damaged base — the structural seed.
        for (int y = 3; y <= 6; y++) {
            g.addBlock(new NodePos(0, y, 0), STONE, false);
        }
        g.getNode(new NodePos(0, 3, 0)).addDamage(0.5);

        Set<NodePos> region = g.affectedRegion(Set.of(new NodePos(0, 3, 0)));

        assertTrue(
                region.contains(new NodePos(0, 2, 0)) && region.contains(new NodePos(0, 1, 0)),
                "the tower's own support column is load-bearing context and must be present");
        assertTrue(
                !region.contains(new NodePos(5, 1, 5)) && !region.contains(new NodePos(-7, 1, 3)),
                "far buried slab cells are inert and must NOT be dragged in, got " + region.size() + " nodes");
        assertTrue(
                region.size() < 50,
                "the closure must stay structure-sized on a slab of 880+ terrain cells, got: " + region.size());
    }
}
