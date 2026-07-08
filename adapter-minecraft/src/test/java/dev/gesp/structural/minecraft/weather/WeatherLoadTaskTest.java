package dev.gesp.structural.minecraft.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.config.EffectsConfig;
import dev.gesp.structural.minecraft.hook.WarZoneService;
import dev.gesp.structural.minecraft.listener.CascadeResumeManager;
import dev.gesp.structural.minecraft.listener.DelayedCollapseManager;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.material.MaterialRegistry;
import dev.gesp.structural.minecraft.perf.PerfTracker;
import dev.gesp.structural.minecraft.perf.TaskTimings;
import dev.gesp.structural.minecraft.protect.ChunkVerdict;
import dev.gesp.structural.minecraft.protect.CollapseGuard;
import dev.gesp.structural.minecraft.protect.NoopCollapseLogger;
import dev.gesp.structural.minecraft.protect.ProtectionService;
import dev.gesp.structural.minecraft.visual.CollapseEffects;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Asymptotics + tick-budget for the weather sweep.
 *
 * <pre>
 *   The bug: processWorld asked the collapse guard "physics allowed here?" PER BLOCK,
 *   and on a WorldGuard server each call is a region query. On a 135k-block world a
 *   single weather change froze the main thread for 20+ seconds.
 *
 *   The fix: ask once per CHUNK (region membership is uniform across a chunk that no
 *   region edge crosses), and spread the sweep across ticks under a wall-clock budget.
 * </pre>
 */
@DisplayName("WeatherLoadTask: per-chunk protection + tick budget")
class WeatherLoadTaskTest {

    private ServerMock server;
    private WorldMock world;
    private StructureManager structureManager;
    private DelayedCollapseManager delayedCollapseManager;

    /** Counts how many times the protection service is consulted, per granularity. */
    private static final class CountingProtection implements ProtectionService {
        final AtomicInteger perBlockCalls = new AtomicInteger();
        final AtomicInteger chunkCalls = new AtomicInteger();

        @Override
        public boolean physicsAllowed(Location loc) {
            perBlockCalls.incrementAndGet();
            return true;
        }

        @Override
        public ChunkVerdict chunkVerdict(World w, int chunkX, int chunkZ) {
            chunkCalls.incrementAndGet();
            return ChunkVerdict.ALL_ALLOWED; // no regions defined → whole chunk allowed
        }

        @Override
        public String describe() {
            return "counting";
        }
    }

    /**
     * A WorldMock that counts the two (x,z)-column world lookups the weather sweep
     * makes: {@code getHighestBlockYAt} (sky access) and {@code getBiome} (snow check).
     *
     * <p>Both are funneled to the leaf overloads the production paths reach
     * ({@code getHighestBlockYAt(int,int)} and {@code getBiome(int,int,int)}, the
     * latter via {@code BlockMock.getBiome()} → {@code world.getBiome(Location)}), so
     * counting here counts exactly the redundant per-node world calls the column cache
     * is meant to eliminate.
     */
    private static final class CountingWorldMock extends WorldMock {
        final AtomicInteger skyCalls = new AtomicInteger();
        final AtomicInteger biomeCalls = new AtomicInteger();

        @Override
        public int getHighestBlockYAt(int x, int z) {
            skyCalls.incrementAndGet();
            return super.getHighestBlockYAt(x, z);
        }

        @Override
        public Biome getBiome(int x, int y, int z) {
            biomeCalls.incrementAndGet();
            return super.getBiome(x, y, z);
        }
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        // Plugin not loaded; we build the weather task directly with a fake plugin so
        // we control its collaborators (esp. the protection seam) precisely.
        world = server.addSimpleWorld("weather_world");
        structureManager = new StructureManager(new MaterialRegistry(), new PhysicsConfig());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private CollapseGuard guardWith(ProtectionService protection) {
        return new CollapseGuard(protection, WarZoneService.ALLOW_ALL, NoopCollapseLogger.INSTANCE);
    }

    /** The timings recorder of the most recently built task (for the work-count assertion). */
    private TaskTimings lastTaskTimings;

