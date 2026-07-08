package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.NodePos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StructureGraph#version()} is the invalidation key for
 * topology-keyed caches (CollapseAtlas). Its contract: bump on every REAL
 * topology change, and ONLY on real changes — a version that churns on no-ops
 * silently degrades every cache built on it (an atlas rebuilt for nothing on
 * each idempotent connect is an atlas that never survives a siege tick).
 */
@DisplayName("Graph version: bumps on real topology changes only")
class GraphVersionTest {

    private static final NodePos A = new NodePos(0, 0, 0);
    private static final NodePos B = new NodePos(0, 1, 0);

    private static StructureGraph pair() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(A);
        g.addBlock(B, TestMaterials.LIGHT, false);
        return g;
    }

    @Test
    @DisplayName("Real changes bump: add, connect, disconnect, remove")
    void realChangesBump() {
        StructureGraph g = new StructureGraph();
        long v0 = g.version();
        g.addGroundBlock(A);
        long v1 = g.version();
        assertTrue(v1 > v0, "add bumps");
        g.addBlock(B, TestMaterials.LIGHT, false);
        long v2 = g.version();
        assertTrue(v2 > v1, "add+connect bumps");
        g.disconnect(A, B);
        long v3 = g.version();
        assertTrue(v3 > v2, "disconnect bumps");
        g.connect(A, B);
        long v4 = g.version();
        assertTrue(v4 > v3, "reconnect bumps");
        g.removeBlock(B);
        assertTrue(g.version() > v4, "remove bumps");
    }

    @Test
    @DisplayName("Idempotent connect does not bump")
    void duplicateConnectDoesNotBump() {
        StructureGraph g = pair(); // A↔B already connected by addBlock
        long v = g.version();
        g.connect(A, B);
        assertEquals(v, g.version(), "the edge already existed — nothing changed");
    }

    @Test
    @DisplayName("Disconnect of a non-existent edge does not bump")
    void noOpDisconnectDoesNotBump() {
        StructureGraph g = pair();
        g.disconnect(A, B);
        long v = g.version();
        g.disconnect(A, B); // already gone
        assertEquals(v, g.version(), "nothing was removed — nothing changed");
        g.disconnect(new NodePos(9, 9, 9), new NodePos(8, 8, 8)); // neither exists
        assertEquals(v, g.version(), "edges between missing nodes are not changes");
    }

    @Test
    @DisplayName("Adding an already-present block does not bump")
    void duplicateAddDoesNotBump() {
        StructureGraph g = pair();
        long v = g.version();
        g.addBlock(B, TestMaterials.HEAVY, false); // position taken — no-op
        assertEquals(v, g.version());
    }

    @Test
    @DisplayName("Removing a missing block does not bump")
    void removeMissingDoesNotBump() {
        StructureGraph g = pair();
        long v = g.version();
        g.removeBlock(new NodePos(5, 5, 5));
        assertEquals(v, g.version());
    }

    @Test
    @DisplayName("Damage and reinforcement edits never bump (stress state is not topology)")
    void stateEditsDoNotBump() {
        StructureGraph g = pair();
        long v = g.version();
        g.getNode(B).addDamage(0.5);
        g.getNode(B).setReinforcement(2.0);
        g.getNode(B).repair();
        assertEquals(v, g.version(), "the atlas must survive damage-only siege ticks");
    }
}
