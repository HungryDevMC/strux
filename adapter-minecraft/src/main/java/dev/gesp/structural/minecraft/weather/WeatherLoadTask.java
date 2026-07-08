package dev.gesp.structural.minecraft.weather;

import dev.gesp.structural.api.CollapseReason;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.listener.BudgetedTask;
import dev.gesp.structural.minecraft.listener.CascadeResumeManager;
import dev.gesp.structural.minecraft.listener.DelayedCollapseManager;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.perf.TaskTimings;
import dev.gesp.structural.minecraft.protect.ChunkVerdict;
import dev.gesp.structural.minecraft.protect.CollapseGuard;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.plugin.Plugin;

/**
 * Environmental weather effects on structures: rain weakens, thunder adds stress
 * spikes, snow accumulates.
 *
 * <pre>
 *   RAIN:
 *   ─────
 *   Exposed blocks have their effective capacity reduced (capacity multiplier).
 *   A structure at 95% stress becomes overloaded when wet.
 *
 *   THUNDER:
 *   ────────
 *   Stronger capacity reduction + random stress spikes on exposed stressed blocks.
 *   A thunderstorm can trigger cascading failures.
 *
 *   SNOW:
 *   ─────
 *   Accumulates over time in cold biomes as additive load. A flat roof in a
 *   snowstorm slowly sags under the weight; clearing the snow (the block above)
 *   relieves it.
 * </pre>
 */
public class WeatherLoadTask extends BudgetedTask implements Listener {

    private final Plugin plugin;
    private final StructureManager structureManager;
    private final PhysicsConfig physicsConfig;
    private final DelayedCollapseManager delayedCollapseManager;
    private final CascadeResumeManager cascadeResumeManager;
    private final CollapseGuard guard;
    private final WeatherConfig config;
    private final TaskTimings taskTimings;

    /**
     * Blocks visited so far in the current {@link #run} pass. Reset at the top of
     * each pass and incremented per node in {@link #processChunk}; read once at the
     * end of the pass to record the work count. Main-thread only.
     */
    private int blocksVisitedThisPass;

    /** Weather state cache per world. */
    private final Map<UUID, WeatherState> weatherCache = new HashMap<>();

    /** Snow accumulation per world per block position. */
    private final Map<UUID, Map<NodePos, Double>> snowAccumulation = new HashMap<>();

    /** In-flight chunk-by-chunk sweep per world, or absent when idle. */
    private final Map<UUID, WorldSweep> activeSweeps = new HashMap<>();

    public WeatherLoadTask(
            Plugin plugin,
            StructureManager structureManager,
            PhysicsConfig physicsConfig,
            DelayedCollapseManager delayedCollapseManager,
            CascadeResumeManager cascadeResumeManager,
            CollapseGuard guard,
            WeatherConfig config,
            TaskTimings taskTimings) {
        this.plugin = plugin;
        this.structureManager = structureManager;
        this.physicsConfig = physicsConfig;
        this.delayedCollapseManager = delayedCollapseManager;
        this.cascadeResumeManager = cascadeResumeManager;
        this.guard = guard;
        this.config = config;
        this.taskTimings = taskTimings;
        setTickBudgetMs(config.tickBudgetMs());
    }

    /** Begin the periodic weather scan (no-op if disabled). */
    public void start() {
        if (config.enabled()) {
            runTaskTimer(plugin, config.scanIntervalTicks(), config.scanIntervalTicks());
        }
    }

