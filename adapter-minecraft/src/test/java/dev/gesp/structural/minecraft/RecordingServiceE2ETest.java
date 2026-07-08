package dev.gesp.structural.minecraft;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.recording.RecordingConfig;
import dev.gesp.structural.minecraft.recording.RecordingHandle;
import dev.gesp.structural.minecraft.recording.RecordingRequest;
import dev.gesp.structural.minecraft.recording.RecordingService;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * E2E tests for the programmatic {@link RecordingService} API a host plugin
 * (e.g. the Siege gamemode) uses to auto-record tagged match/build sessions.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │   1. start a MATCH recording for "arena3" via the service           │
 *   │   2. generate a trivial structural event (break a supported block)  │
 *   │   3. stop via the handle                                            │
 *   │   4. assert the file landed at recordings/match/match-arena3-*.strx │
 *   │   5. assert overlap policy: a second start while one is active      │
 *   │      throws, and command + service share the one global session     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
@DisplayName("E2E: programmatic RecordingService")
class RecordingServiceE2ETest {

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        world = server.addSimpleWorld("test_world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("Auto-record is OFF by default — the global slot stays free for scoped recordings")
    void autoRecordOffByDefault() {
        // Code default matches the bundled config.yml: no boot-time session may
        // squat the single global recorder slot, or every scoped recording a
        // host plugin starts (Siege per-match/per-build) is silently blocked.
        assertFalse(new RecordingConfig().isAutoRecord(), "RecordingConfig field default is off");

        // Configs written before the recording section existed take the load-time
        // fallback — that must be off too.
        plugin.getConfig().set("recording", null);
        assertFalse(
                plugin.loadRecordingConfig().isAutoRecord(),
                "a config.yml without the recording section must not auto-record");

        // And the opt-in still works: an explicit true must wire through to the
        // config object (kills the "drop the setAutoRecord call" mutant, which
        // the two false-default assertions above cannot see).
        plugin.getConfig().set("recording.auto-record", true);
        assertTrue(plugin.loadRecordingConfig().isAutoRecord(), "explicit auto-record: true opts in");

        // And after a normal boot with the bundled config: nothing recording,
        // so a scoped session can start immediately.
        assertFalse(plugin.getRecordingService().isRecording(), "no boot session occupies the recorder");
    }

    @Test
    @DisplayName("Service is registered with Bukkit's ServicesManager")
    void serviceIsRegistered() {
        RecordingService fromManager = server.getServicesManager().load(RecordingService.class);
        assertNotNull(fromManager, "RecordingService must be resolvable via ServicesManager");
        assertSame(plugin.getRecordingService(), fromManager, "the registered service is the plugin's instance");
    }

    @Test
    @DisplayName("A tagged programmatic recording lands in recordings/<tag>/<tag>-<label>-<ts>.strx")
    void taggedRecordingFoldersByType() throws Exception {
        RecordingService service = plugin.getRecordingService();

        RecordingHandle handle = service.startRecording(RecordingRequest.of(RecordingRequest.MATCH, world)
                .label("arena3")
                .build());

        assertTrue(handle.sessionId().startsWith("match/match-arena3-"), "session id carries the tag folder + label");
        assertEquals("match", handle.tag());
        assertEquals("arena3", handle.label());
        assertTrue(service.isActive(handle));

        // Generate a trivial structural event so the session has content.
        placeColumn(0, 0, 0, Material.BEDROCK, Material.STONE, Material.STONE);
        plugin.getStructureManager().onBlockBroken(world.getBlockAt(0, 1, 0));

        assertTrue(handle.stop(), "stop() reports it stopped the active session");

        // Recordings now default to the compact binary .strx container.
        Path expected = recordingsDir().resolve(handle.sessionId() + ".strx");
        awaitFile(expected);
        assertTrue(Files.exists(expected), "recording file must land at " + expected);
        assertTrue(
                plugin.getEventRecorder().listSessions().contains(handle.sessionId()),
                "typed session must show up in listSessions()");
    }

    @Test
    @DisplayName("A host's match metadata + actor roster ride onto the recorded session")
    void recordingCarriesHostMetadataAndActors() throws Exception {
        RecordingService service = plugin.getRecordingService();

        RecordingHandle handle = service.startRecording(RecordingRequest.of(RecordingRequest.MATCH, world)
                .label("arena7")
                .metadata(java.util.Map.of("matchId", "m-99", "team.RED", "uuid-a,uuid-b"))
                .actors(java.util.Map.of("uuid-a", "RedKnight"))
                .build());

        placeColumn(0, 0, 0, Material.BEDROCK, Material.STONE, Material.STONE);
        plugin.getStructureManager().onBlockBroken(world.getBlockAt(0, 1, 0));
        assertTrue(handle.stop());

        Path expected = recordingsDir().resolve(handle.sessionId() + ".strx");
        awaitFile(expected);
        // The save is async; poll-read until the fully-written session shows up. Read
        // through the recorder so it picks the right codec (.strx by default).
        dev.gesp.structural.recording.RecordingSession saved = null;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try {
                var s = plugin.getEventRecorder().loadSession(handle.sessionId());
                if (s != null && s.getMetadata().containsKey("matchId")) {
                    saved = s;
                    break;
                }
            } catch (Exception stillWriting) {
                // half-written file — retry
            }
            Thread.sleep(20);
        }
        assertNotNull(saved, "the session was saved with its metadata");
        assertEquals("m-99", saved.getMetadata().get("matchId"), "host metadata rides onto the recording");
        assertEquals("uuid-a,uuid-b", saved.getMetadata().get("team.RED"), "team roster is preserved");
        assertEquals("RedKnight", saved.getActors().get("uuid-a"), "actor display name is preserved");
    }

