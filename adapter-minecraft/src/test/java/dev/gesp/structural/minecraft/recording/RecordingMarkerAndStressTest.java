package dev.gesp.structural.minecraft.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.minecraft.StructuralIntegrityPlugin;
import dev.gesp.structural.recording.MarkerEvent;
import dev.gesp.structural.recording.RecordingSession;
import dev.gesp.structural.recording.StruxEvent;
import java.util.Map;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Marker recording via {@link RecordingService#mark} and the {@code captureStress}
 * flag plumbing: a marker dropped through the service appears in the saved session,
 * and the "match" tag turns stress capture on while other tags leave it at the
 * (off) config default.
 */
@DisplayName("RecordingService: markers + stress-capture flag")
class RecordingMarkerAndStressTest {

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private MinecraftEventRecorder recorder;
    private RecordingService service;
    private World world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        recorder = (MinecraftEventRecorder) plugin.getStructureManager().getEventRecorder();
        service = new RecordingService(recorder, plugin.getStructureManager());
        world = server.addSimpleWorld("arena");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("a marker recorded via the service lands in the saved session")
    void markerRecorded() throws Exception {
        RecordingHandle handle = service.startRecording(RecordingRequest.of(RecordingRequest.BUILD, world)
                .label("t")
                .verifyOnStop(false)
                .build());

        assertTrue(service.mark(handle, "round start", Map.of("round", "1")));
        service.stopRecording(handle);

        RecordingSession saved = awaitSession(handle.sessionId());
        MarkerEvent marker = saved.getEvents().stream()
                .filter(e -> e instanceof MarkerEvent)
                .map(e -> (MarkerEvent) e)
                .findFirst()
                .orElseThrow();
        assertEquals("round start", marker.name());
        assertEquals("1", marker.meta().get("round"));
        assertEquals(StruxEvent.EventType.MARKER, marker.type());
    }

    @Test
    @DisplayName("mark on a stale handle is rejected and records nothing")
    void markStaleHandleRejected() {
        RecordingHandle handle = service.startRecording(RecordingRequest.of(RecordingRequest.BUILD, world)
                .label("t")
                .verifyOnStop(false)
                .build());
        service.stopRecording(handle);
        assertFalse(service.mark(handle, "late", Map.of()), "a stopped handle can't mark");
    }

    @Test
    @DisplayName("the match tag turns stress capture on; other tags keep it off")
    void matchTagEnablesStressCapture() {
        RecordingHandle match = service.startRecording(RecordingRequest.of(RecordingRequest.MATCH, world)
                .label("m")
                .verifyOnStop(false)
                .build());
        assertTrue(recorder.isCaptureStress(), "a match recording captures stress");
        service.stopRecording(match);

        RecordingHandle build = service.startRecording(RecordingRequest.of(RecordingRequest.BUILD, world)
                .label("b")
                .verifyOnStop(false)
                .build());
        assertFalse(recorder.isCaptureStress(), "a build recording leaves stress capture at the off default");
        service.stopRecording(build);
    }

    @Test
    @DisplayName("isCaptureStress is false when nothing is recording")
    void captureFalseWhenIdle() {
        assertFalse(recorder.isCaptureStress(), "no recording → no capture");
    }

    private RecordingSession awaitSession(String session) throws Exception {
        for (int i = 0; i < 100; i++) {
            RecordingSession loaded = recorder.loadSession(session);
            if (loaded != null) {
                return loaded;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("recording session was never saved: " + session);
    }
}
