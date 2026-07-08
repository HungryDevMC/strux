package dev.gesp.structural.minecraft.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.persistence.PersistenceAdapter;
import dev.gesp.structural.persistence.StructureData;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Focused unit tests for {@link StructurePersistenceCoordinator}: the bounded
 * synchronous waits, disable-persistence-on-failed-load, and the
 * {@code initialLoadPending} save-skip guard — all driven directly against the
 * coordinator (the StructureManager-level wiring is pinned by the existing
 * PersistenceBoundedWaitTest / AsyncStartupLoadTest).
 */
@DisplayName("StructurePersistenceCoordinator: bounded waits + load-pending guard")
class StructurePersistenceCoordinatorTest {

    /** An adapter whose futures never complete — the wedged-disk case. */
    private static PersistenceAdapter hangingAdapter() {
        return new PersistenceAdapter() {
            @Override
            public String getName() {
                return "hanging";
            }

            @Override
            public CompletableFuture<Void> saveAsync(String worldId, StructureData data) {
                return new CompletableFuture<>();
            }

            @Override
            public CompletableFuture<Optional<StructureData>> loadAsync(String worldId) {
                return new CompletableFuture<>();
            }

            @Override
            public CompletableFuture<Void> deleteAsync(String worldId) {
                return new CompletableFuture<>();
            }

            @Override
            public CompletableFuture<Boolean> existsAsync(String worldId) {
                return new CompletableFuture<>();
            }
        };
    }

    /** A recording, instant adapter: captures saved worldIds, loads nothing. */
    private static final class RecordingAdapter implements PersistenceAdapter {
        final List<String> saved = new CopyOnWriteArrayList<>();

        @Override
        public String getName() {
            return "recording";
        }