    private WeatherLoadTask newTask(CollapseGuard guard, WeatherLoadTask.WeatherConfig config) {
        org.bukkit.plugin.Plugin plugin = MockBukkit.createMockPlugin("WeatherTestPlugin");
        CollapseEffects effects = new CollapseEffects(new EffectsConfig(), plugin);
        TaskTimings taskTimings = new TaskTimings();
        lastTaskTimings = taskTimings;
        delayedCollapseManager =
                new DelayedCollapseManager(plugin, structureManager, new EffectsConfig(), effects, guard, taskTimings);
        CascadeResumeManager resume = new CascadeResumeManager(
                plugin, structureManager, delayedCollapseManager, Logger.getLogger("WeatherResume"), 100, taskTimings);
        return new WeatherLoadTask(
                plugin,
                structureManager,
                new PhysicsConfig(),
                delayedCollapseManager,
                resume,
                guard,
                config,
                taskTimings);
    }

    /** A weather config that sweeps every block (rain on) but never collapses anything. */
    private WeatherLoadTask.WeatherConfig harmlessRain(double tickBudgetMs) {
        return new WeatherLoadTask.WeatherConfig(
                true, // enabled
                40, // scanIntervalTicks
                false, // requireSkyAccess — sweep every node, no sky raycast
                true, // rainEnabled
                1.0, // rainCapacityMultiplier — no capacity loss → no collapse
                false, // thunderEnabled
                1.0, // thunderCapacityMultiplier
                false, // thunderSpikesEnabled
                0.0, // thunderSpikeChance
                0.0, // thunderSpikeAmount
                false, // snowEnabled
                0.0, // snowLoadPerScan
                0.0, // snowMaxLoad
                0.0, // snowDecayPerScan
                tickBudgetMs);
    }

    /** Fill N floating nodes spread across a known number of chunks. */
    private int fillNodes(World world, int nodesPerChunkSide, int chunksPerSide) {
        StructureGraph graph = structureManager.getOrCreateGraph(world);
        MaterialSpec spec = new MaterialSpec(1.0, 100.0);
        int y = 80;
        int count = 0;
        for (int cx = 0; cx < chunksPerSide; cx++) {
            for (int cz = 0; cz < chunksPerSide; cz++) {
                // The sweep now skips unloaded chunks (no force-load); MockBukkit doesn't
                // auto-load, so load each chunk we fill or the sweep would touch nothing.
                world.loadChunk(cx, cz);
                for (int dx = 0; dx < nodesPerChunkSide; dx++) {
                    for (int dz = 0; dz < nodesPerChunkSide; dz++) {
                        int x = (cx << 4) + dx;
                        int z = (cz << 4) + dz;
                        graph.addNode(new NodePos(x, y, z), spec, false); // floating
                        count++;
                    }
                }
            }
        }
        return count;
    }

    @Test
    @DisplayName("protection is consulted O(chunks), not O(blocks), during one sweep")
    void perChunkNotPerBlock() {
        CountingProtection protection = new CountingProtection();
        WeatherLoadTask task = newTask(guardWith(protection), harmlessRain(1000.0));

        // 4 chunks, 64 nodes each = 256 nodes.
        int chunksPerSide = 2;
        int total = fillNodes(world, 8, chunksPerSide);
        int chunks = chunksPerSide * chunksPerSide;
        assertEquals(256, total);

        task.run();

        assertEquals(
                0,
                protection.perBlockCalls.get(),
                "no per-block protection query when every chunk is uniformly allowed");
        assertEquals(chunks, protection.chunkCalls.get(), "exactly one chunk query per occupied chunk");
    }