    /** Stop the scan. */
    public void stop() {
        try {
            cancel();
        } catch (IllegalStateException ignored) {
            // never started or already cancelled
        }
        weatherCache.clear();
        snowAccumulation.clear();
        activeSweeps.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWeatherChange(WeatherChangeEvent event) {
        // Bukkit fires this BEFORE the new state is applied, so world.hasStorm() still
        // returns the OLD value here. Read the event's incoming state instead, or the
        // cache stays permanently one transition behind (inverted rain effects). The
        // thunder axis is unchanged by this event, so the world read is correct there.
        weatherCache.put(
                event.getWorld().getUID(),
                new WeatherState(event.toWeatherState(), event.getWorld().isThundering()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onThunderChange(ThunderChangeEvent event) {
        // Same one-transition-behind hazard: world.isThundering() is still the OLD value
        // inside this handler. Use the event's incoming thunder state; the rain axis is
        // untouched here, so the world read is correct for it.
        weatherCache.put(
                event.getWorld().getUID(), new WeatherState(event.getWorld().hasStorm(), event.toThunderState()));
    }

    private void updateWeatherCache(World world) {
        boolean raining = world.hasStorm();
        boolean thundering = world.isThundering();
        weatherCache.put(world.getUID(), new WeatherState(raining, thundering));
    }

    @Override
    public void run() {
        if (!config.enabled()) {
            return;
        }

        // Seatbelt: one scan can't freeze a tick. We process chunk-by-chunk and stop
        // once this scan has spent its wall-clock budget; a sweep in progress simply
        // resumes next scan (the weather effect arrives over a few ticks, never a
        // multi-second freeze).
        long start = System.nanoTime();
        long deadline = start + tickBudgetNanos();
        // Perf: count the nodes actually visited this pass (the chunk-by-chunk sweep
        // touches a slice per pass), so a reading shows whether weather cost is the
        // sweep size or something pricier per block.
        blocksVisitedThisPass = 0;

        for (World world : plugin.getServer().getWorlds()) {
            StructureGraph graph = structureManager.getGraph(world);
            if (graph == null || graph.isEmpty()) {
                activeSweeps.remove(world.getUID());
                continue;
            }

            // Cache weather state if not already present
            if (!weatherCache.containsKey(world.getUID())) {
                updateWeatherCache(world);
            }

            WeatherState weather = weatherCache.get(world.getUID());
            if (weather == null) {
                continue;
            }

            processWorld(world, graph, weather, deadline);

            if (System.nanoTime() >= deadline) {
                break; // remaining worlds resume next scan
            }
        }
        taskTimings.record(TaskTimings.WEATHER_LOAD, System.nanoTime() - start, blocksVisitedThisPass);
    }

    /**
     * Advance (or start) this world's chunk-by-chunk weather sweep until the wall-clock
     * deadline. Protection is consulted ONCE per chunk (a region-free chunk has a
     * uniform answer), not per block — the per-block region query was the source of the
     * 20-second freeze on large worlds.
     *
     * <p>Mid-sweep weather change: a sweep keeps the weather snapshot it started with
     * and runs to completion; the next scan starts a fresh sweep that picks up the new
     * weather. So a change part-way through finishes the old pass, then applies the new
     * one — never a half-applied mix or a double accumulation in one logical pass.
     */
    private void processWorld(World world, StructureGraph graph, WeatherState weather, long deadline) {
        WorldSweep sweep = activeSweeps.get(world.getUID());
        if (sweep == null || sweep.isFinished()) {
            sweep = startSweep(world, graph, weather);
            activeSweeps.put(world.getUID(), sweep);
        }

        Map<NodePos, Double> snow = snowAccumulation.computeIfAbsent(world.getUID(), k -> new HashMap<>());

        // Always make progress: process at least one chunk per scan even on a zero
        // budget, then keep going until the deadline. (A pathologically small budget
        // means a slower sweep, never a stalled one.)
        boolean first = true;
        while (!sweep.isFinished()) {
            if (!first && System.nanoTime() >= deadline) {
                return; // budget spent — resume this same sweep next scan
            }
            first = false;
            ChunkColumn chunk = sweep.next();
            if (processChunk(world, graph, sweep.weather(), snow, chunk, sweep.columnCache())) {
                sweep.markDirtied();
            }
        }

        if (sweep.dirtied()) {
            structureManager.markDirty(world);
        }
        activeSweeps.remove(world.getUID());
    }

    /** Snapshot the world's floating nodes into per-chunk buckets for a fresh sweep. */
    private WorldSweep startSweep(World world, StructureGraph graph, WeatherState weather) {
        Map<Long, ChunkColumn> chunks = new HashMap<>();
        for (Node node : graph.getAllNodes()) {
            if (node.isGrounded()) {
                continue;
            }
            NodePos pos = node.pos();
            int chunkX = pos.x() >> 4;
            int chunkZ = pos.z() >> 4;
            long key = (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
            chunks.computeIfAbsent(key, k -> new ChunkColumn(chunkX, chunkZ))
                    .positions
                    .add(pos);
        }
        return new WorldSweep(weather, new ArrayDeque<>(chunks.values()));
    }

    /**
     * Process one chunk's worth of nodes. The protection gate is asked ONCE for the
     * whole chunk; only when the answer is non-uniform ({@link ChunkVerdict#PER_BLOCK})
     * do we fall back to a per-block check, preserving exactness at region edges.
     *
     * @return whether anything collapsed (the world needs a settle).
     */
    private boolean processChunk(
            World world,
            StructureGraph graph,
            WeatherState weather,
            Map<NodePos, Double> snow,
            ChunkColumn chunk,
            Map<Long, ColumnInfo> columnCache) {
        // A structure in an unloaded chunk isn't being observed, and every column read
        // below (getHighestBlockYAt, getBiome, getBlockAt) would synchronously LOAD the
        // chunk — continuous load/unload churn for weather nobody can see. Skip it; the
        // chunk gets weathered the next time it is actually loaded.
        if (!world.isChunkLoaded(chunk.chunkX, chunk.chunkZ)) {
            return false;
        }
        ChunkVerdict verdict = guard.physicsAllowedInChunk(world, chunk.chunkX, chunk.chunkZ);
        if (verdict == ChunkVerdict.ALL_DENIED) {
            return false; // whole chunk protected — touch nothing here
        }
        boolean perBlock = verdict == ChunkVerdict.PER_BLOCK;

        boolean needsSettle = false;
        for (NodePos pos : chunk.positions) {
            blocksVisitedThisPass++; // perf: a node the sweep examined this pass
            Node node = graph.getNode(pos);
            if (node == null || node.isGrounded()) {
                continue; // node removed since the sweep snapshot
            }
            if (perBlock && !guard.physicsAllowed(StructureManager.toLocation(pos, world))) {
                continue; // exact per-block fallback at a region boundary
            }
            ColumnInfo column = columnFor(world, pos, columnCache);
            if (applyWeatherToNode(world, graph, weather, snow, node, pos, column)) {
                needsSettle = true;
            }
        }
        return needsSettle;
    }

    /**
     * The cached sky-height + biome for this node's (x,z) column, computed at most once
     * per distinct column per sweep. Sky-height and biome are properties of the column,
     * not the node, and change only when the world's terrain/biome changes — never
     * because weather changed or the sweep ran again — so caching them turns one
     * {@code getHighestBlockYAt} + one {@code getBiome} per NODE into one per COLUMN.
     */
    private ColumnInfo columnFor(World world, NodePos pos, Map<Long, ColumnInfo> columnCache) {
        long key = (((long) pos.x()) << 32) ^ (pos.z() & 0xffffffffL);
        return columnCache.computeIfAbsent(key, k -> new ColumnInfo(world, pos.x(), pos.y(), pos.z()));
    }

    /** Apply rain/thunder/snow to a single node; returns true if it collapsed. */
    private boolean applyWeatherToNode(
            World world,
            StructureGraph graph,
            WeatherState weather,
            Map<NodePos, Double> snow,
            Node node,
            NodePos pos,
            ColumnInfo column) {
        boolean exposed = !config.requireSkyAccess() || hasSkyAccess(column, pos);

        // Snow decay when not snowing (even indoors, eventually melts)
        if (!isSnowing(weather, column)) {
            Double currentSnow = snow.get(pos);
            if (currentSnow != null && currentSnow > 0) {
                double newSnow = Math.max(0, currentSnow - config.snowDecayPerScan());
                if (newSnow <= 0) {
                    snow.remove(pos);
                } else {
                    snow.put(pos, newSnow);
                }
            }
        }

        if (!exposed) {
            return false; // Interior blocks not affected by rain/snow
        }

        // Determine capacity multiplier from weather
        double capacityMultiplier = 1.0;
        if (weather.thundering && config.thunderEnabled()) {
            capacityMultiplier = config.thunderCapacityMultiplier();
        } else if (weather.raining && config.rainEnabled()) {
            capacityMultiplier = config.rainCapacityMultiplier();
        }

        // Snow accumulation in cold biomes
        if (config.snowEnabled() && isSnowing(weather, column)) {
            double currentSnow = snow.getOrDefault(pos, 0.0);
            double newSnow = Math.min(config.snowMaxLoad(), currentSnow + config.snowLoadPerScan());
            snow.put(pos, newSnow);
        }

        // Calculate effective stress with weather modifiers
        double snowLoad = snow.getOrDefault(pos, 0.0);
        double weatherAdjustedCapacity = node.effectiveMaxLoad() * capacityMultiplier;
        double totalStress = node.stressValue() + (snowLoad * node.spec().maxLoad());

        // Thunder stress spikes on stressed exposed blocks
        if (weather.thundering && config.thunderSpikesEnabled() && node.stressPercent() >= 0.5) {
            if (ThreadLocalRandom.current().nextDouble() < config.thunderSpikeChance()) {
                double spike = config.thunderSpikeAmount() * weatherAdjustedCapacity;
                totalStress += spike;
            }
        }

        // Check for collapse
        if (totalStress > weatherAdjustedCapacity) {
            triggerCollapse(world, graph, pos);
            return true;
        }
        return false;
    }

    /** Whether a block has direct sky access (nothing solid above). */
    private boolean hasSkyAccess(ColumnInfo column, NodePos pos) {
        return column.skyHeight() <= pos.y();
    }

    /** Whether it's snowing at this position (rain in cold biome). */
    private boolean isSnowing(WeatherState weather, ColumnInfo column) {
        if (!weather.raining) {
            return false;
        }
        // Cold biomes where precipitation falls as snow
        return isColdBiome(column.biome());
    }

    private boolean isColdBiome(Biome biome) {
        String name = biome.name().toLowerCase(Locale.ROOT);
        return name.contains("snow")
                || name.contains("frozen")
                || name.contains("ice")
                || name.contains("taiga")
                || name.equals("grove")
                || name.contains("peak")
                || name.contains("cold");
    }

    /** Trigger a collapse starting from the given position. */
    private void triggerCollapse(World world, StructureGraph graph, NodePos pos) {
        List<NodePos> collapsed = new ArrayList<>();
        var result = new CascadeEngine(physicsConfig).cascade(graph, pos, new SolverCallback() {
            @Override
            public void onStressUpdated(Map<NodePos, Double> stressMap) {}

            @Override
            public void onCascadeStep(CollapsedNode node, int stepNumber, CollapseReason reason) {
                collapsed.add(node.pos());
            }

            @Override
            public void onCascadeComplete(List<CollapsedNode> allCollapsed) {}
        });

        // Resume a weather collapse cut short by the per-event step cap so the remainder
        // settles over the next ticks rather than stranding floating blocks.
        if (result.truncated()) {
            cascadeResumeManager.enqueue(world, result.remainingScope());
        }

        if (collapsed.isEmpty()) {
            return;
        }

        Location origin = StructureManager.toLocation(collapsed.get(0), world);
        int batchId = delayedCollapseManager.startBatch(world, origin, false);
        for (NodePos collapsePos : collapsed) {
            Block block = world.getBlockAt(collapsePos.x(), collapsePos.y(), collapsePos.z());
            Material material = block.getType();
            delayedCollapseManager.scheduleCollapse(world, collapsePos, material, batchId);
        }
    }

    /** Weather state for a world. */
    private record WeatherState(boolean raining, boolean thundering) {}

    /**
     * Cached per-(x,z)-column world reads for one sweep: the sky-height (for the
     * sky-access check) and the biome (for the snow check). Both are computed once,
     * here, from the world — the rest of the sweep reuses them for every node sharing
     * the column instead of asking the world again per node.
     *
     * <p>The biome read mirrors the original per-node call exactly
     * ({@code world.getBlockAt(x,y,z).getBiome()}, with {@code y} the first node seen
     * in the column) so the cached path is byte-identical to the uncached one: within a
     * column every node resolved the same biome, since biome is a column property.
     *
     * <p>Sky-height and biome are read <em>lazily</em>, on first use, so a sweep that
     * never needs one (sky access not required, or weather not raining) never pays for
     * it — matching the uncached code, which also only read what a node actually used.
     * Each is still read at most once per column: the first node in the column triggers
     * the world call, every later node reuses the stored value.
     */
    private static final class ColumnInfo {
        private final World world;
        private final int x;
        private final int y;
        private final int z;
        private int skyHeight;
        private boolean skyHeightLoaded;
        private Biome biome;
        private boolean biomeLoaded;

        ColumnInfo(World world, int x, int y, int z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        int skyHeight() {
            if (!skyHeightLoaded) {
                skyHeight = world.getHighestBlockYAt(x, z);
                skyHeightLoaded = true;
            }
            return skyHeight;
        }

        Biome biome() {
            if (!biomeLoaded) {
                Block block = world.getBlockAt(x, y, z);
                biome = block.getBiome();
                biomeLoaded = true;
            }
            return biome;
        }
    }

    /** One chunk column's tracked node positions, gathered at sweep start. */
    private static final class ChunkColumn {
        final int chunkX;
        final int chunkZ;
        final List<NodePos> positions = new ArrayList<>();

        ChunkColumn(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }

    /**
     * A chunk-by-chunk weather sweep over one world, resumable across ticks. Holds the
     * weather snapshot it started with (so a mid-sweep change doesn't half-apply) and a
     * queue of chunks still to process.
     */
    private static final class WorldSweep {
        private final WeatherState weather;
        private final Deque<ChunkColumn> remaining;
        private boolean dirtied;

        /**
         * Per-(x,z)-column sky-height + biome, computed lazily and at most once per
         * column for the life of THIS sweep. Scoped to the sweep so it auto-invalidates:
         * the next full cycle starts a fresh sweep with an empty cache, which re-reads
         * the world and therefore reflects any terrain/biome change that happened
         * between sweeps. Keyed by packed (x,z).
         */
        private final Map<Long, ColumnInfo> columnCache = new HashMap<>();

        WorldSweep(WeatherState weather, Deque<ChunkColumn> remaining) {
            this.weather = weather;
            this.remaining = remaining;
        }

        WeatherState weather() {
            return weather;
        }

        Map<Long, ColumnInfo> columnCache() {
            return columnCache;
        }

        boolean isFinished() {
            return remaining.isEmpty();
        }

        ChunkColumn next() {
            return remaining.poll();
        }

        void markDirtied() {
            dirtied = true;
        }

        boolean dirtied() {
            return dirtied;
        }
    }

    /** Configuration record for weather settings. */
    public record WeatherConfig(
            boolean enabled,
            int scanIntervalTicks,
            boolean requireSkyAccess,
            boolean rainEnabled,
            double rainCapacityMultiplier,
            boolean thunderEnabled,
            double thunderCapacityMultiplier,
            boolean thunderSpikesEnabled,
            double thunderSpikeChance,
            double thunderSpikeAmount,
            boolean snowEnabled,
            double snowLoadPerScan,
            double snowMaxLoad,
            double snowDecayPerScan,
            double tickBudgetMs) {

        public static WeatherConfig defaults() {
            return new WeatherConfig(
                    true, // enabled
                    40, // scanIntervalTicks
                    true, // requireSkyAccess
                    true, // rainEnabled
                    0.95, // rainCapacityMultiplier (5% weaker)
                    true, // thunderEnabled
                    0.88, // thunderCapacityMultiplier (12% weaker)
                    true, // thunderSpikesEnabled
                    0.02, // thunderSpikeChance (2%)
                    0.15, // thunderSpikeAmount (15% of capacity)
                    true, // snowEnabled
                    0.005, // snowLoadPerScan
                    0.3, // snowMaxLoad (30% of capacity)
                    0.002, // snowDecayPerScan
                    10.0); // tickBudgetMs (per-scan wall-clock seatbelt)
        }
    }
}
