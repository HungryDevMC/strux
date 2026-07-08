package dev.gesp.structural.minecraft;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.persistence.BlockData;
import dev.gesp.structural.persistence.PersistenceAdapter;
import dev.gesp.structural.persistence.StructureData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * End-to-end integration tests using MockBukkit.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                      E2E INTEGRATION TESTS                         │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  These tests simulate actual Minecraft gameplay:                   │
 *   │                                                                     │
 *   │  1. Plugin loads on mock server                                    │
 *   │  2. Player places blocks → structure is tracked                    │
 *   │  3. Player breaks block → cascade occurs                           │
 *   │  4. Collapsed blocks are removed from world                        │
 *   │                                                                     │
 *   │                                                                     │
 *   │       [STONE]  ← Player places                                     │
 *   │          │                                                         │
 *   │       [STONE]                                                      │
 *   │          │                                                         │
 *   │       [STONE]  ← Player breaks this                                │
 *   │          │                                                         │
 *   │       [STONE]                                                      │
 *   │          │                                                         │
 *   │      [BEDROCK] ← Ground                                            │
 *   │                                                                     │
 *   │       Result: Top 2 blocks collapse (become AIR)                   │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
@DisplayName("E2E: Structural Integrity in Minecraft")
class StructuralIntegrityE2ETest {

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private WorldMock world;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        world = server.addSimpleWorld("test_world");
        player = server.addPlayer("TestPlayer");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PLUGIN LIFECYCLE TESTS
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Plugin should load successfully")
    void pluginLoads() {
        assertNotNull(plugin);
        assertTrue(plugin.isEnabled());
    }

    @Test
    @DisplayName("Plugin should expose MaterialRegistry and StructureManager")
    void pluginExposesComponents() {
        assertNotNull(plugin.getMaterialRegistry());
        assertNotNull(plugin.getStructureManager());
    }

    // ─────────────────────────────────────────────────────────────────────
    //  BLOCK PLACEMENT TESTS
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Placing blocks should register them in structure")
    void placingBlocksRegistersInStructure() {
        StructureManager manager = plugin.getStructureManager();

        // Place bedrock as ground
        Block ground = world.getBlockAt(0, 0, 0);
        ground.setType(Material.BEDROCK);
        manager.onBlockPlaced(ground);

        // Place stone above
        Block stone = world.getBlockAt(0, 1, 0);
        stone.setType(Material.STONE);
        manager.onBlockPlaced(stone);

        // Both should be tracked
        assertTrue(manager.isTracked(ground));
        assertTrue(manager.isTracked(stone));
    }