    @Test
    @DisplayName("a tiny budget spreads the sweep across ticks and misses no chunk")
    void budgetSpreadsAcrossTicks() {
        CountingProtection protection = new CountingProtection();
        // Near-zero budget: at most a sliver of work per scan, so the sweep needs
        // several scans to cover every chunk.
        WeatherLoadTask task = newTask(guardWith(protection), harmlessRain(0.0));

        int chunksPerSide = 4; // 16 chunks
        fillNodes(world, 4, chunksPerSide);
        int chunks = chunksPerSide * chunksPerSide;

        // Drive the sweep over many scans; each scan does a bounded amount, so the
        // running chunk-query total must climb monotonically (incremental, not all-at-once).
        Set<Integer> seenChunkCounts = new HashSet<>();
        int scans = 0;
        int previous = 0;
        while (protection.chunkCalls.get() < chunks && scans < 1000) {
            task.run();
            int now = protection.chunkCalls.get();
            assertTrue(now > previous, "each scan must make some progress, never stall");
            previous = now;
            seenChunkCounts.add(now);
            scans++;
        }

        assertEquals(chunks, protection.chunkCalls.get(), "every chunk visited exactly once across the spread sweep");
        assertTrue(scans > 1, "a zero budget must take more than one scan to finish");
        assertTrue(seenChunkCounts.size() > 1, "the sweep advanced through several distinct progress points");
        assertEquals(0, protection.perBlockCalls.get(), "still no per-block queries on the budgeted path");
    }

    @Test
    @DisplayName("each block is swept exactly once across a budgeted multi-tick sweep")
    void noBlockMissedOrDoubleCounted() {
        // A protection double that records which positions were touched at chunk level
        // by returning PER_BLOCK, forcing the per-block path so we can count blocks.
        AtomicInteger blockTouches = new AtomicInteger();
        ProtectionService perBlock = new ProtectionService() {
            @Override
            public boolean physicsAllowed(Location loc) {
                blockTouches.incrementAndGet();
                return true;
            }

            @Override
            public ChunkVerdict chunkVerdict(World w, int chunkX, int chunkZ) {
                return ChunkVerdict.PER_BLOCK; // force the exact per-block fallback
            }

            @Override
            public String describe() {
                return "per-block";
            }
        };
        WeatherLoadTask task = newTask(guardWith(perBlock), harmlessRain(0.0));

        int chunksPerSide = 2;
        int total = fillNodes(world, 6, chunksPerSide); // 36 nodes/chunk * 4 = 144

        int scans = 0;
        while (blockTouches.get() < total && scans < 5000) {
            task.run();
            scans++;
        }

        assertEquals(total, blockTouches.get(), "every block consulted exactly once, none missed or doubled");
        assertTrue(scans > 1, "zero budget must span multiple scans");
    }

    @Test
    @DisplayName("perf evidence: 50k blocks → O(chunks) queries, not O(blocks)")
    void largeWorldQueryCountIsPerChunk() {
        CountingProtection protection = new CountingProtection();
        // Generous budget so the whole sweep finishes in one scan; we measure the query
        // count, which is the deterministic, machine-independent assertion.
        WeatherLoadTask task = newTask(guardWith(protection), harmlessRain(60_000.0));

        // 16x16 chunks, 16x16 = 256 nodes/chunk → 65_536 nodes across 256 chunks.
        int chunksPerSide = 16;
        int total = fillNodes(world, 16, chunksPerSide);
        int chunks = chunksPerSide * chunksPerSide;
        assertEquals(65_536, total);
        assertTrue(total > 50_000, "synthetic world exceeds 50k blocks");

        task.run();

        // The whole point: ~256 chunk queries instead of ~65k per-block queries.
        assertEquals(chunks, protection.chunkCalls.get(), "one protection query per chunk");
        assertEquals(0, protection.perBlockCalls.get(), "zero per-block queries on a region-free world");
        assertTrue(
                protection.chunkCalls.get() * 100L < total, "query count is two orders of magnitude below block count");
    }

    // ----------------------------------------------------------------------------
    // Column cache (perf-weather-column-cache): sky-height + biome are per-(x,z)
    // column, so the sweep must look each up at most ONCE per distinct column, not
    // once per node, while producing byte-identical weather effects.
    // ----------------------------------------------------------------------------

    /**
     * Load every chunk that holds a graph node, so the sweep (which now skips unloaded
     * chunks instead of force-loading them) actually visits them. MockBukkit never
     * auto-loads chunks.
     */
    private void loadGraphChunks(World w) {
        StructureGraph graph = structureManager.getOrCreateGraph(w);
        for (Node node : graph.getAllNodes()) {
            w.loadChunk(node.pos().x() >> 4, node.pos().z() >> 4);
        }
    }

