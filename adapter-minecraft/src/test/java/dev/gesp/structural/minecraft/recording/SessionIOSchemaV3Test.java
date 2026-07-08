package dev.gesp.structural.minecraft.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.persistence.BlockData;
import dev.gesp.structural.persistence.StructureData;
import dev.gesp.structural.recording.BlastEvent;
import dev.gesp.structural.recording.BlockBreakEvent;
import dev.gesp.structural.recording.MarkerEvent;
import dev.gesp.structural.recording.RecordingSession;
import dev.gesp.structural.recording.StressDelta;
import dev.gesp.structural.recording.StruxEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Schema-v3 JSON round-trip for {@link SessionIO}: {@link MarkerEvent} and the
 * optional {@link StressDelta} payload survive a write→read, and a v2 file (no
 * markers, no stress) still loads with those defaulting to absent.
 */
@DisplayName("SessionIO: schema v3 round-trip + v2 back-compat")
class SessionIOSchemaV3Test {

    private RecordingSession v3Session() {
        StructureData initial = new StructureData("world");
        initial.addBlock(new BlockData(0, 0, 0, 0.0, Double.MAX_VALUE, true, 1.0, 0.0, 1.0, 1.0, "minecraft:bedrock"));

        RecordingSession s = new RecordingSession("sess-v3", 1000L, "world", initial);

        Map<NodePos, Double> ratios = new LinkedHashMap<>();
        ratios.put(new NodePos(0, 1, 0), 0.95);
        ratios.put(new NodePos(0, 2, 0), 1.20);
        StressDelta stress = new StressDelta(ratios);

        s.addEvent(new BlockBreakEvent(1010L, 1L, new NodePos(0, 1, 0), "STONE", List.of(), "uuid-red", stress));
        s.addEvent(new BlastEvent(
                1020L, 2L, new NodePos(1, 1, 0), 4.0, "SPHERE", List.of(), List.of(), Map.of(), "uuid-blue", stress));
        s.addEvent(new MarkerEvent(1030L, 3L, "wall breached", Map.of("team", "red", "wall", "north")));
        return s;
    }

    @Test
    @DisplayName("a v3 session round-trips markers and stress payloads")
    void v3RoundTrip() {
        RecordingSession out = SessionIO.fromJson(SessionIO.toJson(v3Session()));

        assertEquals(3, out.getSchemaVersion());

        BlockBreakEvent brk = (BlockBreakEvent) eventAt(out, 1L);
        assertEquals(2, brk.stress().loadRatios().size(), "break stress survives");
        assertEquals(0.95, brk.stress().loadRatios().get(new NodePos(0, 1, 0)), 1e-9);
        assertEquals(1.20, brk.stress().loadRatios().get(new NodePos(0, 2, 0)), 1e-9);

        BlastEvent blast = (BlastEvent) eventAt(out, 2L);
        assertEquals(1.20, blast.stress().loadRatios().get(new NodePos(0, 2, 0)), 1e-9);

        MarkerEvent marker = (MarkerEvent) eventAt(out, 3L);
        assertEquals(StruxEvent.EventType.MARKER, marker.type());
        assertEquals("wall breached", marker.name());
        assertEquals("red", marker.meta().get("team"));
        assertEquals("north", marker.meta().get("wall"));
    }

    @Test
    @DisplayName("a break with no stress omits the field; it reads back null")
    void noStressIsNull() {
        RecordingSession s = new RecordingSession("sess", 0L, "world", new StructureData("world"));
        s.addEvent(new BlockBreakEvent(1L, 1L, new NodePos(0, 0, 0), "STONE", List.of(), "uuid"));
        String json = SessionIO.toJson(s);
        assertTrue(!json.contains("\"stress\""), "no stress payload is written when absent");
        BlockBreakEvent out = (BlockBreakEvent) eventAt(SessionIO.fromJson(json), 1L);
        assertNull(out.stress(), "absent stress reads back null");
    }

    @Test
    @DisplayName("a v2 JSON (no markers, no stress) still loads")
    void v2BackCompat() {
        String v2Json =
                """
                {
                  "schemaVersion": 2,
                  "sessionId": "old2",
                  "startTimeMs": 0,
                  "worldId": "world",
                  "initialState": { "worldId": "world", "version": 3, "blocks": [] },
                  "events": [
                    { "type": "BLOCK_BREAK", "timestampMs": 5, "sequenceId": 1, "pos": {"x":0,"y":1,"z":0},
                      "materialId": "STONE", "collapsed": [], "actorId": "uuid-red" }
                  ]
                }
                """;
        RecordingSession out = SessionIO.fromJson(v2Json);
        BlockBreakEvent brk = (BlockBreakEvent) eventAt(out, 1L);
        assertEquals("uuid-red", brk.actorId());
        assertNull(brk.stress(), "a v2 break has no stress payload");
    }

    @Test
    @DisplayName("a marker with no meta field decodes to an empty map")
    void markerWithoutMetaJson() {
        String json =
                """
                {
                  "schemaVersion": 3,
                  "sessionId": "m",
                  "startTimeMs": 0,
                  "worldId": "world",
                  "initialState": { "worldId": "world", "version": 3, "blocks": [] },
                  "events": [
                    { "type": "MARKER", "timestampMs": 1, "sequenceId": 1, "name": "kickoff" }
                  ]
                }
                """;
        MarkerEvent marker = (MarkerEvent) eventAt(SessionIO.fromJson(json), 1L);
        assertEquals("kickoff", marker.name());
        assertTrue(marker.meta().isEmpty(), "an absent meta field decodes to an empty map");
    }

    private static StruxEvent eventAt(RecordingSession s, long seq) {
        return s.getEvents().stream()
                .filter(ev -> ev.sequenceId() == seq)
                .findFirst()
                .orElseThrow();
    }
}
