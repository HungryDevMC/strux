package dev.gesp.structural.minecraft.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WorldGraphStore#mergeLoaded}: a boot load must MERGE the
 * persisted graph into the live one (live wins on conflict) rather than clobber it,
 * so a plugin's enable-time registrations survive a disk read that lands a tick later.
 */
@DisplayName("WorldGraphStore.mergeLoaded: union with live winning on conflict")
class WorldGraphStoreMergeTest {

    private static final UUID WORLD = UUID.randomUUID();

    private static StructureGraph graphWith(NodePos pos, MaterialSpec spec, boolean grounded) {
        StructureGraph graph = new StructureGraph();
        graph.addBlock(pos, spec, grounded);
        return graph;
    }

    @Test
    @DisplayName("live node wins on a shared position — the saved node is ignored")
    void liveWinsOnConflict() {
        WorldGraphStore store = new WorldGraphStore();
        NodePos pos = new NodePos(0, 64, 0);
        // Register a live node (the plugin's onEnable build) with mass 2, no damage.
        StructureGraph live = graphWith(pos, new MaterialSpec(2.0, 30.0), false);
        store.put(WORLD, live);

        // A stale save for the same position: different spec, with damage.
        StructureGraph loaded = new StructureGraph();
        loaded.addBlock(pos, new MaterialSpec(5.0, 99.0), false);
        loaded.getNode(pos).addDamage(9.0);

        store.mergeLoaded(WORLD, loaded);

        StructureGraph merged = store.get(WORLD);
        assertSame(live, merged, "the live graph instance is kept, not replaced");
        assertEquals(1, merged.size(), "no extra node — the shared position is not duplicated");
        Node node = merged.getNode(pos);
        assertEquals(2.0, node.mass(), "the LIVE spec wins");
        assertEquals(0.0, node.damage(), "the live node keeps its state (no stale damage)");
    }

    @Test
    @DisplayName("persisted-only node is added with full saved state (damage/reinforcement/grounded)")
    void persistedOnlyNodeAddedWithFullState() {
        WorldGraphStore store = new WorldGraphStore();
        store.put(WORLD, graphWith(new NodePos(0, 64, 0), new MaterialSpec(2.0, 30.0), false));

        NodePos saved = new NodePos(5, 64, 5);
        StructureGraph loaded = new StructureGraph();
        loaded.addBlock(saved, new MaterialSpec(3.0, 40.0), true);
        loaded.getNode(saved).addDamage(0.7);
        loaded.getNode(saved).setReinforcement(2.0);

        store.mergeLoaded(WORLD, loaded);

        StructureGraph merged = store.get(WORLD);
        assertEquals(2, merged.size(), "the persisted-only node is added beside the live one");
        Node node = merged.getNode(saved);
        assertNotNull(node, "the saved position now exists");
        assertEquals(0.7, node.damage(), "saved damage carried over");
        assertEquals(2.0, node.reinforcement(), "saved reinforcement carried over");
        assertTrue(node.isGrounded(), "saved grounded flag carried over");
    }

    @Test
    @DisplayName("explicit (non-grid) edges of persisted-only nodes are reproduced exactly")
    void explicitEdgesPreserved() {
        WorldGraphStore store = new WorldGraphStore();
        // A non-empty live graph, disjoint from the saved nodes, so the merge path runs.
        store.put(WORLD, graphWith(new NodePos(100, 64, 100), MaterialSpec.GROUND, true));

        // Two persisted nodes that are NOT grid-adjacent, joined by an explicit edge.
        NodePos a = new NodePos(0, 64, 0);
        NodePos b = new NodePos(0, 70, 0);
        StructureGraph loaded = new StructureGraph();
        loaded.addNode(a, new MaterialSpec(2.0, 30.0), false);
        loaded.addNode(b, new MaterialSpec(2.0, 30.0), false);
        loaded.connect(a, b);

        store.mergeLoaded(WORLD, loaded);

        StructureGraph merged = store.get(WORLD);
        assertTrue(merged.getNeighbors(a).contains(b), "the explicit A-B edge survives");
        assertTrue(merged.getNeighbors(b).contains(a), "edge is undirected");
        assertEquals(1, merged.getNeighbors(a).size(), "no grid edge the save omitted is invented");
    }

    @Test
    @DisplayName("empty/absent live graph → the persisted graph is installed wholesale")
    void emptyLiveInstallsWholesale() {
        WorldGraphStore store = new WorldGraphStore();
        StructureGraph loaded = new StructureGraph();
        loaded.addBlock(new NodePos(0, 64, 0), new MaterialSpec(2.0, 30.0), false);
        loaded.addBlock(new NodePos(0, 65, 0), new MaterialSpec(2.0, 30.0), false);

        store.mergeLoaded(WORLD, loaded); // no live graph at all
        assertSame(loaded, store.get(WORLD), "with nothing to protect, the load is installed as-is");
        assertEquals(2, store.get(WORLD).size());
    }

    @Test
    @DisplayName("null/empty load is a no-op — the live graph is untouched")
    void emptyLoadIsNoOp() {
        WorldGraphStore store = new WorldGraphStore();
        StructureGraph live = graphWith(new NodePos(0, 64, 0), new MaterialSpec(2.0, 30.0), false);
        store.put(WORLD, live);

        store.mergeLoaded(WORLD, null);
        store.mergeLoaded(WORLD, new StructureGraph());

        assertSame(live, store.get(WORLD), "the live graph is untouched by an empty load");
        assertEquals(1, store.get(WORLD).size());
    }

