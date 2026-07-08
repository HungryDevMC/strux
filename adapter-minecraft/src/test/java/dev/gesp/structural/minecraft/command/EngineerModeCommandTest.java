package dev.gesp.structural.minecraft.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.minecraft.StructuralIntegrityPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

@DisplayName("EngineerModeCommand: logout clears the enabled flag")
class EngineerModeCommandTest {

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private EngineerModeCommand command;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        command = plugin.getEngineerModeCommand();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("a player without structuralintegrity.engineer cannot enable engineer mode")
    void deniedWithoutPermission() {
        PlayerMock player = server.addPlayer();
        player.setOp(false);
        // The permission defaults to true in plugin.yml, so pin it false to model an admin
        // who revoked it for a group.
        player.addAttachment(plugin, EngineerModeCommand.USE_PERMISSION, false);

        boolean handled = command.onCommand(player, plugin.getCommand("engineer"), "engineer", new String[0]);

        assertTrue(handled, "the command is handled (Bukkit shows no usage text)");
        assertFalse(command.isEnabled(player), "engineer mode is not enabled without permission");
        assertTrue(player.nextMessage().contains("permission"), "a denial message is shown");
    }

    @Test
    @DisplayName("logging out while enabled clears the flag, so re-enable is not a silent no-op")
    void logoutClearsEnabledFlag() {
        PlayerMock player = server.addPlayer();
        command.setEnabled(player, true);
        assertTrue(command.isEnabled(player), "engineer mode is on");

        // Log out while enabled: the per-player task self-cancels on its next run and must
        // clear the enabled flag too — otherwise a stale entry makes setEnabled(true) no-op
        // after relog, /engineer take the disable branch, and isEnabled() report true.
        player.disconnect();
        server.getScheduler().performTicks(20L); // let the per-player task run once and self-cancel

        assertFalse(command.isEnabled(player), "logout cleared the enabled flag");
    }
}
