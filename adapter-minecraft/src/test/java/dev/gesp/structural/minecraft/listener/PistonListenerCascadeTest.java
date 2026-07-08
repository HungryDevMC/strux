package dev.gesp.structural.minecraft.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.minecraft.StructuralIntegrityPlugin;
import dev.gesp.structural.minecraft.config.EffectsConfig;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.visual.CollapseEffects;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * A piston pulling the sole support out from under a loaded column must bring the
 * column down. This reproduces the "double removal → no-op cascade" bug: the old code
 * called {@code graph.removeBlock(oldPos)} BEFORE seeding the cascade from {@code oldPos},
 * so the dependent subgraph was empty and the column stood forever. The fix routes the
 * removal through {@link StructureManager#onBlockBroken} (which owns the trigger removal),
 * so the column cascades AND the settle runs through the budgeted, instrumented path.
 */
@DisplayName("Piston pulling a support cascades the loaded column (via the StructureManager break path)")
class PistonListenerCascadeTest {

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private WorldMock world;
    private PistonListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        world = server.addSimpleWorld("piston_cascade_world");

        EffectsConfig effects = new EffectsConfig();
        listener = new PistonListener(
                plugin,
                plugin.getStructureManager(),
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
     *   y=67: [C]        ← column top, reaches ground only through the support
     *   y=66: [C]        ← column
     *   y=65: [S]        ← the support (grounded via bedrock below)
     *   y=64: [bedrock]
     *   x:     0
     * </pre>
     *
     * A retract piston to the +X pulls the support from (0,65,0) to (1,65,0). The column
     * at (0,66,0)/(0,67,0) then has no path to ground and must collapse.
     */
    @Test
    @DisplayName("Retract pulling the support collapses the column above it")
    void pistonPullingSupportCollapsesColumn() {
        place(0, 64, 0, Material.BEDROCK);
        place(0, 65, 0, Material.STONE); // the support
        place(0, 66, 0, Material.STONE); // column
        place(0, 67, 0, Material.STONE); // column top

        StructureManager sm = plugin.getStructureManager();
        long removedBefore = sm.getMetrics().blocksRemoved;

        Block support = world.getBlockAt(0, 65, 0);
        Block piston = world.getBlockAt(2, 65, 0); // where the piston body sits (irrelevant to the graph)

        // Fire the retract event with the support still in place (as Bukkit does, pre-move).
        BlockPistonRetractEvent event = new BlockPistonRetractEvent(piston, List.of(support), BlockFace.EAST);
        listener.onPistonRetract(event);

        // Simulate vanilla applying the move: the support leaves (0,65,0) and lands at (1,65,0).
        world.getBlockAt(0, 65, 0).setType(Material.AIR);
        world.getBlockAt(1, 65, 0).setType(Material.STONE);

        // Run the 1-tick-delayed structural processing.
        server.getScheduler().performTicks(2);

        assertEquals(Material.AIR, world.getBlockAt(0, 66, 0).getType(), "column lost its support and collapsed");
        assertEquals(Material.AIR, world.getBlockAt(0, 67, 0).getType(), "column top collapsed too");

        // Routing proof: the cascade ran through StructureManager's engine, which shares the
        // StruxMetrics counter (the old private `new CascadeEngine` had no metrics wired, so
        // this counter would never move). This ties the fix to the budget/metrics brief.
        assertTrue(
                sm.getMetrics().blocksRemoved > removedBefore,
                "the piston cascade routed through the instrumented StructureManager break path");
    }
}
