package dev.gesp.structural.minecraft.protect;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Detects when CoreProtect restores a block and clears any stale damage.
 *
 * <p>When a block is damaged but not destroyed, CoreProtect rollback can restore
 * the "original" block without firing BlockPlaceEvent. The normal detection in
 * DamageVisualizer catches material changes, but same-material restoration is
 * invisible. This detector bridges that gap by querying CoreProtect's API for
 * recent placements at damaged positions.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Track when each damaged position was first observed
 *   <li>Periodically query CoreProtect for placements at those positions
 *   <li>If a placement occurred AFTER we first saw the damage, the block was
 *       restored; clear the damage
 * </ol>
 *
 * <p>Restore detection is 5-second-latency housekeeping, so the scan is throttled to
 * stay off the busy ticks:
 * <ul>
 *   <li>The run period ({@code interval-ticks}) and the number of database lookups a
 *       single run may issue ({@code max-lookups-per-run}) are configurable.
 *   <li>Positions beyond the per-run cap wait for later runs behind a rotating cursor,
 *       so every tracked position is queried within {@code ceil(tracked / cap)} runs —
 *       no starvation.
 *   <li>While a blast or cascade is in flight ({@code defer-during-cascade}) the scan
 *       skips entirely, so it never piles thousands of synchronous DB lookups onto the
 *       busiest ticks. Deferral is bounded: after {@link #MAX_CONSECUTIVE_DEFERRALS}
 *       skips the next run scans anyway.
 * </ul>
 */
public final class CoreProtectRestoreDetector extends BukkitRunnable {

    /** After this many consecutive deferred runs, the next run scans regardless of the busy signal. */
    static final int MAX_CONSECUTIVE_DEFERRALS = 10;

    /** Config defaults (also the fallbacks when a key is absent). */
    static final int DEFAULT_INTERVAL_TICKS = 100;

    static final int DEFAULT_MAX_LOOKUPS_PER_RUN = 50;
    static final boolean DEFAULT_DEFER_DURING_CASCADE = true;

    private final StructureManager structureManager;
    private final JavaPlugin plugin;
    private final RestoreLookup lookup;
    private final RestoreScanConfig config;
    private final BooleanSupplier busy;

    /**
     * Tracks when we first observed damage at each position, per world.
     * Key: world UUID, Value: map of position to first-seen time (System.currentTimeMillis).
     */
    private final Map<UUID, Map<NodePos, Long>> damageFirstSeen = new HashMap<>();

    /**
     * Rotating cursor: the last position queried per world. The next run resumes at the
     * first tracked position that sorts strictly after this one (canonical order), so the
     * per-run lookup cap never re-checks the same head of the map forever.
     */
    private final Map<UUID, NodePos> lookupCursor = new HashMap<>();

    /** How many runs in a row have been deferred because the server was busy. */
    private int consecutiveDeferrals = 0;

    CoreProtectRestoreDetector(
            StructureManager structureManager,
            JavaPlugin plugin,
            RestoreLookup lookup,
            RestoreScanConfig config,
            BooleanSupplier busy) {
        this.structureManager = structureManager;
        this.plugin = plugin;
        this.lookup = lookup;
        this.config = config;
        this.busy = busy;
    }

    /**
     * The three tunables for the restore scan, read from {@code logging.restore-scan}.
     *
     * @param intervalTicks how often the scan runs (ticks between runs)
     * @param maxLookupsPerRun cap on {@code blockLookup} DB calls across all worlds per run
     * @param deferDuringCascade skip the scan entirely while a blast/cascade is in flight
     */
    public record RestoreScanConfig(int intervalTicks, int maxLookupsPerRun, boolean deferDuringCascade) {

        /** Read the block, falling back to the defaults for any absent key. */
        public static RestoreScanConfig fromConfig(ConfigurationSection cfg) {
            return new RestoreScanConfig(
                    cfg.getInt("logging.restore-scan.interval-ticks", DEFAULT_INTERVAL_TICKS),
                    cfg.getInt("logging.restore-scan.max-lookups-per-run", DEFAULT_MAX_LOOKUPS_PER_RUN),
                    cfg.getBoolean("logging.restore-scan.defer-during-cascade", DEFAULT_DEFER_DURING_CASCADE));
        }
    }

    /**
     * The database seam. Real runtime hits CoreProtect; tests supply a counting stub —
     * CoreProtect's concrete API cannot be exercised on the MockBukkit classpath.
     */
    public interface RestoreLookup {

        /** True if CoreProtect recorded a placement at {@code pos} after {@code afterTimeMillis}. */
        boolean wasRestoredAfter(World world, NodePos pos, long afterTimeMillis);
    }

    /**
     * Try to create a detector if CoreProtect is available and its API is usable.
     *
     * @return a detector, or null if CoreProtect is not available
     */
    public static CoreProtectRestoreDetector tryCreate(
            StructureManager structureManager, JavaPlugin plugin, RestoreScanConfig config, BooleanSupplier busy) {
        RestoreLookup lookup = CoreProtectLookup.tryCreate(plugin);
        if (lookup == null) {
            return null;
        }
        return new CoreProtectRestoreDetector(structureManager, plugin, lookup, config, busy);
    }

    /** Start the detector running on the configured interval. */
    public void start() {
        long period = Math.max(1L, config.intervalTicks());
        this.runTaskTimer(plugin, period, period);
    }

    /** Stop the detector. */
    public void stop() {
        try {
            this.cancel();
        } catch (IllegalStateException ignored) {
            // Already cancelled
        }
    }

    /**
     * Conservative first-seen offset: damage could have occurred right after the previous
     * scan, so we record {@code (now - intervalMs)}. Derived from the interval instead of a
     * hardcoded constant so a longer/shorter period keeps the race fix correct.
     */
    private long scanIntervalMs() {
        return Math.max(1L, config.intervalTicks()) * 50L;
    }

    @Override
    public void run() {
        // Busy-gate: while a blast/cascade is in flight, restore detection is deferred so it
        // never piles synchronous DB lookups onto the busiest ticks. Bounded so a permanently
        // busy server still scans after MAX_CONSECUTIVE_DEFERRALS skips.
        if (config.deferDuringCascade() && consecutiveDeferrals < MAX_CONSECUTIVE_DEFERRALS && busy.getAsBoolean()) {
            consecutiveDeferrals++;
            return;
        }
        consecutiveDeferrals = 0;

        int[] budget = {config.maxLookupsPerRun()};
        for (World world : plugin.getServer().getWorlds()) {
            StructureGraph graph = structureManager.getGraph(world);
            if (graph == null) {
                continue;
            }
            processWorld(world, graph, budget);
        }
    }

    private void processWorld(World world, StructureGraph graph, int[] budget) {
        Map<NodePos, Long> worldDamage = damageFirstSeen.computeIfAbsent(world.getUID(), k -> new HashMap<>());

        // Collect currently damaged positions
        Set<NodePos> currentlyDamaged = new HashSet<>();
        for (NodePos pos : graph.getAllPositions()) {
            Node node = graph.getNode(pos);
            if (node != null && !node.isGrounded() && node.damage() > 0) {
                currentlyDamaged.add(pos);
            }
        }

        // Update first-seen times: add new, remove healed. This bookkeeping covers the FULL
        // map every run (it is cheap — no DB); only the lookups below are capped.
        Iterator<Map.Entry<NodePos, Long>> iter = worldDamage.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<NodePos, Long> entry = iter.next();
            if (!currentlyDamaged.contains(entry.getKey())) {
                iter.remove(); // No longer damaged, stop tracking
            }
        }
        long now = System.currentTimeMillis();
        // Use conservative timestamp: damage could have occurred right after the
        // previous scan, so record (now - scanInterval) instead of (now). This
        // fixes the race condition where CoreProtect rollback happens between
        // damage application and our first observation.
        long conservativeFirstSeen = now - scanIntervalMs();
        for (NodePos pos : currentlyDamaged) {
            worldDamage.putIfAbsent(pos, conservativeFirstSeen);
        }

        if (worldDamage.isEmpty() || budget[0] <= 0) {
            lookupCursor.remove(world.getUID());
            return;
        }

        // Query CoreProtect for damaged positions, capped by the shared per-run budget and
        // resuming after the last-checked position so every position is reached within
        // ceil(tracked / cap) runs (no starvation).
        List<NodePos> ordered = new ArrayList<>(worldDamage.keySet());
        ordered.sort(NodePos.CANONICAL_ORDER);
        int n = ordered.size();
        int startIndex = resumeIndex(ordered, lookupCursor.get(world.getUID()));

        List<NodePos> toRepair = new ArrayList<>();
        NodePos lastChecked = null;
        int checked = 0;
        while (budget[0] > 0 && checked < n) {
            NodePos pos = ordered.get((startIndex + checked) % n);
            budget[0]--;
            checked++;
            lastChecked = pos;
            if (lookup.wasRestoredAfter(world, pos, worldDamage.get(pos))) {
                toRepair.add(pos);
            }
        }
        if (lastChecked != null) {
            lookupCursor.put(world.getUID(), lastChecked);
        }

        // Repair detected restorations
        for (NodePos pos : toRepair) {
            graph.repairNode(pos); // via graph so modCount moves (async settle conflict check)
            worldDamage.remove(pos);
        }

        if (!toRepair.isEmpty()) {
            structureManager.markDirty(world);
        }
    }

    /** First index in canonical order that sorts strictly after the cursor (0 if none/absent). */
    private static int resumeIndex(List<NodePos> ordered, NodePos cursor) {
        if (cursor == null) {
            return 0;
        }
        for (int i = 0; i < ordered.size(); i++) {
            if (NodePos.CANONICAL_ORDER.compare(ordered.get(i), cursor) > 0) {
                return i;
            }
        }
        return 0; // cursor was at or past the end — wrap to the start
    }
}
