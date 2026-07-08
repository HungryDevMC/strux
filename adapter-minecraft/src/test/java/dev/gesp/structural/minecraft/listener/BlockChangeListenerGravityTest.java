package dev.gesp.structural.minecraft.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.minecraft.StructuralIntegrityPlugin;
import dev.gesp.structural.minecraft.config.EffectsConfig;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.visual.CollapseEffects;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * When a gravity block (sand/gravel) starts to fall, anything resting on it must cascade —
 * not silently strand in the graph. This reproduces the bug where {@code onBlockPhysics}
 * did a bare {@code graph.removeBlock(pos)} with no settle, leaving a stone wall on a gravel
 * plug floating forever. The fix routes the delayed removal through the same
 * {@link BlockChangeListener}{@code .processBlockRemoval} path every sibling event (burn,
 * fade, enderman) uses, so the dependent block floats/collapses through the budgeted,
 * instrumented cascade.
 */
@DisplayName("A gravity block falling cascades whatever rested on it (via processBlockRemoval)")
class BlockChangeListenerGravityTest {

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private WorldMock world;
    private BlockChangeListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        world = server.addSimpleWorld("gravity_cascade_world");

        EffectsConfig effects = new EffectsConfig();
        listener = new BlockChangeListener(
                plugin,
                plugin.getStructureManager(),
                new PhysicsConfig(),
                plugin.getDelayedCollapseManager(),
                plugin.getCascadeResumeManager(),
                new CollapseEffects(effects, plugin),
                plugin.getCollapseGuard());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void place(int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(material);
        plugin.getStructureManager().onBlockPlaced(block);
    }

    /**
     * <pre>
     *   y=66: [W]           ← stone wall, reaches ground only through the gravel plug
     *   y=65: [G][bedrock]  ← gravel grounded via the bedrock beside it; AIR below it → it falls
     *   y=64: [.]
     *   x:     0  1
     * </pre>
     *
     * The gravel at (0,65,0) starts to fall (air below). In vanilla the stone wall on top
     * doesn't fall on its own — so without a cascade it strands. The fix must collapse it.
     */
    @Test
    @DisplayName("Gravel falling from under a wall collapses the stranded wall")
    void gravelFallCollapsesWallAbove() {
        place(1, 65, 0, Material.BEDROCK); // horizontal ground anchor beside the gravel
        place(0, 65, 0, Material.GRAVEL); // the plug: grounded via bedrock neighbor, AIR below
        place(0, 66, 0, Material.STONE); // wall resting on the gravel — only path to ground

        StructureManager sm = plugin.getStructureManager();
        long removedBefore = sm.getMetrics().blocksRemoved;

        Block gravel = world.getBlockAt(0, 65, 0);

        // Fire the physics event while the gravel is still there and air is below it.
        BlockPhysicsEvent event = new BlockPhysicsEvent(gravel, gravel.getBlockData());
        listener.onBlockPhysics(event);

        // Simulate vanilla turning the falling gravel into a FallingBlock entity: it becomes air.
        world.getBlockAt(0, 65, 0).setType(Material.AIR);

        // Run the 1-tick-delayed structural processing.
        server.getScheduler().performTicks(2);

        assertEquals(
                Material.AIR,
                world.getBlockAt(0, 66, 0).getType(),
                "the stone wall lost its gravel support and collapsed");

        // Routing proof: it went through onBlockBroken's shared StruxMetrics counter, not a
        // silent graph.removeBlock (which would never move this counter).
        assertTrue(
                sm.getMetrics().blocksRemoved > removedBefore,
                "the gravity removal routed through the instrumented processBlockRemoval path");
    }
}
