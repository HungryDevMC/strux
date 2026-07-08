package dev.gesp.structural.minecraft.recording;

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

@DisplayName("RecordingCommand: destructive subcommands require admin")
class RecordingCommandPermissionTest {

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private RecordingCommand command;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        MinecraftEventRecorder recorder =
                (MinecraftEventRecorder) plugin.getStructureManager().getEventRecorder();
        command = new RecordingCommand(plugin, recorder, plugin.getRecordingService());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("a non-admin cannot stop/start recordings, but can read status")
    void destructiveSubcommandsGatedByAdmin() {
        PlayerMock player = server.addPlayer();
        player.setOp(false); // no structuralintegrity.admin

        // Destructive: denied (and the recorder is never touched — gate returns first).
        assertTrue(command.handle(player, new String[] {"stop"}));
        assertTrue(player.nextMessage().contains("permission"), "stop is blocked for a non-admin");

        // Read-only: allowed (status prints recording info, no permission message).
        assertTrue(command.handle(player, new String[] {"status"}));
        String statusMsg = player.nextMessage();
        assertFalse(statusMsg != null && statusMsg.contains("permission"), "status is allowed for a non-admin");
    }

    @Test
    @DisplayName("an admin is not blocked from destructive subcommands")
    void adminMayRunDestructiveSubcommands() {
        PlayerMock admin = server.addPlayer();
        admin.setOp(true); // grants structuralintegrity.admin

        assertTrue(command.handle(admin, new String[] {"stop"}));
        String msg = admin.nextMessage();
        assertFalse(msg != null && msg.contains("permission"), "an admin passes the gate (here: 'no recording')");
    }
}
