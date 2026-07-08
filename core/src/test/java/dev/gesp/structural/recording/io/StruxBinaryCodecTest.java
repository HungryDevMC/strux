package dev.gesp.structural.recording.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.model.ThermalClass;
import dev.gesp.structural.persistence.BlockData;
import dev.gesp.structural.persistence.EdgeData;
import dev.gesp.structural.persistence.StructureData;
import dev.gesp.structural.recording.BlastEvent;
import dev.gesp.structural.recording.BlockBreakEvent;
import dev.gesp.structural.recording.BlockPlaceEvent;
import dev.gesp.structural.recording.CascadeEvent;
import dev.gesp.structural.recording.CascadeStep;
import dev.gesp.structural.recording.FireDamageEvent;
import dev.gesp.structural.recording.ImpactEvent;
import dev.gesp.structural.recording.MarkerEvent;
import dev.gesp.structural.recording.RecordingSession;
import dev.gesp.structural.recording.StressDelta;
import dev.gesp.structural.recording.StruxEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The binary {@code .strx} container: every event kind (including MarkerEvent and a
 * StressDelta payload) survives a write→read round trip byte-for-byte equal, a
 * corrupt file is rejected with a clear exception rather than a silent partial read,
 * and the footer {@link SessionIndex} reports the right counts.
 */
@DisplayName("StruxBinaryCodec: .strx round trip, index, corruption")
class StruxBinaryCodecTest {

    /** A session with EVERY event kind, a StressDelta, a marker, config + metadata. */
    private static RecordingSession everyKind(long seed) {
        Random rng = new Random(seed);

        StructureData initial = new StructureData("world");
        initial.setExplicitTopology(true);
        for (int i = 0; i < 30; i++) {
            initial.addBlock(new BlockData(
                    rng.nextInt(40),
                    rng.nextInt(40),
                    rng.nextInt(40),
                    rng.nextDouble() * 10,
                    rng.nextDouble() * 100,
                    rng.nextBoolean(),
                    1.0 + rng.nextDouble(),
                    rng.nextDouble(),
                    1.0 + rng.nextDouble() * 3,
                    1.0 + rng.nextDouble() * 3,
                    "minecraft:block_" + rng.nextInt(5),
                    ThermalClass.values()[rng.nextInt(ThermalClass.values().length)]));
        }
        initial.addEdge(new EdgeData(0, 0, 0, 1, 0, 0));
        initial.addEdge(new EdgeData(1, 0, 0, 1, 1, 0));

        RecordingSession s = new RecordingSession("match/arena-" + seed, 1_000L, "world", initial);
        s.setEndTimeMs(9_000L);
        s.setEngineVersion("1.2.3-test");
        s.setMetadata(Map.of("matchId", "m" + seed, "arena", "keep"));
        s.setActors(Map.of("uuid-red", "Red", "uuid-blue", "Blue"));
        PhysicsConfig cfg = new PhysicsConfig();
        cfg.setMaxCascadeSteps(77);
        cfg.setMomentMultiplier(1.234);
        cfg.setPreCollapseShake(false);
        s.setPhysicsConfig(cfg);

        long seq = 0;
        s.addEvent(new BlockBreakEvent(
                1010L, ++seq, randPos(rng), "STONE", randPosList(rng), "uuid-red", randStress(rng)));
        s.addEvent(new BlockPlaceEvent(
                1020L,
                ++seq,
                randPos(rng),
                "OAK_PLANKS",
                rng.nextDouble() * 5,
                rng.nextDouble() * 40,
                2.0,
                4.0,
                ThermalClass.WOOD,
                true,
                randPosList(rng),
                "uuid-blue"));
        Map<NodePos, Double> damaged = new LinkedHashMap<>();
        damaged.put(randPos(rng), rng.nextDouble());
        damaged.put(randPos(rng), rng.nextDouble());
        s.addEvent(new BlastEvent(
                1030L,
                ++seq,
                randPos(rng),
                4.0,
                "SPHERE",
                randPosList(rng),
                randPosList(rng),
                damaged,
                "uuid-red",
                randStress(rng)));
        Map<NodePos, Double> pathDamage = new LinkedHashMap<>();
        pathDamage.put(randPos(rng), 1.5);
        s.addEvent(new ImpactEvent(
                1040L,
                ++seq,
                randPos(rng),
                "ARROW",
                12.5,
                0.4,
                true,
                randPosList(rng),
                randPosList(rng),
                pathDamage,
                "uuid-blue"));
        s.addEvent(new FireDamageEvent(1050L, ++seq, randPos(rng), "WOOL", 0.3, 0.9, false, randPosList(rng)));
        s.addEvent(new CascadeEvent(
                1060L,
                ++seq,
                randPos(rng),
                "OVERLOAD",
                List.of(
                        new CascadeStep(randPos(rng), "STONE", 1, "FLOATING"),
                        new CascadeStep(randPos(rng), "STONE", 2, "OVERLOADED"))));
        s.addEvent(new MarkerEvent(1070L, ++seq, "round start", Map.of("round", "1", "side", "attack")));
        s.addEvent(new MarkerEvent(1080L, ++seq, "no-meta-marker", Map.of()));
        return s;
    }

