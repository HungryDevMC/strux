package dev.gesp.structural.minecraft.listener;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.StructuralIntegrityPlugin;
import dev.gesp.structural.model.NodePos;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

@DisplayName("BlockPlaceListener runs at HIGHEST, so a later handler's cancellation is honored")
class BlockPlaceListenerCancellationTest {

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private WorldMock world;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        world = server.addSimpleWorld("place_cancel_world");
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("a normal placement is tracked, but one a later handler cancels is not")
    void cancelledPlacementIsNotTracked() {
        StructureGraph graph = plugin.getStructureManager().getOrCreateGraph(world);
        // Grounded base so a block placed directly on top is stable (won't insta-collapse).
        graph.addGroundBlock(new NodePos(0, 64, 0));
        world.getBlockAt(0, 64, 0).setType(Material.STONE);

        // 1. An uncancelled placement on the base → strux tracks it.
        Block first = world.getBlockAt(0, 65, 0);
        first.setType(Material.STONE);
        server.getPluginManager().callEvent(placeEvent(first));
        assertTrue(graph.hasBlock(new NodePos(0, 65, 0)), "strux tracks a normal placement");

        // 2. A higher-priority (HIGH) handler cancels the next placement. strux now runs at
        // HIGHEST with ignoreCancelled, so it must NOT track the cancelled block (before the
        // fix it ran at HIGH and could register before the cancellation landed).
        server.getPluginManager()
                .registerEvents(
                        new Listener() {
                            @EventHandler(priority = EventPriority.HIGH)
                            public void cancel(BlockPlaceEvent event) {
                                event.setCancelled(true);
                            }
                        },
                        plugin);

        Block second = world.getBlockAt(0, 66, 0);
        second.setType(Material.STONE);
        server.getPluginManager().callEvent(placeEvent(second));
        assertFalse(graph.hasBlock(new NodePos(0, 66, 0)), "strux skips a placement a later handler cancelled");
    }

    private BlockPlaceEvent placeEvent(Block placed) {
        Block against = world.getBlockAt(placed.getX(), placed.getY() - 1, placed.getZ());
        return new BlockPlaceEvent(placed, placed.getState(), against, new ItemStack(Material.STONE), player, true);
    }
}
