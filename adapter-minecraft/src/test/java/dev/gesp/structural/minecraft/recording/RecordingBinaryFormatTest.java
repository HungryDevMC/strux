package dev.gesp.structural.minecraft.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.StructuralIntegrityPlugin;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.persistence.StructureData;
import dev.gesp.structural.recording.BlockBreakEvent;
import dev.gesp.structural.recording.MarkerEvent;
import dev.gesp.structural.recording.RecordingSession;
import dev.gesp.structural.recording.StressDelta;
import dev.gesp.structural.recording.io.StruxBinaryCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Format selection: the recorder writes the compact binary {@code .strx} by default,
 * {@code setWriteJson} forces JSON for a session, and both forms load back through
 * {@code loadSession}. Plus a JSON↔binary equivalence check on the same session.
 */
@DisplayName("Recorder: binary .strx default, JSON opt-out, both readable")
class RecordingBinaryFormatTest {

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private MinecraftEventRecorder recorder;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        recorder = (MinecraftEventRecorder) plugin.getStructureManager().getEventRecorder();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("by default a recording is saved as .strx and loads back")
    void defaultIsBinary() throws Exception {
        String session =
                recorder.startRecording("fmt/binary", UUID.randomUUID().toString(), new StructureGraph(), false);
        recorder.record(new BlockBreakEvent(1, 1, new NodePos(0, 1, 0), "STONE", List.of(), "p"));
        recorder.record(new MarkerEvent(2, 2, "go", Map.of("k", "v")));
        recorder.stopRecording();

        Path binary = awaitFile(session, ".strx");
        assertTrue(Files.exists(binary), "default save is a .strx file");
        assertFalse(
                Files.exists(recorder.getRecordingsDir().resolve(session + ".json")),
                "no JSON file is written by default");

        RecordingSession loaded = recorder.loadSession(session);
        assertEquals(2, loaded.getEvents().size(), "binary recording loads back through loadSession");
        assertTrue(loaded.getEvents().stream().anyMatch(e -> e instanceof MarkerEvent), "the marker survives");
    }

    @Test
    @DisplayName("setWriteJson forces a JSON save that still loads back")
    void jsonOptOut() throws Exception {
        String session = recorder.startRecording("fmt/json", UUID.randomUUID().toString(), new StructureGraph(), false);
        recorder.setWriteJson(true);
        recorder.record(new BlockBreakEvent(1, 1, new NodePos(0, 1, 0), "STONE", List.of(), "p"));
        recorder.stopRecording();

        Path json = awaitFile(session, ".json");
        assertTrue(Files.exists(json), "setWriteJson produced a .json file");
        assertFalse(
                Files.exists(recorder.getRecordingsDir().resolve(session + ".strx")),
                "no binary file when JSON was forced");

        RecordingSession loaded = recorder.loadSession(session);
        assertEquals(1, loaded.getEvents().size(), "JSON recording loads back too");
    }

    @Test
    @DisplayName("the same session encoded to JSON and to binary decodes to equal data")
    void jsonAndBinaryEquivalent() throws Exception {
        RecordingSession original = new RecordingSession("equiv", 100L, "world", new StructureData("world"));
        original.setEndTimeMs(200L);
        StressDelta stress = new StressDelta(Map.of(new NodePos(0, 1, 0), 0.95));
        original.addEvent(new BlockBreakEvent(110L, 1L, new NodePos(0, 1, 0), "STONE", List.of(), "red", stress));
        original.addEvent(new MarkerEvent(120L, 2L, "breach", Map.of("wall", "north")));

        RecordingSession viaJson = SessionIO.fromJson(SessionIO.toJson(original));
        RecordingSession viaBinary = StruxBinaryCodec.fromBytes(StruxBinaryCodec.toBytes(original));

        // Both codecs reconstruct the same events.
        assertEquals(viaJson.getEvents(), viaBinary.getEvents(), "JSON and binary decode to the same events");
        assertEquals(viaJson.getSessionId(), viaBinary.getSessionId());
        assertEquals(viaJson.getEndTimeMs(), viaBinary.getEndTimeMs());
        BlockBreakEvent jb = (BlockBreakEvent) viaJson.getEvents().get(0);
        BlockBreakEvent bb = (BlockBreakEvent) viaBinary.getEvents().get(0);
        assertEquals(jb.stress().loadRatios(), bb.stress().loadRatios(), "stress payload matches across formats");
    }

    private Path awaitFile(String session, String ext) throws Exception {
        Path path = recorder.getRecordingsDir().resolve(session + ext);
        for (int i = 0; i < 100; i++) {
            if (Files.exists(path)) {
                return path;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("file never written: " + path);
    }
}
