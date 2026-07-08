package dev.gesp.structural.minecraft.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.config.MemoryEvictionConfig;
import dev.gesp.structural.minecraft.material.MaterialRegistry;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * The chunk bookkeeping that decides when a dormant component is evicted and restored.
 * Drives {@link ChunkEvictionManager} directly (no Bukkit scheduler): the component
 * granularity invariant (whole component or nothing), the straddle guard, the churn guard
 * and the master switch.
 */
@DisplayName("ChunkEvictionManager evicts whole dormant components and restores on load")
final class ChunkEvictionManagerTest {

    private static final MaterialSpec STONE = new MaterialSpec(2.0, 30.0);

    private ServerMock server;
    private World world;
    private StructureManager manager;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("w");
        manager = new StructureManager(new MaterialRegistry());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private ChunkEvictionManager managerWith(boolean enabled) {
        MemoryEvictionConfig config = new MemoryEvictionConfig();
        config.setEnabled(enabled);
        config.setGraceTicks(0);
        ChunkEvictionManager evictionManager = new ChunkEvictionManager(manager, new ComponentEvictor(), config, null);
        manager.setResidencyGuard(evictionManager::ensureResident);
        return evictionManager;
    }

    /** A 6-node tower entirely inside chunk column (0,0). */
    private void buildTowerInColumn0() {
        StructureGraph g = manager.getOrCreateGraph(world);
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= 5; y++) {
            g.addBlock(new NodePos(0, y, 0), STONE, false);
        }
    }

    @Test
    void unloadingEveryChunk_evictsTheWholeComponent() {
        buildTowerInColumn0();
        ChunkEvictionManager ev = managerWith(true);
        ev.onChunkLoad(world, 0, 0);
        assertEquals(6, manager.getGraph(world).size());

        ev.onChunkUnload(world, 0, 0); // grace 0 → evict immediately

        assertEquals(0, manager.getGraph(world).size(), "component removed from live graph");
        assertEquals(1, ev.parkedComponentCount(), "parked in a sidecar");
    }

    @Test
    void reloadingAChunk_rematerializesTheComponent() {
        buildTowerInColumn0();
        ChunkEvictionManager ev = managerWith(true);
        ev.onChunkLoad(world, 0, 0);
        ev.onChunkUnload(world, 0, 0);
        assertEquals(0, manager.getGraph(world).size());

        ev.onChunkLoad(world, 0, 0);

        assertEquals(6, manager.getGraph(world).size(), "restored on reload");
        assertEquals(0, ev.parkedComponentCount());
        assertTrue(manager.getGraph(world).hasBlock(new NodePos(0, 3, 0)));
    }

    @Test
    void componentStraddlingALoadedChunk_staysFullyResident() {
        // A bridge spanning columns (0,0) and (1,0): x = 0..20 at y=1, grounded at x=0.
        StructureGraph g = manager.getOrCreateGraph(world);
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int x = 0; x <= 20; x++) {
            g.addBlock(new NodePos(x, 1, 0), STONE, false);
        }
        int before = g.size();
        ChunkEvictionManager ev = managerWith(true);
        ev.onChunkLoad(world, 0, 0);
        ev.onChunkLoad(world, 1, 0); // both columns loaded

        ev.onChunkUnload(world, 0, 0); // (1,0) still loaded → must not evict

        assertEquals(before, manager.getGraph(world).size(), "straddling component stays resident");
        assertEquals(0, ev.parkedComponentCount());
    }

    @Test
    void chunkReloadedDuringGrace_doesNotEvict() {
        buildTowerInColumn0();
        ChunkEvictionManager ev = managerWith(true);
        ev.onChunkLoad(world, 0, 0); // reloaded (still loaded)

        ev.runGraceCheck(world, 0, 0); // grace fires but column is loaded again

        assertEquals(6, manager.getGraph(world).size(), "loaded column is never evicted");
        assertEquals(0, ev.parkedComponentCount());
    }

    @Test
    void disabled_neverEvicts() {
        buildTowerInColumn0();
        ChunkEvictionManager ev = managerWith(false);
        ev.onChunkLoad(world, 0, 0);

        ev.onChunkUnload(world, 0, 0);

        assertEquals(6, manager.getGraph(world).size(), "nothing evicted when disabled");
        assertEquals(0, ev.parkedComponentCount());
    }

    @Test
    void breakingAnEvictedPosition_rematerializesViaResidencyGuard() {
        buildTowerInColumn0();
        ChunkEvictionManager ev = managerWith(true);
        ev.onChunkLoad(world, 0, 0);
        ev.onChunkUnload(world, 0, 0);
        assertEquals(0, manager.getGraph(world).size(), "parked");

        // A block op addressing the parked column must restore it first (defensive guard).
        manager.onBlockBroken(world.getBlockAt(0, 1, 0));

        assertEquals(0, ev.parkedComponentCount(), "guard re-materialized the component");
        assertTrue(manager.getGraph(world).size() > 0, "structure is live again");
    }

    @Test
    void placingIntoAnEvictedColumn_rematerializesViaResidencyGuard() {
        buildTowerInColumn0();
        ChunkEvictionManager ev = managerWith(true);
        ev.onChunkLoad(world, 0, 0);
        ev.onChunkUnload(world, 0, 0);
        assertEquals(1, ev.parkedComponentCount(), "sanity: parked one");

        org.bukkit.block.Block block = world.getBlockAt(0, 6, 0);
        block.setType(org.bukkit.Material.STONE);
        manager.onBlockPlaced(block);

        assertEquals(0, ev.parkedComponentCount(), "guard re-materialized before the place");
        assertTrue(manager.getGraph(world).hasBlock(new NodePos(0, 3, 0)), "restored tower present");
    }

    @Test
    void thermallySoftenedComponent_isNotEvicted() {
        buildTowerInColumn0();
        manager.getGraph(world).getNode(new NodePos(0, 2, 0)).setTemperatureCapacityFactor(0.5);
        ChunkEvictionManager ev = managerWith(true);
        ev.onChunkLoad(world, 0, 0);

        ev.onChunkUnload(world, 0, 0);

        assertEquals(6, manager.getGraph(world).size(), "softened component refuses the lossy round-trip");
        assertEquals(0, ev.parkedComponentCount());
    }
}
