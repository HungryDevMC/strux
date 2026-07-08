package dev.gesp.structural.minecraft.manager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.minecraft.material.MaterialRegistry;
import dev.gesp.structural.persistence.BlockData;
import dev.gesp.structural.persistence.PersistenceAdapter;
import dev.gesp.structural.persistence.StructureData;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Startup loading must NOT block {@code onEnable}. The disk read +
 * {@code StructureData → StructureGraph} deserialization run off the main
 * thread; each finished graph is published into the live {@code worldGraphs}
 * map ONLY on the main thread (via the scheduler). Until that publish runs the
 * world is treated as untracked and is not saved (so an empty graph can never
 * overwrite good disk data).
 *
 * <p>These tests exercise {@link StructureManager#loadAllWorldsAsync} directly
 * with a <b>gated</b> adapter — a load future the test completes on demand — so
 * the in-flight window (load kicked off, not yet published) is fully under the
 * test's control. MockBukkit's scheduler runs on the test thread, so the
 * main-thread publish only happens when the test ticks it.
 */
@DisplayName("Startup load is async + publishes on the main thread")
class AsyncStartupLoadTest {

    private ServerMock server;
    private WorldMock world;
    private Plugin plugin;
    private StructureManager manager;

    /**
     * An adapter whose {@code loadAsync} returns a future the test completes by
     * hand. Saves complete instantly (never leave a pending save future that
     * would wedge MockBukkit teardown).
     */
    private static class GatedAdapter implements PersistenceAdapter {
        final CompletableFuture<Optional<StructureData>> gate = new CompletableFuture<>();

        @Override
        public String getName() {
            return "gated-test-adapter";
        }

        @Override
        public CompletableFuture<Void> saveAsync(String worldId, StructureData data) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Optional<StructureData>> loadAsync(String worldId) {
            return gate;
        }

        @Override
        public CompletableFuture<Void> deleteAsync(String worldId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Boolean> existsAsync(String worldId) {
            return CompletableFuture.completedFuture(false);
        }
    }

    /** A two-block saved structure: ground at world bottom, stone above it. */
    private StructureData savedTower(String worldId) {
        StructureData data = new StructureData(worldId);
        int bottom = world.getMinHeight();
        data.addBlock(new BlockData(0, bottom, 0, 0.0, Double.POSITIVE_INFINITY, true));
        data.addBlock(new BlockData(0, bottom + 1, 0, 2.0, 30.0, false));
        return data;
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("async-load-world");
        plugin = MockBukkit.createMockPlugin();
        manager = new StructureManager(new MaterialRegistry());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("crit 1: loadAllWorldsAsync returns while the load is still in flight")
    void enableDoesNotBlockOnSlowLoad() {
        GatedAdapter adapter = new GatedAdapter();
        manager.setPersistenceAdapter(adapter);

        CompletableFuture<Void> loads = manager.loadAllWorldsAsync(plugin, List.of(world));

        // The gate is still open: the off-thread deserialization cannot have
        // finished, so the returned future MUST still be pending. (If the call
        // blocked on the load, control would never have reached this assert.)
        assertFalse(loads.isDone(), "loadAllWorldsAsync must return before a slow load finishes");
        assertNotNull(manager.getPersistenceAdapter(), "an in-flight load must not disable persistence");

        // Drain the gate so teardown has nothing pending.
        adapter.gate.complete(Optional.empty());
        server.getScheduler().performTicks(2);
    }

    @Test
    @DisplayName("crit 2: after the load completes + one tick, the world's blocks are tracked")
    void blocksTrackedAfterCompletionAndTick() throws Exception {
        GatedAdapter adapter = new GatedAdapter();
        manager.setPersistenceAdapter(adapter);

        CompletableFuture<Void> loads = manager.loadAllWorldsAsync(plugin, List.of(world));
        int bottom = world.getMinHeight();
        Block stone = world.getBlockAt(0, bottom + 1, 0);

        // Before publish: untracked (the live map has not been touched).
        assertNull(manager.getGraph(world), "graph must not exist before the main-thread publish");
        assertFalse(manager.isTracked(stone), "blocks must not be tracked before publish");

        // Off-thread deserialize: complete the gate, wait for the returned future.
        adapter.gate.complete(Optional.of(savedTower(world.getUID().toString())));
        loads.get(5, TimeUnit.SECONDS);

        // The future being done is NOT enough — publish is a scheduled main-thread
        // task. It lands on the next tick.
        server.getScheduler().performTicks(1);

        assertNotNull(manager.getGraph(world), "graph must be published after a tick");
        assertTrue(manager.isTracked(stone), "the saved block must be tracked after publish");
        assertEquals(2, manager.getGraph(world).size(), "both saved blocks must be present");
    }

    @Test
    @DisplayName("crit 3+4: a break racing the load completion is a safe no-op + does not corrupt the graph")
    void breakDuringInFlightWindowIsSafe() throws Exception {
        GatedAdapter adapter = new GatedAdapter();
        manager.setPersistenceAdapter(adapter);

        CompletableFuture<Void> loads = manager.loadAllWorldsAsync(plugin, List.of(world));
        int bottom = world.getMinHeight();
        Block stone = world.getBlockAt(0, bottom + 1, 0);

        // Off-thread deserialize finishes, but the main-thread publish has NOT run
        // yet (no tick). This is the in-flight window.
        adapter.gate.complete(Optional.of(savedTower(world.getUID().toString())));
        loads.get(5, TimeUnit.SECONDS);

        // A block event in the window: getGraph is still null, so the handler must
        // no-op without touching anything (no CME, no crash).
        assertDoesNotThrow(() -> manager.onBlockBroken(stone), "a break in the in-flight window must be a safe no-op");
        assertNull(manager.getGraph(world), "the racing break must not have created/published a graph");

        // Now the publish tick runs: the later-published graph is the full, intact
        // saved structure — the racing event did not corrupt it.
        server.getScheduler().performTicks(1);
        assertNotNull(manager.getGraph(world), "graph must publish after the tick");
        assertEquals(2, manager.getGraph(world).size(), "the published graph must be intact (2 blocks)");
        assertTrue(manager.isTracked(stone), "the published block must be tracked");
    }

    @Test
    @DisplayName("crit 5: auto-save during the in-flight window does not overwrite good disk data")
    void saveDuringInFlightWindowSkipsTheWorld() {
        // A recording adapter: capture any worldIds saved so we can prove the
        // pending world was NOT written.
        List<String> saved = new CopyOnWriteArrayList<>();
        GatedAdapter adapter = new GatedAdapter() {
            @Override
            public CompletableFuture<Void> saveAsync(String worldId, StructureData data) {
                saved.add(worldId);
                return CompletableFuture.completedFuture(null);
            }
        };
        manager.setPersistenceAdapter(adapter);

        manager.loadAllWorldsAsync(plugin, List.of(world));

        // Simulate the realistic clobber risk: during the in-flight window a
        // block event creates an EMPTY in-memory graph for this world (the saved
        // structure hasn't been published yet). Without the load-pending guard,
        // an auto-save would now write this empty/partial graph over the good
        // data on disk.
        int bottom = world.getMinHeight();
        Block ground = world.getBlockAt(0, bottom, 0);
        ground.setType(Material.BEDROCK);
        manager.onBlockPlaced(ground);
        assertNotNull(manager.getGraph(world), "the in-flight event created an in-memory graph for this world");

        // Auto-save fires while the load is still in flight: the world must be
        // skipped (its in-memory graph isn't the real one yet) so the good disk
        // data is never overwritten.
        manager.saveAllAsync().join();
        manager.saveWorldAsync(world).join();
        assertTrue(saved.isEmpty(), "a world whose initial load hasn't finished must not be saved");

        // Once loaded + published, the world becomes saveable again.
        adapter.gate.complete(Optional.of(savedTower(world.getUID().toString())));
        server.getScheduler().performTicks(2);
        manager.saveAllAsync().join();
        assertTrue(saved.contains(world.getUID().toString()), "after publish the world must save normally");
    }

    @Test
    @DisplayName("crit 5: a failed load disables persistence so it can never overwrite disk")
    void failedLoadDisablesPersistence() {
        GatedAdapter adapter = new GatedAdapter();
        manager.setPersistenceAdapter(adapter);

        manager.loadAllWorldsAsync(plugin, List.of(world));

        // The disk read / deserialization fails.
        adapter.gate.completeExceptionally(new RuntimeException("corrupt save file"));
        server.getScheduler().performTicks(1);

        assertNull(manager.getPersistenceAdapter(), "a failed load must disable persistence for the session");
        // The world is no longer pending, but with persistence off every save is a
        // no-op, so the half-loaded state can never reach disk.
        CompletableFuture<Void> save = manager.saveWorldAsync(world);
        assertTrue(save.isDone(), "saves after a failed load must be instant no-ops");
        assertNull(manager.getGraph(world), "a failed load must not publish a graph");
    }

    @Test
    @DisplayName("no persistence adapter: async load is an instant no-op")
    void noAdapterIsNoOp() {
        CompletableFuture<Void> loads = manager.loadAllWorldsAsync(plugin, List.of(world));
        assertTrue(loads.isDone(), "with no adapter the async load completes immediately");
        assertNull(manager.getGraph(world), "with no adapter nothing is loaded");
    }
}
