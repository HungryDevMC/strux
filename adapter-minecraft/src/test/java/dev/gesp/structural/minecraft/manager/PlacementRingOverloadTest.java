package dev.gesp.structural.minecraft.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.material.MaterialRegistry;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.StressSolver;
import java.util.List;
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
 * Live-bug regression: placing a single block against a large connected curtain-wall
 * ring must not collapse wall members. The scoped placement-overload solve used to
 * misread the ring's excluded lateral members as phantom load and crush wall a
 * whole-structure solve (and the green stress particles) said was fine.
 */
@DisplayName("Placing a block on a stable curtain-wall ring collapses nothing")
class PlacementRingOverloadTest {

    private ServerMock server;
    private WorldMock world;
    private MaterialRegistry materials;
    private StructureManager manager;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("ring-world");
        materials = new MaterialRegistry();
        manager = new StructureManager(materials);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Register a standing block directly (no solver pass) — models the pre-built keep. */
    private void wall(int x, int y, int z) {
        Block b = world.getBlockAt(x, y, z);
        b.setType(Material.STONE);
        manager.addBlockDirect(b);
    }

    @Test
    @DisplayName("a TNT block placed on top of a 20x20x8 stone ring breaks no wall blocks")
    void placingOnRingDoesNotCollapseWall() {
        int x0 = 0, x1 = 19, z0 = 0, z1 = 19;
        // bedrock footprint (ground anchors)
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                world.getBlockAt(x, 0, z).setType(Material.BEDROCK);
            }
        }
        // closed crenel-less curtain-wall ring, y=1..8
        for (int y = 1; y <= 8; y++) {
            for (int x = x0; x <= x1; x++) {
                wall(x, y, z0);
                wall(x, y, z1);
            }
            for (int z = z0 + 1; z < z1; z++) {
                wall(x0, y, z);
                wall(x1, y, z);
            }
        }
        StructureGraph graph = manager.getGraph(world);
        int wallSize = graph.size();

        // The whole structure is comfortably stable — this is what the stress particles show.
        StressSolver solver = new StressSolver(new PhysicsConfig());
        solver.solveAll(graph);
        for (Node n : graph.getAllNodes()) {
            assertFalse(n.isOverloaded(), "the standing ring must be stable everywhere");
        }

        // Place a TNT block on top of the west wall — the reported live action.
        Block tnt = world.getBlockAt(x0, 9, 10);
        tnt.setType(Material.TNT);
        List<NodePos> collapsed = manager.onBlockPlaced(tnt);

        assertTrue(collapsed.isEmpty(), "placing one block on a stable ring must collapse nothing, got " + collapsed);
        // The wall is intact: every original ring node is still tracked (+ the new TNT node).
        assertEquals(wallSize + 1, graph.size(), "no wall block may be removed by the placement");
    }
}