        @Override
        public CompletableFuture<Void> saveAsync(String worldId, StructureData data) {
            saved.add(worldId);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Optional<StructureData>> loadAsync(String worldId) {
            return CompletableFuture.completedFuture(Optional.empty());
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

    /** An instant adapter that loads exactly one block for any world. */
    private static final class OneBlockAdapter implements PersistenceAdapter {
        @Override
        public String getName() {
            return "one-block";
        }

        @Override
        public CompletableFuture<Void> saveAsync(String worldId, StructureData data) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Optional<StructureData>> loadAsync(String worldId) {
            StructureData data = new StructureData(worldId);
            data.addBlock(new dev.gesp.structural.persistence.BlockData(0, 65, 0, 2.0, 30.0, false));
            return CompletableFuture.completedFuture(Optional.of(data));
        }

        @Override
        public CompletableFuture<Void> deleteAsync(String worldId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Boolean> existsAsync(String worldId) {
            return CompletableFuture.completedFuture(true);
        }
    }

    private ServerMock server;
    private WorldMock world;
    private WorldGraphStore store;
    private StructurePersistenceCoordinator coordinator;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("coord-world");
        store = new WorldGraphStore();
        coordinator = new StructurePersistenceCoordinator(store, new PhysicsConfig());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Put a non-empty graph for the world so a save has something to write. */
    private void trackBlock() {
        StructureGraph graph = store.getOrCreateGraph(world);
        graph.addBlock(new NodePos(0, 65, 0), new MaterialSpec(2.0, 30.0), false);
    }

    @Test
    @DisplayName("no adapter: saveAllAsync and loadAllWorlds are instant no-ops")
    void noAdapterNoOps() {
        assertTrue(coordinator.saveAllAsync().isDone(), "no adapter means saveAllAsync completes instantly");
        assertTrue(coordinator.loadAllWorlds(List.of(world)), "no adapter means load trivially succeeds");
    }

    @Test
    @DisplayName("loadWorldAsync defers its publish to the main thread once a plugin is captured")
    void loadWorldAsyncPublishesOnMainThread() {
        Plugin mockPlugin = MockBukkit.createMockPlugin();
        coordinator.setPersistenceAdapter(new OneBlockAdapter());
        // loadAllWorldsAsync captures the plugin used to schedule main-thread publishes.
        coordinator.loadAllWorldsAsync(mockPlugin, List.of()).join();

        boolean found = coordinator.loadWorldAsync(world).join();
        assertTrue(found, "the saved block was found");
        // The deserialize ran off-thread but the install is a SCHEDULED main-thread
        // task — the live store must not be mutated until that task runs.
        assertNull(store.get(world.getUID()), "publish is deferred to the main thread, not done off-thread");

        server.getScheduler().performTicks(1);
        assertNotNull(store.get(world.getUID()), "the graph is published on the main thread");
        assertEquals(1, store.get(world.getUID()).size(), "the one saved block was installed");
    }

    @Test
    @DisplayName("loadWorld installs the graph inline on the calling thread and marks it dirty")
    void loadWorldInstallsInline() {
        coordinator.setPersistenceAdapter(new OneBlockAdapter());

        assertTrue(coordinator.loadWorld(world), "saved data loads");
        // loadWorld is synchronous: the graph is already installed when it returns,
        // with no scheduler tick needed (it publishes inline on this thread).
        assertNotNull(store.get(world.getUID()), "loadWorld publishes inline before returning");
        assertEquals(1, store.get(world.getUID()).size());
        // The publish also bumps the world's revision so grade/visualizer caches refresh.
        assertTrue(store.revision(world) > 0, "installing a loaded graph marks the world dirty");
    }

    @Test
    @DisplayName("loadWorld returns false and installs nothing when the world has no saved data")
    void loadWorldNoData() {
        coordinator.setPersistenceAdapter(new RecordingAdapter()); // loads Optional.empty()

        assertFalse(coordinator.loadWorld(world), "no saved data → loadWorld reports nothing loaded");
        assertNull(store.get(world.getUID()), "an empty load installs no graph");
        assertEquals(0, store.revision(world), "an empty load does not mark the world dirty");
    }

    @Test
    @DisplayName("loadWorldAsync returns false when the world has no saved data")
    void loadWorldAsyncNoData() {
        coordinator.setPersistenceAdapter(new RecordingAdapter()); // loads Optional.empty()

        assertFalse(coordinator.loadWorldAsync(world).join(), "no saved data → loadWorldAsync reports false");
        assertNull(store.get(world.getUID()), "an empty async load installs no graph");
    }

    @Test
    @DisplayName("loadWorld is a no-op false when persistence is disabled")
    void loadWorldNoAdapter() {
        assertFalse(coordinator.loadWorld(world), "no adapter → nothing to load");
        assertNull(store.get(world.getUID()), "no adapter installs no graph");
    }

    @Test
    @DisplayName("loadWorld returns false (and installs nothing) when the read wedges past the bound")
    void loadWorldTimeout() {
        coordinator.setPersistenceAdapter(hangingAdapter());
        coordinator.setPersistenceWaitTimeout(Duration.ofMillis(120));

        boolean loaded = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> coordinator.loadWorld(world));
        assertFalse(loaded, "a wedged read times out to false");
        assertNull(store.get(world.getUID()), "a timed-out read installs nothing");
    }

    @Test
    @DisplayName("loadWorld restores the interrupt flag and returns false when interrupted")
    void loadWorldInterrupted() {
        coordinator.setPersistenceAdapter(hangingAdapter());
        coordinator.setPersistenceWaitTimeout(Duration.ofMillis(120));

        Thread.currentThread().interrupt();
        boolean loaded = coordinator.loadWorld(world);

        assertTrue(Thread.interrupted(), "loadWorld restores the interrupt flag (and this check clears it)");
        assertFalse(loaded, "an interrupted read reports false");
    }

    @Test
    @DisplayName("a per-world read failure is swallowed: loadWorld returns false, installs nothing")
    void loadWorldReadFailure() {
        coordinator.setPersistenceAdapter(new PersistenceAdapter() {
            @Override
            public String getName() {
                return "throwing";
            }

            @Override
            public CompletableFuture<Void> saveAsync(String worldId, StructureData data) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Optional<StructureData>> loadAsync(String worldId) {
                return CompletableFuture.failedFuture(new IllegalStateException("corrupt save"));
            }

            @Override
            public CompletableFuture<Void> deleteAsync(String worldId) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Boolean> existsAsync(String worldId) {
                return CompletableFuture.completedFuture(true);
            }
        });

        assertFalse(coordinator.loadWorld(world), "a read failure is swallowed to 'no data'");
        assertNull(store.get(world.getUID()), "a failed read installs nothing");
    }

    @Test
    @DisplayName("saveAll returns within the bound when the write never completes")
    void saveAllBounded() {
        trackBlock();
        coordinator.setPersistenceAdapter(hangingAdapter());
        coordinator.setPersistenceWaitTimeout(Duration.ofMillis(120));

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> coordinator.saveAll());
    }

    @Test
    @DisplayName("a timed-out load disables persistence so a later save cannot overwrite disk")
    void loadTimeoutDisablesPersistence() {
        coordinator.setPersistenceAdapter(hangingAdapter());
        coordinator.setPersistenceWaitTimeout(Duration.ofMillis(120));

        boolean loaded =
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> coordinator.loadAllWorlds(List.of(world)));

        assertFalse(loaded, "a timed-out load reports failure");
        assertNull(coordinator.getPersistenceAdapter(), "a timed-out load disables persistence");

        // With the adapter cleared, every later save is an instant no-op.
        trackBlock();
        assertTrue(coordinator.saveWorldAsync(world).isDone(), "saves after a failed load must be no-ops");
    }

