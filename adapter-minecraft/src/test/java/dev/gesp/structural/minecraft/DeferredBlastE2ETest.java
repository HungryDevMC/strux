package dev.gesp.structural.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.blast.StruxExplosionEngine;
import dev.gesp.structural.minecraft.listener.BlastProcessor;
import dev.gesp.structural.minecraft.listener.CraterApplier;
import dev.gesp.structural.minecraft.listener.DebrisVisuals;
import dev.gesp.structural.minecraft.listener.QueuedBlast;
import dev.gesp.structural.minecraft.listener.StreamedBukkitCraterRemover;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.visual.CollapseEffects;
import dev.gesp.structural.model.NodePos;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * E2E tests for the tick-budgeted explosion (blast) path.
 *
 * <pre>
 *   An explosion no longer settles the structure inside the Bukkit event
 *   handler. The handler does only the cheap, event-bound work (gate, claim our
 *   tracked blocks out of vanilla's destroy list, queue the blast); a repeating
 *   {@link BlastProcessor} drains the queue under a per-tick wall-clock budget.
 *   Several explosions in one tick (a TNT chain) therefore spread their solves
 *   over the following ticks instead of freezing the one tick they all landed in.
 * </pre>
 */
@DisplayName("E2E: tick-budgeted explosions (deferred blast processing)")
class DeferredBlastE2ETest {

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        world = server.addSimpleWorld("blast_world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  (1) The event handler only gates + claims + enqueues — no settle
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("The explosion event only queues: nothing is destroyed on the event itself")
    void eventHandlerOnlyEnqueuesNoSettleInline() {
        StructureManager manager = plugin.getStructureManager();
        placeTower(0);
        long revisionBefore = manager.revision(world);

        BlastProcessor processor = plugin.getBlastProcessor();
        assertEquals(0, processor.queueSize(), "nothing queued before the explosion");

        // The event's block list contains the tower blocks vanilla would destroy.
        List<Block> vanillaBlocks = new ArrayList<>();
        for (int y = 65; y <= 68; y++) {
            vanillaBlocks.add(world.getBlockAt(0, y, 0));
        }
        fireExplosion(new Location(world, 0, 67, 0), vanillaBlocks, 4.0f);

        // Criterion 1: the handler claimed our tracked blocks out of vanilla's list
        // (so vanilla won't crater them), enqueued the blast, and did NOT settle.
        assertTrue(
                vanillaBlocks.isEmpty(),
                "strux-tracked blocks must be removed from the vanilla destroy list at event time");
        assertEquals(1, processor.queueSize(), "the blast must be queued, not settled inline");
        for (int y = 65; y <= 68; y++) {
            assertEquals(
                    Material.STONE,
                    world.getBlockAt(0, y, 0).getType(),
                    "no block may be destroyed on the event itself (y=" + y + ")");
        }
        assertEquals(
                revisionBefore, manager.revision(world), "an un-settled blast must not bump the world revision yet");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  (2) FIFO drain under a re-checked budget; ≥1 blast per tick at 0 budget
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A zero budget settles exactly one blast per tick (no deadlock, no all-at-once)")
    void zeroBudgetSettlesOneBlastPerTick() {
        StructureManager manager = plugin.getStructureManager();
        int[] xs = {0, 8, 16, 24};
        for (int x : xs) {
            placeTower(x);
        }

        BlastProcessor processor = plugin.getBlastProcessor();
        processor.setTickBudgetMs(0.0);

        // Fire all four explosions in the SAME tick (a TNT chain).
        for (int x : xs) {
            fireExplosion(new Location(world, x, 67, 0), towerBlocks(x), 4.0f);
        }
        assertEquals(xs.length, processor.queueSize(), "all four blasts queued in the one event tick, none settled");

        // One tick → exactly one blast settled (the budget is re-checked AFTER the
        // first item, so even a zero budget makes forward progress of exactly one).
        server.getScheduler().performTicks(1);
        assertEquals(xs.length - 1, processor.queueSize(), "a zero budget settles exactly one blast in the first tick");

        server.getScheduler().performTicks(1);
        assertEquals(xs.length - 2, processor.queueSize(), "the queue keeps shrinking one per tick, not all at once");

        server.getScheduler().performTicks(xs.length);
        assertEquals(0, processor.queueSize(), "given enough ticks the whole chain settles");
    }

    @Test
    @DisplayName("With the normal (constructor) budget a small chain drains in a single tick")
    void defaultBudgetDrainsSmallChainInOneTick() {
        int[] xs = {0, 8, 16};
        for (int x : xs) {
            placeTower(x);
        }
        BlastProcessor processor = plugin.getBlastProcessor();
        for (int x : xs) {
            fireExplosion(new Location(world, x, 67, 0), towerBlocks(x), 4.0f);
        }
        assertEquals(xs.length, processor.queueSize(), "the whole chain is queued at event time");

        server.getScheduler().performTicks(1);

        assertEquals(0, processor.queueSize(), "a normal (10ms) budget drains this small chain in a single tick");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  (E2E) Three blasts fired in one tick settle one-per-tick, in FIFO order,
    //         and each tower's outcome matches the synchronous path.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Three blasts fired in one tick settle FIFO over the next ticks, each matching the sync result")
    void threeBlastsSettleFifoMatchingSyncPath() {
        StructureManager manager = plugin.getStructureManager();

        // Reference outcome: a tower blasted on its OWN, fully settled, then read
        // back as a set of destroyed (now-AIR) positions relative to the tower base.
        // This is the result the synchronous path produced before deferral: the core
        // solve is identical, only the tick it lands in moved.
        List<NodePos> expectedDestroyed = blastOneTowerAndReadCrater(99);

        int[] xs = {0, 8, 16};
        for (int x : xs) {
            placeTower(x);
        }

        BlastProcessor processor = plugin.getBlastProcessor();
        processor.setTickBudgetMs(0.0); // force one-per-tick so we can watch the order

        // Fire all three in the same tick, in a deliberately unsorted order so a
        // correct FIFO drain is distinguishable from any re-sorting.
        int[] firedOrder = {16, 0, 8};
        for (int x : firedOrder) {
            fireExplosion(new Location(world, x, 67, 0), towerBlocks(x), 4.0f);
        }
        assertEquals(3, processor.queueSize(), "three blasts queued in the one event tick");

        // No tower may be cratered while the blasts sit in the queue.
        for (int x : xs) {
            assertEquals(
                    Material.STONE,
                    world.getBlockAt(x, 67, 0).getType(),
                    "no settle inside the event tick (x=" + x + ")");
        }

        // Tick 1: the FIRST-fired tower (x=16) settles, the others stay intact.
        server.getScheduler().performTicks(1);
        assertTowerCratered(16);
        assertTowerIntact(0);
        assertTowerIntact(8);

        // Tick 2: the SECOND-fired tower (x=0).
        server.getScheduler().performTicks(1);
        assertTowerCratered(0);
        assertTowerIntact(8);

        // Tick 3: the THIRD-fired tower (x=8).
        server.getScheduler().performTicks(1);
        assertTowerCratered(8);

        assertEquals(0, processor.queueSize(), "all three blasts drained");

        // Each tower's destroyed set matches the reference, exactly — same crater,
        // just settled a few ticks later and one-per-tick.
        assertFalse(expectedDestroyed.isEmpty(), "the reference blast must destroy at least one block");
        for (int x : xs) {
            for (NodePos p : expectedDestroyed) {
                Block b = world.getBlockAt(x + p.x(), p.y(), p.z());
                assertEquals(
                        Material.AIR,
                        b.getType(),
                        "tower x=" + x + " block " + p + " must be destroyed exactly as the sync path did");
            }
        }
        assertTrue(manager.revision(world) > 0, "settling bumped the world revision");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  (4) A blast whose world graph is gone/empty at settle time is dropped
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A blast whose graph is empty by settle time is dropped without error (FINE log)")
    void staleEmptyGraphBlastIsDropped() {
        BlastProcessor processor = plugin.getBlastProcessor();

        AtomicFine fine = new AtomicFine();
        plugin.getLogger().setLevel(Level.FINE);
        plugin.getLogger().addHandler(fine);
        try {
            // Queue a blast against a world that tracks nothing (empty graph).
            processor.enqueue(new QueuedBlast(world, new Location(world, 5, 70, 5), 4.0));
            processor.run();
        } finally {
            plugin.getLogger().removeHandler(fine);
        }

        assertEquals(0, processor.queueSize(), "the stale blast must be consumed (dropped), not stuck");
        assertNotNull(fine.message, "a dropped stale blast must log at FINE");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  LIFECYCLE + wiring
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("stop() on a never-started blast processor is a no-op and clears the queue")
    void stopOnUnstartedProcessorClearsQueue() {
        BlastProcessor fresh = new BlastProcessor(
                plugin,
                plugin.getStructureManager(),
                new StruxExplosionEngine(),
                plugin.getDelayedCollapseManager(),
                plugin.getCascadeResumeManager(),
                new CraterApplier(
                        plugin.getCollapseGuard(),
                        new CollapseEffects(plugin.getEffectsConfig(), plugin),
                        new DebrisVisuals(plugin),
                        new StreamedBukkitCraterRemover(),
                        8),
                plugin.getEffectsConfig(),
                new CollapseEffects(plugin.getEffectsConfig(), plugin),
                plugin.getLogger(),
                10.0,
                4096,
                64,
                plugin.getTaskTimings());
        fresh.enqueue(new QueuedBlast(world, world.getSpawnLocation(), 4.0));
        assertEquals(1, fresh.queueSize(), "the blast is queued");

        fresh.stop();

        assertEquals(0, fresh.queueSize(), "stop() drops anything still queued");
    }

    @Test
    @DisplayName("The plugin wires and exposes the blast processor; the listener feeds it")
    void pluginExposesBlastProcessor() {
        assertNotNull(plugin.getBlastProcessor(), "the blast processor must be wired and exposed");
        assertSame(
                plugin.getBlastProcessor(),
                plugin.getBlastProcessor(),
                "getBlastProcessor returns the one live instance");

        placeTower(0);
        fireExplosion(new Location(world, 0, 67, 0), towerBlocks(0), 4.0f);
        assertEquals(1, plugin.getBlastProcessor().queueSize(), "the listener enqueues onto the exposed processor");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private static final class AtomicFine extends Handler {
        volatile String message;

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel() == Level.FINE) {
                message = record.getMessage();
            }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Settle side-effects: crater, debris budget, sounds, chat notify
    // ─────────────────────────────────────────────────────────────────────

    /**
     * The settle must visibly happen in the WORLD, not just in the graph: the
     * crater blocks turn to air as the streamed applier drains (a few blocks per
     * tick, here finishing inside the settle ticks because the crater is tiny),
     * debris spawns within the per-explosion budget, the aggregate cascade-complete
     * effect plays, and nearby players get the blast summary while far players hear
     * nothing about it.
     *
     * <p>Per-block collapse effects are now SAMPLED (1 in {@code
     * crater-effect-sample-rate}), so this no longer asserts a sound at every single
     * shattered block — a dedicated test
     * ({@link CraterStreamingE2ETest}) pins the sampling cap directly.
     */
    @Test
    @DisplayName("A settled blast craters the world, budgets debris, and notifies nearby players only")
    void settledBlastCratersWorldBudgetsDebrisAndNotifies() {
        // Cap debris at 1 so the budget is actually contended by a multi-block crater.
        plugin.getEffectsConfig().setMaxDebrisPerExplosion(1);

        var near = server.addPlayer();
        near.teleport(new Location(world, 6, 66, 0)); // ~6 blocks from the blast
        var far = server.addPlayer();
        far.teleport(new Location(world, 500, 66, 0)); // way past explosion-notify-radius

        placeTower(0);
        int fallingBefore = world.getEntitiesByClass(FallingBlock.class).size();

        fireExplosion(new Location(world, 0, 67, 0), towerBlocks(0), 4.0f);
        // Two ticks: enough to settle (default budget), but BEFORE the delayed
        // collapse applies (explosion-collapse-delay-ticks = 4) — so any AIR
        // below is the immediate crater, not the delayed fall.
        server.getScheduler().performTicks(2);

        // Crater applied to the world at settle time.
        List<Block> crater = new ArrayList<>();
        for (int y = 65; y <= 68; y++) {
            Block b = world.getBlockAt(0, y, 0);
            if (b.getType() == Material.AIR) {
                crater.add(b);
            }
        }
        assertTrue(crater.size() >= 2, "this blast must shatter at least two blocks (got " + crater.size() + ")");

        // Debris budget: a 1-debris cap on a 2+ block crater spawns EXACTLY one.
        int fallingSpawned = world.getEntitiesByClass(FallingBlock.class).size() - fallingBefore;
        assertEquals(1, fallingSpawned, "debris must respect the per-explosion budget exactly");

        // The aggregate cascade-complete effect plays for the settled blast (the
        // single boom on top of any sampled per-block effects).
        assertFalse(near.getHeardSounds().isEmpty(), "the cascade-complete effect must play for a settled blast");

        // Chat notify: in range hears about it, out of range does not.
        String nearMsg = near.nextMessage();
        assertNotNull(nearMsg, "a player within explosion-notify-radius must get the blast summary");
        assertTrue(nearMsg.contains("shattered"), "the summary must report shattered blocks: " + nearMsg);
        assertNull(far.nextMessage(), "a player far beyond explosion-notify-radius must hear nothing");
    }

    /**
     * Disabling the plugin stops the drain task and drops whatever is queued —
     * a disabled plugin must not keep settling blasts (or leak scheduled tasks).
     */
    @Test
    @DisplayName("Plugin disable stops the blast processor and drops the queue")
    void disableStopsProcessorAndDropsQueue() {
        placeTower(0);
        BlastProcessor processor = plugin.getBlastProcessor();
        fireExplosion(new Location(world, 0, 67, 0), towerBlocks(0), 4.0f);
        assertEquals(1, processor.queueSize(), "the blast must be queued before disable");

        server.getPluginManager().disablePlugin(plugin);

        assertTrue(processor.isCancelled(), "disable must cancel the drain task");
        assertEquals(0, processor.queueSize(), "disable must drop the queued blasts");
    }

    /** A BEDROCK-footed stone tower (y=65..68) at x, on the z=0 line. */
    private void placeTower(int x) {
        place(x, 64, 0, Material.BEDROCK);
        for (int y = 65; y <= 68; y++) {
            place(x, y, 0, Material.STONE);
        }
    }

    private List<Block> towerBlocks(int x) {
        List<Block> blocks = new ArrayList<>();
        for (int y = 65; y <= 68; y++) {
            blocks.add(world.getBlockAt(x, y, 0));
        }
        return blocks;
    }

    /**
     * Fire and FULLY settle one isolated tower through the real plugin path, then
     * read back the crater as positions relative to the tower base. This is the
     * synchronous-path reference: the same core solve that used to run inline.
     */
    private List<NodePos> blastOneTowerAndReadCrater(int x) {
        placeTower(x);
        fireExplosion(new Location(world, x, 67, 0), towerBlocks(x), 4.0f);
        server.getScheduler().performTicks(3); // let it settle fully (default budget)

        List<NodePos> relative = new ArrayList<>();
        StructureManager manager = plugin.getStructureManager();
        for (int y = 65; y <= 68; y++) {
            Block b = world.getBlockAt(x, y, 0);
            if (b.getType() == Material.AIR) {
                relative.add(new NodePos(0, y, 0));
                // Clean residual tracking so the throw-away tower can't interfere.
                manager.onBlockBroken(b);
            }
        }
        // Clear whatever survived too, so the reference tower leaves no trace.
        for (int y = 64; y <= 68; y++) {
            Block b = world.getBlockAt(x, y, 0);
            if (b.getType() != Material.AIR) {
                manager.onBlockBroken(b);
                b.setType(Material.AIR);
            }
        }
        return relative;
    }

    private void assertTowerCratered(int x) {
        boolean anyGone = false;
        for (int y = 65; y <= 68; y++) {
            if (world.getBlockAt(x, y, 0).getType() == Material.AIR) {
                anyGone = true;
            }
        }
        assertTrue(anyGone, "tower x=" + x + " should have been cratered after its blast settled");
    }

    private void assertTowerIntact(int x) {
        boolean allStone = true;
        for (int y = 65; y <= 68; y++) {
            if (world.getBlockAt(x, y, 0).getType() != Material.STONE) {
                allStone = false;
            }
        }
        assertTrue(allStone, "tower x=" + x + " must still be intact (its blast has not settled yet)");
    }

    private void place(int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(material);
        plugin.getStructureManager().onBlockPlaced(block);
    }

    /** Fire the real EntityExplodeEvent the plugin listens for. */
    private void fireExplosion(Location center, List<Block> vanillaBlocks, float power) {
        TNTPrimed tnt = (TNTPrimed) world.spawn(center, TNTPrimed.class);
        EntityExplodeEvent event = new EntityExplodeEvent(tnt, center, vanillaBlocks, power, ExplosionResult.DESTROY);
        server.getPluginManager().callEvent(event);
    }
}
