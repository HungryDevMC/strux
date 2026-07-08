package dev.gesp.structural.minecraft.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.minecraft.material.MaterialRegistry;
import dev.gesp.structural.persistence.PersistenceAdapter;
import dev.gesp.structural.persistence.StructureData;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * The synchronous persistence waits must be BOUNDED. A save/load future that
 * never completes (wedged disk, broken adapter — or a mutation-testing mutant)
 * used to hang server shutdown, and any test teardown that joined it, forever.
 *
 * <pre>
 *   saveAll():           waits at most the timeout, logs SEVERE, moves on.
 *   loadAllWorlds(...):  waits at most the timeout; on timeout DISABLES
 *                        persistence so the partial in-memory state can never
 *                        be saved over the good data on disk.
 * </pre>
 */
@DisplayName("Persistence waits are bounded (no eternal join)")
class PersistenceBoundedWaitTest {

    private ServerMock server;
    private WorldMock world;
    private StructureManager manager;
    private final List<LogRecord> logRecords = new ArrayList<>();

    /** An adapter whose futures never complete — the wedged-disk case. */
    private static PersistenceAdapter hangingAdapter() {
        return new PersistenceAdapter() {
            @Override
            public String getName() {
                return "hanging-test-adapter";
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

    /** An adapter that answers instantly with nothing — the healthy empty case. */
    private static PersistenceAdapter instantEmptyAdapter() {
        return new PersistenceAdapter() {
            @Override
            public String getName() {
                return "instant-empty-test-adapter";
            }

            @Override
            public CompletableFuture<Void> saveAsync(String worldId, StructureData data) {
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
        };
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("bounded-wait-world");
        manager = new StructureManager(new MaterialRegistry());

        // Capture what the manager logs, so tests can assert the operator-facing
        // SEVERE messages (the honest "your data may not be saved" warnings).
        Logger testLogger = Logger.getLogger("bounded-wait-test");
        testLogger.setUseParentHandlers(false);
        testLogger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logRecords.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
        manager.setLogger(testLogger);
    }

    /** All SEVERE messages logged so far, joined for simple contains-checks. */
    private String severeLog() {
        StringBuilder sb = new StringBuilder();
        for (LogRecord record : logRecords) {
            if (record.getLevel() == Level.SEVERE) {
                sb.append(record.getMessage()).append('\n');
            }
        }
        return sb.toString();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Track one block so saveAllAsync actually has a world snapshot to write. */
    private void trackOneBlock() {
        Block ground = world.getBlockAt(0, 64, 0);
        ground.setType(Material.BEDROCK);
        manager.onBlockPlaced(ground);
        Block stone = world.getBlockAt(0, 65, 0);
        stone.setType(Material.STONE);
        manager.onBlockPlaced(stone);
        assertTrue(manager.totalTrackedBlocks() > 0, "test needs at least one tracked block to save");
    }

    @Test
    @DisplayName("saveAll returns within the bound when the write never completes")
    void saveAllReturnsWhenWriteNeverCompletes() {
        trackOneBlock();
        manager.setPersistenceAdapter(hangingAdapter());
        manager.setPersistenceWaitTimeout(Duration.ofMillis(150));

        // Before the fix this joined a never-completing future = eternal hang;
        // the preemptive timeout below is what failing-for-the-right-reason
        // looks like for an unbounded wait.
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> manager.saveAll());

        assertTrue(
                severeLog().contains("may not be on disk"),
                "a timed-out save must warn the operator that data may be missing");
    }

    @Test
    @DisplayName("an interrupted save restores the interrupt flag and warns")
    void interruptedSaveRestoresFlagAndWarns() {
        trackOneBlock();
        manager.setPersistenceAdapter(hangingAdapter());
        // Belt: even if interrupt detection broke, the wait is bounded at 150ms.
        // (No assertTimeoutPreemptively here — it runs the lambda on a SEPARATE
        // thread, so the interrupt below would never reach it.)
        manager.setPersistenceWaitTimeout(Duration.ofMillis(150));

        Thread.currentThread().interrupt();
        manager.saveAll();

        assertTrue(Thread.interrupted(), "saveAll must restore the interrupt flag (and this check clears it)");
        assertTrue(severeLog().contains("interrupted"), "an interrupted save must warn the operator");
    }

    @Test
    @DisplayName("an interrupted load disables persistence like a timeout does")
    void interruptedLoadDisablesPersistence() {
        manager.setPersistenceAdapter(hangingAdapter());
        manager.setPersistenceWaitTimeout(Duration.ofMillis(150));

        Thread.currentThread().interrupt();
        boolean loaded = manager.loadAllWorlds(List.of(world));

        assertTrue(Thread.interrupted(), "loadAllWorlds must restore the interrupt flag (and this check clears it)");
        assertFalse(loaded, "an interrupted load must report failure");
        assertNull(manager.getPersistenceAdapter(), "persistence must be disabled after an interrupted load");
    }

    @Test
    @DisplayName("load timeout disables persistence so a later save cannot overwrite disk")
    void loadTimeoutDisablesPersistence() {
        manager.setPersistenceAdapter(hangingAdapter());
        manager.setPersistenceWaitTimeout(Duration.ofMillis(150));

        boolean loaded = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> manager.loadAllWorlds(List.of(world)));

        assertFalse(loaded, "a timed-out load must report failure");
        assertNull(manager.getPersistenceAdapter(), "persistence must be disabled after a timed-out load");
        assertTrue(
                severeLog().contains("DISABLED"),
                "a timed-out load must tell the operator that saving is now disabled");

        // The disabled adapter makes every later save an instant no-op — the
        // half-loaded state can never reach the disk.
        trackOneBlock();
        CompletableFuture<Void> save = manager.saveWorldAsync(world);
        assertTrue(save.isDone(), "saves after a failed load must be no-ops");
    }

    @Test
    @DisplayName("healthy load completes in time and keeps persistence enabled")
    void healthyLoadKeepsPersistence() {
        manager.setPersistenceAdapter(instantEmptyAdapter());
        manager.setPersistenceWaitTimeout(Duration.ofMillis(150));

        assertTrue(manager.loadAllWorlds(List.of(world)), "an instant load must succeed within the bound");
        assertNotNull(manager.getPersistenceAdapter(), "a healthy load must keep persistence enabled");
    }

    @Test
    @DisplayName("healthy save completes normally within the bound")
    void healthySaveCompletes() {
        trackOneBlock();
        int tracked = manager.totalTrackedBlocks();
        manager.setPersistenceAdapter(instantEmptyAdapter());
        manager.setPersistenceWaitTimeout(Duration.ofMillis(150));

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> manager.saveAll());
        assertEquals(tracked, manager.totalTrackedBlocks(), "saveAll must not mutate the tracked structures");
    }
}
