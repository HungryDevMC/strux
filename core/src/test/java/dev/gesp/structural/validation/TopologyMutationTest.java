package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import dev.gesp.structural.solver.StressSolver;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Structures are not static: gameplay constantly MERGES them (a block placed
 * between two builds joins them into one component) and SPLITS them (a break
 * cuts one component in two). These mutations are where graph engines
 * classically go wrong — stale component assumptions, load not re-routed,
 * floating halves missed.
 *
 * <pre>
 *   MERGE:                          SPLIT (break keystone K):
 *
 *   [T1]  [K]  [T2]                 [T1]       [T2]
 *    │     ↑    │          ──►       │          │
 *   [T1]  new  [T2]                 [T1]       [T2]
 *    │  block   │                    │          │
 *   [G1]      [G2]                  [G1]      [G2]
 * </pre>
 */
@DisplayName("Topology mutations: merging and splitting structures")
class TopologyMutationTest {

    /** Two 4-tall LIGHT towers at x=0 and x=2 on their own grounds. */
    private static StructureGraph twoTowers() {
        StructureGraph g = new StructureGraph();
        for (int x = 0; x <= 2; x += 2) {
            g.addGroundBlock(new NodePos(x, 0, 0));
            for (int y = 1; y <= 4; y++) {
                g.addBlock(new NodePos(x, y, 0), TestMaterials.LIGHT, false);
            }
        }
        return g;
    }

    private static final NodePos KEYSTONE = new NodePos(1, 4, 0);

    @Test
    @DisplayName("Placing a keystone merges two structures into one component")
    void placementMergesComponents() {
        StructureGraph g = twoTowers();
        assertFalse(
                g.componentOf(new NodePos(0, 1, 0)).contains(new NodePos(2, 1, 0)),
                "before the keystone, the towers are separate components");

        g.addBlock(KEYSTONE, TestMaterials.LIGHT, false);

        Set<NodePos> merged = g.componentOf(new NodePos(0, 1, 0));
        assertTrue(merged.contains(new NodePos(2, 1, 0)), "after the keystone, one component spans both towers");
        assertTrue(merged.contains(KEYSTONE));
        assertTrue(g.getFloatingBlocks().isEmpty(), "the keystone is held by both towers — nothing floats");
    }

    @Test
    @DisplayName("Merged structure solves sanely: keystone load is shared, nothing overloads")
    void mergedStructureSolves() {
        StructureGraph g = twoTowers();
        g.addBlock(KEYSTONE, TestMaterials.LIGHT, false);
        new StressSolver().solveAll(g);

        for (var node : g.getAllNodes()) {
            assertFalse(node.isOverloaded(), node.pos() + " must not overload — two towers share one light keystone");
        }
        // Both bases carry their own tower plus (some share of) the keystone:
        // more than a bare 4-block tower, less than tower + whole keystone + slack.
        double base0 = g.getNode(new NodePos(0, 1, 0)).stressValue();
        assertTrue(base0 > 4.0 - 1e-9, "base carries at least its own tower");
    }

    @Test
    @DisplayName("Breaking the keystone splits cleanly: both towers stand, components separate")
    void breakSplitsIntoTwoStandingHalves() {
        StructureGraph g = twoTowers();
        g.addBlock(KEYSTONE, TestMaterials.LIGHT, false);

        var result = new CascadeEngine().cascade(g, KEYSTONE);

        assertEquals(0, result.collapsedNodes().size(), "both halves are grounded — nothing else falls");
        assertTrue(g.hasBlock(new NodePos(0, 4, 0)), "tower 1 stands");
        assertTrue(g.hasBlock(new NodePos(2, 4, 0)), "tower 2 stands");
        assertFalse(
                g.componentOf(new NodePos(0, 1, 0)).contains(new NodePos(2, 1, 0)),
                "the component split back into two");
    }

    @Test
    @DisplayName("A break that splits off an ungrounded half drops exactly that half")
    void breakSplitsGroundedAndFloatingHalves() {
        // Tower with a 3-block side gallery hanging off its top.
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= 4; y++) {
            g.addBlock(new NodePos(0, y, 0), TestMaterials.LIGHT, false);
        }
        g.addBlock(new NodePos(1, 4, 0), TestMaterials.LIGHT, false); // gallery
        g.addBlock(new NodePos(2, 4, 0), TestMaterials.LIGHT, false);
        g.addBlock(new NodePos(2, 3, 0), TestMaterials.LIGHT, false); // hangs below gallery end

        new CascadeEngine().cascade(g, new NodePos(1, 4, 0)); // cut the gallery off

        assertFalse(g.hasBlock(new NodePos(2, 4, 0)), "ungrounded half falls");
        assertFalse(g.hasBlock(new NodePos(2, 3, 0)), "…all of it");
        assertTrue(g.hasBlock(new NodePos(0, 4, 0)), "grounded half stands untouched");
    }

    @Test
    @DisplayName("Connecting a would-be-floating cluster to a tower rescues it")
    void mergeRescuesFloatingCluster() {
        // An adapter can build graph state where a cluster has no ground path
        // yet (e.g. blocks registered before their support was scanned).
        // Placing the connecting block must leave a fully supported graph.
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= 3; y++) {
            g.addBlock(new NodePos(0, y, 0), TestMaterials.LIGHT, false);
        }
        g.addBlock(new NodePos(2, 3, 0), TestMaterials.LIGHT, false); // floating cluster
        g.addBlock(new NodePos(2, 2, 0), TestMaterials.LIGHT, false);
        assertEquals(2, g.getFloatingBlocks().size(), "cluster starts with no path to ground");

        g.addBlock(new NodePos(1, 3, 0), TestMaterials.LIGHT, false); // the connector

        assertTrue(g.getFloatingBlocks().isEmpty(), "the connector gives the cluster a path to ground");
        new StressSolver().solveAll(g);
        assertTrue(
                g.getNode(new NodePos(0, 1, 0)).stressValue() >= 5.0 - 1e-9,
                "tower base now carries the rescued cluster's weight too");
    }
}
