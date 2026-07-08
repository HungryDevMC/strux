package dev.gesp.structural.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
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
 * Verifies that placing a block at a damaged position clears the damage.
 * This is critical for CoreProtect rollback integration: when CoreProtect
 * restores a block, the Bukkit BlockPlaceEvent fires, and any damage from
 * before the rollback should be cleared since it's a "fresh" block.
 */
@DisplayName("StructureManager: block placement clears existing damage")
class StructureManagerRepairOnPlaceTest {

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        world = server.addSimpleWorld("repair_world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void place(int x, int y, int z, Material material) {
        Block b = world.getBlockAt(x, y, z);
        b.setType(material);
        plugin.getStructureManager().onBlockPlaced(b);
    }

    @Test
    @DisplayName("placing at a damaged position clears the damage (CoreProtect rollback)")
    void placingClearsDamage() {
        StructureManager manager = plugin.getStructureManager();
        // Build a simple structure
        place(0, 64, 0, Material.BEDROCK);
        place(0, 65, 0, Material.STONE);

        // Simulate damage (e.g., from an explosion that didn't destroy the block)
        StructureGraph graph = manager.getGraph(world);
        NodePos damagedPos = new NodePos(0, 65, 0);
        Node node = graph.getNode(damagedPos);
        node.addDamage(0.75); // 75% damaged
        assertEquals(0.75, node.damage(), 0.001, "damage was applied");

        // Now "place" the same block again (simulates CoreProtect rollback or
        // player placing a block at the same position). The block type is already
        // STONE so this is really just triggering the placement logic.
        Block block = world.getBlockAt(0, 65, 0);
        manager.onBlockPlaced(block);

        // Damage should be cleared
        Node afterPlace = graph.getNode(damagedPos);
        assertEquals(0.0, afterPlace.damage(), 0.001, "damage should be cleared after placement");
    }

    @Test
    @DisplayName("placing at an undamaged position works normally")
    void placingAtUndamagedPositionWorks() {
        StructureManager manager = plugin.getStructureManager();
        place(0, 64, 0, Material.BEDROCK);
        place(0, 65, 0, Material.STONE);

        StructureGraph graph = manager.getGraph(world);
        NodePos pos = new NodePos(0, 65, 0);
        Node node = graph.getNode(pos);
        assertEquals(0.0, node.damage(), 0.001, "no damage initially");

        // Place again (no-op for the node itself, but should not fail)
        Block block = world.getBlockAt(0, 65, 0);
        manager.onBlockPlaced(block);

        Node afterPlace = graph.getNode(pos);
        assertEquals(0.0, afterPlace.damage(), 0.001, "still no damage");
    }

    @Test
    @DisplayName("placing at a new position adds the node")
    void placingAtNewPositionAddsNode() {
        StructureManager manager = plugin.getStructureManager();
        place(0, 64, 0, Material.BEDROCK);

        StructureGraph graph = manager.getGraph(world);
        NodePos newPos = new NodePos(0, 65, 0);
        assertTrue(graph.getNode(newPos) == null || !graph.hasBlock(newPos), "position is initially empty");

        place(0, 65, 0, Material.STONE);

        assertTrue(graph.hasBlock(newPos), "node was added");
        assertEquals(0.0, graph.getNode(newPos).damage(), 0.001, "new node has no damage");
    }
}