    @Test
    @DisplayName("Stress should be calculated for placed blocks")
    void stressIsCalculatedForBlocks() {
        StructureManager manager = plugin.getStructureManager();

        // Build: BEDROCK - STONE - STONE - STONE (vertical)
        placeColumn(0, 0, 0, Material.BEDROCK, Material.STONE, Material.STONE, Material.STONE);

        // Ground has 0 stress
        Block ground = world.getBlockAt(0, 0, 0);
        assertEquals(0.0, manager.getStress(ground), 0.001);

        // Higher blocks have stress (load from above)
        Block bottom = world.getBlockAt(0, 1, 0);
        double stress = manager.getStress(bottom);
        assertTrue(stress > 0, "Bottom stone should have stress from blocks above");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  CASCADE COLLAPSE TESTS
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Breaking middle block should collapse blocks above")
    void breakingMiddleBlockCollapses() {
        StructureManager manager = plugin.getStructureManager();

        // Build: BEDROCK(y=0) - STONE(y=1) - STONE(y=2) - STONE(y=3) - STONE(y=4)
        placeColumn(0, 0, 0, Material.BEDROCK, Material.STONE, Material.STONE, Material.STONE, Material.STONE);

        // Break the middle block (y=2)
        Block middle = world.getBlockAt(0, 2, 0);
        var result = manager.onBlockBroken(middle);

        // Should cascade - blocks at y=3 and y=4 should collapse
        assertTrue(result.hadCascade(), "Should have cascade");
        assertEquals(2, result.totalCollapsed(), "Two blocks above should collapse");
    }

    @Test
    @DisplayName("Breaking top block should NOT cause cascade")
    void breakingTopBlockNoCascade() {
        StructureManager manager = plugin.getStructureManager();

        // Build: BEDROCK - STONE - STONE - STONE
        placeColumn(0, 0, 0, Material.BEDROCK, Material.STONE, Material.STONE, Material.STONE);

        // Break the top block
        Block top = world.getBlockAt(0, 3, 0);
        var result = manager.onBlockBroken(top);

        // No cascade - nothing depends on the top block
        assertFalse(result.hadCascade());
        assertEquals(0, result.totalCollapsed());
    }

    @Test
    @DisplayName("Breaking ground should collapse everything above")
    void breakingGroundCollapsesAll() {
        StructureManager manager = plugin.getStructureManager();

        // Build: BEDROCK - STONE - STONE
        placeColumn(0, 0, 0, Material.BEDROCK, Material.STONE, Material.STONE);

        // Break the ground
        Block ground = world.getBlockAt(0, 0, 0);
        var result = manager.onBlockBroken(ground);

        // Everything should collapse
        assertTrue(result.hadCascade());
        assertEquals(2, result.totalCollapsed());
    }

    // ─────────────────────────────────────────────────────────────────────
    //  MATERIAL TESTS
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Different materials should have different properties")
    void materialPropertiesDiffer() {
        var registry = plugin.getMaterialRegistry();

        var stone = registry.getSpec(Material.STONE);
        var glass = registry.getSpec(Material.GLASS);
        var iron = registry.getSpec(Material.IRON_BLOCK);

        // Stone is heavier than glass
        assertTrue(stone.mass() > glass.mass());

        // Iron is stronger than glass
        assertTrue(iron.maxLoad() > glass.maxLoad());

        // Bedrock is ground
        assertTrue(registry.isGround(Material.BEDROCK));
    }

    @Test
    @DisplayName("Glass should collapse under heavy load when placed")
    void glassCollapsesUnderHeavyLoad() {
        StructureManager manager = plugin.getStructureManager();

        // Build: BEDROCK - GLASS - then stack stones until collapse
        // Glass can only hold maxLoad=5, stone mass=3 each
        // 2 stones = 6 mass, should break the glass

        Block ground = world.getBlockAt(0, 0, 0);
        ground.setType(Material.BEDROCK);
        manager.onBlockPlaced(ground);

        Block glass = world.getBlockAt(0, 1, 0);
        glass.setType(Material.GLASS);
        manager.onBlockPlaced(glass);

        // Place first stone - glass at 60% stress (3/5)
        Block stone1 = world.getBlockAt(0, 2, 0);
        stone1.setType(Material.STONE);
        var result1 = manager.onBlockPlaced(stone1);
        assertTrue(result1.isEmpty(), "First stone should not cause collapse");

        // Place second stone - glass at 120% stress (6/5), should collapse
        Block stone2 = world.getBlockAt(0, 3, 0);
        stone2.setType(Material.STONE);
        var result2 = manager.onBlockPlaced(stone2);

        // Glass should have collapsed, and stones above it should fall
        assertFalse(result2.isEmpty(), "Second stone should trigger collapse");
        assertFalse(manager.isTracked(glass), "Glass should have collapsed");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HORIZONTAL CANTILEVER TESTS
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Horizontal cantilever should collapse when too long")
    void horizontalCantileverCollapses() {
        StructureManager manager = plugin.getStructureManager();

        // Build horizontal: BEDROCK - STONE - STONE - STONE - ...
        // Stone maxLoad=100, mass=3
        // Each block must support all blocks farther from ground
        // At some point, a block will be overloaded (around 33 blocks)

        // Use y=64 to avoid world boundary issues
        Block ground = world.getBlockAt(0, 64, 0);
        ground.setType(Material.BEDROCK);
        manager.onBlockPlaced(ground);

        // Build horizontally until collapse
        int collapseX = -1;
        for (int x = 1; x <= 50; x++) {
            Block stone = world.getBlockAt(x, 64, 0);
            stone.setType(Material.STONE);
            var result = manager.onBlockPlaced(stone);

            if (!result.isEmpty()) {
                collapseX = x;
                break;
            }
        }

        // Should collapse around x=34 (100 / 3 = ~33 blocks before overload)
        assertTrue(
                collapseX > 0 && collapseX <= 40, "Horizontal cantilever should collapse, collapsed at x=" + collapseX);
    }

    @Test
    @DisplayName("Weak horizontal cantilever collapses sooner")
    void weakHorizontalCantileverCollapsesSooner() {
        StructureManager manager = plugin.getStructureManager();

        // Build horizontal with glass (maxLoad=5, mass=0.5)
        // Should collapse much sooner than stone (~10 blocks)

        // Use y=64 to avoid world boundary issues
        Block ground = world.getBlockAt(0, 64, 0);
        ground.setType(Material.BEDROCK);
        manager.onBlockPlaced(ground);

        // Build horizontally with glass
        int collapseX = -1;
        for (int x = 1; x <= 20; x++) {
            Block glass = world.getBlockAt(x, 64, 0);
            glass.setType(Material.GLASS);
            var result = manager.onBlockPlaced(glass);

            if (!result.isEmpty()) {
                collapseX = x;
                break;
            }
        }

        // Glass should collapse around x=10 (maxLoad=5 / mass=0.5 = 10 blocks)
        assertTrue(
                collapseX > 0 && collapseX <= 15,
                "Glass cantilever should collapse early, collapsed at x=" + collapseX);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  STABILITY CHECK TESTS
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Stability check should detect safe placements")
    void stabilityCheckSafe() {
        StructureManager manager = plugin.getStructureManager();

        // Place ground
        Block ground = world.getBlockAt(0, 0, 0);
        ground.setType(Material.BEDROCK);
        manager.onBlockPlaced(ground);

        // Place stone above
        Block stone = world.getBlockAt(0, 1, 0);
        stone.setType(Material.STONE);
        manager.onBlockPlaced(stone);

        // Another stone should be safe
        Block newStone = world.getBlockAt(0, 2, 0);
        newStone.setType(Material.STONE);

        assertTrue(manager.wouldBeStable(newStone), "Stone on stone should be stable");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  FULL-STACK: real Bukkit event → listener → WORLD is mutated
    //
    //  The tests above call StructureManager directly and assert on the result
    //  object. These instead fire the actual events the plugin registered for,
    //  and assert that the Minecraft WORLD ends up in sync — collapsed blocks
    //  become AIR — which is the wiring (listener → callback → world) that the
    //  pure-:core scenario tests cannot cover.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BlockBreakEvent on a base support clears the collapsed column from the world")
    void breakEventSyncsCollapseToWorld() {
        StructureManager manager = plugin.getStructureManager();

        // BEDROCK(y=0) - STONE(y=1..4)
        placeColumn(0, 0, 0, Material.BEDROCK, Material.STONE, Material.STONE, Material.STONE, Material.STONE);

        // Fire the real event a player breaking the base would produce.
        Block base = world.getBlockAt(0, 1, 0);
        server.getPluginManager().callEvent(new BlockBreakEvent(base, player));

        // The blocks above lost support and were removed from the WORLD itself,
        // not merely from the structure graph.
        for (int y = 2; y <= 4; y++) {
            Block above = world.getBlockAt(0, y, 0);
            assertEquals(Material.AIR, above.getType(), "y=" + y + " must be cleared from the world");
            assertFalse(manager.isTracked(above), "y=" + y + " must no longer be tracked");
        }
        // The foundation is untouched.
        assertEquals(Material.BEDROCK, world.getBlockAt(0, 0, 0).getType());
        assertTrue(manager.isTracked(world.getBlockAt(0, 0, 0)));
    }

    @Test
    @DisplayName("Breaking the only pillar collapses the whole platform out of the world")
    void breakPillarCollapsesPlatformInWorld() {
        StructureManager manager = plugin.getStructureManager();

        // A plus-shaped platform held up by a single central pillar.
        place(0, 0, 0, Material.BEDROCK); // ground
        place(0, 1, 0, Material.STONE); // pillar
        place(0, 2, 0, Material.STONE); // pillar top
        place(0, 3, 0, Material.STONE); // platform centre
        place(1, 3, 0, Material.STONE); // platform arms
        place(-1, 3, 0, Material.STONE);
        place(0, 3, 1, Material.STONE);
        place(0, 3, -1, Material.STONE);

        // Knock out the pillar at its base.
        server.getPluginManager().callEvent(new BlockBreakEvent(world.getBlockAt(0, 1, 0), player));

        // Pillar top + the entire platform are now floating → gone from the world.
        int[][] shouldFall = {{0, 2, 0}, {0, 3, 0}, {1, 3, 0}, {-1, 3, 0}, {0, 3, 1}, {0, 3, -1}};
        for (int[] c : shouldFall) {
            Block b = world.getBlockAt(c[0], c[1], c[2]);
            assertEquals(
                    Material.AIR,
                    b.getType(),
                    "(" + c[0] + "," + c[1] + "," + c[2] + ") must be cleared from the world");
            assertFalse(manager.isTracked(b));
        }
        assertEquals(Material.BEDROCK, world.getBlockAt(0, 0, 0).getType(), "ground holds");
    }

    @Test
    @DisplayName("An explosion event craters the tracked structure and removes it from the world")
    void explosionEventRemovesTrackedBlocksFromWorld() {
        StructureManager manager = plugin.getStructureManager();

        // A tracked column the blast can reach.
        placeColumn(
                0,
                0,
                0,
                Material.BEDROCK,
                Material.STONE,
                Material.STONE,
                Material.STONE,
                Material.STONE,
                Material.STONE);

        // Fire the explosion event the plugin listens for, centred on the column top.
        Location center = new Location(world, 0, 5, 0);
        TNTPrimed tnt = (TNTPrimed) world.spawn(center, TNTPrimed.class);
        var event = new EntityExplodeEvent(tnt, center, new ArrayList<>(), 4.0f, ExplosionResult.DESTROY);
        server.getPluginManager().callEvent(event);

        // The blast is queued at event time and settled by the tick-budgeted
        // BlastProcessor a tick later — advance the scheduler so it drains.
        server.getScheduler().performTicks(2);

        // Something the structure tracked is now gone from the world (crater + cascade),
        // and the graph stayed in sync (no orphaned tracking of cleared blocks).
        int clearedAndUntracked = 0;
        for (int y = 1; y <= 5; y++) {
            Block b = world.getBlockAt(0, y, 0);
            if (b.getType() == Material.AIR) {
                assertFalse(manager.isTracked(b), "y=" + y + " is AIR but still tracked — world/graph drift");
                clearedAndUntracked++;
            }
        }
        assertTrue(clearedAndUntracked > 0, "the blast should have cleared at least one tracked block");
        assertEquals(Material.BEDROCK, world.getBlockAt(0, 0, 0).getType(), "ground is never blown up");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  FREEZE: grade is cached until the world actually changes
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("World grade is frozen (no re-solve) until the structure changes")
    void gradeFreezesUntilWorldChanges() {
        StructureManager manager = plugin.getStructureManager();
        placeColumn(0, 0, 0, Material.BEDROCK, Material.STONE, Material.STONE, Material.STONE);

        manager.assessWorld(world); // first grade — solves
        long afterFirst = manager.getMetrics().solveInvocations;
        manager.assessWorld(world); // unchanged — must be a cache hit, no solve
        long afterSecond = manager.getMetrics().solveInvocations;
        assertEquals(afterFirst, afterSecond, "an unchanged world must not re-solve when graded again");

        // Change the world, then grade again — now it must recompute.
        Block more = world.getBlockAt(0, 4, 0);
        more.setType(Material.STONE);
        manager.onBlockPlaced(more);
        manager.assessWorld(world);
        long afterChange = manager.getMetrics().solveInvocations;
        assertTrue(afterChange > afterSecond, "a changed world must re-solve when graded");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  FIRE: barren burnout
    //
    //  A flame with no fuel beside it (lit against bare stone) must gutter out
    //  after fire.barren-burnout-ticks; a flame touching anything flammable
    //  keeps burning. Drives the real FireScorchTask through the scheduler.
    // ─────────────────────────────────────────────────────────────────────

    /** Default fire.barren-burnout-ticks (600) plus a couple of scan intervals of slack. */
    private static final long BURNOUT_TICKS_PLUS_SLACK = 700;

    @Test
    @DisplayName("Fire against bare stone dies out after the barren-burnout time")
    void barrenFireAgainstStoneDiesOut() {
        // Tracked stone column — nothing flammable anywhere near the fire.
        placeColumn(0, 0, 0, Material.BEDROCK, Material.STONE, Material.STONE);

        Block fire = world.getBlockAt(1, 1, 0); // beside the tracked stone
        fire.setType(Material.FIRE);
        server.getPluginManager()
                .callEvent(new BlockIgniteEvent(fire, BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL, player));

        server.getScheduler().performTicks(BURNOUT_TICKS_PLUS_SLACK);

        assertEquals(Material.AIR, fire.getType(), "a fuel-less flame must gutter out, not burn forever");
        // The stone it leaned on is still standing.
        assertEquals(Material.STONE, world.getBlockAt(0, 1, 0).getType());
    }

    @Test
    @DisplayName("Fire touching a flammable block keeps burning past the burnout time")
    void fuelledFireKeepsBurning() {
        // Tracked stone wall, with an (untracked) wood pile feeding the flame —
        // fuel keeps resetting the burnout clock, so the wall keeps cooking.
        placeColumn(0, 0, 0, Material.BEDROCK, Material.STONE, Material.STONE);
        world.getBlockAt(2, 1, 0).setType(Material.OAK_PLANKS); // fuel, not part of the structure

        Block fire = world.getBlockAt(1, 1, 0); // between the wall and its fuel
        fire.setType(Material.FIRE);
        server.getPluginManager()
                .callEvent(new BlockIgniteEvent(fire, BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL, player));

        server.getScheduler().performTicks(BURNOUT_TICKS_PLUS_SLACK);

        assertEquals(Material.FIRE, fire.getType(), "a fuelled siege fire must keep threatening the wall");
    }

    @Test
    @DisplayName("A zero scorch budget makes the scan a no-op, so a barren fire never gutters out")
    void zeroFireBudgetSkipsScanEntirely() {
        // Same barren setup as barrenFireAgainstStoneDiesOut, but with the scorch
        // budget pinned to zero. The seatbelt makes each pass stop before doing any
        // work, so the barren-burnout clock never advances — the fire that WOULD
        // have guttered out now stays lit no matter how many ticks pass. This pins
        // the per-scan budget actually bounding the work (not just decorating it).
        placeColumn(0, 0, 0, Material.BEDROCK, Material.STONE, Material.STONE);
        plugin.getFireScorchTask().setTickBudgetMs(0.0);

        Block fire = world.getBlockAt(1, 1, 0); // beside tracked stone, no fuel
        fire.setType(Material.FIRE);
        server.getPluginManager()
                .callEvent(new BlockIgniteEvent(fire, BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL, player));

        server.getScheduler().performTicks(BURNOUT_TICKS_PLUS_SLACK);

        assertEquals(Material.FIRE, fire.getType(), "a zero-budget scan does no work, so the fire never burns out");
    }

    @Test
    @DisplayName("Fire is not structural: igniting adds no phantom load and is never tracked")
    void fireIsNotStructural() {
        // Flint & steel fires a BlockPlaceEvent for the FIRE block. Registering
        // it would give fire the DEFAULT material mass — igniting a stressed
        // wall would knock it over by phantom weight (found in live playtest
        // verification: lighting an 80%-stressed beam collapsed it instantly).
        StructureManager manager = plugin.getStructureManager();
        placeColumn(0, 0, 0, Material.BEDROCK, Material.STONE, Material.STONE);

        Block fire = world.getBlockAt(0, 3, 0); // on top of the wall
        fire.setType(Material.FIRE);
        manager.onBlockPlaced(fire);

        assertFalse(manager.isTracked(fire), "fire must never enter the structure graph");
        assertEquals(Material.STONE, world.getBlockAt(0, 2, 0).getType(), "wall is unaffected");
        assertEquals(Material.STONE, world.getBlockAt(0, 1, 0).getType());
    }

    @Test
    @DisplayName("A block burning away does not extinguish nearby fire (our handler leaves fire alone)")
    void burnEventLeavesNearbyFireAlone() {
        // MockBukkit runs NO vanilla fire ticks, so if a fire block disappears
        // after BlockBurnEvent it can only be the plugin's doing. This pins the
        // playtest finding: fire vanishing next to a burnt block is vanilla
        // mechanics (orphaned fire pops / fuel-less fire ages out), not strux.
        placeColumn(0, 0, 0, Material.BEDROCK, Material.OAK_PLANKS, Material.OAK_PLANKS);

        Block fireBesideWall = world.getBlockAt(1, 1, 0); // still has plank fuel after the burn
        fireBesideWall.setType(Material.FIRE);
        Block fireBesideBurnt = world.getBlockAt(1, 2, 0); // its fuel is the block that burns away
        fireBesideBurnt.setType(Material.FIRE);

        // The top plank burns away: the real event, then vanilla's removal.
        Block burnt = world.getBlockAt(0, 2, 0);
        server.getPluginManager().callEvent(new BlockBurnEvent(burnt, fireBesideBurnt));
        burnt.setType(Material.AIR);

        // Our handler reacted (the burnt block is gone from tracking)...
        StructureManager manager = plugin.getStructureManager();
        assertFalse(manager.isTracked(burnt), "burnt block must leave the structure graph");
        // ...but did not touch any fire block.
        assertEquals(Material.FIRE, fireBesideWall.getType(), "fire on remaining fuel must persist");
        assertEquals(Material.FIRE, fireBesideBurnt.getType(), "strux must not extinguish fire itself");
        // The rest of the wall still stands.
        assertEquals(Material.OAK_PLANKS, world.getBlockAt(0, 1, 0).getType());
    }

    @Test
    @DisplayName("Fire scorching a support to failure settles the structure above it (the post-scorch settle path)")
    void fireScorchThroughSupportCollapsesAbove() {
        // A plank pier (lower plank holds an upper plank). Fire beside the lower plank
        // cooks it with burn damage (planks are flammable) — at 0.0006/0.5 = 0.0012/tick
        // it crosses full damage in ~840 ticks. When it fails, the scorch scan runs its
        // post-scorch settle (settleAndSchedule) over what it held up: the upper plank,
        // now unsupported, comes down. This drives the fire collapse path that — for a
        // big burning wall — must resume across ticks rather than freeze or drop blocks.
        placeColumn(0, 0, 0, Material.BEDROCK, Material.OAK_PLANKS, Material.OAK_PLANKS);
        StructureManager manager = plugin.getStructureManager();

        // Park the player far away: the damage visualizer renders crack overlays to
        // nearby players via sendBlockDamage, which MockBukkit doesn't implement — out
        // of range, the gradual fire cracking never tries to draw. (Same trick the
        // deferred-blast E2E uses for its notify radius.)
        player.teleport(world.getBlockAt(500, 66, 0).getLocation());

        Block fire = world.getBlockAt(1, 1, 0); // beside the lower plank (its fuel) — never goes barren
        fire.setType(Material.FIRE);
        server.getPluginManager()
                .callEvent(new BlockIgniteEvent(fire, BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL, player));

        // Cook well past the ~840 ticks the lower plank needs to fail, plus collapse drama.
        server.getScheduler().performTicks(1400);

        assertFalse(
                manager.isTracked(world.getBlockAt(0, 1, 0)),
                "fire must scorch the lower plank clean through (out of the graph)");
        assertEquals(
                Material.AIR,
                world.getBlockAt(0, 2, 0).getType(),
                "the upper plank must come down once its scorched support fails");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PERSISTENCE / AUTO-SAVE CONCURRENCY
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Auto-save used to serialize the live graph on an async scheduler thread,
     * racing the main thread that mutates it on cascade/blast and throwing a
     * ConcurrentModificationException. The fix serializes synchronously at the
     * call site (the main thread) and only the disk write stays async. This test
     * pins that contract: {@code saveAllAsync()} must read the graph and build the
     * snapshot before it returns, so a mutation made *after* the call cannot leak
     * into the captured data.
     */
    @Test
    @DisplayName("saveAllAsync snapshots the graph synchronously at call time")
    void saveAllSnapshotsSynchronously() {
        StructureManager manager = plugin.getStructureManager();

        // A capturing adapter: records the snapshot it is handed, per world, and
        // completes immediately. (No hand-pended future here — teardown calls
        // saveAll(), which joins this adapter's future; a pending one would hang.)
        Map<String, StructureData> captured = new HashMap<>();
        manager.setPersistenceAdapter(new PersistenceAdapter() {
            @Override
            public CompletableFuture<Void> saveAsync(String worldId, StructureData data) {
                captured.put(worldId, data);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Optional<StructureData>> loadAsync(String worldId) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<Void> deleteAsync(String worldId) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Boolean> existsAsync(String worldId) {
                return CompletableFuture.completedFuture(false);
            }

            @Override
            public String getName() {
                return "CapturingTestAdapter";
            }
        });

        // Two tracked blocks at save time.
        place(0, 0, 0, Material.BEDROCK);
        place(0, 1, 0, Material.STONE);

        CompletableFuture<Void> save = manager.saveAllAsync();

        // The snapshot must already be built when the call returns: serialization
        // happens at the call site (the main thread), not on a writer thread.
        StructureData snapshot = captured.get(world.getUID().toString());
        assertNotNull(snapshot, "saveAllAsync must serialize the graph before returning");
        int countAtSave = snapshot.blockCount();
        assertTrue(countAtSave >= 2, "snapshot must contain at least the two placed blocks");

        // Mutate the graph after the call. If serialization had been deferred to
        // the write thread, this block could leak into the snapshot (or, in the
        // original bug, this is exactly the concurrent mutation that threw a CME).
        place(0, 2, 0, Material.STONE);
        assertEquals(countAtSave, snapshot.blockCount(), "post-call mutation must not change the captured snapshot");

        // A fresh save sees the new block — so the first snapshot really was
        // call-time state, not a stale or shared view.
        manager.saveAllAsync();
        assertTrue(
                captured.get(world.getUID().toString()).blockCount() > countAtSave,
                "second save must include the block placed after the first");

        assertDoesNotThrow(() -> save.join(), "save future should complete cleanly");
    }

    /**
     * E2E for async startup load. A persistence adapter returns a saved
     * structure only after a gated delay. Loading is kicked off without blocking;
     * the world's blocks become tracked only after the load finishes AND a
     * scheduler tick publishes the graph on the main thread. A break fired during
     * the in-flight window is a safe no-op and does not corrupt the published
     * graph.
     */
    @Test
    @DisplayName("E2E: async startup load — non-blocking, main-thread publish, race-safe")
    void asyncStartupLoadEndToEnd() throws Exception {
        StructureManager manager = plugin.getStructureManager();
        WorldMock loadWorld = server.addSimpleWorld("e2e_async_load");
        int bottom = loadWorld.getMinHeight();

        // The saved structure that will "load from disk" once the gate opens.
        StructureData saved = new StructureData(loadWorld.getUID().toString());
        saved.addBlock(new BlockData(0, bottom, 0, 0.0, Double.POSITIVE_INFINITY, true));
        saved.addBlock(new BlockData(0, bottom + 1, 0, 2.0, 30.0, false));

        CompletableFuture<Optional<StructureData>> gate = new CompletableFuture<>();
        manager.setPersistenceAdapter(new PersistenceAdapter() {
            @Override
            public CompletableFuture<Void> saveAsync(String worldId, StructureData data) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Optional<StructureData>> loadAsync(String worldId) {
                return gate;
            }

            @Override
            public CompletableFuture<Void> deleteAsync(String worldId) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Boolean> existsAsync(String worldId) {
                return CompletableFuture.completedFuture(false);
            }

            @Override
            public String getName() {
                return "GatedE2EAdapter";
            }
        });

        // Crit 1: kicking off the load returns immediately, before the data is ready.
        CompletableFuture<Void> loads = manager.loadAllWorldsAsync(plugin, List.of(loadWorld));
        assertFalse(loads.isDone(), "load kickoff must return before the gated data is ready");

        Block stone = loadWorld.getBlockAt(0, bottom + 1, 0);
        assertFalse(manager.isTracked(stone), "blocks must not be tracked before the load finishes");

        // Off-thread deserialize finishes; the main-thread publish hasn't run yet.
        gate.complete(Optional.of(saved));
        loads.get(5, TimeUnit.SECONDS);

        // Crit 3+4: a break during the in-flight window is a safe no-op.
        assertDoesNotThrow(() -> manager.onBlockBroken(stone), "an in-flight break must not crash");
        assertFalse(manager.isTracked(stone), "still untracked until the publish tick runs");

        // Crit 2: after one tick the graph is published on the main thread.
        server.getScheduler().performTicks(1);
        assertTrue(manager.isTracked(stone), "blocks must be tracked after the publish tick");
        assertNotNull(manager.getGraph(loadWorld), "graph must be published after the tick");
        assertEquals(2, manager.getGraph(loadWorld).size(), "the published graph must be intact");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HELPER METHODS
    // ─────────────────────────────────────────────────────────────────────

    /** Place a single block in the world and register it with the manager. */
    private void place(int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(material);
        plugin.getStructureManager().onBlockPlaced(block);
    }

    /**
     * Place a vertical column of blocks.
     */
    private void placeColumn(int x, int startY, int z, Material... materials) {
        StructureManager manager = plugin.getStructureManager();
        for (int i = 0; i < materials.length; i++) {
            Block block = world.getBlockAt(x, startY + i, z);
            block.setType(materials[i]);
            manager.onBlockPlaced(block);
        }
    }
}
