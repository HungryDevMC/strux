package dev.gesp.structural.minecraft.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.material.MaterialRegistry;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Direct unit tests for {@link RevisionCachedNodeView}: the revision-cached
 * candidate-set mechanism shared by StressVisualizer, DamageVisualizer and
 * EntityWeightTask. Rebuilds are counted with a spy predicate so we can prove the
 * cache is frozen while the revision is unchanged and rebuilt exactly once on a
 * bump.
 */
@DisplayName("RevisionCachedNodeView: freeze-cache mechanism")
class RevisionCachedNodeViewTest {

    private ServerMock server;
    private WorldMock world;
    private StructureManager manager;
    private StructureGraph graph;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("view_world");
        manager = new StructureManager(new MaterialRegistry(), new PhysicsConfig());
        graph = manager.getOrCreateGraph(world);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Add a floating tracked node with the given damage and return it. */
    private Node addNode(int x, int y, int z, double damage) {
        graph.addNode(new NodePos(x, y, z), new MaterialSpec(2.0, 100.0), false);
        Node node = graph.getNode(new NodePos(x, y, z));
        if (damage > 0.0) {
            node.addDamage(damage);
        }
        return node;
    }

    /** A predicate that counts how many nodes it has been asked to test. */
    private static final class SpyPredicate implements Predicate<Node> {
        final AtomicInteger tests = new AtomicInteger();
        private final Predicate<Node> delegate;

        SpyPredicate(Predicate<Node> delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean test(Node node) {
            tests.incrementAndGet();
            return delegate.test(node);
        }
    }

    @Test
    @DisplayName("unchanged revision → zero rebuilds: the predicate is never re-run after the first scan")
    void stableWhileRevisionUnchanged() {
        addNode(0, 64, 0, 0.6); // matches
        addNode(1, 64, 0, 0.0); // does not match
        addNode(2, 64, 0, 0.6); // matches
        manager.markDirty(world);

        SpyPredicate spy = new SpyPredicate(n -> n.damage() >= 0.5);
        RevisionCachedNodeView view = new RevisionCachedNodeView(manager, spy);

        // First call rebuilds: scans all 3 nodes once.
        Set<NodePos> first = view.nodes(world, graph);
        assertEquals(3, spy.tests.get(), "the first call scans every node exactly once");
        assertEquals(3, view.lastScanned(), "lastScanned reports the rebuild scan size");
        assertEquals(
                Set.of(new NodePos(0, 64, 0), new NodePos(2, 64, 0)), first, "only the matching nodes are returned");

        // Several further calls at the same revision: no rescans, same instance.
        for (int i = 0; i < 5; i++) {
            Set<NodePos> again = view.nodes(world, graph);
            assertEquals(3, spy.tests.get(), "predicate is never re-run while the revision is unchanged");
            assertEquals(-1, view.lastScanned(), "lastScanned reports -1 on a cache hit (no scan)");
            assertTrue(again == first, "the cached set instance is returned unchanged");
        }
    }

    @Test
    @DisplayName("revision bump → exactly one rebuild on the next access")
    void rebuildsExactlyOnceOnRevisionBump() {
        Node a = addNode(0, 64, 0, 0.6);
        addNode(1, 64, 0, 0.0);
        manager.markDirty(world);

        SpyPredicate spy = new SpyPredicate(n -> n.damage() >= 0.5);
        RevisionCachedNodeView view = new RevisionCachedNodeView(manager, spy);

        view.nodes(world, graph); // warm rebuild: 2 tests
        view.nodes(world, graph); // cache hit: still 2
        assertEquals(2, spy.tests.get(), "two nodes scanned by the warm rebuild, none on the cache hit");

        // A second node crosses the threshold AND the revision bumps.
        Node b = graph.getNode(new NodePos(1, 64, 0));
        b.addDamage(0.6);
        manager.markDirty(world);

        Set<NodePos> rebuilt = view.nodes(world, graph);
        assertEquals(4, spy.tests.get(), "the bump triggers exactly one rebuild that rescans both nodes once");
        assertEquals(2, view.lastScanned(), "the rebuild scanned the whole graph");
        assertEquals(
                Set.of(new NodePos(0, 64, 0), new NodePos(1, 64, 0)),
                rebuilt,
                "the rebuilt set now contains the newly-matching node");

        // And it freezes again: another access at the same revision does not rescan.
        view.nodes(world, graph);
        assertEquals(4, spy.tests.get(), "no further scans until the next revision bump");
        assertTrue(a.damage() >= 0.5, "sanity: node a is still a match");
    }

    @Test
    @DisplayName("removed node drops out of the rebuilt set (the view cannot grow without bound)")
    void removedNodeDropsOutOnRebuild() {
        addNode(0, 64, 0, 0.6);
        addNode(1, 64, 0, 0.6);
        manager.markDirty(world);

        RevisionCachedNodeView view = new RevisionCachedNodeView(manager, n -> n.damage() >= 0.5);

        Set<NodePos> first = view.nodes(world, graph);
        assertEquals(
                Set.of(new NodePos(0, 64, 0), new NodePos(1, 64, 0)),
                first,
                "both matching nodes are present at first");

        // Remove one node from the graph and bump the revision.
        graph.removeBlock(new NodePos(0, 64, 0));
        manager.markDirty(world);

        Set<NodePos> rebuilt = view.nodes(world, graph);
        assertEquals(1, view.lastScanned(), "the rebuild scanned the one surviving node");
        assertEquals(Set.of(new NodePos(1, 64, 0)), rebuilt, "the removed node is gone from the rebuilt set");
        assertFalse(rebuilt.contains(new NodePos(0, 64, 0)), "the removed node cannot linger in the cache");
    }
}
