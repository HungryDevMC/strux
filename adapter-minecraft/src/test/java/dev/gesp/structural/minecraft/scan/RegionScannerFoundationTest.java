package dev.gesp.structural.minecraft.scan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.config.FoundationConfig;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.material.MaterialRegistry;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * The {@link RegionScanner} honours the foundation policy when retroactively
 * registering an existing build: depth-grounded blocks and foundation blocks on
 * terrain become ground anchors, and the DEFAULT config leaves the legacy
 * "only ground material anchors" behaviour intact.
 */
@DisplayName("RegionScanner: foundation grounding on scanned builds")
class RegionScannerFoundationTest {

    private ServerMock server;
    private WorldMock world;
    private MaterialRegistry materials;
    private StructureManager manager;
    private PhysicsConfig physics;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("scan-world");
        materials = new MaterialRegistry();
        physics = new PhysicsConfig();
        manager = new StructureManager(materials, physics);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private boolean isGroundedNode(int x, int y, int z) {
        StructureGraph graph = manager.getGraph(world);
        assertNotNull(graph, "scan should have created a graph");
        Node node = graph.getNode(new NodePos(x, y, z));
        return node != null && node.isGrounded();
    }

    @Test
    @DisplayName("Depth grounding ON: a scanned block over deep terrain becomes a ground anchor")
    void scanDepthGroundsOverTerrain() {
        FoundationConfig config = new FoundationConfig();
        config.setDepthGroundingEnabled(true);
        config.setMinDepth(3);
        manager.setFoundationConfig(config);

        // Terrain y=10..12; the scanned build block at y=13.
        world.getBlockAt(2, 12, 2).setType(Material.STONE);
        world.getBlockAt(2, 11, 2).setType(Material.STONE);
        world.getBlockAt(2, 10, 2).setType(Material.STONE);
        world.getBlockAt(2, 13, 2).setType(Material.OAK_PLANKS);

        RegionScanner scanner = new RegionScanner(manager, materials, physics);
        // Scan only the build block (terrain is outside the box, below it).
        RegionScanner.Result result = scanner.scan(world, 2, 13, 2, 2, 13, 2);

        assertFalse(result.tooLarge());
        assertTrue(isGroundedNode(2, 13, 2), "depth grounding must anchor the scanned block resting on terrain");
    }

    @Test
    @DisplayName("Foundation block ON terrain in a scan becomes a ground anchor")
    void scanFoundationBlockOnTerrain() {
        FoundationConfig config = new FoundationConfig();
        config.setFoundationBlock(Material.BRICKS);
        manager.setFoundationConfig(config);

        world.getBlockAt(2, 12, 2).setType(Material.STONE);
        world.getBlockAt(2, 13, 2).setType(Material.BRICKS);

        RegionScanner scanner = new RegionScanner(manager, materials, physics);
        RegionScanner.Result result = scanner.scan(world, 2, 13, 2, 2, 13, 2);

        assertFalse(result.tooLarge());
        assertTrue(isGroundedNode(2, 13, 2), "a scanned foundation block on terrain must anchor");
    }

    @Test
    @DisplayName("DEFAULT config: a scanned block over terrain is NOT auto-grounded by depth")
    void scanDefaultDoesNotDepthGround() {
        // No setFoundationConfig — default policy (depth off, no block).
        world.getBlockAt(2, 12, 2).setType(Material.STONE);
        world.getBlockAt(2, 11, 2).setType(Material.STONE);
        world.getBlockAt(2, 10, 2).setType(Material.STONE);
        world.getBlockAt(2, 13, 2).setType(Material.OAK_PLANKS);

        RegionScanner scanner = new RegionScanner(manager, materials, physics);
        scanner.scan(world, 2, 13, 2, 2, 13, 2);

        // Legacy scan still anchors to the terrain BELOW via pass 2 (the block
        // directly under the build), but the SCANNED block itself stays a normal
        // load-bearing node — it is not turned into ground by depth.
        assertFalse(isGroundedNode(2, 13, 2), "default config must not depth-ground the scanned block itself");
    }
}
