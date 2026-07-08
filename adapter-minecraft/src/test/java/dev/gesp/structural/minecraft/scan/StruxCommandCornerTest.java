package dev.gesp.structural.minecraft.scan;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.minecraft.StructuralIntegrityPlugin;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

@DisplayName("StruxCommand: wand corner lifecycle (quit cleanup + unloaded-world scan)")
class StruxCommandCornerTest {

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private StruxCommand command;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        command = (StruxCommand) plugin.getCommand("strux").getExecutor();
        world = server.addSimpleWorld("corner_world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("a player's wand corners are dropped when they log out (no unbounded leak)")
    void cornersClearedOnQuit() throws Exception {
        PlayerMock player = server.addPlayer();
        UUID id = player.getUniqueId();
        corners("corner1").put(id, new Location(world, 1, 64, 1));
        corners("corner2").put(id, new Location(world, 5, 64, 5));

        player.disconnect(); // fires PlayerQuitEvent through the registered listener

        assertFalse(corners("corner1").containsKey(id), "corner1 cleared on quit");
        assertFalse(corners("corner2").containsKey(id), "corner2 cleared on quit");
    }

    @Test
    @DisplayName("/strux scan fails gracefully (no crash) when a corner's world has been unloaded")
    void scanGracefulOnUnloadedCornerWorld() throws Exception {
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(world, 0, 64, 0));
        // A corner whose world was unloaded: Bukkit's Location.getWorld() throws rather than
        // returning null. Model that with a Location that throws on getWorld().
        Location unloaded = new Location(world, 0, 64, 0) {
            @Override
            public World getWorld() {
                throw new IllegalArgumentException("World unloaded");
            }
        };
        corners("corner1").put(player.getUniqueId(), unloaded);
        corners("corner2").put(player.getUniqueId(), unloaded);

        assertDoesNotThrow(
                () -> command.onCommand(player, plugin.getCommand("strux"), "strux", new String[] {"scan"}),
                "scan must not propagate the unloaded-world exception");
        assertTrue(
                drainFor(player, "no longer loaded"),
                "the player is told their selection's world is gone, not given a stack trace");
    }

    @Test
    @DisplayName("/strux scan with valid corners in a loaded world scans the selection")
    void scanWithValidCornersSucceeds() throws Exception {
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(world, 0, 64, 0));
        world.getBlockAt(0, 64, 0).setType(Material.STONE);
        world.getBlockAt(1, 64, 0).setType(Material.STONE);
        corners("corner1").put(player.getUniqueId(), new Location(world, 0, 64, 0));
        corners("corner2").put(player.getUniqueId(), new Location(world, 1, 64, 0));

        command.onCommand(player, plugin.getCommand("strux"), "strux", new String[] {"scan"});

        assertTrue(
                drainFor(player, "Scanned"), "a valid same-world selection scans (no 'unloaded'/'same world' error)");
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, Location> corners(String field) throws Exception {
        Field f = StruxCommand.class.getDeclaredField(field);
        f.setAccessible(true);
        return (Map<UUID, Location>) f.get(command);
    }

    private static boolean drainFor(PlayerMock player, String needle) {
        String msg;
        while ((msg = player.nextMessage()) != null) {
            if (msg.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
