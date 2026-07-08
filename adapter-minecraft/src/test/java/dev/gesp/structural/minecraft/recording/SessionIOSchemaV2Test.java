package dev.gesp.structural.minecraft.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.model.ThermalClass;
import dev.gesp.structural.persistence.BlockData;
import dev.gesp.structural.persistence.StructureData;
import dev.gesp.structural.recording.BlastEvent;
import dev.gesp.structural.recording.BlockBreakEvent;
import dev.gesp.structural.recording.BlockPlaceEvent;
import dev.gesp.structural.recording.ImpactEvent;
import dev.gesp.structural.recording.RecordingSession;
import dev.gesp.structural.recording.StruxEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Schema-v2 round-trip for {@link SessionIO}: the new attribution + context fields
 * (per-event {@code actorId}, per-block {@code materialId}, and the session's
 * {@code physicsConfig}/{@code engineVersion}/{@code metadata}/{@code actors})
 * survive a JSON write→read, and a pre-v2 file still loads (the new fields default
 * to null/empty rather than throwing).
 */
@DisplayName("SessionIO: schema v2 round-trip + v1 back-compat")
class SessionIOSchemaV2Test {

    private RecordingSession v2Session() {
        StructureData initial = new StructureData("world");
        initial.addBlock(new BlockData(0, 0, 0, 0.0, Double.MAX_VALUE, true, 1.0, 0.0, 1.0, 1.0, "minecraft:bedrock"));
        initial.addBlock(new BlockData(0, 1, 0, 2.0, 30.0, false, 1.0, 0.0, 1.5, 1.0, "minecraft:oak_planks[axis=y]"));

        RecordingSession s = new RecordingSession("sess-1", 1000L, "world", initial);
        s.setEngineVersion("0.1.0-test");
        s.setMetadata(Map.of("matchId", "m42", "arena", "keep"));
        s.setActors(Map.of("uuid-red", "RedKnight", "uuid-blue", "BlueKnight"));
        PhysicsConfig cfg = new PhysicsConfig();
        cfg.setMaxCascadeSteps(99);
        s.setPhysicsConfig(cfg);

        s.addEvent(new BlockBreakEvent(1010L, 1L, new NodePos(0, 1, 0), "STONE", List.of(), "uuid-red"));
        s.addEvent(new BlockPlaceEvent(
                1020L,
                2L,
                new NodePos(0, 2, 0),
                "STONE",
                2.0,
                30.0,
                3.0, // blastResistance
                5.0, // fireResistance
                ThermalClass.MASONRY,
                true, // grounded anchor
                List.of(),
                "uuid-blue"));
        s.addEvent(new BlastEvent(
                1030L, 3L, new NodePos(1, 1, 0), 4.0, "SPHERE", List.of(), List.of(), Map.of(), "uuid-red"));
        s.addEvent(new ImpactEvent(
                1040L,
                4L,
                new NodePos(2, 1, 0),
                "ARROW",
                10.0,
                0.3,
                false,
                List.of(),
                List.of(),
                Map.of(),
                "uuid-blue"));
        return s;
    }

