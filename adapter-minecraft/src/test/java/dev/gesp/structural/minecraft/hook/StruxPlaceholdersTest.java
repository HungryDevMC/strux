package dev.gesp.structural.minecraft.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.minecraft.StructuralIntegrityPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * PlaceholderAPI resolves placeholders on whatever thread the consumer plugin uses —
 * routinely an async chat / scoreboard / TAB thread. {@link StruxPlaceholders} must
 * therefore NEVER solve the live world graph off the main thread (the solver and the
 * graph are main-thread-only); off-main it can only serve the last cached report.
 */
@DisplayName("StruxPlaceholders is safe to resolve off the main thread")
class StruxPlaceholdersTest {

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        world = server.addSimpleWorld("ph_world");
        // A grounded bedrock foot carrying two load-bearing stone blocks → two
        // assessed (non-ground) nodes, so a real solve reports tracked >= 2.
        place(0, 64, 0, Material.BEDROCK);
        place(0, 65, 0, Material.STONE);
        place(0, 66, 0, Material.STONE);
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

    private PlayerMock playerInWorld() {
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(world, 0, 70, 0));
        return player;
    }

    @Test
    @DisplayName("off the main thread with a cold cache it returns the empty report and never solves")
    void offMainColdCacheNeverSolves() throws InterruptedException {
        StruxPlaceholders placeholders = new StruxPlaceholders(plugin, plugin.getStructureManager());
        PlayerMock player = playerInWorld();

        // Off the main thread, cache cold: the guard returns empty() (tracked = 0),
        // never touching the live graph or the shared, stateful solver.
        String[] offMain = new String[1];
        Thread t = new Thread(() -> offMain[0] = placeholders.onRequest(player, "tracked"));
        t.start();
        t.join();
        assertEquals("0", offMain[0], "off-main with a cold cache must return the empty report, not solve");

        // On the main thread the SAME request solves and counts the tracked nodes.
        String onMain = placeholders.onRequest(player, "tracked");
        assertNotEquals("0", onMain, "on-main solves the live graph");
        assertTrue(Integer.parseInt(onMain) >= 2, "the two stone blocks above bedrock are load-bearing: " + onMain);
    }

    @Test
    @DisplayName("off the main thread it serves the last cached report instead of re-solving")
    void offMainServesCachedReport() throws InterruptedException {
        StruxPlaceholders placeholders = new StruxPlaceholders(plugin, plugin.getStructureManager());
        PlayerMock player = playerInWorld();

        // Warm the cache on the main thread.
        String onMain = placeholders.onRequest(player, "tracked");
        assertNotEquals("0", onMain);

        // Within the TTL, an off-main request returns the SAME cached value.
        String[] offMain = new String[1];
        Thread t = new Thread(() -> offMain[0] = placeholders.onRequest(player, "tracked"));
        t.start();
        t.join();
        assertEquals(onMain, offMain[0], "off-main serves the cached report, not a fresh solve");
    }
}
