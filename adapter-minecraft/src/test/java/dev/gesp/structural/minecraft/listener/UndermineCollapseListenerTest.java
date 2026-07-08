package dev.gesp.structural.minecraft.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.minecraft.StructuralIntegrityPlugin;
import dev.gesp.structural.minecraft.config.EffectsConfig;
import dev.gesp.structural.minecraft.material.MaterialRegistry;
import dev.gesp.structural.minecraft.rubble.RubbleHandler;
import dev.gesp.structural.minecraft.visual.CollapseEffects;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * End-to-end undermining through {@link BlockBreakListener}: digging out the lone
 * support under a cantilevered wall brings the wall down (real cascade physics) AND
 * the break-triggered rubble is biased back toward the dug void so the tunnel
 * partially backfills. The toggle off keeps the collapse but drops the bias.
 *
 * <p>The listener is wired by hand (like {@code DelayedCollapseManagerRunTest})
 * around the plugin's real managers with a test {@link RubbleHandler} whose physics
 * makes rubble survival deterministic (fall-damage factor 0, base chance 1.0).
 */
@DisplayName("Undermine via BlockBreakListener: wall collapses, rubble backfills the tunnel")
class UndermineCollapseListenerTest {

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private WorldMock world;
    private EffectsConfig effects;
    private BlockBreakListener listener;
    private RubbleHandler rubbleHandler;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        world = server.addSimpleWorld("undermine_listener_world");

        PhysicsConfig physics = new PhysicsConfig();
        physics.setRubbleEnabled(true);
        physics.setRubbleFallDamageFactor(0.0); // survival = 1.0, deterministic
        physics.setRubbleBaseChance(1.0);
        physics.setRubbleMinChance(1.0);

        effects = new EffectsConfig();
        effects.setRubbleEnabled(true);
        effects.setUndermineBackfillRubble(true);
        effects.setMaxRubblePerCollapse(200);

        rubbleHandler = new RubbleHandler(plugin, physics, effects, new MaterialRegistry());
        listener = new BlockBreakListener(
                plugin.getStructureManager(),
                plugin.getDelayedCollapseManager(),
                plugin.getCascadeResumeManager(),
                new CollapseEffects(effects, plugin),
                plugin.getCollapseGuard(),
                rubbleHandler,
                effects,
                new CollapseNotifier(plugin, effects));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /**
     * Build a wall cantilevered off a single ground support at x=0:
     *
     * <pre>
     *   y=66:  [S][W][W][W]      ← wall, only path to ground is through the support
     *   y=65:  [.]               ← bedrock under the support only
     *   x:      0  1  2  3
     * </pre>
     *
     * Breaking the support at (0,66,0) leaves the +X wall with no path to ground, so
     * it collapses as FLOATING — the dig-under-a-wall undermine.
     */
    private Block buildCantileverOnSupport() {
        place(0, 64, 0, Material.BEDROCK);
        place(0, 65, 0, Material.STONE); // the support pillar
        for (int x = 0; x <= 3; x++) {
            place(x, 66, 0, Material.STONE); // the wall, attached to the support at x=0
        }
        return world.getBlockAt(0, 65, 0);
    }

    private void place(int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(material);
        plugin.getStructureManager().onBlockPlaced(block);
    }

    private void breakBlock(Block block, Player player) {
        block.setType(Material.AIR); // the player's own break, as Bukkit would apply it
        listener.onBlockBreak(new BlockBreakEvent(block, player));
    }

    @Test
    @DisplayName("Digging the support collapses the cantilever AND backfills rubble toward the void")
    void underminingCollapsesAndBackfills() {
        Block support = buildCantileverOnSupport();
        PlayerMock player = server.addPlayer();

        breakBlock(support, player);

        // The cantilevered wall lost its only path to ground and came down.
        assertEquals(Material.AIR, world.getBlockAt(2, 66, 0).getType(), "the far wall block collapsed");
        assertEquals(Material.AIR, world.getBlockAt(3, 66, 0).getType(), "the far wall block collapsed");

        // Rubble spawned for the collapse, biased back toward the dug void at x=0.
        // The cantilever sat at x≥1, east of the void, so its backfill nudge points -X
        // and overwhelms the cosmetic scatter: no off-column rubble drifts the +X
        // (away) direction. A piece that fell from the void column itself (x=0) has
        // nothing to backfill toward, so it keeps the plain scatter — we only assert
        // the directional claim on the off-column rubble.
        var rubble = world.getEntitiesByClass(FallingBlock.class);
        assertFalse(rubble.isEmpty(), "the collapse spawned rubble");
        boolean sawOffColumnRubble = false;
        for (FallingBlock fb : rubble) {
            if (fb.getLocation().getBlockX() > 0) { // east of the void column
                sawOffColumnRubble = true;
                assertTrue(
                        fb.getVelocity().getX() <= 1.0e-9,
                        "off-column rubble drifts back toward the tunnel (vx="
                                + fb.getVelocity().getX() + ")");
            }
        }
        assertTrue(sawOffColumnRubble, "the cantilevered wall east of the void produced backfilling rubble");
    }