    @Test
    @DisplayName("a v2 session round-trips every new field")
    void v2RoundTrip() {
        RecordingSession out = SessionIO.fromJson(SessionIO.toJson(v2Session()));

        // A freshly-built session now stamps the current schema version (3); the v2
        // FIELDS it carries are what this test pins, and they all survive a round-trip.
        assertEquals(RecordingSession.SCHEMA_VERSION, out.getSchemaVersion());
        assertEquals("0.1.0-test", out.getEngineVersion());
        assertEquals("m42", out.getMetadata().get("matchId"));
        assertEquals("RedKnight", out.getActors().get("uuid-red"));
        assertEquals(99, out.getPhysicsConfig().getMaxCascadeSteps(), "the recorded physics config survives");

        // Per-block material id survives on the initial state.
        BlockData planks = out.getInitialState().getBlocks().stream()
                .filter(b -> b.y() == 1)
                .findFirst()
                .orElseThrow();
        assertEquals("minecraft:oak_planks[axis=y]", planks.materialId());

        // Per-event actor ids survive on all four attributed event kinds.
        assertEquals("uuid-red", actorOf(out, 1L));
        assertEquals("uuid-blue", actorOf(out, 2L));
        assertEquals("uuid-red", actorOf(out, 3L));
        assertEquals("uuid-blue", actorOf(out, 4L));

        // The placed block's grounding flag and full material spec survive — without
        // them replay would guess the anchor and rebuild a default (INERT, res 1.0) spec.
        BlockPlaceEvent place = (BlockPlaceEvent) out.getEvents().stream()
                .filter(ev -> ev.sequenceId() == 2L)
                .findFirst()
                .orElseThrow();
        assertTrue(place.grounded(), "the recorded grounded anchor survives the round-trip");
        assertEquals(3.0, place.blastResistance(), 1e-9, "blast resistance survives");
        assertEquals(5.0, place.fireResistance(), 1e-9, "fire resistance survives");
        assertEquals(ThermalClass.MASONRY, place.thermalClass(), "thermal class survives");
    }

    @Test
    @DisplayName("a pre-v2 JSON (no new fields) still loads, defaulting them to null/empty")
    void v1BackCompat() {
        // A minimal v1-shaped file: schemaVersion 1, no physicsConfig/engineVersion/
        // metadata/actors, an event with no actorId, a block with no materialId.
        String v1Json =
                """
                {
                  "schemaVersion": 1,
                  "sessionId": "old",
                  "startTimeMs": 0,
                  "worldId": "world",
                  "initialState": {
                    "worldId": "world",
                    "version": 3,
                    "blocks": [ { "x": 0, "y": 0, "z": 0, "mass": 0.0, "maxLoad": 1.0, "grounded": true } ]
                  },
                  "events": [
                    { "type": "BLOCK_BREAK", "timestampMs": 5, "sequenceId": 1, "pos": {"x":0,"y":1,"z":0},
                      "materialId": "STONE", "collapsed": [] },
                    { "type": "BLOCK_PLACE", "timestampMs": 6, "sequenceId": 2, "pos": {"x":0,"y":2,"z":0},
                      "materialId": "STONE", "mass": 2.0, "maxLoad": 30.0, "collapsed": [] }
                  ]
                }
                """;

        RecordingSession out = SessionIO.fromJson(v1Json);

        assertNull(out.getEngineVersion(), "no engine version on an old file");
        assertNull(out.getPhysicsConfig(), "no recorded config on an old file");
        assertTrue(out.getMetadata().isEmpty(), "metadata defaults empty");
        assertTrue(out.getActors().isEmpty(), "actors default empty");
        assertNull(actorOf(out, 1L), "an old event has no actor");
        assertNull(out.getInitialState().getBlocks().get(0).materialId(), "an old block has no material id");

        // A pre-thermal place event lacks grounded/blast/fire/thermal — they default
        // to the non-anchored, neutral, INERT spec rather than throwing.
        BlockPlaceEvent place = (BlockPlaceEvent) out.getEvents().stream()
                .filter(ev -> ev.sequenceId() == 2L)
                .findFirst()
                .orElseThrow();
        assertFalse(place.grounded(), "an old place defaults to non-grounded");
        assertEquals(1.0, place.blastResistance(), 1e-9, "old place defaults blast resistance to 1.0");
        assertEquals(1.0, place.fireResistance(), 1e-9, "old place defaults fire resistance to 1.0");
        assertEquals(ThermalClass.INERT, place.thermalClass(), "old place defaults thermal class to INERT");
    }

    private static String actorOf(RecordingSession s, long seq) {
        StruxEvent e = s.getEvents().stream()
                .filter(ev -> ev.sequenceId() == seq)
                .findFirst()
                .orElseThrow();
        return switch (e) {
            case BlockBreakEvent b -> b.actorId();
            case BlockPlaceEvent b -> b.actorId();
            case BlastEvent b -> b.actorId();
            case ImpactEvent b -> b.actorId();
            default -> null;
        };
    }
}
