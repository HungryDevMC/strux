package dev.gesp.structural.minecraft.recording;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.StructuralIntegrityPlugin;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.persistence.StructureData;
import dev.gesp.structural.recording.BlockBreakEvent;
import dev.gesp.structural.recording.RecordingSession;
import dev.gesp.structural.recording.StruxEvent;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Collection;
import java.util.LinkedHashMap;
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
 * The recorder buffers events on a lock-free queue and drains them into the session on
 * a single executor thread. These tests pin that contract: every buffered event reaches
 * the saved session (the drain happens on the executor at stop, never racing the
 * periodic flush), and a flush that throws is swallowed so it can never silently cancel
 * the periodic schedule for the recorder's lifetime.
 */
@DisplayName("MinecraftEventRecorder: lossless, single-threaded buffer flush")
class MinecraftEventRecorderFlushTest {

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

    private static BlockBreakEvent breakEvent(int seq) {
        return new BlockBreakEvent(seq, seq, new NodePos(0, seq, 0), "STONE", List.of(), null);
    }

    @Test
    @DisplayName("every buffered event reaches the saved session — none lost at stop")
    void stopFlushesEveryBufferedEvent() throws Exception {
        // A flush with no active session is a safe no-op.
        recorder.flushTick();

        String session =
                recorder.startRecording("flush/test", UUID.randomUUID().toString(), new StructureGraph(), false);
        for (int i = 0; i < 25; i++) {
            recorder.record(breakEvent(i));
        }
        // A mid-recording flush drains a batch into the session; the rest is drained by
        // the final pass that stopRecording submits to the executor.
        recorder.flushTick();
        recorder.record(breakEvent(99));
        recorder.stopRecording();

        RecordingSession saved = awaitSession(session);
        assertEquals(26, saved.getEvents().size(), "all 26 buffered events are flushed, none lost");
    }

    @Test
    @DisplayName("flushTick drains every buffered event into the active session")
    void flushTickDrainsTheBuffer() throws Exception {
        recorder.startRecording("flush/drain", UUID.randomUUID().toString(), new StructureGraph(), false);
        for (int i = 0; i < 5; i++) {
            recorder.record(breakEvent(i));
        }
        // Drain explicitly. We assert the buffer is empty AFTER the flush (robust to the
        // 1s periodic flush, which only ever helps drain): if flushBuffer is broken, the
        // periodic flush is equally broken, so the events would still be sitting here.
        recorder.flushTick();

        assertEquals(0, bufferOf().size(), "flushTick drained the buffer into the active session");
        recorder.stopRecording();
    }

    @Test
    @DisplayName("loadSession/deleteSession reject a path-traversal session id")
    void sessionIdPathTraversalRejected() throws Exception {
        // Session ids come from /strux record replay/verify/export <name> — raw player
        // input. A traversing or absolute name must never reach a file outside recordings.
        assertNull(recorder.loadSession("../../../../etc/hosts"), "a ../ name must not load outside recordings");
        assertNull(recorder.loadSession("/etc/hosts"), "an absolute name must not escape either");
        assertFalse(recorder.deleteSession("../../../../etc/hosts"), "delete must refuse a traversing name");
    }

    @Test
    @DisplayName("listSessions orders by modification time, newest first — not alphabetically by name")
    void listSessionsOrdersByModificationTimeNotName() throws Exception {
        Path dir = recordingsDir();
        Files.createDirectories(dir.resolve("build"));
        Files.createDirectories(dir.resolve("match"));
        // Oldest → newest by real mtime, but NOT in that order by name. A name sort
        // (reverseOrder) would yield [session-mid, match/fresh, build/old] — putting the
        // freshly recorded "match/fresh" SECOND, so cleanup could delete it.
        writeRecording(dir.resolve("build/old.json"), 1_000L);
        writeRecording(dir.resolve("session-mid.json"), 2_000L);
        writeRecording(dir.resolve("match/fresh.json"), 3_000L);

        assertEquals(
                List.of("match/fresh", "session-mid", "build/old"),
                recorder.listSessions(),
                "newest by modification time first, regardless of name prefix");
    }

