package dev.gesp.structural.minecraft.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.config.MemoryEvictionConfig;
import dev.gesp.structural.minecraft.manager.ChunkEvictionManager;
import dev.gesp.structural.minecraft.manager.ComponentEvictor;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.material.MaterialRegistry;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/** The Bukkit event bridge forwards chunk load/unload to the eviction manager. */
@DisplayName("ChunkEvictionListener bridges chunk events to the eviction manager")
final class ChunkEvictionListenerTest {

    private ServerMock server;
    private World world;
    private StructureManager manager;
    private ChunkEvictionManager evictionManager;
    private ChunkEvictionListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("w");
        manager = new StructureManager(new MaterialRegistry());
        MemoryEvictionConfig config = new MemoryEvictionConfig();
        config.setEnabled(true);
        config.setGraceTicks(0);
        evictionManager = new ChunkEvictionManager(manager, new ComponentEvictor(), config, null);
        manager.setResidencyGuard(evictionManager::ensureResident);
        listener = new ChunkEvictionListener(evictionManager);

        StructureGraph g = manager.getOrCreateGraph(world);
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= 5; y++) {
            g.addBlock(new NodePos(0, y, 0), new MaterialSpec(2.0, 30.0), false);
        }
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void unloadEventEvictsAndLoadEventRestores() {
        Chunk chunk = world.getChunkAt(0, 0);
        listener.onChunkLoad(new ChunkLoadEvent(chunk, false));

        listener.onChunkUnload(new ChunkUnloadEvent(chunk));
        assertEquals(0, manager.getGraph(world).size(), "unload event evicted the component");

        listener.onChunkLoad(new ChunkLoadEvent(chunk, false));
        assertEquals(6, manager.getGraph(world).size(), "load event restored the component");
    }
}
