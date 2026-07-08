package dev.gesp.structural.minecraft.listener;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.minecraft.StructuralIntegrityPlugin;
import dev.gesp.structural.minecraft.config.EffectsConfig;
import dev.gesp.structural.minecraft.visual.CollapseEffects;
import dev.gesp.structural.model.NodePos;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Pins the per-tick decision logic of {@link DelayedCollapseManager#run()} — the
 * "is this block due?" / "does it want a warning this tick?" gating that the hot-path
 * perf change added so most pending blocks are skipped without any world read.
 *
 * <p>The manager is driven directly: each {@code run()} call advances its internal
 * tick by one, so a block scheduled at tick 0 has {@code elapsed == n} after the
 * {@code n}-th call. A {@link WarningSpy} counts warning effects so the interval and
 * per-tick-cap branches are observable without timing flakiness.
 */
@DisplayName("DelayedCollapseManager.run(): due/warning gating")
class DelayedCollapseManagerRunTest {

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private WorldMock world;
    private EffectsConfig config;
    private WarningSpy effects;
    private DelayedCollapseManager dcm;

    /** Counts cracking-warning + batch-complete effects; everything else is real. */
    private static final class WarningSpy extends CollapseEffects {
        int warnings = 0;
        int cascadeCompletes = 0;

        WarningSpy(EffectsConfig config, Plugin plugin) {
            super(config, plugin);
        }

        @Override
        public void playFailureWarning(Location location, Material material, float progress) {
            warnings++;
        }

        @Override
        public void playCascadeComplete(World world, Location origin, int totalBlocks) {
            cascadeCompletes++;
        }
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        world = server.addSimpleWorld("dcm_run_world");
        config = plugin.getEffectsConfig();
        effects = new WarningSpy(config, plugin);
        // A standalone manager we tick by hand — never start()ed, so the plugin's own
        // scheduler never advances it and our run() calls are the only ticks.
        dcm = new DelayedCollapseManager(
                plugin,
                plugin.getStructureManager(),
                config,
                effects,
                plugin.getCollapseGuard(),
                plugin.getTaskTimings());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("startBatch hands out strictly incrementing ids")
    void startBatchIncrements() {
        Location origin = new Location(world, 0, 65, 0);
        int b1 = dcm.startBatch(world, origin, false);
        int b2 = dcm.startBatch(world, origin, false);
        int b3 = dcm.startBatch(world, origin, true);
        assertEquals(1, b1, "first batch id is 1 (++nextBatchId from 0)");
        assertEquals(2, b2, "ids increment by exactly one");
        assertEquals(3, b3, "ids keep incrementing");
    }

    @Test
    @DisplayName("A not-yet-due block is left standing; once its delay elapses it collapses")
    void notDueSurvivesThenDueCollapses() {
        config.setCrackingWarningsEnabled(false); // isolate the due/skip decision
        config.setCollapseDelayTicks(3);
        config.setMaxCollapsesPerTick(8);

        NodePos pos = new NodePos(0, 65, 0);
        world.getBlockAt(0, 65, 0).setType(Material.STONE);
        int batch = dcm.startBatch(world, new Location(world, 0, 65, 0), false);
        assertTrue(dcm.scheduleCollapse(world, pos, Material.STONE, batch), "block scheduled for collapse");

        // elapsed 1, then 2 — both < delay 3 → not due, no warning → skipped untouched.
        dcm.run();
        dcm.run();
        assertEquals(
                Material.STONE,
                world.getBlockAt(0, 65, 0).getType(),
                "a not-yet-due block must NOT be collapsed early");
        assertEquals(0, effects.warnings, "warnings disabled → none emitted");

        // elapsed 3 ≥ delay 3 → due → collapses to air.
        dcm.run();
        assertEquals(Material.AIR, world.getBlockAt(0, 65, 0).getType(), "once the delay elapses the block collapses");
    }

    @Test
    @DisplayName("Warnings fire only on the interval tick, while the block is not yet due")
    void warningFiresOnIntervalOnly() {
        config.setCrackingWarningsEnabled(true);
        config.setCrackingWarningInterval(2);
        config.setMaxCrackingWarningsPerTick(6);
        config.setCollapseDelayTicks(100); // never due during this test

        NodePos pos = new NodePos(1, 65, 0);
        world.getBlockAt(1, 65, 0).setType(Material.STONE);
        int batch = dcm.startBatch(world, new Location(world, 1, 65, 0), false); // non-fast
        dcm.scheduleCollapse(world, pos, Material.STONE, batch);

        dcm.run(); // elapsed 1: 1 % 2 = 1 → off-interval → no warning
        assertEquals(0, effects.warnings, "no warning on an off-interval tick");
        dcm.run(); // elapsed 2: 2 % 2 = 0 → warning
        assertEquals(1, effects.warnings, "exactly one warning on the interval tick");
        dcm.run(); // elapsed 3: 3 % 2 = 1 → no warning
        assertEquals(1, effects.warnings, "still one — no warning off-interval");
        dcm.run(); // elapsed 4: 4 % 2 = 0 → warning
        assertEquals(2, effects.warnings, "another warning on the next interval tick");

        assertEquals(
                Material.STONE, world.getBlockAt(1, 65, 0).getType(), "delay 100 → the block has not collapsed yet");
    }

    @Test
    @DisplayName("A fast-collapse (explosion) block never emits cracking warnings")
    void fastCollapseSuppressesWarnings() {
        config.setCrackingWarningsEnabled(true);
        config.setCrackingWarningInterval(1); // every tick would warn if allowed
        config.setExplosionCollapseDelayTicks(100);

        NodePos pos = new NodePos(2, 65, 0);
        world.getBlockAt(2, 65, 0).setType(Material.STONE);
        int batch = dcm.startBatch(world, new Location(world, 2, 65, 0), true); // FAST
        dcm.scheduleCollapse(world, pos, Material.STONE, batch);

        dcm.run();
        dcm.run();
        assertEquals(0, effects.warnings, "the fast-collapse path suppresses cracking warnings");
    }

    @Test
    @DisplayName("a pending collapse whose world was unloaded is dropped, not run against a stale world")
    void runDropsStaleWorldEntries() {
        config.setCrackingWarningsEnabled(false);
        config.setCollapseDelayTicks(1);

        NodePos pos = new NodePos(0, 65, 0);
        world.getBlockAt(0, 65, 0).setType(Material.STONE);
        int batch = dcm.startBatch(world, new Location(world, 0, 65, 0), false);
        dcm.scheduleCollapse(world, pos, Material.STONE, batch);

        server.removeWorld(world); // the world is unloaded out from under the pending collapse
        assertFalse(server.getWorlds().contains(world), "precondition: the world is no longer loaded");

        assertDoesNotThrow(dcm::run, "run() must not call getBlockAt on a stale, unloaded world");
        assertFalse(
                dcm.isPendingCollapse(world, pos),
                "a stale-world entry is dropped (it can never resolve — the key is CraftWorld identity)");
        // Dropping the batch's only block completes the batch (so its accounting is not
        // left dangling), which fires the cascade-complete effect exactly once.
        assertEquals(1, effects.cascadeCompletes, "dropping the last block of a batch completes it");
    }

    @Test
    @DisplayName("stop() flushes pending world removals so a restart's saved graph matches the world")
    void stopFlushesPendingRemovals() {
        config.setCollapseDelayTicks(100); // never due → the blocks stay pending until stop
        for (int x = 0; x < 3; x++) {
            world.getBlockAt(x, 65, 0).setType(Material.STONE);
        }
        int batch = dcm.startBatch(world, new Location(world, 0, 65, 0), false);
        for (int x = 0; x < 3; x++) {
            dcm.scheduleCollapse(world, new NodePos(x, 65, 0), Material.STONE, batch);
        }
        // The cascade already removed these nodes from the graph; the WORLD blocks still
        // stand, pending their delayed removal.
        assertEquals(Material.STONE, world.getBlockAt(0, 65, 0).getType(), "still standing while pending");

        dcm.stop();

        // Shutdown applies the pending removals instead of dropping them, so the world
        // matches the (node-removed) graph the plugin is about to save. Without this the
        // blocks would survive in the world but be absent from the saved graph — floating,
        // untracked, never-collapsible after restart.
        for (int x = 0; x < 3; x++) {
            assertEquals(
                    Material.AIR, world.getBlockAt(x, 65, 0).getType(), "stop() flushes the pending removal at x=" + x);
            assertFalse(dcm.isPendingCollapse(world, new NodePos(x, 65, 0)), "no pending collapse remains at x=" + x);
        }
    }

    @Test
    @DisplayName("Per-tick warning cap throttles how many blocks warn in one tick")
    void warningsThrottledByPerTickCap() {
        config.setCrackingWarningsEnabled(true);
        config.setCrackingWarningInterval(1); // every block is interval-eligible every tick
        config.setMaxCrackingWarningsPerTick(1); // ...but only one may warn per tick
        config.setCollapseDelayTicks(100); // never due

        int batch = dcm.startBatch(world, new Location(world, 0, 65, 0), false);
        for (int x = 0; x < 3; x++) {
            world.getBlockAt(x, 65, 0).setType(Material.STONE);
            dcm.scheduleCollapse(world, new NodePos(x, 65, 0), Material.STONE, batch);
        }

        dcm.run(); // three eligible blocks, cap 1 → exactly one warns
        assertEquals(1, effects.warnings, "the per-tick warning cap is honoured (1 of 3 warns)");
    }
}
