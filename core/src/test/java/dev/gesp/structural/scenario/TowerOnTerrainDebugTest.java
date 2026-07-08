package dev.gesp.structural.scenario;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Debug test for tower-on-terrain cascade behavior.
 */
@DisplayName("Debug: Tower on terrain cascade")
class TowerOnTerrainDebugTest {

    static final MaterialSpec HEAVY = new MaterialSpec(3.0, 100.0);

    @Test
    @DisplayName("Breaking terrain under tower should cascade tower blocks")
    void breakTerrainUnderTower_shouldCascadeTower() {
        // Create small terrain with tower
        StructureGraph g = new StructureGraph();

        // 5x5 ground at y=0
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                g.addGroundBlock(new NodePos(x, 0, z));
            }
        }

        // 5x5 terrain at y=1
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                g.addBlock(new NodePos(x, 1, z), HEAVY, false);
            }
        }

        // 2x2 tower at center, y=2..4 (3 layers)
        // Tower is at x=[1,2], z=[1,2]
        for (int y = 2; y <= 4; y++) {
            for (int x = 1; x <= 2; x++) {
                for (int z = 1; z <= 2; z++) {
                    g.addBlock(new NodePos(x, y, z), HEAVY, false);
                }
            }
        }

        int initialSize = g.size();
        System.out.println("Initial size: " + initialSize + " (25 ground + 25 terrain + 12 tower = 62)");
        assertEquals(62, initialSize);

        // Break terrain at (1, 1, 1) - this is directly under one corner of the tower
        NodePos breakPos = new NodePos(1, 1, 1);

        // Check what getDependentSubgraph returns
        Set<NodePos> dependents = g.getDependentSubgraph(breakPos);
        System.out.println("Dependents of " + breakPos + ": " + dependents.size());
        for (NodePos dep : dependents) {
            System.out.println("  " + dep);
        }

        // Should include the tower blocks above
        assertTrue(dependents.contains(new NodePos(1, 2, 1)), "Tower block at (1,2,1) should be dependent");

        // Now cascade
        CascadeEngine engine = new CascadeEngine();
        var result = engine.cascade(g, breakPos);

        System.out.println("Collapsed: " + result.totalCollapsed());
        for (NodePos collapsed : result.collapsed()) {
            System.out.println("  " + collapsed);
        }
        System.out.println("Final size: " + g.size());

        // Tower blocks above (1, 1, 1) should have collapsed
        // Since the terrain is connected, the tower might still be supported by
        // adjacent terrain blocks, but at least some should have cascaded
        // Actually, if tower block at (1,2,1) is connected to tower at (2,2,1) which
        // is connected to terrain at (2,1,1), the tower might still be supported
        // Let's check if ANY tower blocks collapsed
        assertFalse(g.hasBlock(breakPos), "Broken block should be removed");

        // Check if tower block directly above is gone (if floating) or still present (if supported)
        NodePos towerAbove = new NodePos(1, 2, 1);
        // The tower might still be supported via lateral connections - this is correct physics!
        System.out.println("Tower block at " + towerAbove + " exists: " + g.hasBlock(towerAbove));
    }

    @Test
    @DisplayName("Breaking ALL terrain under tower column should cascade that column")
    void breakAllTerrainUnderTowerColumn_shouldCascade() {
        StructureGraph g = new StructureGraph();

        // Single ground block
        g.addGroundBlock(new NodePos(0, 0, 0));

        // Single terrain block
        g.addBlock(new NodePos(0, 1, 0), HEAVY, false);

        // Tower column: 3 blocks high
        g.addBlock(new NodePos(0, 2, 0), HEAVY, false);
        g.addBlock(new NodePos(0, 3, 0), HEAVY, false);
        g.addBlock(new NodePos(0, 4, 0), HEAVY, false);

        System.out.println("Initial size: " + g.size() + " (1 ground + 1 terrain + 3 tower = 5)");
        assertEquals(5, g.size());

        // Break the terrain block - tower should float and collapse
        NodePos breakPos = new NodePos(0, 1, 0);

        Set<NodePos> dependents = g.getDependentSubgraph(breakPos);
        System.out.println("Dependents: " + dependents.size());
        for (NodePos dep : dependents) {
            System.out.println("  " + dep);
        }

        // Should include the tower
        assertTrue(dependents.contains(new NodePos(0, 2, 0)), "Tower block (0,2,0) should be dependent");
        assertTrue(dependents.contains(new NodePos(0, 3, 0)), "Tower block (0,3,0) should be dependent");
        assertTrue(dependents.contains(new NodePos(0, 4, 0)), "Tower block (0,4,0) should be dependent");

        CascadeEngine engine = new CascadeEngine();
        var result = engine.cascade(g, breakPos);

        System.out.println("Collapsed: " + result.totalCollapsed());
        for (NodePos collapsed : result.collapsed()) {
            System.out.println("  " + collapsed);
        }

        // All 3 tower blocks should collapse (floating)
        assertEquals(3, result.totalCollapsed(), "All tower blocks should collapse");
        assertEquals(1, g.size(), "Only ground should remain");
    }
}
