package dev.gesp.structural.minecraft.recording;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.model.ThermalClass;
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
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON serialization for recording sessions using Gson.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                        SESSION I/O                                 │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Handles serialization/deserialization of RecordingSession to/from │
 *   │  JSON format. Uses custom type adapters to handle the sealed       │
 *   │  StruxEvent interface and NodePos record.                          │
 *   │                                                                     │
 *   │  File format:                                                       │
 *   │  {                                                                  │
 *   │    "schemaVersion": 1,                                             │
 *   │    "sessionId": "session-2024-06-04-14-32-15",                     │
 *   │    "startTimeMs": 1717516800000,                                   │
 *   │    "worldId": "00000000-...",                                      │
 *   │    "initialState": { ... },                                        │
 *   │    "events": [ ... ]                                               │
 *   │  }                                                                  │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public final class SessionIO {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(StruxEvent.class, new StruxEventAdapter())
            .registerTypeAdapter(NodePos.class, new NodePosAdapter())
            .create();

    private SessionIO() {}

    /**
     * Write a session to a file.
     */
    public static void write(RecordingSession session, Path path) throws IOException {
        Files.createDirectories(path.getParent());
        try (Writer writer = Files.newBufferedWriter(path)) {
            GSON.toJson(session, writer);
        }
    }

    /**
     * Read a session from a file.
     */
    public static RecordingSession read(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path)) {
            return GSON.fromJson(reader, RecordingSession.class);
        }
    }

    /**
     * Serialize a session to JSON string.
     */
    public static String toJson(RecordingSession session) {
        return GSON.toJson(session);
    }

    /**
     * Deserialize a session from JSON string.
     */
    public static RecordingSession fromJson(String json) {
        return GSON.fromJson(json, RecordingSession.class);
    }

    /**
     * Custom adapter for the sealed StruxEvent interface.
     */
    private static class StruxEventAdapter implements JsonSerializer<StruxEvent>, JsonDeserializer<StruxEvent> {

        @Override
        public JsonElement serialize(StruxEvent event, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", event.type().name());
            obj.addProperty("timestampMs", event.timestampMs());
            obj.addProperty("sequenceId", event.sequenceId());

            switch (event) {
                case BlockBreakEvent e -> {
                    obj.add("pos", context.serialize(e.pos()));
                    obj.addProperty("materialId", e.materialId());
                    obj.add("collapsed", context.serialize(e.collapsed()));
                    obj.addProperty("actorId", e.actorId());
                    addStress(obj, e.stress());
                }
                case BlockPlaceEvent e -> {
                    obj.add("pos", context.serialize(e.pos()));
                    obj.addProperty("materialId", e.materialId());
                    obj.addProperty("mass", e.mass());
                    obj.addProperty("maxLoad", e.maxLoad());
                    obj.addProperty("blastResistance", e.blastResistance());
                    obj.addProperty("fireResistance", e.fireResistance());
                    obj.addProperty("thermalClass", e.thermalClass().name());
                    obj.addProperty("grounded", e.grounded());
                    obj.add("collapsed", context.serialize(e.collapsed()));
                    obj.addProperty("actorId", e.actorId());
                }
                case BlastEvent e -> {
                    obj.add("center", context.serialize(e.center()));
                    obj.addProperty("power", e.power());
                    obj.addProperty("shape", e.shape());
                    obj.add("destroyed", context.serialize(e.destroyed()));
                    obj.add("collapsed", context.serialize(e.collapsed()));
                    obj.add("damaged", serializeDamagedMap(e.damaged(), context));
                    obj.addProperty("actorId", e.actorId());
                    addStress(obj, e.stress());
                }
                case ImpactEvent e -> {
                    obj.add("pos", context.serialize(e.pos()));
                    obj.addProperty("projectileId", e.projectileId());
                    obj.addProperty("energy", e.energy());
                    obj.addProperty("damageDealt", e.damageDealt());
                    obj.addProperty("destroyed", e.destroyed());
                    obj.add("collapsed", context.serialize(e.collapsed()));
                    // Path fields (new): the blocks the projectile punched through and
                    // the per-block crack levels it left along its path. Old readers
                    // ignore these; old files lack them and deserialize to empty.
                    obj.add("penetrated", context.serialize(e.penetrated()));
                    obj.add("pathDamage", serializeDamagedMap(e.pathDamage(), context));
                    obj.addProperty("actorId", e.actorId());
                }
                case FireDamageEvent e -> {
                    obj.add("pos", context.serialize(e.pos()));
                    obj.addProperty("materialId", e.materialId());
                    obj.addProperty("damageDealt", e.damageDealt());
                    obj.addProperty("totalDamage", e.totalDamage());
                    obj.addProperty("destroyed", e.destroyed());
                    obj.add("collapsed", context.serialize(e.collapsed()));
                }
                case CascadeEvent e -> {
                    obj.add("trigger", context.serialize(e.trigger()));
                    obj.addProperty("reason", e.reason());
                    obj.add("steps", context.serialize(e.steps()));
                }
                case MarkerEvent e -> {
                    obj.addProperty("name", e.name());
                    obj.add("meta", serializeStringMap(e.meta()));
                }
            }

            return obj;
        }

        @Override
        public StruxEvent deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
                throws JsonParseException {
            JsonObject obj = json.getAsJsonObject();
            String type = obj.get("type").getAsString();
            long timestampMs = obj.get("timestampMs").getAsLong();
            long sequenceId = obj.get("sequenceId").getAsLong();

            return switch (StruxEvent.EventType.valueOf(type)) {
                case BLOCK_BREAK -> new BlockBreakEvent(
                        timestampMs,
                        sequenceId,
                        context.deserialize(obj.get("pos"), NodePos.class),
                        obj.get("materialId").getAsString(),
                        deserializeNodePosList(obj.get("collapsed"), context),
                        optString(obj, "actorId"),
                        deserializeStress(obj.get("stress")));
                case BLOCK_PLACE -> new BlockPlaceEvent(
                        timestampMs,
                        sequenceId,
                        context.deserialize(obj.get("pos"), NodePos.class),
                        obj.get("materialId").getAsString(),
                        obj.get("mass").getAsDouble(),
                        obj.get("maxLoad").getAsDouble(),
                        optDouble(obj, "blastResistance", 1.0),
                        optDouble(obj, "fireResistance", 1.0),
                        optThermalClass(obj, "thermalClass"),
                        optBoolean(obj, "grounded", false),
                        deserializeNodePosList(obj.get("collapsed"), context),
                        optString(obj, "actorId"));
                case BLAST -> new BlastEvent(
                        timestampMs,
                        sequenceId,
                        context.deserialize(obj.get("center"), NodePos.class),
                        obj.get("power").getAsDouble(),
                        obj.get("shape").getAsString(),
                        deserializeNodePosList(obj.get("destroyed"), context),
                        deserializeNodePosList(obj.get("collapsed"), context),
                        deserializeDamagedMap(obj.get("damaged"), context),
                        optString(obj, "actorId"),
                        deserializeStress(obj.get("stress")));
                case IMPACT -> new ImpactEvent(
                        timestampMs,
                        sequenceId,
                        context.deserialize(obj.get("pos"), NodePos.class),
                        obj.get("projectileId").getAsString(),
                        obj.get("energy").getAsDouble(),
                        obj.get("damageDealt").getAsDouble(),
                        obj.get("destroyed").getAsBoolean(),
                        deserializeNodePosList(obj.get("collapsed"), context),
                        // Path fields are absent in pre-fix recordings → empty (origin-only replay).
                        deserializeNodePosList(obj.get("penetrated"), context),
                        deserializeDamagedMap(obj.get("pathDamage"), context),
                        optString(obj, "actorId"));
                case FIRE_DAMAGE -> new FireDamageEvent(
                        timestampMs,
                        sequenceId,
                        context.deserialize(obj.get("pos"), NodePos.class),
                        obj.get("materialId").getAsString(),
                        obj.get("damageDealt").getAsDouble(),
                        obj.get("totalDamage").getAsDouble(),
                        obj.get("destroyed").getAsBoolean(),
                        deserializeNodePosList(obj.get("collapsed"), context));
                case CASCADE -> new CascadeEvent(
                        timestampMs,
                        sequenceId,
                        context.deserialize(obj.get("trigger"), NodePos.class),
                        obj.get("reason").getAsString(),
                        deserializeCascadeSteps(obj.get("steps"), context));
                case MARKER -> new MarkerEvent(
                        timestampMs, sequenceId, optString(obj, "name"), deserializeStringMap(obj.get("meta")));
            };
        }

        /** Read an optional string field: {@code null} when absent or JSON null (a v1 file has no actorId). */
        private String optString(JsonObject obj, String key) {
            JsonElement el = obj.get(key);
            return el == null || el.isJsonNull() ? null : el.getAsString();
        }

        /** Read an optional double field, falling back to {@code def} (an older file lacks it). */
        private double optDouble(JsonObject obj, String key, double def) {
            JsonElement el = obj.get(key);
            return el == null || el.isJsonNull() ? def : el.getAsDouble();
        }

        /** Read an optional boolean field, falling back to {@code def} (an older file lacks it). */
        private boolean optBoolean(JsonObject obj, String key, boolean def) {
            JsonElement el = obj.get(key);
            return el == null || el.isJsonNull() ? def : el.getAsBoolean();
        }

        /** Read an optional thermal-class field; an absent or unknown token maps to INERT. */
        private ThermalClass optThermalClass(JsonObject obj, String key) {
            String name = optString(obj, key);
            if (name != null) {
                for (ThermalClass tc : ThermalClass.values()) {
                    if (tc.name().equals(name)) {
                        return tc;
                    }
                }
            }
            return ThermalClass.INERT;
        }

        /** Write the optional v3 stress payload; absent entirely when {@code null}. */
        private void addStress(JsonObject obj, StressDelta stress) {
            if (stress != null && !stress.isEmpty()) {
                JsonObject inner = new JsonObject();
                for (Map.Entry<NodePos, Double> entry : stress.loadRatios().entrySet()) {
                    NodePos p = entry.getKey();
                    inner.addProperty(p.x() + "," + p.y() + "," + p.z(), entry.getValue());
                }
                obj.add("stress", inner);
            }
        }

        /** Read the optional v3 stress payload; {@code null} when absent (a v1/v2 file). */
        private StressDelta deserializeStress(JsonElement json) {
            if (json == null || json.isJsonNull()) {
                return null;
            }
            JsonObject obj = json.getAsJsonObject();
            Map<NodePos, Double> ratios = new LinkedHashMap<>();
            for (String key : obj.keySet()) {
                String[] parts = key.split(",");
                NodePos pos =
                        new NodePos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                ratios.put(pos, obj.get(key).getAsDouble());
            }
            return ratios.isEmpty() ? null : new StressDelta(ratios);
        }

        private JsonElement serializeStringMap(Map<String, String> map) {
            JsonObject obj = new JsonObject();
            for (Map.Entry<String, String> entry : map.entrySet()) {
                obj.addProperty(entry.getKey(), entry.getValue());
            }
            return obj;
        }

        private Map<String, String> deserializeStringMap(JsonElement json) {
            Map<String, String> result = new LinkedHashMap<>();
            if (json == null || json.isJsonNull()) {
                return result;
            }
            JsonObject obj = json.getAsJsonObject();
            for (String key : obj.keySet()) {
                result.put(key, obj.get(key).getAsString());
            }
            return result;
        }

        private JsonElement serializeDamagedMap(Map<NodePos, Double> damaged, JsonSerializationContext context) {
            JsonObject obj = new JsonObject();
            for (Map.Entry<NodePos, Double> entry : damaged.entrySet()) {
                String key = entry.getKey().x() + "," + entry.getKey().y() + ","
                        + entry.getKey().z();
                obj.addProperty(key, entry.getValue());
            }
            return obj;
        }

        private Map<NodePos, Double> deserializeDamagedMap(JsonElement json, JsonDeserializationContext context) {
            Map<NodePos, Double> result = new HashMap<>();
            if (json == null || json.isJsonNull()) {
                return result;
            }
            JsonObject obj = json.getAsJsonObject();
            for (String key : obj.keySet()) {
                String[] parts = key.split(",");
                NodePos pos =
                        new NodePos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                result.put(pos, obj.get(key).getAsDouble());
            }
            return result;
        }

        private List<NodePos> deserializeNodePosList(JsonElement json, JsonDeserializationContext context) {
            List<NodePos> result = new ArrayList<>();
            if (json == null || json.isJsonNull()) {
                return result;
            }
            for (JsonElement element : json.getAsJsonArray()) {
                result.add(context.deserialize(element, NodePos.class));
            }
            return result;
        }

        private List<CascadeStep> deserializeCascadeSteps(JsonElement json, JsonDeserializationContext context) {
            List<CascadeStep> result = new ArrayList<>();
            if (json == null || json.isJsonNull()) {
                return result;
            }
            for (JsonElement element : json.getAsJsonArray()) {
                result.add(context.deserialize(element, CascadeStep.class));
            }
            return result;
        }
    }

    /**
     * Custom adapter for NodePos since it's an immutable record.
     */
    private static class NodePosAdapter implements JsonSerializer<NodePos>, JsonDeserializer<NodePos> {

        @Override
        public JsonElement serialize(NodePos pos, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject obj = new JsonObject();
            obj.addProperty("x", pos.x());
            obj.addProperty("y", pos.y());
            obj.addProperty("z", pos.z());
            return obj;
        }

        @Override
        public NodePos deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
                throws JsonParseException {
            JsonObject obj = json.getAsJsonObject();
            return new NodePos(
                    obj.get("x").getAsInt(),
                    obj.get("y").getAsInt(),
                    obj.get("z").getAsInt());
        }
    }
}
