package dev.gesp.structural.minecraft.manager;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.persistence.PersistenceAdapter;
import dev.gesp.structural.persistence.StructureConverter;
import dev.gesp.structural.persistence.StructureData;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * The async load/save engine for world structures.
 *
 * <p>Owns the persistence adapter, the bounded synchronous waits, the
 * detached-deserialize then main-thread-publish startup load, and the
 * {@code initialLoadPending} guard that keeps an as-yet-empty graph from
 * overwriting good disk data. The graph map it reads and writes is owned by
 * {@link WorldGraphStore}; this coordinator never mutates that map off the main
 * thread (publishes are scheduled on the main thread via the plugin scheduler).
 */
final class StructurePersistenceCoordinator {

    private final WorldGraphStore graphStore;
    private final PhysicsConfig config;

    private PersistenceAdapter persistenceAdapter;
    private Logger logger;

    // Worlds whose ASYNC initial load was kicked off but not yet finished
    // (the detached graph is still deserializing off-thread, or its main-thread
    // publish has not run). Auto-save and shutdown-save MUST skip these worlds:
    // saving an as-yet-empty graph for a world that has saved data on disk would
    // overwrite the good data. Concurrent because it is *read* off the main thread
    // by saveAllAsync's snapshot, but only ever *mutated* on the main thread.
    private final Set<UUID> initialLoadPending = ConcurrentHashMap.newKeySet();

    // How long a synchronous persistence wait (startup load, shutdown save) may
    // block before we give up. A pending future must never hang the server — or
    // a test teardown — forever. Visible for testing via the setter.
    private Duration persistenceWaitTimeout = Duration.ofSeconds(30);

    // The plugin used to schedule a main-thread publish for the async single-world
    // load path. Captured the first time loadAllWorldsAsync runs (always at onEnable,
    // before any caller could reach loadWorldAsync). Null only in a direct unit test
    // of this coordinator with no async load — in which case the single-threaded
    // loadWorldAsync falls back to an inline publish, which is safe there.
    private volatile Plugin plugin;

    StructurePersistenceCoordinator(WorldGraphStore graphStore, PhysicsConfig config) {
        this.graphStore = graphStore;
        this.config = config;
    }

    /** Set the persistence adapter for saving/loading structures. */
    void setPersistenceAdapter(PersistenceAdapter adapter) {
        this.persistenceAdapter = adapter;
    }

    /** Get the persistence adapter. */
    PersistenceAdapter getPersistenceAdapter() {
        return persistenceAdapter;
    }

    /** Set the logger for persistence messages. */
    void setLogger(Logger logger) {
        this.logger = logger;
    }

    /**
     * Set how long the synchronous persistence waits ({@link #saveAll},
     * {@link #loadAllWorlds}) may block before giving up. Defaults to 30s.
     * Visible for testing the timeout behaviour without a 30s wait.
     */
    void setPersistenceWaitTimeout(Duration timeout) {
        this.persistenceWaitTimeout = timeout;
    }

    /**
     * Save all world structures to persistence.
     *
     * @return future that completes when all saves are done
     */
    CompletableFuture<Void> saveAllAsync() {
        if (persistenceAdapter == null) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<?>[] futures = graphStore.entries().entrySet().stream()
                // Skip any world whose async initial load hasn't finished: its
                // in-memory graph is not yet the real one, so saving it would
                // clobber the good data on disk with an empty/partial snapshot.
                .filter(entry -> !initialLoadPending.contains(entry.getKey()))
                .map(entry -> {
                    String worldId = entry.getKey().toString();
                    StructureGraph graph = entry.getValue();
                    StructureData data = StructureConverter.toData(graph, worldId);

                    return persistenceAdapter
                            .saveAsync(worldId, data)
                            .thenRun(() -> {
                                if (logger != null && config.isDebugLogging()) {
                                    logger.info("Saved " + data.blockCount() + " blocks for world " + worldId);
                                }
                            })
                            .exceptionally(ex -> {
                                if (logger != null) {
                                    logger.severe("Failed to save world " + worldId + ": " + ex.getMessage());
                                }
                                return null;
                            });
                })
                .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(futures);
    }