    @Test
    @DisplayName("orderedByRecency sorts ids newest-first even when inserted oldest-first")
    void orderedByRecencyNewestFirst() throws Exception {
        Path dir = recordingsDir();
        Files.createDirectories(dir.resolve("build"));
        Files.createDirectories(dir.resolve("match"));
        Files.createDirectories(dir.resolve("manual"));
        Path old = dir.resolve("build/old.json");
        Path mid = dir.resolve("session-mid.json");
        Path fresh = dir.resolve("match/fresh.json");
        Path ghost = dir.resolve("manual/ghost.json"); // never written: mtime read throws → epoch
        writeRecording(old, 1_000L);
        writeRecording(mid, 2_000L);
        writeRecording(fresh, 3_000L);

        // Deterministic insertion order, OLDEST first: a dropped sort would return exactly
        // this (wrong) order, so the assertion fails without the recency sort.
        Map<String, Path> files = new LinkedHashMap<>();
        files.put("build/old", old);
        files.put("session-mid", mid);
        files.put("match/fresh", fresh);
        files.put("manual/ghost", ghost);

        // ghost has no readable mtime → treated as epoch (oldest), so it sorts dead last.
        assertEquals(
                List.of("match/fresh", "session-mid", "build/old", "manual/ghost"),
                MinecraftEventRecorder.orderedByRecency(files),
                "newest modification time first; an unreadable file is treated as oldest");
    }

    private Path recordingsDir() throws Exception {
        Field f = MinecraftEventRecorder.class.getDeclaredField("recordingsDir");
        f.setAccessible(true);
        return (Path) f.get(recorder);
    }

    private static void writeRecording(Path path, long mtimeMillis) throws Exception {
        Files.writeString(path, "{}");
        Files.setLastModifiedTime(path, FileTime.fromMillis(mtimeMillis));
    }

    @Test
    @DisplayName("starting a recording with a null graph yields an empty initial state, never an NPE")
    void startRecordingToleratesNullGraph() throws Exception {
        // The auto-record boot path passes structureManager.getGraph(world), which is
        // null for any world with no tracked structure yet — at onEnable that is every
        // world. beginSession must not NPE on it (it used to, aborting plugin enable).
        String session = recorder.startRecording(UUID.randomUUID().toString(), null);
        assertNotNull(session, "a null graph must not abort the recording start");

        recorder.record(breakEvent(0));
        recorder.stopRecording();

        RecordingSession saved = awaitSession(session);
        assertTrue(saved.getInitialState().getBlocks().isEmpty(), "a null graph yields an empty initial state");
        assertEquals(1, saved.getEvents().size(), "events still record normally after a null-graph start");
    }

    @Test
    @DisplayName("a flush whose addEvent throws is swallowed (never cancels the periodic schedule)")
    void flushTickSwallowsAFailingFlush() throws Exception {
        // A session that blows up on every addEvent — the kind of failure that, thrown
        // out of a scheduleAtFixedRate task, would silently kill the periodic drain.
        RecordingSession boom = new RecordingSession("boom", 0L, "w", new StructureData("w")) {
            @Override
            public void addEvent(StruxEvent event) {
                throw new IllegalStateException("kaboom");
            }
        };
        setField("currentSession", boom);
        bufferOf().add(breakEvent(1));

        assertDoesNotThrow(() -> recorder.flushTick(), "a failing flush is logged and swallowed, not propagated");
    }

    private void setField(String name, Object value) throws Exception {
        Field f = MinecraftEventRecorder.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(recorder, value);
    }

    @SuppressWarnings("unchecked")
    private Collection<StruxEvent> bufferOf() throws Exception {
        Field f = MinecraftEventRecorder.class.getDeclaredField("buffer");
        f.setAccessible(true);
        return (Collection<StruxEvent>) f.get(recorder);
    }

    /** Wait (briefly) for the async session save to land on disk, then load it. */
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
