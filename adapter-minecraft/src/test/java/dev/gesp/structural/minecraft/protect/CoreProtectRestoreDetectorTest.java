package dev.gesp.structural.minecraft.protect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.material.MaterialRegistry;
import dev.gesp.structural.minecraft.protect.CoreProtectRestoreDetector.RestoreLookup;
import dev.gesp.structural.minecraft.protect.CoreProtectRestoreDetector.RestoreScanConfig;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Proves the throttle behaviour of the restore scan: the per-run lookup cap, the
 * non-starving rotating cursor, the busy-gate with bounded deferral, and that a detected
 * restore still repairs the node and marks the world dirty (unchanged end behaviour).
 *
 * <p>CoreProtect's concrete API cannot run on the MockBukkit classpath, so the DB is a
 * counting {@link RestoreLookup} stub — exactly the seam the runtime wraps around
 * {@code CoreProtectAPI.blockLookup}.
 */
@DisplayName("CoreProtect restore scan is throttled, fair, and deferrable")
class CoreProtectRestoreDetectorTest {

    private static final MaterialSpec LIGHT = new MaterialSpec(1.0, 100.0);

    private ServerMock server;
    private JavaPlugin plugin;
    private StructureManager manager;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        manager = new StructureManager(new MaterialRegistry());
        world = server.addSimpleWorld("restore_world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Counts lookups and remembers which positions were queried (and the timestamps received). */
    private static final class CountingLookup implements RestoreLookup {
        int calls;
        final Set<NodePos> queried = new HashSet<>();
        final Set<NodePos> restore = new HashSet<>();
        long lastAfterTime;

        @Override
        public boolean wasRestoredAfter(World w, NodePos pos, long afterTimeMillis) {
            calls++;
            queried.add(pos);
            lastAfterTime = afterTimeMillis;
            return restore.contains(pos);
        }
    }

    /** Add a damaged, non-grounded node the scan will track. */
    private void addDamaged(StructureGraph graph, NodePos pos) {
        graph.addBlock(pos, LIGHT, false);
        graph.getNode(pos).addDamage(0.5);
    }

    private CoreProtectRestoreDetector detector(RestoreLookup lookup, RestoreScanConfig config, BooleanSupplier busy) {
        return new CoreProtectRestoreDetector(manager, plugin, lookup, config, busy);
    }

    @Test
    @DisplayName("AC#1: cap holds and the cursor rotates — 200 positions, 50/run, full coverage in 4 runs")
    void capAndCursorRotationNoStarvation() {
        StructureGraph graph = manager.getOrCreateGraph(world);
        for (int i = 0; i < 200; i++) {
            addDamaged(graph, new NodePos(i, 1, 0));
        }
        CountingLookup lookup = new CountingLookup();
        CoreProtectRestoreDetector det = detector(lookup, new RestoreScanConfig(100, 50, true), () -> false);

        for (int run = 1; run <= 4; run++) {
            int before = lookup.calls;
            det.run();
            assertEquals(50, lookup.calls - before, "run " + run + " must issue exactly the 50-lookup cap");
        }
        assertEquals(
                200, lookup.queried.size(), "every one of the 200 positions is queried within 4 runs (no starvation)");
    }

    @Test
    @DisplayName("AC#2: defer-during-cascade skips the scan entirely while busy — zero lookups, no repair")
    void deferDuringCascadeIssuesNothing() {
        StructureGraph graph = manager.getOrCreateGraph(world);
        NodePos pos = new NodePos(0, 1, 0);
        addDamaged(graph, pos);
        CountingLookup lookup = new CountingLookup();
        lookup.restore.add(pos); // would repair if it scanned
        CoreProtectRestoreDetector det = detector(lookup, new RestoreScanConfig(100, 50, true), () -> true);

        det.run();

        assertEquals(0, lookup.calls, "a busy server must issue zero blockLookup calls");
        assertTrue(graph.getNode(pos).damage() > 0, "no tracked state is mutated — the node stays damaged");
    }

    @Test
    @DisplayName("AC#3: deferral is bounded — the 11th consecutive busy run scans anyway")
    void deferralIsBoundedToTenSkips() {
        StructureGraph graph = manager.getOrCreateGraph(world);
        addDamaged(graph, new NodePos(0, 1, 0));
        CountingLookup lookup = new CountingLookup();
        CoreProtectRestoreDetector det = detector(lookup, new RestoreScanConfig(100, 50, true), () -> true);

        for (int i = 1; i <= 10; i++) {
            det.run();
            assertEquals(0, lookup.calls, "run " + i + " (of the first 10) must stay deferred while busy");
        }
        det.run(); // 11th
        assertEquals(1, lookup.calls, "the 11th run forces a scan so a busy server still detects rollbacks");
    }

    @Test
    @DisplayName("AC#4: the run period and conservative timestamp both derive from interval-ticks")
    void intervalDrivesScheduleAndTimestamp() {
        StructureGraph graph = manager.getOrCreateGraph(world);
        addDamaged(graph, new NodePos(0, 1, 0));
        CountingLookup lookup = new CountingLookup();
        // interval 40 ticks -> period 40, conservative offset 40*50 = 2000 ms.
        CoreProtectRestoreDetector det = detector(lookup, new RestoreScanConfig(40, 50, true), () -> false);

        det.start();
        server.getScheduler().performTicks(39);
        assertEquals(0, lookup.calls, "no run before the 40-tick period elapses");
        server.getScheduler().performTicks(1);
        assertEquals(1, lookup.calls, "the task fires once at the configured 40-tick period");
        det.stop();

        long now = System.currentTimeMillis();
        assertTrue(
                Math.abs((now - lookup.lastAfterTime) - 2000L) <= 100L,
                "conservative first-seen must be now - (interval*50) = now - 2000 ms, was offset "
                        + (now - lookup.lastAfterTime));
    }

    @Test
    @DisplayName("AC#5: a detected restore repairs the node and marks the world dirty (unchanged end behaviour)")
    void detectedRestoreRepairsAndMarksDirty() {
        StructureGraph graph = manager.getOrCreateGraph(world);
        NodePos pos = new NodePos(0, 1, 0);
        addDamaged(graph, pos);
        CountingLookup lookup = new CountingLookup();
        lookup.restore.add(pos);
        CoreProtectRestoreDetector det = detector(lookup, new RestoreScanConfig(100, 50, true), () -> false);

        long revBefore = manager.revision(world);
        det.run();

        assertEquals(0.0, graph.getNode(pos).damage(), "node.repair() cleared the stale damage");
        assertTrue(manager.revision(world) > revBefore, "structureManager.markDirty(world) fired");
    }

    @Test
    @DisplayName("Bounded-deferral counter resets after a real scan, so a quiet moment re-arms the 10-skip budget")
    void deferralCounterResetsAfterScan() {
        StructureGraph graph = manager.getOrCreateGraph(world);
        addDamaged(graph, new NodePos(0, 1, 0));
        CountingLookup lookup = new CountingLookup();
        boolean[] busy = {false};
        CoreProtectRestoreDetector det = detector(lookup, new RestoreScanConfig(100, 50, true), () -> busy[0]);

        det.run(); // not busy -> scans, counter stays 0
        assertEquals(1, lookup.calls, "a quiet run scans");
        busy[0] = true;
        for (int i = 0; i < 10; i++) {
            det.run(); // 10 fresh deferrals available again
        }
        assertEquals(1, lookup.calls, "after a real scan the full 10-skip budget is available again");
        det.run();
        assertEquals(2, lookup.calls, "and the 11th busy run forces a scan");
    }

    @Test
    @DisplayName("tryCreate returns null when CoreProtect is not installed")
    void tryCreateNullWithoutCoreProtect() {
        // MockBukkit has no CoreProtect plugin, so the factory declines to build a detector.
        assertNull(
                CoreProtectRestoreDetector.tryCreate(
                        manager, plugin, new RestoreScanConfig(100, 50, true), () -> false),
                "no CoreProtect → no detector");
    }

    @Test
    @DisplayName("defer-during-cascade: false always scans, even while busy")
    void deferDisabledAlwaysScans() {
        StructureGraph graph = manager.getOrCreateGraph(world);
        addDamaged(graph, new NodePos(0, 1, 0));
        CountingLookup lookup = new CountingLookup();
        CoreProtectRestoreDetector det = detector(lookup, new RestoreScanConfig(100, 50, false), () -> true);

        det.run();

        assertFalse(lookup.queried.isEmpty(), "with deferral disabled the scan runs regardless of the busy signal");
    }
}