    /** Register a counting world under a fresh name and return it. */
    private CountingWorldMock addCountingWorld(String name) {
        CountingWorldMock w = new CountingWorldMock();
        w.setName(name);
        server.addWorld(w);
        return w;
    }

    /** A weather config that requires sky access and snows; tunable rain multiplier. */
    private WeatherLoadTask.WeatherConfig realRain(double rainCapacityMultiplier, boolean snowEnabled) {
        return new WeatherLoadTask.WeatherConfig(
                true, // enabled
                40, // scanIntervalTicks
                true, // requireSkyAccess — exercises getHighestBlockYAt
                true, // rainEnabled
                rainCapacityMultiplier,
                false, // thunderEnabled
                1.0, // thunderCapacityMultiplier
                false, // thunderSpikesEnabled
                0.0, // thunderSpikeChance
                0.0, // thunderSpikeAmount
                snowEnabled,
                0.05, // snowLoadPerScan
                0.3, // snowMaxLoad
                0.0, // snowDecayPerScan
                60_000.0); // generous budget — finish each sweep in one scan
    }

    /**
     * Criterion 1: over one sweep, each distinct (x,z) column triggers at most one
     * sky-height and one biome lookup, regardless of how many nodes share it.
     */
    @Test
    @DisplayName("column cache: one sky + one biome lookup per distinct column, not per node")
    void oneLookupPerColumnNotPerNode() {
        CountingWorldMock counting = addCountingWorld("col_cache_world");
        WeatherLoadTask task = newTask(guardWith(new CountingProtection()), realRain(1.0, true));

        // Stack several floating nodes in each of a few distinct (x,z) columns.
        StructureGraph graph = structureManager.getOrCreateGraph(counting);
        MaterialSpec spec = new MaterialSpec(1.0, 100.0);
        int[][] columns = {{1, 1}, {2, 5}, {10, 3}};
        int nodesPerColumn = 4;
        Set<Long> distinct = new HashSet<>();
        for (int[] c : columns) {
            distinct.add((((long) c[0]) << 32) ^ (c[1] & 0xffffffffL));
            for (int dy = 0; dy < nodesPerColumn; dy++) {
                graph.addNode(new NodePos(c[0], 70 + dy, c[1]), spec, false);
            }
        }
        int distinctColumns = distinct.size();
        assertEquals(3, distinctColumns);

        // Force rain so the snow (biome) path is reached for every column.
        counting.setStorm(true);
        loadGraphChunks(counting);

        task.run();

        assertEquals(
                distinctColumns,
                counting.skyCalls.get(),
                "getHighestBlockYAt called once per distinct column, not once per node ("
                        + (distinctColumns * nodesPerColumn) + " nodes)");
        assertEquals(
                distinctColumns,
                counting.biomeCalls.get(),
                "getBiome called once per distinct column, not once per node");
    }