    @Test
    @DisplayName("an interrupted load restores the interrupt flag and disables persistence")
    void interruptedLoadDisablesPersistence() {
        coordinator.setPersistenceAdapter(hangingAdapter());
        coordinator.setPersistenceWaitTimeout(Duration.ofMillis(120));

        Thread.currentThread().interrupt();
        boolean loaded = coordinator.loadAllWorlds(List.of(world));

        assertTrue(Thread.interrupted(), "loadAllWorlds must restore the interrupt flag (and this check clears it)");
        assertFalse(loaded, "an interrupted load reports failure");
        assertNull(coordinator.getPersistenceAdapter(), "an interrupted load disables persistence");
    }

    @Test
    @DisplayName("healthy load completes in time and keeps persistence enabled")
    void healthyLoadKeepsPersistence() {
        coordinator.setPersistenceAdapter(new RecordingAdapter());
        coordinator.setPersistenceWaitTimeout(Duration.ofMillis(120));

        assertTrue(coordinator.loadAllWorlds(List.of(world)), "an instant load succeeds within the bound");
        assertNotNull(coordinator.getPersistenceAdapter(), "a healthy load keeps persistence enabled");
    }

    @Test
    @DisplayName("a load-pending world is skipped by saveAllAsync and saveWorldAsync")
    void loadPendingGuardSkipsSave() {
        RecordingAdapter adapter = new RecordingAdapter();
        coordinator.setPersistenceAdapter(adapter);

        // Kick off the async load: it marks the world load-pending up front. The
        // gate (empty load) is delivered only when the scheduler ticks the publish.
        coordinator.loadAllWorldsAsync(plugin(), List.of(world));

        // During the in-flight window an event creates an in-memory graph for the
        // world. Without the guard a save would clobber the good disk data.
        trackBlock();

        coordinator.saveAllAsync().join();
        coordinator.saveWorldAsync(world).join();
        assertTrue(adapter.saved.isEmpty(), "a load-pending world must never be saved");

        // After the publish tick the world is no longer pending → saveable again.
        server.getScheduler().performTicks(2);
        coordinator.saveAllAsync().join();
        assertTrue(adapter.saved.contains(world.getUID().toString()), "after publish the world saves normally");
    }

    @Test
    @DisplayName("saveAllAsync writes every non-pending tracked world")
    void saveAllWritesTrackedWorlds() {
        RecordingAdapter adapter = new RecordingAdapter();
        coordinator.setPersistenceAdapter(adapter);
        trackBlock();

        coordinator.saveAllAsync().join();
        assertEquals(
                List.of(world.getUID().toString()), adapter.saved, "saveAllAsync must write the one tracked world");
    }

    @Test
    @DisplayName("an enable-time registration survives a stale save that publishes a tick later")
    void enableRegistrationSurvivesLatePublish() {
        Plugin mockPlugin = MockBukkit.createMockPlugin();
        coordinator.setPersistenceAdapter(new OneBlockAdapter()); // saved: (0,65,0)

        // Boot: kick the async load (deserialize off-thread, publish scheduled for a tick).
        coordinator.loadAllWorldsAsync(mockPlugin, List.of(world));

        // onEnable then registers its own structure at a DIFFERENT position before the
        // publish runs — the live-clobber bug wiped exactly this.
        NodePos registered = new NodePos(3, 70, 3);
        store.getOrCreateGraph(world).addBlock(registered, new MaterialSpec(2.0, 30.0), false);

        // The publish lands.
        server.getScheduler().performTicks(2);

        StructureGraph merged = store.get(world.getUID());
        assertNotNull(merged, "graph exists after the publish");
        assertTrue(merged.hasBlock(registered), "the enable-time registration survives the load");
        assertTrue(merged.hasBlock(new NodePos(0, 65, 0)), "the saved block is merged in too");
        assertEquals(2, merged.size(), "both the registered and the saved node are present");
    }

    private Plugin plugin() {
        return MockBukkit.createMockPlugin();
    }
}
