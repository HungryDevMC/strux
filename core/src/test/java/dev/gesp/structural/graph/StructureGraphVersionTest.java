package dev.gesp.structural.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link StructureGraph#modCount()} — the stamp the async settle path uses to
 * detect that the graph changed under an in-flight solve. The risk is a silent miss:
 * a mutator that does NOT bump the stamp lets a stale collapse apply (blocks vanish
 * that a player just reinforced). So this enumerates <em>every</em> graph mutator and
 * asserts it moves the stamp — topology AND per-node state (damage / repair /
 * reinforcement) — while the topology-only {@link StructureGraph#version()} stays put
 * for the state-only edits.
 */
@DisplayName("StructureGraph.modCount moves on every state change")
class StructureGraphVersionTest {

    private static final MaterialSpec LIGHT = new MaterialSpec(1.0, 100.0);

    @Test
    @DisplayName("topology mutators bump modCount")
    void topologyBumpsModCount() {
        StructureGraph g = new StructureGraph();

        long m0 = g.modCount();
        g.addNode(new NodePos(0, 0, 0), MaterialSpec.GROUND, true);
        assertTrue(g.modCount() > m0, "addNode must bump modCount");

        long m1 = g.modCount();
        g.addNode(new NodePos(0, 1, 0), LIGHT, false);
        assertTrue(g.modCount() > m1, "second addNode must bump modCount");

        long m2 = g.modCount();
        g.connect(new NodePos(0, 0, 0), new NodePos(0, 1, 0));
        assertTrue(g.modCount() > m2, "connect must bump modCount");

        long m3 = g.modCount();
        g.disconnect(new NodePos(0, 0, 0), new NodePos(0, 1, 0));
        assertTrue(g.modCount() > m3, "disconnect must bump modCount");

        long m4 = g.modCount();
        g.addBlock(new NodePos(0, 2, 0), LIGHT, false);
        assertTrue(g.modCount() > m4, "addBlock must bump modCount");

        long m5 = g.modCount();
        g.removeBlock(new NodePos(0, 2, 0));
        assertTrue(g.modCount() > m5, "removeBlock must bump modCount");
    }

    @Test
    @DisplayName("damage / repair / reinforcement bump modCount but NOT the topology version")
    void stateMutatorsBumpModCountOnly() {
        StructureGraph g = new StructureGraph();
        NodePos p = new NodePos(0, 1, 0);
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(p, LIGHT, false);

        long topoVersion = g.version();
        long m0 = g.modCount();

        assertTrue(g.applyDamage(p, 0.3), "applyDamage on a present node returns true");
        assertTrue(g.modCount() > m0, "applyDamage must bump modCount");

        long m1 = g.modCount();
        assertTrue(g.reinforceNode(p, 1.5), "reinforceNode on a present node returns true");
        assertTrue(g.modCount() > m1, "reinforceNode must bump modCount");

        long m2 = g.modCount();
        assertTrue(g.repairNode(p), "repairNode on a present node returns true");
        assertTrue(g.modCount() > m2, "repairNode must bump modCount");

        assertEquals(
                topoVersion,
                g.version(),
                "state-only edits must NOT bump the topology version (connectivity caches survive them)");
        assertEquals(0.0, g.getNode(p).damage(), "repair cleared the damage");
        assertEquals(1.5, g.getNode(p).reinforcement(), "reinforcement was applied");
    }

    @Test
    @DisplayName("state mutators on a missing node are a no-op and do not bump modCount")
    void missingNodeMutatorsAreNoOp() {
        StructureGraph g = new StructureGraph();
        NodePos ghost = new NodePos(9, 9, 9);
        long m0 = g.modCount();

        assertFalse(g.applyDamage(ghost, 1.0), "applyDamage on a missing node returns false");
        assertFalse(g.repairNode(ghost), "repairNode on a missing node returns false");
        assertFalse(g.reinforceNode(ghost, 2.0), "reinforceNode on a missing node returns false");
        assertEquals(m0, g.modCount(), "no-op mutators must not bump modCount");
    }

    @Test
    @DisplayName("copySubgraph carries per-node state and starts its own modCount")
    void copySubgraphCarriesState() {
        StructureGraph g = new StructureGraph();
        NodePos p = new NodePos(0, 1, 0);
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(p, LIGHT, false);
        g.applyDamage(p, 0.4);
        g.reinforceNode(p, 1.25);

        StructureGraph snap = g.copySubgraph(java.util.Set.of(new NodePos(0, 0, 0), p));
        assertEquals(0.4, snap.getNode(p).damage(), "snapshot preserves damage");
        assertEquals(1.25, snap.getNode(p).reinforcement(), "snapshot preserves reinforcement");
        // A mutation on the live graph must not touch the snapshot's stamp.
        long snapMod = snap.modCount();
        g.applyDamage(p, 0.1);
        assertEquals(snapMod, snap.modCount(), "the snapshot is an independent copy — live edits don't move its stamp");
    }
}