    /**
     * Criterion 2: the cached path produces byte-identical weather effects — the same
     * exposed/covered decision, the same cold-biome snow, the same collapses — as a
     * straightforward per-node reference computed from the same world reads.
     */
    @Test
    @DisplayName("column cache: identical exposed/snow/collapse outcome vs uncached reference")
    void identicalOutcomeAsReference() {
        CountingWorldMock counting = addCountingWorld("equiv_world");
        WeatherLoadTask.WeatherConfig cfg = realRain(0.95, true);
        WeatherLoadTask task = newTask(guardWith(new CountingProtection()), cfg);

        StructureGraph graph = structureManager.getOrCreateGraph(counting);
        MaterialSpec spec = new MaterialSpec(1.0, 100.0);

        // Each column is a vertical stack: grounded base (y=69), a stressed TRIGGER
        // node (y=70) connected to ground, and a DEPENDENT node (y=71) hung off the
        // trigger. When the trigger overloads-when-wet it is removed, the dependent
        // loses its only path to ground and cascades — an observable pending collapse
        // on the DEPENDENT. (A lone overloaded node is just removed with no cascade,
        // so the dependent is what makes "did this column collapse?" observable.)
        //
        // Trigger stress is 93/100. Wet capacity = 100 * 0.95 = 95.
        //   exposed + warm: 93 < 95          -> no collapse
        //   exposed + cold: 93 + snow(5) = 98 > 95 -> collapses (SNOW is the tipping load)
        //   covered (no sky): never affected -> no collapse, even cold
        // So the cold-vs-warm difference proves the per-column biome read drives snow,
        // and the covered columns prove the per-column sky read drives exposure.
        int[][] columns = {
            {0, 0}, // exposed, warm
            {3, 0}, // covered, warm
            {6, 0}, // exposed, cold
            {9, 0}, // covered, cold
        };
        boolean[] covered = {false, true, false, true};
        boolean[] cold = {false, false, true, true};

        List<NodePos> dependents = new ArrayList<>();
        for (int i = 0; i < columns.length; i++) {
            int x = columns[i][0];
            int z = columns[i][1];
            NodePos ground = new NodePos(x, 69, z);
            NodePos trigger = new NodePos(x, 70, z);
            NodePos dependent = new NodePos(x, 71, z);
            graph.addNode(ground, MaterialSpec.GROUND, true);
            graph.addNode(trigger, spec, false);
            graph.addNode(dependent, spec, false);
            graph.connect(ground, trigger);
            graph.connect(trigger, dependent);
            graph.getNode(trigger).setVerticalStress(93.0);
            dependents.add(dependent);
            if (covered[i]) {
                counting.getBlockAt(x, 75, z).setType(Material.STONE); // roof -> no sky
            }
            if (cold[i]) {
                counting.setBiome(x, 70, z, Biome.SNOWY_TAIGA);
                counting.setBiome(x, 71, z, Biome.SNOWY_TAIGA);
            }
        }

        counting.setStorm(true);

        // Reference: compute, from the SAME world reads, which TRIGGER overloads and
        // therefore which DEPENDENT cascades.
        Set<NodePos> expectedCollapses = new HashSet<>();
        for (int i = 0; i < columns.length; i++) {
            int x = columns[i][0];
            int z = columns[i][1];
            NodePos trigger = new NodePos(x, 70, z);
            Node node = graph.getNode(trigger);
            boolean exposed = counting.getHighestBlockYAt(x, z) <= trigger.y();
            boolean isCold = counting.getBlockAt(x, trigger.y(), z)
                    .getBiome()
                    .name()
                    .toLowerCase()
                    .contains("snow");
            if (!exposed) {
                continue;
            }
            double snowLoad = isCold ? Math.min(cfg.snowMaxLoad(), cfg.snowLoadPerScan()) : 0.0;
            double wetCapacity = node.effectiveMaxLoad() * cfg.rainCapacityMultiplier();
            double totalStress = node.stressValue() + snowLoad * node.spec().maxLoad();
            if (totalStress > wetCapacity) {
                expectedCollapses.add(new NodePos(x, 71, z)); // the dependent cascades
            }
        }
        // Sanity: only the exposed COLD column should tip (snow is the deciding load).
        assertEquals(
                Set.of(new NodePos(6, 71, 0)),
                expectedCollapses,
                "reference scenario: only the exposed cold column tips (snow load decides)");

        loadGraphChunks(counting);
        task.run();

        for (NodePos dependent : dependents) {
            boolean expectCollapse = expectedCollapses.contains(dependent);
            assertEquals(
                    expectCollapse,
                    delayedCollapseManager.isPendingCollapse(counting, dependent),
                    "collapse outcome at " + dependent + " must match the uncached reference");
        }
    }

