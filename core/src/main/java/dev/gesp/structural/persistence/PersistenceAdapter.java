package dev.gesp.structural.persistence;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for persisting structure data.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                      PERSISTENCE ADAPTER                           │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  This is the "plug" that connects to different storage systems.    │
 *   │                                                                     │
 *   │                                                                     │
 *   │    ┌──────────────┐                                                │
 *   │    │ StructureData │                                               │
 *   │    └──────┬───────┘                                                │
 *   │           │                                                        │
 *   │           ▼                                                        │
 *   │    ┌──────────────────┐                                            │
 *   │    │ PersistenceAdapter │  ◄── The interface (this file)          │
 *   │    └──────────────────┘                                            │
 *   │           │                                                        │
 *   │     ┌─────┴─────┬──────────┐                                       │
 *   │     ▼           ▼          ▼                                       │
 *   │  ┌──────┐   ┌───────┐  ┌────────┐                                  │
 *   │  │ File │   │ Redis │  │ Spring │   ← Different implementations   │
 *   │  │ JSON │   │  DB   │  │  API   │                                  │
 *   │  └──────┘   └───────┘  └────────┘                                  │
 *   │                                                                     │
 *   │                                                                     │
 *   │  WHY ASYNC?                                                        │
 *   │  ─────────                                                         │
 *   │  Saving to disk or network can be SLOW.                            │
 *   │  Using CompletableFuture means the game doesn't freeze.            │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public interface PersistenceAdapter {

    /**
     * Save structure data for a world.
     *
     * <pre>
     *   saveAsync("world", data)
     *      .thenRun(() -> System.out.println("Saved!"))
     *      .exceptionally(ex -> { log.error("Failed", ex); return null; });
     * </pre>
     *
     * @param worldId unique identifier for the world (e.g., UUID or name)
     * @param data    the structure data to save
     * @return future that completes when save is done
     */
    CompletableFuture<Void> saveAsync(String worldId, StructureData data);

    /**
     * Load structure data for a world.
     *
     * <pre>
     *   loadAsync("world")
     *      .thenAccept(opt -> opt.ifPresent(data -> rebuild(data)))
     *      .exceptionally(ex -> { log.error("Failed", ex); return null; });
     * </pre>
     *
     * @param worldId unique identifier for the world
     * @return future with Optional containing data (empty if no saved data)
     */
    CompletableFuture<Optional<StructureData>> loadAsync(String worldId);

    /**
     * Delete saved data for a world.
     *
     * @param worldId unique identifier for the world
     * @return future that completes when deletion is done
     */
    CompletableFuture<Void> deleteAsync(String worldId);

    /**
     * Check if saved data exists for a world.
     *
     * @param worldId unique identifier for the world
     * @return future with true if data exists
     */
    CompletableFuture<Boolean> existsAsync(String worldId);

    /**
     * Synchronous save - blocks until complete.
     * Use sparingly (e.g., during server shutdown).
     */
    default void save(String worldId, StructureData data) {
        saveAsync(worldId, data).join();
    }

    /**
     * Synchronous load - blocks until complete.
     * Use sparingly (e.g., during server startup).
     */
    default Optional<StructureData> load(String worldId) {
        return loadAsync(worldId).join();
    }

    /**
     * Synchronous delete - blocks until complete.
     */
    default void delete(String worldId) {
        deleteAsync(worldId).join();
    }

    /**
     * Synchronous exists check.
     */
    default boolean exists(String worldId) {
        return existsAsync(worldId).join();
    }

    /**
     * Get a human-readable name for this adapter (for logging).
     */
    String getName();

    /**
     * Initialize the adapter (e.g., create directories, connect to DB).
     * Called once when the adapter is created.
     *
     * @throws PersistenceException if initialization fails
     */
    default void initialize() throws PersistenceException {
        // Default: no initialization needed
    }

    /**
     * Shutdown the adapter (e.g., close connections, flush buffers).
     * Called when the plugin/application is shutting down.
     */
    default void shutdown() {
        // Default: no shutdown needed
    }
}
