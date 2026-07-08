package dev.gesp.structural.minecraft.visual;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.minecraft.StructuralIntegrityPlugin;
import dev.gesp.structural.model.NodePos;
import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

@DisplayName("StressVisualizer: only emit FX a player can perceive, without force-loading chunks")
class StressVisualizerTest {

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private StressVisualizer visualizer;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        visualizer = new StressVisualizer(
                plugin.getStructureManager(), plugin, null, plugin.getEffectsConfig(), plugin.getTaskTimings());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("shouldEmit requires a loaded chunk AND a player within 48 blocks")
    void shouldEmitGatesOnChunkAndViewer() {
        WorldMock world = server.addSimpleWorld("viz");
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(world, 10, 64, 10)); // chunk (0,0)

        NodePos near = new NodePos(20, 64, 10); // chunk (1,0), ~10 blocks from the player
        NodePos far = new NodePos(200, 64, 10); // chunk (12,0), ~190 blocks away

        // Chunk not loaded yet: skip, even though the player is close — never force-load
        // a chunk just to play particles.
        assertFalse(visualizer.shouldEmit(world, near), "an unloaded chunk is skipped");

        world.loadChunk(1, 0);
        assertTrue(visualizer.shouldEmit(world, near), "loaded chunk + a player within 48 blocks → emit");

        world.loadChunk(12, 0);
        assertFalse(visualizer.shouldEmit(world, far), "loaded chunk but no player within 48 blocks → skip");
    }
}