    /**
     * Criterion 3: the cache is scoped to one sweep, so terrain that changes BETWEEN
     * sweeps is reflected — a column newly exposed after the first sweep is seen as
     * exposed in the next, and a node there can then collapse.
     */
    @Test
    @DisplayName("column cache: a column newly exposed between sweeps is seen as exposed")
    void noStaleCacheAcrossSweeps() {
        CountingWorldMock counting = addCountingWorld("stale_cache_world");
        WeatherLoadTask task = newTask(guardWith(new CountingProtection()), realRain(0.95, false));

        StructureGraph graph = structureManager.getOrCreateGraph(counting);
        MaterialSpec spec = new MaterialSpec(1.0, 100.0);
        // Stack: ground (69) -> stressed trigger (70) -> dependent (71). The dependent
        // is the observable: it cascades only when the trigger overloads-when-wet.
        NodePos ground = new NodePos(0, 69, 0);
        NodePos trigger = new NodePos(0, 70, 0);
        NodePos dependent = new NodePos(0, 71, 0);
        graph.addNode(ground, MaterialSpec.GROUND, true);
        graph.addNode(trigger, spec, false);
        graph.addNode(dependent, spec, false);
        graph.connect(ground, trigger);
        graph.connect(trigger, dependent);
        graph.getNode(trigger).setVerticalStress(98.0); // 98 > wet capacity 95 once exposed

        // First sweep: a solid roof above keeps the column COVERED -> not exposed -> no
        // weather effect -> no collapse.
        counting.getBlockAt(0, 75, 0).setType(Material.STONE);
        counting.setStorm(true);
        loadGraphChunks(counting);
        task.run();
        assertFalse(
                delayedCollapseManager.isPendingCollapse(counting, dependent),
                "covered column must not collapse in the first sweep");

        // Terrain change between sweeps: remove the roof -> column now exposed.
        counting.getBlockAt(0, 75, 0).setType(Material.AIR);

        // Second sweep: a fresh sweep rebuilds the column cache, sees the new sky
        // height, the now-exposed trigger overloads-when-wet and the dependent cascades.
        task.run();
        assertTrue(
                delayedCollapseManager.isPendingCollapse(counting, dependent),
                "newly-exposed column must be seen as exposed in the next sweep (no stale cache)");
    }

    /**
     * Criterion 4: the column cache removes only the redundant world calls — the
     * recorded per-pass work ({@code blocksVisitedThisPass}) still equals the number of
     * floating nodes the sweep examines, unchanged by the cache.
     */
    @Test
    @DisplayName("column cache: recorded per-pass work still equals the floating-node count")
    void recordedWorkUnchangedByCache() {
        CountingWorldMock counting = addCountingWorld("work_count_world");
        // Generous budget so the whole sweep completes in one pass; rain on so the
        // sky/biome paths run for every node.
        WeatherLoadTask task = newTask(guardWith(new CountingProtection()), realRain(1.0, true));

        StructureGraph graph = structureManager.getOrCreateGraph(counting);
        MaterialSpec spec = new MaterialSpec(1.0, 100.0);
        int chunksPerSide = 2;
        int nodesPerColumn = 3;
        int floatingNodes = 0;
        for (int cx = 0; cx < chunksPerSide; cx++) {
            for (int cz = 0; cz < chunksPerSide; cz++) {
                for (int dx = 0; dx < 4; dx++) {
                    for (int dz = 0; dz < 4; dz++) {
                        for (int dy = 0; dy < nodesPerColumn; dy++) {
                            int x = (cx << 4) + dx;
                            int z = (cz << 4) + dz;
                            graph.addNode(new NodePos(x, 70 + dy, z), spec, false);
                            floatingNodes++;
                        }
                    }
                }
            }
        }
        counting.setStorm(true);
        loadGraphChunks(counting);

        task.run();

        PerfTracker weather = lastTaskTimings.tracker(TaskTimings.WEATHER_LOAD);
        assertEquals(1, weather.sampleCount(), "the whole sweep finished in one recorded pass");
        assertEquals(
                (double) floatingNodes,
                weather.averageWork(),
                0.0,
                "per-pass work equals the floating-node count — the cache changes world calls, not work");

        // And the cache really did its job: far fewer column lookups than nodes.
        int distinctColumns = chunksPerSide * chunksPerSide * 4 * 4; // one (x,z) per column
        assertEquals(distinctColumns, counting.skyCalls.get(), "sky lookups dropped to one per distinct column");
        assertEquals(distinctColumns, counting.biomeCalls.get(), "biome lookups dropped to one per distinct column");
        assertTrue(distinctColumns < floatingNodes, "there really are more nodes than columns in this fixture");
    }
}
