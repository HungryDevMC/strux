package dev.gesp.structural.minecraft.manager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Focused unit tests for {@link WorldGraphStore}: the per-world graph map and its
 * change-revision counter. The store is the single owner of {@code worldGraphs}
 * after the StructureManager split — these tests pin its get/create/clear and the
 * revision math (a markDirty bumps by exactly one; an untouched world reads 0).
 */
@DisplayName("WorldGraphStore: graph map + revision counter")
class WorldGraphStoreTest {

    private ServerMock server;
    private WorldMock worldA;
    private WorldMock worldB;
    private WorldGraphStore store;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        worldA = server.addSimpleWorld("store-world-a");
        worldB = server.addSimpleWorld("store-world-b");
        store = new WorldGraphStore();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("getGraph returns null for an unknown world; getOrCreateGraph creates one")
    void getAndCreate() {
        assertNull(store.getGraph(worldA), "an untouched world has no graph");

        StructureGraph created = store.getOrCreateGraph(worldA);
        assertNotNull(created, "getOrCreateGraph must create a graph");
        assertSame(created, store.getGraph(worldA), "getGraph must return the just-created graph");
        assertSame(created, store.getOrCreateGraph(worldA), "getOrCreateGraph must reuse the existing graph");
    }

    @Test
    @DisplayName("worlds get independent graphs")
    void independentPerWorld() {
        StructureGraph a = store.getOrCreateGraph(worldA);
        StructureGraph b = store.getOrCreateGraph(worldB);
        assertNotNull(a);
        assertNotNull(b);
        assertNotSame(a, b, "each world must have its own graph");
    }

    @Test
    @DisplayName("clearAll drops every world; clearWorld drops only the named one")
    void clearing() {
        store.getOrCreateGraph(worldA);
        store.getOrCreateGraph(worldB);

        store.clearWorld(worldA);
        assertNull(store.getGraph(worldA), "clearWorld must drop the named world");
        assertNotNull(store.getGraph(worldB), "clearWorld must not touch other worlds");

        store.getOrCreateGraph(worldA);
        store.clearAll();
        assertNull(store.getGraph(worldA), "clearAll must drop all worlds");
        assertNull(store.getGraph(worldB), "clearAll must drop all worlds");
    }

    @Test
    @DisplayName("revision starts at 0 and bumps by exactly one per markDirty, per world")
    void revisionMath() {
        assertEquals(0L, store.revision(worldA), "an untouched world's revision is 0");

        store.markDirty(worldA);
        assertEquals(1L, store.revision(worldA), "markDirty bumps the revision by one");

        store.markDirty(worldA);
        store.markDirty(worldA);
        assertEquals(3L, store.revision(worldA), "each markDirty adds exactly one");

        assertEquals(0L, store.revision(worldB), "markDirty on world A must not bump world B");
    }

    @Test
    @DisplayName("markDirty(null) is a safe no-op")
    void markDirtyNullIsNoOp() {
        assertDoesNotThrow(() -> store.markDirty((World) null));
    }

    @Test
    @DisplayName("totalTrackedBlocks sums sizes across all worlds")
    void totalTracked() {
        assertEquals(0, store.totalTrackedBlocks(), "no graphs means zero tracked blocks");

        StructureGraph a = store.getOrCreateGraph(worldA);
        a.addBlock(new NodePos(0, 65, 0), new MaterialSpec(2.0, 30.0), false);
        a.addBlock(new NodePos(0, 66, 0), new MaterialSpec(2.0, 30.0), false);
        StructureGraph b = store.getOrCreateGraph(worldB);
        b.addBlock(new NodePos(5, 65, 5), new MaterialSpec(2.0, 30.0), false);

        assertEquals(3, store.totalTrackedBlocks(), "total must sum every world's graph size");
    }

    @Test
    @DisplayName("put installs a graph and get returns it by id (the load-publish path)")
    void putAndGetById() {
        StructureGraph graph = new StructureGraph();
        graph.addBlock(new NodePos(1, 65, 1), new MaterialSpec(2.0, 30.0), false);
        store.put(worldA.getUID(), graph);

        assertSame(graph, store.get(worldA.getUID()), "get(id) must return the put graph");
        assertSame(graph, store.getGraph(worldA), "getGraph must see the published graph");
    }
}
