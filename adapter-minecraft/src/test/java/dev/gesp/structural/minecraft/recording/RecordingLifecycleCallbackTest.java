package dev.gesp.structural.minecraft.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.minecraft.StructuralIntegrityPlugin;
import dev.gesp.structural.minecraft.recording.RecordingService.RecordingLifecycleListener;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * The 7a recording-side coupling: a dependent plugin (the Siege gamemode) registers a
 * {@link RecordingLifecycleListener} and is told when the strux anchor recording starts and stops,
 * with the shared {@code sessionId} + {@code startTimeMs} timebase, so it can start/stop its own
 * parallel track in lockstep.
 */
@DisplayName("RecordingService: lifecycle callbacks on start/stop")
class RecordingLifecycleCallbackTest {

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

    /** A listener that records each callback with its (sessionId, startTimeMs) arguments. */
    private static final class CapturingListener implements RecordingLifecycleListener {
        final List<String> events = new ArrayList<>();
        String startedSession;
        long startedTime;
        String stoppedSession;
        long stoppedTime;

        @Override
        public void onRecordingStarted(String sessionId, long startTimeMs) {
            events.add("started");
            startedSession = sessionId;
            startedTime = startTimeMs;
        }

        @Override
        public void onRecordingStopped(String sessionId, long startTimeMs) {
            events.add("stopped");
            stoppedSession = sessionId;
            stoppedTime = startTimeMs;
        }
    }

    @Test
    @DisplayName("started/stopped fire with the right sessionId and the same start-time timebase")
    void startStopCallbacks() {
        CapturingListener listener = new CapturingListener();
        service.addLifecycleListener(listener);

        RecordingHandle handle = service.startRecording(RecordingRequest.of(RecordingRequest.BUILD, world)
                .label("t")
                .verifyOnStop(false)
                .build());

        assertEquals(List.of("started"), listener.events, "started fires on startRecording");
        assertEquals(handle.sessionId(), listener.startedSession, "started carries the anchor sessionId");
        assertTrue(listener.startedTime > 0, "started carries the anchor start time");
        // The start time the listener got is the same timebase the service exposes.
        assertEquals(listener.startedTime, service.currentSessionStartMs());

        long startedTime = listener.startedTime;
        assertTrue(service.stopRecording(handle));

        assertEquals(List.of("started", "stopped"), listener.events, "stopped fires on stopRecording");
        assertEquals(handle.sessionId(), listener.stoppedSession, "stopped carries the same sessionId");
        assertEquals(startedTime, listener.stoppedTime, "stopped carries the same start-time timebase as started");
    }

    @Test
    @DisplayName("a removed listener no longer receives callbacks")
    void removedListenerSilent() {
        CapturingListener listener = new CapturingListener();
        service.addLifecycleListener(listener);
        service.removeLifecycleListener(listener);

        RecordingHandle handle = service.startRecording(RecordingRequest.of(RecordingRequest.BUILD, world)
                .label("t")
                .verifyOnStop(false)
                .build());
        service.stopRecording(handle);

        assertTrue(listener.events.isEmpty(), "a removed listener gets nothing");
    }

    @Test
    @DisplayName("stopping a handle that is not the active session fires no stopped callback")
    void noCallbackForInactiveHandleStop() {
        CapturingListener listener = new CapturingListener();
        RecordingHandle handle = service.startRecording(RecordingRequest.of(RecordingRequest.BUILD, world)
                .label("t")
                .verifyOnStop(false)
                .build());
        service.stopRecording(handle); // first stop is the real one
        service.addLifecycleListener(listener);

        assertFalse(service.stopRecording(handle), "second stop is a no-op");
        assertTrue(listener.events.isEmpty(), "no stopped callback for an already-stopped handle");
    }
}
