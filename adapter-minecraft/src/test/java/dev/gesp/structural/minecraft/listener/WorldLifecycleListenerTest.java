package dev.gesp.structural.minecraft.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.material.MaterialRegistry;
import dev.gesp.structural.persistence.BlockData;
import dev.gesp.structural.persistence.PersistenceAdapter;
import dev.gesp.structural.persistence.StructureData;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * A world loaded after {@code onEnable} (Multiverse {@code /mv load}, dynamic creation)
 * must still read its saved structures — and the load must run through the same
 * load-pending-guarded, main-thread-publish path the boot load uses, so a save during
 * the in-flight window can't overwrite the good disk data. Without it, the world's
 * builds lose physics and the next save silently clobbers the file.
 */
@DisplayName("WorldLifecycleListener loads late-loaded worlds safely")
class WorldLifecycleListenerTest {

    private ServerMock server;
    private WorldMock world;
    private Plugin plugin;
    private StructureManager manager;
    private WorldLifecycleListener listener;

    /** A load adapter the test completes by hand; saves complete instantly. */
    private static class GatedAdapter implements PersistenceAdapter {
        final CompletableFuture<Optional<StructureData>> gate = new CompletableFuture<>();
        final List<String> loaded = new CopyOnWriteArrayList<>();

        @Override
        public String getName() {
            return "gated";
        }

        @Override
        public CompletableFuture<Void> saveAsync(String worldId, StructureData data) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Optional<StructureData>> loadAsync(String worldId) {
            loaded.add(worldId);
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
        world = server.addSimpleWorld("late-world");
        plugin = MockBukkit.createMockPlugin();
        manager = new StructureManager(new MaterialRegistry());
        listener = new WorldLifecycleListener(plugin, manager);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("a world loaded late reads its saved structures (published on the main thread)")
    void lateWorldLoadsSavedStructures() throws Exception {
        GatedAdapter adapter = new GatedAdapter();
        manager.setPersistenceAdapter(adapter);

        listener.onWorldLoad(new WorldLoadEvent(world));

        // The load is async + published on a tick — nothing tracked yet.
        assertNull(manager.getGraph(world), "graph is not published before the load completes + a tick");

        adapter.gate.complete(Optional.of(savedTower(world.getUID().toString())));
        // Wait for the off-thread deserialize, then tick the main-thread publish.
        for (int i = 0; i < 100 && manager.getGraph(world) == null; i++) {
            server.getScheduler().performTicks(1);
            Thread.sleep(10);
        }

        assertNotNull(manager.getGraph(world), "the late world's saved structure is loaded");
        assertEquals(2, manager.getGraph(world).size(), "both saved blocks are present");
    }

    @Test
    @DisplayName("a world that is already tracked is not reloaded over its live graph")
    void alreadyTrackedWorldIsNotReloaded() {
        GatedAdapter adapter = new GatedAdapter();
        manager.setPersistenceAdapter(adapter);
        // Give the world a live graph (as if already loaded/built).
        manager.getOrCreateGraph(world);

        listener.onWorldLoad(new WorldLoadEvent(world));

        assertTrue(adapter.loaded.isEmpty(), "an already-tracked world must not trigger a disk reload");
    }

    @Test
    @DisplayName("with no persistence adapter the load is a harmless no-op")
    void noAdapterNoOp() {
        listener.onWorldLoad(new WorldLoadEvent(world));
        assertNull(manager.getGraph(world), "with no adapter nothing is loaded and nothing crashes");
    }

    @Test
    @DisplayName("a load racing an auto-save is skipped, so the save cannot overwrite good disk data")
    void inFlightWorldIsNotSaved() throws Exception {
        List<String> saved = new CopyOnWriteArrayList<>();
        GatedAdapter adapter = new GatedAdapter() {
            @Override
            public CompletableFuture<Void> saveAsync(String worldId, StructureData data) {
                saved.add(worldId);
                return CompletableFuture.completedFuture(null);
            }
        };
        manager.setPersistenceAdapter(adapter);

        listener.onWorldLoad(new WorldLoadEvent(world));
        // While the load is in flight, an auto-save must skip this world (it is
        // load-pending) so it cannot write an empty graph over the saved file.
        manager.saveAllAsync().join();
        assertTrue(saved.isEmpty(), "a load-pending late world must not be saved");

        // Complete the load so teardown has nothing pending, then confirm it published.
        adapter.gate.complete(Optional.of(savedTower(world.getUID().toString())));
        for (int i = 0; i < 100 && manager.getGraph(world) == null; i++) {
            server.getScheduler().performTicks(1);
            Thread.sleep(10);
        }
        assertNotNull(manager.getGraph(world), "after the load completes + a tick the world publishes");
    }
}
