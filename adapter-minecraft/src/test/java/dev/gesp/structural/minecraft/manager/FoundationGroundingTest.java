package dev.gesp.structural.minecraft.manager;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.config.FoundationConfig;
import dev.gesp.structural.minecraft.material.MaterialRegistry;
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
 * Foundation system: anchoring builds to NATURAL TERRAIN.
 *
 * <p>Covers depth-based grounding (a block with N solid terrain blocks straight
 * below it is grounded), the configured foundation block (anchors when set on
 * solid terrain), and the regression guard that the DEFAULT config preserves the
 * old behaviour — a build on deep dirt is NOT auto-grounded, only bedrock /
 * explicit ground is.
 */
@DisplayName("Foundation grounding: depth + foundation block, with legacy-preserving defaults")
class FoundationGroundingTest {

    private ServerMock server;
    private WorldMock world;
    private MaterialRegistry materials;
    private StructureManager manager;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("foundation-world");
        materials = new MaterialRegistry();
        manager = new StructureManager(materials);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Fill a vertical column of the world with one material, low (inclusive) to high (inclusive). */
    private void fillColumn(int x, int z, int loY, int hiY, Material type) {
        for (int y = loY; y <= hiY; y++) {
            world.getBlockAt(x, y, z).setType(type);
        }
    }