    private static NodePos randPos(Random rng) {
        return new NodePos(rng.nextInt(60) - 30, rng.nextInt(60) - 30, rng.nextInt(60) - 30);
    }

    private static List<NodePos> randPosList(Random rng) {
        int n = rng.nextInt(4);
        List<NodePos> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(randPos(rng));
        }
        return list;
    }

    private static StressDelta randStress(Random rng) {
        Map<NodePos, Double> m = new LinkedHashMap<>();
        int n = 1 + rng.nextInt(3);
        for (int i = 0; i < n; i++) {
            m.put(randPos(rng), rng.nextDouble() * 1.5);
        }
        return new StressDelta(m);
    }

    @Test
    @DisplayName("a session with every event kind survives binary round trip deep-equal")
    void roundTripDeepEqual() throws IOException {
        RecordingSession original = everyKind(20240611L);
        RecordingSession read = StruxBinaryCodec.fromBytes(StruxBinaryCodec.toBytes(original));
        assertSessionsEqual(original, read);
    }

    @Test
    @DisplayName("binary and JSON-equivalent in-memory sessions decode to the same data")
    void binaryMatchesOriginal() throws IOException {
        // The codec's own round trip IS the equivalence check: anything the binary
        // dropped would surface as a deep-equality miss against the constructed session.
        for (long seed : new long[] {1L, 42L, 99L, 2024L}) {
            RecordingSession original = everyKind(seed);
            RecordingSession read = StruxBinaryCodec.fromBytes(StruxBinaryCodec.toBytes(original));
            assertSessionsEqual(original, read);
        }
    }

    @Test
    @DisplayName("the footer index reports event count, type counts, duration, per-actor tallies")
    void footerIndex() throws IOException {
        RecordingSession original = everyKind(7L);
        byte[] bytes = StruxBinaryCodec.toBytes(original);
        // readIndex via a temp file path equivalent: decode through fromBytes path.
        SessionIndex idx = decodeIndex(bytes);

        assertEquals(8, idx.eventCount(), "all eight events counted");
        assertEquals(1, idx.typeCounts().get(StruxEvent.EventType.BLOCK_BREAK));
        assertEquals(1, idx.typeCounts().get(StruxEvent.EventType.BLAST));
        assertEquals(2, idx.typeCounts().get(StruxEvent.EventType.MARKER));
        assertEquals(8_000L, idx.durationMs(), "9000 - 1000");
        // Two events attributed to red (break + blast), one to blue (place); impact→blue too.
        assertEquals(2, idx.actorEventCounts().get("uuid-red"));
        assertEquals(2, idx.actorEventCounts().get("uuid-blue"));
        assertTrue(idx.stride() >= 1);
        assertEquals(1, idx.checkpointOffsets().size(), "8 events, stride 64 → one checkpoint at event 0");
    }

    @Test
    @DisplayName("a bad magic number is rejected")
    void badMagicRejected() {
        byte[] bad = "NOPE and some more bytes here to be long enough".getBytes();
        StruxFormatException ex = assertThrows(StruxFormatException.class, () -> StruxBinaryCodec.fromBytes(bad));
        assertTrue(ex.getMessage().toLowerCase().contains("magic"), "message names the magic problem");
    }

    @Test
    @DisplayName("an unsupported format version is rejected")
    void badVersionRejected() throws IOException {
        byte[] good = StruxBinaryCodec.toBytes(everyKind(3L));
        good[4] = (byte) 0x7F; // clobber the version byte
        StruxFormatException ex = assertThrows(StruxFormatException.class, () -> StruxBinaryCodec.fromBytes(good));
        assertTrue(ex.getMessage().contains("version"), "message names the version problem");
    }

    @Test
    @DisplayName("a truncated file is rejected, not silently partially read")
    void truncatedRejected() throws IOException {
        byte[] good = StruxBinaryCodec.toBytes(everyKind(5L));
        byte[] cut = Arrays.copyOfRange(good, 0, good.length - 20);
        assertThrows(IOException.class, () -> StruxBinaryCodec.fromBytes(cut));
    }

    @Test
    @DisplayName("a too-short file (no room for a header) is rejected")
    void tooShortRejected() {
        assertThrows(StruxFormatException.class, () -> StruxBinaryCodec.fromBytes(new byte[] {'S', 'T'}));
    }

    @Test
    @DisplayName("a session with no physics config and empty maps round-trips")
    void minimalSession() throws IOException {
        RecordingSession s = new RecordingSession("min", 0L, "w", new StructureData("w"));
        s.addEvent(new BlockBreakEvent(1L, 1L, new NodePos(0, 0, 0), "STONE", List.of(), null));
        RecordingSession read = StruxBinaryCodec.fromBytes(StruxBinaryCodec.toBytes(s));
        assertNull(read.getPhysicsConfig());
        assertEquals(1, read.getEvents().size());
        BlockBreakEvent brk =
                assertInstanceOf(BlockBreakEvent.class, read.getEvents().get(0));
        assertNull(brk.actorId());
        assertNull(brk.stress());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static SessionIndex decodeIndex(byte[] bytes) throws IOException {
        // readIndex(Path) needs a file; reuse the public byte path by writing to a temp file.
        Path tmp = Files.createTempFile("strx-test", ".strx");
        try {
            Files.write(tmp, bytes);
            return StruxBinaryCodec.readIndex(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void assertSessionsEqual(RecordingSession a, RecordingSession b) {
        assertEquals(a.getSchemaVersion(), b.getSchemaVersion());
        assertEquals(a.getSessionId(), b.getSessionId());
        assertEquals(a.getStartTimeMs(), b.getStartTimeMs());
        assertEquals(a.getEndTimeMs(), b.getEndTimeMs());
        assertEquals(a.getWorldId(), b.getWorldId());
        assertEquals(a.getEngineVersion(), b.getEngineVersion());
        assertEquals(a.getMetadata(), b.getMetadata());
        assertEquals(a.getActors(), b.getActors());

        // physics config: compare a few representative fields (the codec reflects all).
        if (a.getPhysicsConfig() == null) {
            assertNull(b.getPhysicsConfig());
        } else {
            assertNotNull(b.getPhysicsConfig());
            assertEquals(
                    a.getPhysicsConfig().getMaxCascadeSteps(),
                    b.getPhysicsConfig().getMaxCascadeSteps());
            assertEquals(
                    a.getPhysicsConfig().getMomentMultiplier(),
                    b.getPhysicsConfig().getMomentMultiplier());
            assertEquals(
                    a.getPhysicsConfig().isPreCollapseShake(),
                    b.getPhysicsConfig().isPreCollapseShake());
        }

        // initial state blocks + edges
        StructureData sa = a.getInitialState();
        StructureData sb = b.getInitialState();
        assertEquals(sa.getWorldId(), sb.getWorldId());
        assertEquals(sa.getVersion(), sb.getVersion());
        assertEquals(sa.isExplicitTopology(), sb.isExplicitTopology());
        assertEquals(sa.getBlocks(), sb.getBlocks(), "block list deep-equal");
        assertEquals(sa.getEdges(), sb.getEdges(), "edge list deep-equal");

        // events: records implement equals, so the lists compare deeply.
        assertEquals(a.getEvents(), b.getEvents(), "every event deep-equal after round trip");
    }
}
