package dev.gesp.structural.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.minecraft.listener.BlastProcessor;
import dev.gesp.structural.minecraft.manager.StructureManager;
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
 * {@link StructureManager#blast} lets callers (e.g. siege's trebuchet) route a custom
 * explosion through the SAME budgeted blast model as TNT, instead of removing blocks
 * directly. The plugin wires the {@link BlastProcessor} via {@code setBlastProcessor}.
 */
@DisplayName("StructureManager.blast — route a custom explosion through the blast model")
class StructureManagerBlastTest {

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

    private void place(int x, int y, int z, Material material) {
        Block b = world.getBlockAt(x, y, z);
        b.setType(material);
        plugin.getStructureManager().onBlockPlaced(b);
    }

    @Test
    @DisplayName("queues a blast (not an inline settle) when the world has tracked structure")
    void enqueuesThroughTheModel() {
        StructureManager manager = plugin.getStructureManager();
        BlastProcessor processor = plugin.getBlastProcessor();
        place(0, 64, 0, Material.BEDROCK);
        place(0, 65, 0, Material.STONE);
        assertEquals(0, processor.queueSize(), "nothing queued before the blast");

        boolean queued = manager.blast(world, new Location(world, 0, 65, 0), 6.0);

        assertTrue(queued, "blast() reports it queued the explosion");
        assertEquals(1, processor.queueSize(), "the blast is queued for the budgeted processor, not settled inline");
        assertEquals(Material.STONE, world.getBlockAt(0, 65, 0).getType(), "no inline destruction at call time");

        // Drain it — the model applies the crater/cascade a later tick under budget.
        server.getScheduler().performTicks(20);
        assertEquals(0, processor.queueSize(), "the queued blast drains on later ticks");
    }

    @Test
    @DisplayName("no-op (returns false) when the world has no tracked structure")
    void noOpWithoutStructure() {
        StructureManager manager = plugin.getStructureManager();
        BlastProcessor processor = plugin.getBlastProcessor();

        boolean queued = manager.blast(world, new Location(world, 5, 65, 5), 6.0);

        assertFalse(queued, "an empty world has nothing to blast");
        assertEquals(0, processor.queueSize(), "nothing queued");
    }

    @Test
    @DisplayName("no-op (returns false) for non-positive power")
    void noOpForNonPositivePower() {
        StructureManager manager = plugin.getStructureManager();
        place(0, 64, 0, Material.BEDROCK);
        place(0, 65, 0, Material.STONE);

        assertFalse(manager.blast(world, new Location(world, 0, 65, 0), 0.0), "zero power is a no-op");
        assertEquals(0, plugin.getBlastProcessor().queueSize(), "nothing queued");
    }
}