    @Test
    @DisplayName("Match and build sessions are separable on disk under different folders")
    void matchAndBuildAreSeparable() throws Exception {
        RecordingService service = plugin.getRecordingService();

        RecordingHandle match = service.startRecording(RecordingRequest.of(RecordingRequest.MATCH, world)
                .label("arena3")
                .build());
        match.stop();

        RecordingHandle build = service.startRecording(RecordingRequest.of(RecordingRequest.BUILD, world)
                .label("lobby")
                .build());
        build.stop();

        Path matchPath = recordingsDir().resolve(match.sessionId() + ".strx");
        Path buildPath = recordingsDir().resolve(build.sessionId() + ".strx");
        awaitFile(matchPath);
        awaitFile(buildPath);
        assertTrue(Files.exists(matchPath), "match folder populated");
        assertTrue(Files.exists(buildPath), "build folder populated");
    }

    @Test
    @DisplayName("Overlapping sessions are rejected: only one recording at a time")
    void overlappingSessionsRejected() {
        RecordingService service = plugin.getRecordingService();

        RecordingHandle first = service.startRecording(RecordingRequest.of(RecordingRequest.MATCH, world)
                .label("arena3")
                .build());

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> service.startRecording(RecordingRequest.of(RecordingRequest.BUILD, world)
                        .label("lobby")
                        .build()),
                "a second start while one is active must throw");
        assertTrue(ex.getMessage().contains("already in progress"));

        // After stopping the first, a new one can start.
        assertTrue(first.stop());
        RecordingHandle second = service.startRecording(RecordingRequest.of(RecordingRequest.BUILD, world)
                .label("lobby")
                .build());
        assertTrue(second.isActive());
        second.stop();
    }

    @Test
    @DisplayName("verifyOnStop can be opted out per request")
    void verifyOnStopOptOut() throws Exception {
        RecordingService service = plugin.getRecordingService();
        RecordingHandle handle = service.startRecording(RecordingRequest.of("throwaway", world)
                .label("scratch")
                .verifyOnStop(false)
                .build());
        handle.stop();
        // The file still saves even with verification skipped.
        Path path = recordingsDir().resolve(handle.sessionId() + ".strx");
        awaitFile(path);
        assertTrue(Files.exists(path));
    }

    @Test
    @DisplayName("stop() is idempotent and only stops the active session")
    void stopIsIdempotent() {
        RecordingService service = plugin.getRecordingService();
        RecordingHandle handle = service.startRecording(RecordingRequest.of(RecordingRequest.MATCH, world)
                .label("arena3")
                .build());
        assertTrue(handle.stop(), "first stop stops it");
        assertFalse(handle.stop(), "second stop is a no-op");
        assertFalse(handle.isActive());
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private Path recordingsDir() {
        return plugin.getEventRecorder().getRecordingsDir();
    }

    /**
     * The recorder saves asynchronously on a daemon thread (submitted on stop,
     * plus a 1s periodic flush). Poll for the file rather than sleeping a fixed
     * amount, with a generous ceiling so the test isn't flaky.
     */
    private static void awaitFile(Path path) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!Files.exists(path) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    private void placeColumn(int x, int startY, int z, Material... materials) {
        StructureManager manager = plugin.getStructureManager();
        for (int i = 0; i < materials.length; i++) {
            Block block = world.getBlockAt(x, startY + i, z);
            block.setType(materials[i]);
            manager.onBlockPlaced(block);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  CONSOLE / RCON OPERABILITY
    // ─────────────────────────────────────────────────────────────────────

    /**
     * `/strux record …` must work from the console (and therefore RCON/CI):
     * verifying a recording is an operator task, not a gameplay action. The
     * rest of `/strux` (scan, wand, …) stays player-only — those genuinely
     * need a body in the world.
     */
    @Test
    @DisplayName("Console can run /strux record (status, list, verify) — not player-gated")
    void consoleCanUseRecordCommands() {
        var console = server.getConsoleSender();

        assertTrue(server.dispatchCommand(console, "strux record status"));
        String reply = console.nextMessage();
        assertNotNull(reply, "console must get a response");
        assertNotEquals("This command is for players.", reply, "record subcommands must not be player-gated");
        // Drain the rest of the status output before the next dispatch.
        while (console.nextMessage() != null) {
            // drained
        }

        // A non-record subcommand stays player-only.
        assertTrue(server.dispatchCommand(console, "strux scan"));
        assertEquals("This command is for players.", console.nextMessage());
    }
}