    @Test
    @DisplayName("With backfill off the wall still collapses but rubble is not pulled toward the void")
    void toggleOffStillCollapsesWithoutBias() {
        effects.setUndermineBackfillRubble(false);
        Block support = buildCantileverOnSupport();
        PlayerMock player = server.addPlayer();

        breakBlock(support, player);

        assertEquals(Material.AIR, world.getBlockAt(3, 66, 0).getType(), "the wall still collapses with backfill off");

        // The only horizontal motion left with backfill off is the cosmetic scatter,
        // bounded to ±0.15. The undermine nudge (0.15 toward -X) would push the biased
        // path below -0.15, so "every vx ≥ -0.15" is a deterministic, run-independent
        // proof that NO backfill bias was applied.
        var rubble = world.getEntitiesByClass(FallingBlock.class);
        assertFalse(rubble.isEmpty(), "the collapse still spawns rubble");
        for (FallingBlock fb : rubble) {
            assertTrue(
                    fb.getVelocity().getX() >= -0.15 - 1.0e-9,
                    "backfill off → only cosmetic scatter, no -X undermine nudge (vx="
                            + fb.getVelocity().getX() + ")");
        }
    }

    @Test
    @DisplayName("Delayed (overloaded) collapses also backfill toward the batch origin void")
    void delayedBatchRubbleBacksFillTowardOrigin() {
        // Drive a delayed-collapse batch straight through the manager: the overloaded
        // path that BlockBreakListener hands off to. The batch origin is the dug void,
        // and the rubble for the whole batch must drift back toward it.
        DelayedCollapseManager dcm = new DelayedCollapseManager(
                plugin,
                plugin.getStructureManager(),
                effects,
                new CollapseEffects(effects, plugin),
                plugin.getCollapseGuard(),
                plugin.getTaskTimings());
        dcm.setRubbleHandler(rubbleHandler);

        effects.setCollapseDelayTicks(1); // due on the first run()
        effects.setMaxCollapsesPerTick(16);
        effects.setCrackingWarningsEnabled(false);

        // The dug void column is at x=0; the overloaded wall sits to the +X side.
        Location origin = new Location(world, 0, 65, 0);
        int batch = dcm.startBatch(world, origin, false);
        MaterialSpec spec = new MaterialSpec(2.0, 30.0, 1.5, 1.0);
        for (int x = 2; x <= 5; x++) {
            world.getBlockAt(x, 70, 0).setType(Material.STONE);
            dcm.scheduleCollapse(world, new CollapsedNode(new NodePos(x, 70, 0), spec), Material.STONE, batch);
        }

        dcm.run(); // elapsed 1 ≥ delay 1 → all four collapse, batch completes, rubble runs

        for (int x = 2; x <= 5; x++) {
            assertEquals(
                    Material.AIR,
                    world.getBlockAt(x, 70, 0).getType(),
                    "overloaded wall block collapsed (x=" + x + ")");
        }

        var rubble = world.getEntitiesByClass(FallingBlock.class);
        assertFalse(rubble.isEmpty(), "the delayed batch spawned rubble");
        for (FallingBlock fb : rubble) {
            // Every piece sat east of the void (x≥2), so the backfill nudge is -X and
            // overwhelms the +0.15-max scatter: no piece drifts the +X (away) way.
            assertTrue(
                    fb.getVelocity().getX() <= 1.0e-9,
                    "delayed-collapse rubble drifts back toward the batch-origin void (vx="
                            + fb.getVelocity().getX() + ")");
        }
    }
}
