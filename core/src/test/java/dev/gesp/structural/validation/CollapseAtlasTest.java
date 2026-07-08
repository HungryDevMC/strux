package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.assess.CollapseAtlas;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import dev.gesp.structural.solver.CascadeResult;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The collapse atlas: predict which blocks lose support if a given block is
 * removed, without running a simulation — and stay correct against the real
 * cascade for connectivity collapse.
 */
@DisplayName("CollapseAtlas: predict loss-of-support collapse")
class CollapseAtlasTest {

    private static void column(StructureGraph g, int x, int height) {
        g.addGroundBlock(new NodePos(x, 0, 0));
        for (int y = 1; y <= height; y++) {
            g.addBlock(new NodePos(x, y, 0), TestMaterials.HEAVY, false);
        }
    }

    @Test
    @DisplayName("Breaking a column's base drops everything above it")
    void columnBaseIsCritical() {
        StructureGraph g = new StructureGraph();
        column(g, 0, 6);
        CollapseAtlas atlas = new CollapseAtlas(g);

        assertEquals(5, atlas.collapseSize(new NodePos(0, 1, 0)), "base supports the 5 blocks above it");
        assertEquals(0, atlas.collapseSize(new NodePos(0, 6, 0)), "top block supports nothing");
        // A middle block supports everything above it (y=4,5,6).
        assertEquals(3, atlas.collapseSize(new NodePos(0, 3, 0)));
    }

    @Test
    @DisplayName("dependents() matches the real cascade for a connectivity collapse")
    void matchesCascade() {
        StructureGraph g = new StructureGraph();
        column(g, 0, 8);
        NodePos base = new NodePos(0, 1, 0);

        Set<NodePos> predicted = new HashSet<>(new CollapseAtlas(g).dependents(base));

        // Run the real cascade on a copy and collect what actually fell.
        CascadeResult result = new CascadeEngine().cascade(g.copy(), base);
        Set<NodePos> actuallyFell = new HashSet<>();
        for (CollapsedNode fell : result.collapsedNodes()) {
            actuallyFell.add(fell.pos());
        }

        assertEquals(actuallyFell, predicted, "atlas prediction must match the cascade's collapse set");
    }

    @Test
    @DisplayName("A redundantly-supported block is not critical")
    void redundantSupportIsNotCritical() {
        // Two side-by-side columns joined by a top block: removing one base leaves
        // the top still reachable via the other column.
        StructureGraph g = new StructureGraph();
        column(g, 0, 3);
        column(g, 1, 3);
        g.connect(new NodePos(0, 3, 0), new NodePos(1, 3, 0)); // already adjacent, idempotent

        CollapseAtlas atlas = new CollapseAtlas(g);
        // Removing one base: that column's own blocks reroute through the bridge, so
        // little (or nothing) is fully cut off from ground.
        assertTrue(atlas.collapseSize(new NodePos(0, 1, 0)) < 3, "redundancy reduces criticality");
    }

    @Test
    @DisplayName("Cache invalidates when the topology changes")
    void cacheInvalidatesOnTopologyChange() {
        StructureGraph g = new StructureGraph();
        column(g, 0, 4);
        CollapseAtlas atlas = new CollapseAtlas(g);

        assertEquals(3, atlas.collapseSize(new NodePos(0, 1, 0)));

        long before = g.version();
        g.addBlock(new NodePos(0, 5, 0), TestMaterials.HEAVY, false); // extend the column
        assertTrue(g.version() > before, "adding a block bumps the topology version");

        assertEquals(4, atlas.collapseSize(new NodePos(0, 1, 0)), "atlas re-reads after the topology changed");
    }

    @Test
    @DisplayName("rankBySupport surfaces the base as the most critical block")
    void rankingFindsTheWeakPoint() {
        StructureGraph g = new StructureGraph();
        column(g, 0, 5);
        var ranked = new CollapseAtlas(g).rankBySupport(3);

        assertFalse(ranked.isEmpty());
        assertEquals(new NodePos(0, 1, 0), ranked.get(0).pos(), "the base is the single most critical support");
        assertEquals(4, ranked.get(0).collapseSize());
    }
}