    private boolean isGroundedNode(int x, int y, int z) {
        StructureGraph graph = manager.getGraph(world);
        assertNotNull(graph, "world should have a graph after a placement");
        Node node = graph.getNode(new NodePos(x, y, z));
        return node != null && node.isGrounded();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  (a) DEPTH-BASED GROUNDING
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Depth grounding ON: a block on N solid terrain blocks is grounded and stands")
    void depthGroundingAnchorsOnDeepTerrain() {
        FoundationConfig config = new FoundationConfig();
        config.setDepthGroundingEnabled(true);
        config.setMinDepth(3);
        manager.setFoundationConfig(config);

        // Terrain column y=10..12 is dirt/stone; the tracked block sits at y=13.
        world.getBlockAt(5, 12, 5).setType(Material.STONE);
        world.getBlockAt(5, 11, 5).setType(Material.DIRT);
        world.getBlockAt(5, 10, 5).setType(Material.STONE);

        Block resting = world.getBlockAt(5, 13, 5);
        resting.setType(Material.OAK_PLANKS);
        manager.onBlockPlaced(resting);

        assertTrue(
                isGroundedNode(5, 13, 5),
                "a block with 3 solid natural-terrain blocks straight below should be grounded");
    }

    @Test
    @DisplayName("Depth grounding ON: a block on TOO FEW terrain blocks is NOT auto-grounded")
    void depthGroundingRejectsShallowTerrain() {
        FoundationConfig config = new FoundationConfig();
        config.setDepthGroundingEnabled(true);
        config.setMinDepth(4);
        manager.setFoundationConfig(config);

        // Only 2 terrain blocks below — short of the required 4.
        world.getBlockAt(5, 12, 5).setType(Material.STONE);
        world.getBlockAt(5, 11, 5).setType(Material.DIRT);

        Block resting = world.getBlockAt(5, 13, 5);
        resting.setType(Material.OAK_PLANKS);
        manager.onBlockPlaced(resting);

        assertFalse(isGroundedNode(5, 13, 5), "only 2 terrain blocks below (min-depth 4) must NOT ground the block");
    }

    @Test
    @DisplayName("Depth grounding ON: an AIR GAP in the column breaks the run (not grounded)")
    void depthGroundingRequiresUnbrokenColumn() {
        FoundationConfig config = new FoundationConfig();
        config.setDepthGroundingEnabled(true);
        config.setMinDepth(3);
        manager.setFoundationConfig(config);

        // y=12 solid, y=11 AIR (cave), y=10..8 solid. The run below the block is
        // only 1 deep before the air gap, so it must not ground.
        world.getBlockAt(5, 12, 5).setType(Material.STONE);
        // y=11 left as AIR
        world.getBlockAt(5, 10, 5).setType(Material.STONE);
        world.getBlockAt(5, 9, 5).setType(Material.STONE);
        world.getBlockAt(5, 8, 5).setType(Material.STONE);

        Block resting = world.getBlockAt(5, 13, 5);
        resting.setType(Material.OAK_PLANKS);
        manager.onBlockPlaced(resting);

        assertFalse(isGroundedNode(5, 13, 5), "an air gap must break the terrain column, leaving the block ungrounded");
    }

    @Test
    @DisplayName("Depth grounding ON: a PLAYER-BUILD column below (not terrain) does NOT ground")
    void depthGroundingIgnoresNonTerrainColumns() {
        FoundationConfig config = new FoundationConfig();
        config.setDepthGroundingEnabled(true);
        config.setMinDepth(3);
        manager.setFoundationConfig(config);

        // A solid but PLAYER-BUILD column (planks) — solid, yet not natural terrain.
        fillColumn(5, 5, 10, 12, Material.OAK_PLANKS);

        Block resting = world.getBlockAt(5, 13, 5);
        resting.setType(Material.OAK_PLANKS);
        manager.onBlockPlaced(resting);

        assertFalse(
                isGroundedNode(5, 13, 5), "planks are solid but not natural terrain, so they must not depth-ground");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  (b) FOUNDATION BLOCK
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Foundation block on solid terrain anchors the build")
    void foundationBlockOnTerrainAnchors() {
        FoundationConfig config = new FoundationConfig();
        config.setFoundationBlock(Material.BRICKS);
        manager.setFoundationConfig(config);

        // A single solid terrain block beneath the foundation block.
        world.getBlockAt(5, 12, 5).setType(Material.STONE);

        Block foundation = world.getBlockAt(5, 13, 5);
        foundation.setType(Material.BRICKS);
        manager.onBlockPlaced(foundation);

        assertTrue(isGroundedNode(5, 13, 5), "the configured foundation block on solid terrain must anchor");
    }

    @Test
    @DisplayName("Foundation block NOT on solid terrain (air below) does NOT anchor")
    void foundationBlockInAirDoesNotAnchor() {
        FoundationConfig config = new FoundationConfig();
        config.setFoundationBlock(Material.BRICKS);
        manager.setFoundationConfig(config);

        // No terrain below — the block at y=12 is air.
        Block foundation = world.getBlockAt(5, 13, 5);
        foundation.setType(Material.BRICKS);
        manager.onBlockPlaced(foundation);

        assertFalse(isGroundedNode(5, 13, 5), "a foundation block floating in air must not anchor");
    }

    @Test
    @DisplayName("A NON-foundation block on terrain is not anchored just by being the wrong material")
    void nonFoundationBlockOnTerrainNotAnchored() {
        FoundationConfig config = new FoundationConfig();
        config.setFoundationBlock(Material.BRICKS);
        // depth grounding stays OFF
        manager.setFoundationConfig(config);

        world.getBlockAt(5, 12, 5).setType(Material.STONE);

        Block notFoundation = world.getBlockAt(5, 13, 5);
        notFoundation.setType(Material.OAK_PLANKS); // not the configured foundation block
        manager.onBlockPlaced(notFoundation);

        assertFalse(
                isGroundedNode(5, 13, 5),
                "only the configured foundation material anchors; planks on terrain stay ungrounded");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  REGRESSION: DEFAULT CONFIG PRESERVES LEGACY BEHAVIOUR
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DEFAULT config: a block on deep dirt is NOT auto-grounded (legacy behaviour preserved)")
    void defaultConfigDoesNotGroundOnTerrain() {
        // No setFoundationConfig call — manager uses its default (depth off, no block).
        fillColumn(5, 5, 8, 12, Material.STONE);

        Block resting = world.getBlockAt(5, 13, 5);
        resting.setType(Material.OAK_PLANKS);
        manager.onBlockPlaced(resting);

        assertFalse(
                isGroundedNode(5, 13, 5),
                "with default config, deep terrain must NOT silently ground a build (no behaviour change)");
    }

    @Test
    @DisplayName("DEFAULT config: bedrock still grounds (explicit ground unaffected)")
    void defaultConfigStillGroundsBedrock() {
        Block bedrock = world.getBlockAt(5, 0, 5);
        bedrock.setType(Material.BEDROCK);
        manager.onBlockPlaced(bedrock);

        assertTrue(isGroundedNode(5, 0, 5), "bedrock must remain a ground anchor regardless of the foundation config");
    }

    @Test
    @DisplayName("Depth grounding lets a build STAND that would otherwise collapse as floating")
    void depthGroundedBuildSurvivesFloatingSweep() {
        FoundationConfig config = new FoundationConfig();
        config.setDepthGroundingEnabled(true);
        config.setMinDepth(3);
        manager.setFoundationConfig(config);

        fillColumn(7, 7, 10, 12, Material.STONE); // terrain
        Block base = world.getBlockAt(7, 13, 7);
        base.setType(Material.STONE);
        manager.onBlockPlaced(base);
        Block top = world.getBlockAt(7, 14, 7);
        top.setType(Material.STONE);
        manager.onBlockPlaced(top);

        // A floating sweep must NOT remove the grounded build.
        var collapsed = manager.refreshGroundAndCollapse(world);
        assertTrue(collapsed.isEmpty(), "a depth-grounded build must survive a floating sweep");
        assertTrue(isGroundedNode(7, 13, 7), "the base stays grounded");
    }
}
