package dev.gesp.structural.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.blast.StruxExplosionEngine;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.minecraft.listener.BlastProcessor;
import dev.gesp.structural.minecraft.listener.CraterApplier;
import dev.gesp.structural.minecraft.listener.DebrisVisuals;
import dev.gesp.structural.minecraft.listener.QueuedBlast;
import dev.gesp.structural.minecraft.listener.StreamedBukkitCraterRemover;
import dev.gesp.structural.minecraft.visual.CollapseEffects;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.Location;
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
 * E2E tests for the per-blast tick-chunking slice: a single large explosion
 * spreads its phase-1 sphere scan across ticks (bounded by {@code
 * blast.max-scan-per-tick}) instead of solving the whole thing in one frozen
 * call. The crater it ultimately carves must be identical to the same blast
 * solved in one huge-budget tick — slicing the scan only moves WHEN the world
 * changes, never WHAT changes.
 */
@DisplayName("E2E: per-blast tick-chunking (one big blast scanned over several ticks)")
class BlastTickChunkingE2ETest {

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        world = server.addSimpleWorld("chunk_world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  (5) A big blast with a tiny scan budget is NOT done after one tick, and
    //      the multi-tick crater equals a single-tick huge-budget crater.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName(
            "A big blast scanned a tiny chunk per tick is not done after tick 1, and its crater matches a one-shot run")
    void chunkedBigBlastMatchesOneShotCrater() {
        // Two identical solid stone slabs, far apart so their graphs/craters don't
        // overlap. Slab A is the reference (one huge-budget tick); slab B is chunked.
        int baseAx = 0;
        int baseBx = 200;
        placeSlab(baseAx);
        placeSlab(baseBx);

        Location centerA = new Location(world, baseAx + 4, 67, 4);
        Location centerB = new Location(world, baseBx + 4, 67, 4);
        double power = 6.0; // radius 9 → 19³ ≈ 6859 scan candidates

        // Reference: one tick, unbounded scan, generous wall-clock budget.
        BlastProcessor oneShot = newProcessor(1_000_000.0, Integer.MAX_VALUE);
        oneShot.enqueue(new QueuedBlast(world, centerA, power));
        oneShot.run();
        assertFalse(oneShot.hasActiveBlast(), "the one-shot blast must finish in a single run");
        Set<Integer> craterA = readCraterKeys(baseAx);
        assertFalse(craterA.isEmpty(), "the reference blast must crater at least one block");

        // Chunked: 64 candidates per tick, zero wall-clock budget → exactly one scan
        // chunk per tick. With ~6859 candidates this needs MANY ticks to finish.
        BlastProcessor chunked = newProcessor(0.0, 64);
        chunked.enqueue(new QueuedBlast(world, centerB, power));

        chunked.run(); // tick 1
        assertTrue(chunked.hasActiveBlast(), "a big blast must still be mid-scan after a single tiny-budget tick");
        assertEquals(0, chunked.queueSize(), "the blast left the queue to become the active session");
        // Nothing applied to the world yet: the crater only lands once the scan finishes.
        assertEquals(
                Material.STONE,
                world.getBlockAt(baseBx + 4, 67, 4).getType(),
                "the chunked blast must not crater the world until its scan completes");

        // Drive ticks until the session finishes (bounded guard against a stuck loop).
        int ticks = 1;
        while (chunked.hasActiveBlast()) {
            chunked.run();
            assertTrue(++ticks < 100_000, "chunked session never finished");
        }
        assertTrue(ticks > 1, "the chunked blast must have spanned more than one tick");

        Set<Integer> craterB = readCraterKeys(baseBx);
        assertEquals(craterA, craterB, "the multi-tick chunked crater must equal the one-shot crater exactly");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  (slice 2) A blast whose COLLAPSE exceeds the per-tick budget settles its
    //      collapse over several ticks; the cumulative crater equals a one-shot run.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A blast whose collapse exceeds the per-tick budget settles its collapse over several ticks")
    void chunkedBigCollapseSettlesOverManyTicks() {
        // Same blast (same power → same scan-cube cost), two towers of different
        // HEIGHT. The extra ticks the tall tower needs over the short one are pure
        // SETTLE work: blowing the base out orphans the whole shaft, so the tall
        // tower's floating collapse is far bigger. With an atomic settle both finish
        // one tick after the scan, so the gap would be ~0; a resumable settle makes
        // the tall tower span many more ticks. Far apart so the graphs don't overlap.
        int baseShortX = 0;
        int baseTallX = 400;
        placeTowerOfHeight(baseShortX, 2); // tiny collapse
        // 30 stone blocks (mass 3) stack to load 90 < stone's 100 capacity, so the
        // tower STANDS pre-blast; blowing the base out then floats the long shaft.
        placeTowerOfHeight(baseTallX, 30); // big floating collapse (shaft above the crater)
        double power = 4.0; // identical scan cube for both (radius 6 → 13³ candidates)

        int shortTicks = drainActiveTicks(baseShortX, power, 4);
        int tallTicks = drainActiveTicks(baseTallX, power, 4);

        // The scan cost is identical; the difference is the chunked settle. With an
        // atomic settle the two would be within a tick of each other.
        assertTrue(
                tallTicks - shortTicks > 5,
                "the tall tower's bigger collapse must add many settle ticks over the short one (tall=" + tallTicks
                        + " short=" + shortTicks + ")");

        // And the chunked tall-tower collapse equals the same tower at one huge tick.
        int baseRefX = 800;
        placeTowerOfHeight(baseRefX, 30);
        BlastProcessor oneShot = newProcessor(1_000_000.0, Integer.MAX_VALUE);
        oneShot.enqueue(new QueuedBlast(world, new Location(world, baseRefX, 66, 0), power));
        oneShot.run();
        assertFalse(oneShot.hasActiveBlast(), "the one-shot blast must finish in a single run");
        server.getScheduler().performTicks(40); // flush the delayed collapse to AIR

        Set<Integer> craterTall = readTowerAir(baseTallX);
        Set<Integer> craterRef = readTowerAir(baseRefX);
        assertTrue(craterRef.size() > 5, "the reference collapse must be big (was " + craterRef.size() + ")");
        assertEquals(craterRef, craterTall, "the multi-tick chunked collapse must equal the one-shot collapse exactly");
    }

    /** Fire one blast at {@code budget} steps/tick, count the ticks it stays active, flush its collapse. */
    private int drainActiveTicks(int baseX, double power, int budget) {
        BlastProcessor proc = newProcessor(0.0, budget);
        proc.enqueue(new QueuedBlast(world, new Location(world, baseX, 66, 0), power));
        int activeTicks = 0;
        while (true) {
            proc.run();
            if (!proc.hasActiveBlast()) {
                break;
            }
            activeTicks++;
            assertTrue(activeTicks < 1_000_000, "session never finished for base " + baseX);
        }
        server.getScheduler().performTicks(40); // apply the delayed collapse
        return activeTicks;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  stop() abandons the active (mid-scan) session
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("stop() drops the active mid-scan session (no crater is ever applied)")
    void stopDropsActiveSession() {
        placeSlab(0);
        BlastProcessor chunked = newProcessor(0.0, 64);
        chunked.enqueue(new QueuedBlast(world, new Location(world, 4, 67, 4), 6.0));

        chunked.run(); // begins + advances one chunk → active, mid-scan
        assertTrue(chunked.hasActiveBlast(), "the blast must be active after the first tiny-budget tick");

        chunked.stop();

        assertFalse(chunked.hasActiveBlast(), "stop() must drop the active session");
        assertEquals(0, chunked.queueSize(), "stop() clears the queue too");
        // The abandoned blast never finished, so nothing was applied to the world:
        // the slab's stone (y=66,67) is still all stone.
        for (int y = 66; y <= 67; y++) {
            assertEquals(
                    Material.STONE,
                    world.getBlockAt(4, y, 4).getType(),
                    "a dropped mid-scan blast must leave the world untouched (y=" + y + ")");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────────────────────

    /** A fresh, unstarted processor with explicit budget knobs (drive it via run()). */
    private BlastProcessor newProcessor(double tickBudgetMs, int maxScanPerTick) {
        return newProcessor(tickBudgetMs, maxScanPerTick, 1_000_000);
    }

    /**
     * A fresh processor with an explicit crater-removal cap too. A huge cap (the
     * default overload) keeps the crater applying inside the same {@code run()} call
     * the scan finishes in, so the existing scan-equivalence assertions read the full
     * crater immediately — the crater stream is pinned separately in
     * {@link CraterStreamingE2ETest}.
     */
    private BlastProcessor newProcessor(double tickBudgetMs, int maxScanPerTick, int maxCraterRemovalsPerTick) {
        CraterApplier craterApplier = new CraterApplier(
                plugin.getCollapseGuard(),
                new CollapseEffects(plugin.getEffectsConfig(), plugin),
                new DebrisVisuals(plugin),
                new StreamedBukkitCraterRemover(),
                8);
        return new BlastProcessor(
                plugin,
                plugin.getStructureManager(),
                new StruxExplosionEngine(new PhysicsConfig()),
                plugin.getDelayedCollapseManager(),
                plugin.getCascadeResumeManager(),
                craterApplier,
                plugin.getEffectsConfig(),
                new CollapseEffects(plugin.getEffectsConfig(), plugin),
                plugin.getLogger(),
                tickBudgetMs,
                maxScanPerTick,
                maxCraterRemovalsPerTick,
                plugin.getTaskTimings());
    }

    /**
     * A 9×9 solid stone slab two blocks tall (y=66,67) on a bedrock floor (y=65),
     * its corner at {@code baseX}, on z=0..8. Big enough that a power-6 blast scans
     * thousands of candidate positions.
     */
    private void placeSlab(int baseX) {
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                place(baseX + x, 65, z, Material.BEDROCK);
                place(baseX + x, 66, z, Material.STONE);
                place(baseX + x, 67, z, Material.STONE);
            }
        }
    }

    /**
     * A BEDROCK-footed (y=65) stone tower {@code height} blocks tall (y=66..) at
     * {@code baseX} on z=0. Blowing out its lower blocks orphans the shaft above, so
     * the SETTLE collapses far more blocks than a tiny per-tick budget — slice 2.
     */
    private void placeTowerOfHeight(int baseX, int height) {
        place(baseX, 65, 0, Material.BEDROCK);
        for (int y = 66; y < 66 + height; y++) {
            place(baseX, y, 0, Material.STONE);
        }
    }

    /** The set of absolute y-levels that became AIR in a settled tower (y=66..120). */
    private Set<Integer> readTowerAir(int baseX) {
        Set<Integer> air = new HashSet<>();
        for (int y = 66; y <= 120; y++) {
            if (world.getBlockAt(baseX, y, 0).getType() == Material.AIR) {
                air.add(y);
            }
        }
        return air;
    }

    /** Encode the AIR positions of a settled slab as base-relative keys (a stable set to compare). */
    private Set<Integer> readCraterKeys(int baseX) {
        Set<Integer> keys = new HashSet<>();
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                for (int y = 66; y <= 67; y++) {
                    if (world.getBlockAt(baseX + x, y, z).getType() == Material.AIR) {
                        keys.add((x * 100 + y) * 100 + z);
                    }
                }
            }
        }
        return keys;
    }

    private void place(int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(material);
        plugin.getStructureManager().onBlockPlaced(block);
    }
}
