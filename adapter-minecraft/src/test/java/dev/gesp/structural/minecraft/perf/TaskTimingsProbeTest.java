package dev.gesp.structural.minecraft.perf;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.minecraft.StructuralIntegrityPlugin;
import dev.gesp.structural.persistence.BlockData;
import dev.gesp.structural.persistence.PersistenceAdapter;
import dev.gesp.structural.persistence.StructureData;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * The measurement probe (acceptance criterion 5): build a synthetic
 * multi-thousand-node world through the persistence load path, run every
 * repeating task for several periods with a player online, PRINT the per-task
 * ranking (the empirical evidence for the next optimization round), and pin the
 * structural claim that drives it.
 *
 * <pre>
 *   Static-world claim (post perf-damage-visualizer-cache):
 *     BOTH visualizers are revision-cached. On a freshly loaded, unstressed,
 *     static world the distressed and stressed candidate sets are empty, so
 *     neither does whole-graph work — each pass's recorded work is FAR below the
 *     tracked-node count. (Before the cache, damage-visualizer scanned every
 *     tracked node every pass; that regression is what this round removed.)
 * </pre>
 */
@DisplayName("Probe: per-task ranking on a synthetic multi-thousand-node world")
class TaskTimingsProbeTest {

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private WorldMock world;

    // 40x40 footprint, 3 floating blocks per column on a grounded base = 4800
    // floating nodes + 1600 grounded = 6400 tracked nodes.
    private static final int FOOTPRINT = 40;
    private static final int HEIGHT = 3;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        world = server.addSimpleWorld("probe_world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Build the synthetic world data: a grounded base with HEIGHT floating blocks per column. */
    private int buildSynthetic() {
        StructureData data = new StructureData(world.getUID().toString());
        int total = 0;
        for (int x = 0; x < FOOTPRINT; x++) {
            for (int z = 0; z < FOOTPRINT; z++) {
                data.addBlock(new BlockData(x, 64, z, 0.0, Double.MAX_VALUE, true)); // grounded foundation
                total++;
                for (int dy = 1; dy <= HEIGHT; dy++) {
                    data.addBlock(new BlockData(x, 64 + dy, z, 2.0, 60.0, false));
                    total++;
                }
            }
        }
        plugin.getStructureManager().setPersistenceAdapter(adapterReturning(data));
        assertTrue(plugin.getStructureManager().loadWorld(world), "synthetic world must load");
        // The footprint spans chunks 0..(FOOTPRINT-1)>>4. A real server keeps a chunk
        // holding tracked structure loaded; MockBukkit does not, so load them here — else
        // the tasks' "skip unloaded chunks" gates would skip the whole synthetic world.
        int maxChunk = (FOOTPRINT - 1) >> 4;
        for (int cx = 0; cx <= maxChunk; cx++) {
            for (int cz = 0; cz <= maxChunk; cz++) {
                world.loadChunk(cx, cz);
            }
        }
        return total;
    }

    private static PersistenceAdapter adapterReturning(StructureData data) {
        return new PersistenceAdapter() {
            @Override
            public String getName() {
                return "probe-test-adapter";
            }

            @Override
            public CompletableFuture<Void> saveAsync(String worldId, StructureData d) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Optional<StructureData>> loadAsync(String worldId) {
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
        };
    }

    @Test
    @Timeout(15)
    @DisplayName("both visualizers are revision-cached: static-world work stays far below the node count")
    void rankingAndStructuralClaim() {
        int totalNodes = buildSynthetic();
        assertTrue(totalNodes >= 4000, "the probe needs a few thousand nodes; built " + totalNodes);

        // A player online: the visualizers only iterate worlds with viewers.
        PlayerMock player = server.addPlayer("Prober");
        player.teleport(world.getBlockAt(0, 66, 0).getLocation());

        TaskTimings timings = plugin.getTaskTimings();

        // Run past every periodic task's period a few times so each has samples.
        server.getScheduler().performTicks(120);

        PerfTracker damage = timings.tracker(TaskTimings.DAMAGE_VISUALIZER);
        PerfTracker stress = timings.tracker(TaskTimings.STRESS_VISUALIZER);

        assertTrue(damage.sampleCount() >= 1, "damage-visualizer must have recorded at least one pass");
        assertTrue(stress.sampleCount() >= 1, "stress-visualizer must have recorded at least one pass");

        // STRUCTURAL CLAIM (a) — perf-damage-visualizer-cache: damage-visualizer is
        // now revision-cached, exactly like stress-visualizer. On a static, freshly
        // loaded world with no damage and no stress cracks the distressed set is
        // empty, so a pass iterates ~0 candidates instead of all tracked nodes. The
        // recorded per-pass work is therefore FAR below the tracked-node count — the
        // whole point of the optimization (was: == totalNodes every pass).
        assertTrue(
                damage.averageWork() < totalNodes / 10.0,
                "damage-visualizer must no longer scan all nodes on a static world: work (" + damage.averageWork()
                        + ") should be far below the tracked total (" + totalNodes + ")");

        // STRUCTURAL CLAIM (b): both visualizers are bounded by their revision-cached
        // candidate sets. On this static, unstressed world both sets are tiny, so
        // neither does whole-graph work and stress stays within damage's order.
        assertTrue(
                stress.averageWork() < totalNodes / 10.0,
                "stress-visualizer work (" + stress.averageWork() + ") must also stay far below the tracked total ("
                        + totalNodes + ")");

        // STRUCTURAL CLAIM (c) — perf-weather-column-cache: the column cache for
        // sky-height + biome removes redundant WORLD calls, not work. The weather
        // sweep's recorded per-pass work is the count of FLOATING nodes it examined
        // (grounded foundation nodes are skipped at sweep start), so it stays positive
        // and bounded by the floating set — unchanged by caching the column reads.
        PerfTracker weather = timings.tracker(TaskTimings.WEATHER_LOAD);
        int floatingNodes = FOOTPRINT * FOOTPRINT * HEIGHT; // HEIGHT floating per column
        assertTrue(weather.sampleCount() >= 1, "weather-load must have recorded at least one pass");
        assertTrue(weather.averageWork() > 0.0, "weather-load examines floating nodes, so its work is positive");
        assertTrue(
                weather.averageWork() <= floatingNodes,
                "weather-load per-pass work (" + weather.averageWork() + ") never exceeds the floating-node count ("
                        + floatingNodes + ") — the cache changes world calls, not the nodes visited");

        // PRINT the empirical ranking — the analysis deliverable.
        printRanking(timings, totalNodes);
    }

    /** Sorted by average ms descending: the per-task cost ranking. */
    private void printRanking(TaskTimings timings, int totalNodes) {
        Map<String, PerfTracker> snap = timings.snapshot();
        StringBuilder sb = new StringBuilder();
        sb.append("\n==== strux per-task ranking (synthetic ")
                .append(totalNodes)
                .append("-node world) ====\n");
        sb.append(String.format("%-20s %12s %12s %10s %12s%n", "task", "avg ms", "worst ms", "passes", "avg work"));
        snap.entrySet().stream()
                .sorted((a, b) -> Double.compare(
                        b.getValue().averageMillis(), a.getValue().averageMillis()))
                .forEach(e -> {
                    PerfTracker t = e.getValue();
                    sb.append(String.format(
                            "%-20s %12.4f %12.4f %10d %12.1f%n",
                            e.getKey(), t.averageMillis(), t.worstMillis(), t.sampleCount(), t.averageWork()));
                });
        sb.append("========================================================\n");
        System.out.println(sb);
    }
}