    @Test
    @DisplayName("merging the same load twice is idempotent (two racing boot loads)")
    void mergeIsIdempotent() {
        WorldGraphStore store = new WorldGraphStore();
        store.put(WORLD, graphWith(new NodePos(0, 64, 0), new MaterialSpec(2.0, 30.0), false));

        StructureGraph loaded = new StructureGraph();
        loaded.addBlock(new NodePos(0, 64, 0), new MaterialSpec(5.0, 99.0), false); // conflict
        loaded.addBlock(new NodePos(1, 64, 0), new MaterialSpec(3.0, 40.0), false); // persisted-only

        store.mergeLoaded(WORLD, loaded);
        int sizeAfterFirst = store.get(WORLD).size();
        long edgesAfterFirst = edgeCount(store.get(WORLD));

        store.mergeLoaded(WORLD, loaded); // second racing load: must change nothing

        assertEquals(sizeAfterFirst, store.get(WORLD).size(), "a second identical load adds no nodes");
        assertEquals(edgesAfterFirst, edgeCount(store.get(WORLD)), "a second identical load adds no edges");
        assertEquals(2.0, store.get(WORLD).getNode(new NodePos(0, 64, 0)).mass(), "live still wins after the replay");
    }

    @Test
    @DisplayName("disjoint live/persisted sets merge to the same graph in either order")
    void orderIndependentForDisjointSets() {
        // Live positions and persisted positions are disjoint, so there is no conflict
        // and the outcome is identical regardless of which arrives first.
        NodePos livePos = new NodePos(0, 64, 0);
        NodePos savedA = new NodePos(10, 64, 0);
        NodePos savedB = new NodePos(11, 64, 0); // grid-adjacent to savedA

        // Order X: live registered first, then the save merges in.
        WorldGraphStore x = new WorldGraphStore();
        x.put(WORLD, graphWith(livePos, new MaterialSpec(2.0, 30.0), false));
        x.mergeLoaded(WORLD, savedPair(savedA, savedB));

        // Order Y: the save installs first, then the live node is registered on top.
        WorldGraphStore y = new WorldGraphStore();
        y.mergeLoaded(WORLD, savedPair(savedA, savedB));
        y.get(WORLD).addBlock(livePos, new MaterialSpec(2.0, 30.0), false);

        assertGraphsEquivalent(x.get(WORLD), y.get(WORLD));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static StructureGraph savedPair(NodePos a, NodePos b) {
        StructureGraph g = new StructureGraph();
        g.addBlock(a, new MaterialSpec(3.0, 40.0), false);
        g.addBlock(b, new MaterialSpec(3.0, 40.0), false);
        g.getNode(a).addDamage(0.4);
        return g;
    }

    private static long edgeCount(StructureGraph graph) {
        long half = 0;
        for (NodePos pos : graph.getAllPositions()) {
            half += graph.getNeighbors(pos).size();
        }
        return half; // directed count; parity is all we compare
    }

    private static void assertGraphsEquivalent(StructureGraph a, StructureGraph b) {
        assertEquals(a.getAllPositions(), b.getAllPositions(), "same node set");
        for (NodePos pos : a.getAllPositions()) {
            Node na = a.getNode(pos);
            Node nb = b.getNode(pos);
            assertEquals(na.mass(), nb.mass(), "same spec at " + pos);
            assertEquals(na.damage(), nb.damage(), "same damage at " + pos);
            assertEquals(na.reinforcement(), nb.reinforcement(), "same reinforcement at " + pos);
            assertEquals(na.isGrounded(), nb.isGrounded(), "same grounded flag at " + pos);
            assertEquals(a.getNeighbors(pos), b.getNeighbors(pos), "same edges at " + pos);
        }
    }

    @Test
    @DisplayName("a stale save cannot wipe a live registration (the demo-server bug)")
    void staleSaveDoesNotWipeLiveRegistration() {
        WorldGraphStore store = new WorldGraphStore();
        // Live: a small "castle" built at enable.
        StructureGraph live = new StructureGraph();
        live.addBlock(new NodePos(0, 64, 0), new MaterialSpec(2.0, 30.0), true);
        live.addBlock(new NodePos(0, 65, 0), new MaterialSpec(2.0, 30.0), false);
        live.addBlock(new NodePos(0, 66, 0), new MaterialSpec(2.0, 30.0), false);
        store.put(WORLD, live);

        // Stale previous-session save at a different spot.
        StructureGraph loaded = new StructureGraph();
        loaded.addBlock(new NodePos(50, 64, 50), new MaterialSpec(9.0, 9.0), true);

        store.mergeLoaded(WORLD, loaded);

        StructureGraph merged = store.get(WORLD);
        assertTrue(merged.hasBlock(new NodePos(0, 65, 0)), "the live castle survives the load");
        assertTrue(merged.hasBlock(new NodePos(50, 64, 50)), "the saved node is merged in too");
        assertEquals(4, merged.size());
        assertFalse(merged.getNode(new NodePos(0, 64, 0)).mass() == 9.0, "the live base was not replaced by the save");
    }
}