    /**
     * Save all world structures synchronously, waiting at most
     * {@link #setPersistenceWaitTimeout the persistence wait timeout}.
     * Use sparingly (e.g., during server shutdown).
     *
     * <p>The wait is bounded on purpose: a save future that never completes
     * (a wedged disk, a broken adapter) used to hang shutdown — and any test
     * teardown that joins it — forever. Now it logs SEVERE and moves on; the
     * latest changes may not be on disk, but the server gets to shut down.
     */
    void saveAll() {
        try {
            saveAllAsync().get(persistenceWaitTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            severe("Structure save interrupted — the latest changes may not be on disk.");
        } catch (TimeoutException | ExecutionException e) {
            // Timeout: the write is wedged. ExecutionException: per-world failures
            // are swallowed inside saveAllAsync(), so a combined-future failure is
            // unexpected — either way the save did not finish.
            severe("Structure save did not complete (" + e + ") — the latest changes may not be on disk.");
        }
    }

    private void severe(String message) {
        if (logger != null) {
            logger.severe(message);
        }
    }

    /**
     * Load every world's structures, waiting at most
     * {@link #setPersistenceWaitTimeout the persistence wait timeout}.
     *
     * <p>If loading does not finish in time, persistence is <b>disabled</b>
     * (the adapter is cleared) and this returns {@code false}: the server runs
     * on without the saved structures, and — crucially — a later save can never
     * overwrite the good data on disk with the partial state we booted with.
     *
     * @return true if every world loaded in time
     */
    boolean loadAllWorlds(Collection<World> worlds) {
        if (persistenceAdapter == null) {
            return true; // nothing to load; trivially succeeds
        }
        // Read + deserialize every world OFF-THREAD in parallel into detached graphs,
        // bounded by the wait timeout. We then publish each INLINE on this (calling,
        // main) thread — so the non-thread-safe store is never mutated off the owner
        // thread, even if a load wedges and this method bails out.
        Map<World, CompletableFuture<StructureGraph>> reads = new LinkedHashMap<>();
        for (World world : worlds) {
            reads.put(world, readDetachedAsync(world));
        }
        try {
            CompletableFuture.allOf(reads.values().toArray(new CompletableFuture[0]))
                    .get(persistenceWaitTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            disablePersistenceAfterFailedLoad("Structure loading was interrupted");
            return false;
        } catch (TimeoutException | ExecutionException e) {
            // Timeout: a load is wedged. ExecutionException: readDetachedAsync swallows
            // per-world failures, so this is unexpected — either way the data did not
            // load, and the same overwrite risk applies. Nothing was published, so the
            // store is untouched.
            disablePersistenceAfterFailedLoad("Structure loading did not complete (" + e + ")");
            return false;
        }
        // Every read finished in time — publish them on this thread.
        for (Map.Entry<World, CompletableFuture<StructureGraph>> entry : reads.entrySet()) {
            installDetached(entry.getKey(), entry.getValue().join());
        }
        return true;
    }

    private void disablePersistenceAfterFailedLoad(String reason) {
        persistenceAdapter = null;
        severe(reason + " — running WITHOUT saved structures, and saving is now DISABLED"
                + " so this incomplete state can never overwrite the good data on disk.");
    }

    /**
     * Load every world's structures <b>without blocking</b> the calling (main)
     * thread, then publish each finished graph back on the main thread.
     *
     * <p>This is the startup path: {@code onEnable} must return immediately so a
     * big saved world doesn't freeze the boot. For each world we:
     *
     * <ol>
     *   <li>read + deserialize ({@link StructureConverter#toGraph}) on the
     *       persistence executor, building a <b>detached</b> graph — the live
     *       graph map is never touched off the main thread;</li>
     *   <li>hand that detached graph to a {@code runTask} on the main thread,
     *       which is the ONLY place it gets installed into the graph map.</li>
     * </ol>
     *
     * <p>Until a world's graph is published, it is treated as having no tracked
     * structures (block events no-op, exactly as for an unloaded world) and the
     * world is marked load-pending so auto-save/shutdown-save skip it (an empty
     * graph must never overwrite good disk data).
     *
     * <p>A load that <b>fails</b> (the disk read or deserialization throws)
     * disables persistence for the session — the same protection the bounded
     * synchronous {@link #loadAllWorlds} gives — so the half-loaded state can
     * never be saved back over the good data on disk.
     *
     * @param plugin the plugin used to schedule the main-thread publish
     * @param worlds the worlds to load
     * @return a future that completes when every world's off-thread
     *     deserialization has finished (its publish is then scheduled on the
     *     main thread and runs on the next tick); never blocks the caller
     */
    CompletableFuture<Void> loadAllWorldsAsync(Plugin plugin, Collection<World> worlds) {
        this.plugin = plugin; // capture for the async single-world publish path
        if (persistenceAdapter == null) {
            return CompletableFuture.completedFuture(null);
        }
        // Mark every world load-pending up front (on the main thread, before any
        // off-thread work) so an auto-save that fires during the in-flight window
        // already sees the world as "don't touch".
        for (World world : worlds) {
            initialLoadPending.add(world.getUID());
        }
        CompletableFuture<?>[] futures = worlds.stream()
                .map(world -> loadWorldDetachedThenPublish(plugin, world))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    /**
     * Off-thread: read + deserialize one world into a DETACHED graph. On-thread:
     * publish it. The returned future completes when the off-thread part is done;
     * the publish is a separately-scheduled main-thread task (so it lands one tick
     * later, leaving a real in-flight window for racing events to no-op against).
     */
    private CompletableFuture<Void> loadWorldDetachedThenPublish(Plugin plugin, World world) {
        UUID worldId = world.getUID();
        return persistenceAdapter
                .loadAsync(worldId.toString())
                // toGraph is pure CPU on data with no Bukkit access — safe off-thread.
                .thenApply(optData -> optData.map(StructureConverter::toGraph).orElse(null))
                // handle() returns a non-exceptional future even when the load failed,
                // so the combined allOf(...) future never surfaces a load error to the
                // caller — the failure is handled here (persistence disabled on the
                // main thread) instead.
                .handle((graph, ex) -> {
                    if (ex != null) {
                        // Deserialize/read failed. Publish the failure handling on
                        // the main thread (it mutates shared persistence state).
                        runOnMain(plugin, () -> {
                            initialLoadPending.remove(worldId);
                            disablePersistenceAfterFailedLoad(
                                    "Structure loading failed for world " + world.getName() + " (" + ex + ")");
                        });
                    } else {
                        // Success: install the detached graph on the main thread ONLY.
                        runOnMain(plugin, () -> publishLoadedGraph(world, graph));
                    }
                    return (Void) null;
                });
    }

    /**
     * MAIN-THREAD ONLY. Merge a freshly-deserialized detached graph into the live
     * graph map (live nodes win on conflict; see {@link WorldGraphStore#mergeLoaded})
     * and clear the world's load-pending flag so saves are allowed again. This is the
     * single writer of the graph map on the load path — the invariant that keeps the
     * (non-thread-safe) graph from ever being touched concurrently with a cascade.
     */
    private void publishLoadedGraph(World world, StructureGraph graph) {
        UUID worldId = world.getUID();
        if (graph != null) {
            graphStore.mergeLoaded(worldId, graph);
            graphStore.markDirty(world);
            if (logger != null) {
                logger.info("Loaded " + graph.size() + " blocks for world " + world.getName());
            }
        }
        // Clear pending whether or not there was saved data: an empty world has
        // finished loading too, and must become saveable.
        initialLoadPending.remove(worldId);
    }

    /**
     * Run a task on the main server thread via the scheduler. The whole point of
     * the async-load design is that the graph map is only ever mutated here.
     */
    private void runOnMain(Plugin plugin, Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    /**
     * Save a specific world's structures.
     *
     * @param world the world to save
     * @return future that completes when save is done
     */
    CompletableFuture<Void> saveWorldAsync(World world) {
        if (persistenceAdapter == null) {
            return CompletableFuture.completedFuture(null);
        }

        // Don't save a world whose async initial load hasn't finished: its graph
        // is still being deserialized (or its publish hasn't run), so saving now
        // would write an empty/partial snapshot over the good data on disk.
        if (initialLoadPending.contains(world.getUID())) {
            return CompletableFuture.completedFuture(null);
        }

        StructureGraph graph = graphStore.get(world.getUID());
        if (graph == null || graph.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        String worldId = world.getUID().toString();
        StructureData data = StructureConverter.toData(graph, worldId);

        return persistenceAdapter.saveAsync(worldId, data).thenRun(() -> {
            if (logger != null && config.isDebugLogging()) {
                logger.info("Saved " + data.blockCount() + " blocks for world " + world.getName());
            }
        });
    }

    /**
     * Load structures for a world from persistence.
     *
     * @param world the world to load
     * @return future with true if data was loaded, false if no saved data
     */
    CompletableFuture<Boolean> loadWorldAsync(World world) {
        if (persistenceAdapter == null) {
            return CompletableFuture.completedFuture(false);
        }
        // Deserialize off-thread into a DETACHED graph, then PUBLISH on the main
        // thread — the graph store is non-thread-safe and main-thread-owned, so it
        // must never be mutated from the persistence executor (the old bug). The
        // returned Boolean reports whether saved data was found; the publish is a
        // separately-scheduled main-thread task, so a caller must not assume the
        // graph is installed the instant the future completes (mirrors
        // loadAllWorldsAsync). Do NOT .join() this on the main thread.
        return readDetachedAsync(world).thenApply(graph -> {
            Plugin p = plugin;
            if (p != null) {
                runOnMain(p, () -> installDetached(world, graph));
            } else {
                // No scheduler captured yet (a direct single-threaded unit test of the
                // coordinator). Installing inline is safe there because there is no
                // concurrent owner thread.
                installDetached(world, graph);
            }
            return graph != null;
        });
    }

    /**
     * Load a world's structures synchronously. Safe to call on the main thread: the
     * read + deserialize runs off-thread (bounded), but the graph is installed
     * INLINE on this (calling) thread, so the non-thread-safe store is never mutated
     * off the owner thread.
     *
     * @param world the world to load
     * @return true if data was loaded
     */
    boolean loadWorld(World world) {
        if (persistenceAdapter == null) {
            return false;
        }
        StructureGraph graph;
        try {
            graph = readDetachedAsync(world).get(persistenceWaitTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            severe("Structure load for world " + world.getName() + " was interrupted.");
            return false;
        } catch (TimeoutException | ExecutionException e) {
            severe("Structure load for world " + world.getName() + " did not complete (" + e + ").");
            return false;
        }
        return installDetached(world, graph);
    }

    /**
     * Off-thread: read a world's saved data and deserialize it into a DETACHED graph
     * ({@code null} when there is no saved data). Touches no shared state — {@code
     * toGraph} is pure CPU and the graph is not yet published. A per-world read
     * failure is swallowed to {@code null} (treated as "no data") so a sibling
     * world's load is unaffected, exactly as before.
     */
    private CompletableFuture<StructureGraph> readDetachedAsync(World world) {
        return persistenceAdapter
                .loadAsync(world.getUID().toString())
                .thenApply(optData -> optData.map(StructureConverter::toGraph).orElse(null))
                .exceptionally(ex -> {
                    if (logger != null) {
                        logger.severe("Failed to load world " + world.getName() + ": " + ex.getMessage());
                    }
                    return null;
                });
    }

    /**
     * MAIN-THREAD ONLY. Merge a freshly-deserialized detached graph into the live
     * store (no-op when {@code graph} is null, i.e. the world had no saved data),
     * live nodes winning on conflict (see {@link WorldGraphStore#mergeLoaded}).
     * Stress is recalculated lazily on the next break/place/grade, so no solve runs
     * here — startup stays fast even with large terrain.
     *
     * @return true if a graph was installed (saved data existed)
     */
    private boolean installDetached(World world, StructureGraph graph) {
        if (graph == null) {
            return false;
        }
        graphStore.mergeLoaded(world.getUID(), graph);
        graphStore.markDirty(world);
        if (logger != null) {
            logger.info("Loaded " + graph.size() + " blocks for world " + world.getName());
        }
        return true;
    }
}
