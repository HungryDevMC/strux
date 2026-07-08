package dev.gesp.structural.recording.io;

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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Compact binary container for a {@link RecordingSession} — the {@code .strx} format.
 *
 * <pre>
 *   FILE
 *   ┌────────────────────────────────────────────────────────────────────┐
 *   │ MAGIC  "STRX"          4 bytes                                      │
 *   │ VERSION                1 byte   (format version, currently 1)       │
 *   │ FLAGS                  1 byte   (bit0 = body is gzip-compressed)    │
 *   │ BODY                   gzip(payload) — everything below             │
 *   └────────────────────────────────────────────────────────────────────┘
 *
 *   PAYLOAD (inside the gzip)
 *     string table   varint n, then n × (varint len + UTF-8 bytes)
 *     session meta   schemaVersion, sessionId*, startMs, endMs, worldId*, engine*
 *     physics config presence byte, then n × (name* + kind + value)   (reflected)
 *     metadata map   varint n, then n × (key* , value*)
 *     actors map     varint n, then n × (key* , value*)
 *     initial state  worldId*, version, explicitTopology, blocks…, edges…
 *     event stream   varint n, then n × event (positions delta+varint encoded)
 *     SESSION INDEX  the footer (see {@link SessionIndex}) — counts + checkpoints
 *
 *   (* = string-table index, written as a varint of index+1; 0 means null.)
 * </pre>
 *
 * <p>Positions are zig-zag varint deltas against a running reference position, so a
 * structure whose blocks march by ±1 costs about a byte per coordinate. Pure Java,
 * no game types beyond the core recording/model classes it serializes.
 */
public final class StruxBinaryCodec {

    /** File magic: the ASCII bytes {@code S T R X}. */
    static final byte[] MAGIC = {'S', 'T', 'R', 'X'};

    /** Current binary format version. Independent of the recording schema version. */
    static final int FORMAT_VERSION = 1;

    /** Flags byte bit 0: the body is gzip-compressed. */
    static final int FLAG_GZIP = 0x01;

    /** Every Nth event gets a checkpoint offset in the {@link SessionIndex}. */
    static final int CHECKPOINT_STRIDE = 64;

    /** The standard file extension for a binary recording. */
    public static final String FILE_EXTENSION = ".strx";

    private StruxBinaryCodec() {}

    // ─────────────────────────────────────────────────────────────────────
    //  WRITE
    // ─────────────────────────────────────────────────────────────────────

    /** Write a session to a {@code .strx} file (creating parent directories). */
    public static void write(RecordingSession session, Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] bytes = toBytes(session);
        Files.write(path, bytes);
    }

    /** Encode a session to the full {@code .strx} byte array (header + gzipped body). */
    public static byte[] toBytes(RecordingSession session) throws IOException {
        byte[] payload = encodePayload(session);

        ByteArrayOutputStream file = new ByteArrayOutputStream(payload.length / 2 + 16);
        file.write(MAGIC);
        file.write(FORMAT_VERSION);
        file.write(FLAG_GZIP);
        try (GZIPOutputStream gz = new GZIPOutputStream(file)) {
            gz.write(payload);
        }
        return file.toByteArray();
    }

    private static byte[] encodePayload(RecordingSession session) throws IOException {
        // First pass interns every string into the table while encoding the
        // body-after-table into a scratch buffer; then we prepend the table.
        StringTable table = new StringTable();
        ByteArrayOutputStream afterTable = new ByteArrayOutputStream();

        writeSessionMeta(afterTable, session, table);
        writePhysicsConfig(afterTable, session.getPhysicsConfig(), table);
        writeStringMap(afterTable, session.getMetadata(), table);
        writeStringMap(afterTable, session.getActors(), table);
        writeInitialState(afterTable, session.getInitialState(), table);
        List<Long> checkpoints = writeEvents(afterTable, session.getEvents(), table);
        writeIndex(afterTable, buildIndex(session, checkpoints));

        // Now emit: string table, then the buffered remainder.
        ByteArrayOutputStream payload = new ByteArrayOutputStream(afterTable.size() + 64);
        writeStringTable(payload, table);
        afterTable.writeTo(payload);
        return payload.toByteArray();
    }

    private static void writeStringTable(OutputStream out, StringTable table) throws IOException {
        List<String> entries = table.entries();
        VarInt.writeUnsigned(out, entries.size());
        for (String s : entries) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            VarInt.writeUnsigned(out, b.length);
            out.write(b);
        }
    }

    private static void writeSessionMeta(OutputStream out, RecordingSession s, StringTable t) throws IOException {
        VarInt.writeUnsigned(out, s.getSchemaVersion());
        writeStrRef(out, s.getSessionId(), t);
        VarInt.writeUnsigned(out, s.getStartTimeMs());
        VarInt.writeUnsigned(out, s.getEndTimeMs());
        writeStrRef(out, s.getWorldId(), t);
        writeStrRef(out, s.getEngineVersion(), t);
    }

    /**
     * Encode the PhysicsConfig generically: every declared primitive field, sorted by
     * name for determinism. Reflection means new config fields ride along automatically
     * rather than being silently dropped by a hand-maintained list.
     */
    private static void writePhysicsConfig(OutputStream out, PhysicsConfig cfg, StringTable t) throws IOException {
        if (cfg == null) {
            out.write(0);
            return;
        }
        out.write(1);
        List<Field> fields = configFields();
        VarInt.writeUnsigned(out, fields.size());
        try {
            for (Field f : fields) {
                writeStrRef(out, f.getName(), t);
                Class<?> type = f.getType();
                DataOutputStream dos = new DataOutputStream(out);
                if (type == double.class) {
                    out.write('d');
                    dos.writeDouble(f.getDouble(cfg));
                } else if (type == int.class) {
                    out.write('i');
                    VarInt.writeSigned(out, f.getInt(cfg));
                } else if (type == boolean.class) {
                    out.write('b');
                    out.write(f.getBoolean(cfg) ? 1 : 0);
                } else if (type == long.class) {
                    out.write('l');
                    VarInt.writeSigned(out, f.getLong(cfg));
                } else {
                    throw new IOException("unsupported PhysicsConfig field type: " + type + " " + f.getName());
                }
            }
        } catch (IllegalAccessException e) {
            throw new IOException("cannot read PhysicsConfig field", e);
        }
    }

    private static void writeStringMap(OutputStream out, Map<String, String> map, StringTable t) throws IOException {
        VarInt.writeUnsigned(out, map.size());
        for (Map.Entry<String, String> e : map.entrySet()) {
            writeStrRef(out, e.getKey(), t);
            writeStrRef(out, e.getValue(), t);
        }
    }

    private static void writeInitialState(OutputStream out, StructureData data, StringTable t) throws IOException {
        if (data == null) {
            out.write(0);
            return;
        }
        out.write(1);
        writeStrRef(out, data.getWorldId(), t);
        VarInt.writeUnsigned(out, data.getVersion());
        out.write(data.isExplicitTopology() ? 1 : 0);

        List<BlockData> blocks = data.getBlocks();
        VarInt.writeUnsigned(out, blocks.size());
        long[] ref = {0, 0, 0};
        DataOutputStream dos = new DataOutputStream(out);
        for (BlockData b : blocks) {
            writePosDelta(out, b.x(), b.y(), b.z(), ref);
            dos.writeDouble(b.mass());
            dos.writeDouble(b.maxLoad());
            out.write(b.grounded() ? 1 : 0);
            dos.writeDouble(b.reinforcement());
            dos.writeDouble(b.damage());
            dos.writeDouble(b.blastResistance());
            dos.writeDouble(b.fireResistance());
            writeStrRef(out, b.materialId(), t);
            writeStrRef(out, b.thermalClass() == null ? null : b.thermalClass().name(), t);
        }

        List<EdgeData> edges = data.getEdges();
        VarInt.writeUnsigned(out, edges.size());
        for (EdgeData e : edges) {
            VarInt.writeSigned(out, e.x1());
            VarInt.writeSigned(out, e.y1());
            VarInt.writeSigned(out, e.z1());
            VarInt.writeSigned(out, e.x2());
            VarInt.writeSigned(out, e.y2());
            VarInt.writeSigned(out, e.z2());
        }
    }

    /** Write the event stream; return the checkpoint byte offsets (into {@code out}). */
    private static List<Long> writeEvents(ByteArrayOutputStream out, List<StruxEvent> events, StringTable t)
            throws IOException {
        List<Long> checkpoints = new ArrayList<>();
        VarInt.writeUnsigned(out, events.size());
        long[] ref = {0, 0, 0}; // running reference position for delta coding
        for (int i = 0; i < events.size(); i++) {
            if (i % CHECKPOINT_STRIDE == 0) {
                checkpoints.add((long) out.size());
            }
            writeEvent(out, events.get(i), t, ref);
        }
        return checkpoints;
    }

    private static void writeEvent(OutputStream out, StruxEvent e, StringTable t, long[] ref) throws IOException {
        out.write(e.type().ordinal());
        VarInt.writeUnsigned(out, e.timestampMs());
        VarInt.writeUnsigned(out, e.sequenceId());
        DataOutputStream dos = new DataOutputStream(out);
        switch (e) {
            case BlockBreakEvent b -> {
                writePos(out, b.pos(), ref);
                writeStrRef(out, b.materialId(), t);
                writePosList(out, b.collapsed(), ref);
                writeStrRef(out, b.actorId(), t);
                writeStress(out, b.stress(), ref);
            }
            case BlockPlaceEvent b -> {
                writePos(out, b.pos(), ref);
                writeStrRef(out, b.materialId(), t);
                dos.writeDouble(b.mass());
                dos.writeDouble(b.maxLoad());
                dos.writeDouble(b.blastResistance());
                dos.writeDouble(b.fireResistance());
                writeStrRef(out, b.thermalClass().name(), t);
                out.write(b.grounded() ? 1 : 0);
                writePosList(out, b.collapsed(), ref);
                writeStrRef(out, b.actorId(), t);
            }
            case BlastEvent b -> {
                writePos(out, b.center(), ref);
                dos.writeDouble(b.power());
                writeStrRef(out, b.shape(), t);
                writePosList(out, b.destroyed(), ref);
                writePosList(out, b.collapsed(), ref);
                writeDamagedMap(out, b.damaged(), ref);
                writeStrRef(out, b.actorId(), t);
                writeStress(out, b.stress(), ref);
            }
            case ImpactEvent b -> {
                writePos(out, b.pos(), ref);
                writeStrRef(out, b.projectileId(), t);
                dos.writeDouble(b.energy());
                dos.writeDouble(b.damageDealt());
                out.write(b.destroyed() ? 1 : 0);
                writePosList(out, b.collapsed(), ref);
                writePosList(out, b.penetrated(), ref);
                writeDamagedMap(out, b.pathDamage(), ref);
                writeStrRef(out, b.actorId(), t);
            }
            case FireDamageEvent b -> {
                writePos(out, b.pos(), ref);
                writeStrRef(out, b.materialId(), t);
                dos.writeDouble(b.damageDealt());
                dos.writeDouble(b.totalDamage());
                out.write(b.destroyed() ? 1 : 0);
                writePosList(out, b.collapsed(), ref);
            }
            case CascadeEvent b -> {
                writePos(out, b.trigger(), ref);
                writeStrRef(out, b.reason(), t);
                List<CascadeStep> steps = b.steps();
                VarInt.writeUnsigned(out, steps.size());
                for (CascadeStep s : steps) {
                    writePos(out, s.pos(), ref);
                    writeStrRef(out, s.materialId(), t);
                    VarInt.writeSigned(out, s.stepNumber());
                    writeStrRef(out, s.reason(), t);
                }
            }
            case MarkerEvent b -> {
                writeStrRef(out, b.name(), t);
                writeStringMap(out, b.meta(), t);
            }
        }
    }

    private static void writeStress(OutputStream out, StressDelta stress, long[] ref) throws IOException {
        if (stress == null || stress.isEmpty()) {
            out.write(0);
            return;
        }
        out.write(1);
        writeDamagedMap(out, stress.loadRatios(), ref);
    }

    private static void writeDamagedMap(OutputStream out, Map<NodePos, Double> map, long[] ref) throws IOException {
        VarInt.writeUnsigned(out, map.size());
        DataOutputStream dos = new DataOutputStream(out);
        // Sort by position for deterministic output (maps may not preserve order).
        List<Map.Entry<NodePos, Double>> entries = new ArrayList<>(map.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey, NodePos.CANONICAL_ORDER));
        for (Map.Entry<NodePos, Double> e : entries) {
            writePos(out, e.getKey(), ref);
            dos.writeDouble(e.getValue());
        }
    }

    private static void writePosList(OutputStream out, List<NodePos> list, long[] ref) throws IOException {
        VarInt.writeUnsigned(out, list.size());
        for (NodePos p : list) {
            writePos(out, p, ref);
        }
    }

    private static void writePos(OutputStream out, NodePos p, long[] ref) throws IOException {
        writePosDelta(out, p.x(), p.y(), p.z(), ref);
    }

    private static void writePosDelta(OutputStream out, int x, int y, int z, long[] ref) throws IOException {
        VarInt.writeSigned(out, x - ref[0]);
        VarInt.writeSigned(out, y - ref[1]);
        VarInt.writeSigned(out, z - ref[2]);
        ref[0] = x;
        ref[1] = y;
        ref[2] = z;
    }

    private static void writeStrRef(OutputStream out, String s, StringTable t) throws IOException {
        // index+1 so that 0 unambiguously means null and real indices stay small.
        VarInt.writeUnsigned(out, t.intern(s) + 1);
    }

    private static void writeIndex(OutputStream out, SessionIndex idx) throws IOException {
        VarInt.writeUnsigned(out, idx.eventCount());
        VarInt.writeUnsigned(out, idx.typeCounts().size());
        for (Map.Entry<StruxEvent.EventType, Integer> e : idx.typeCounts().entrySet()) {
            out.write(e.getKey().ordinal());
            VarInt.writeUnsigned(out, e.getValue());
        }
        VarInt.writeUnsigned(out, idx.durationMs());
        VarInt.writeUnsigned(out, idx.actorEventCounts().size());
        // The codec doesn't keep the actor string table for the index separately; store
        // actor ids inline as length-prefixed UTF-8 so the footer is self-contained.
        for (Map.Entry<String, Integer> e : idx.actorEventCounts().entrySet()) {
            byte[] b = e.getKey().getBytes(StandardCharsets.UTF_8);
            VarInt.writeUnsigned(out, b.length);
            out.write(b);
            VarInt.writeUnsigned(out, e.getValue());
        }
        VarInt.writeUnsigned(out, idx.stride());
        VarInt.writeUnsigned(out, idx.checkpointOffsets().size());
        for (long off : idx.checkpointOffsets()) {
            VarInt.writeUnsigned(out, off);
        }
    }

    private static SessionIndex buildIndex(RecordingSession session, List<Long> checkpoints) {
        Map<StruxEvent.EventType, Integer> typeCounts = new LinkedHashMap<>();
        Map<String, Integer> actorCounts = new LinkedHashMap<>();
        for (StruxEvent e : session.getEvents()) {
            typeCounts.merge(e.type(), 1, Integer::sum);
            String actor = actorOf(e);
            if (actor != null) {
                actorCounts.merge(actor, 1, Integer::sum);
            }
        }
        return new SessionIndex(
                session.eventCount(), typeCounts, session.durationMs(), actorCounts, CHECKPOINT_STRIDE, checkpoints);
    }

    private static String actorOf(StruxEvent e) {
        return switch (e) {
            case BlockBreakEvent b -> b.actorId();
            case BlockPlaceEvent b -> b.actorId();
            case BlastEvent b -> b.actorId();
            case ImpactEvent b -> b.actorId();
            case FireDamageEvent b -> null;
            case CascadeEvent b -> null;
            case MarkerEvent b -> null;
        };
    }

    // ─────────────────────────────────────────────────────────────────────
    //  READ
    // ─────────────────────────────────────────────────────────────────────

    /** Read a session from a {@code .strx} file. */
    public static RecordingSession read(Path path) throws IOException {
        return fromBytes(Files.readAllBytes(path));
    }

    /** Read just the footer index of a {@code .strx} file (still decompresses the body). */
    public static SessionIndex readIndex(Path path) throws IOException {
        return decode(Files.readAllBytes(path)).index;
    }

    /** Decode a full session from a {@code .strx} byte array. */
    public static RecordingSession fromBytes(byte[] bytes) throws IOException {
        return decode(bytes).session;
    }

    private record Decoded(RecordingSession session, SessionIndex index) {}

    private static Decoded decode(byte[] bytes) throws IOException {
        if (bytes.length < 6) {
            throw new StruxFormatException("file too short to be a .strx recording (" + bytes.length + " bytes)");
        }
        if (!Arrays.equals(Arrays.copyOfRange(bytes, 0, 4), MAGIC)) {
            throw new StruxFormatException("bad magic: not a .strx recording");
        }
        int version = bytes[4] & 0xFF;
        if (version != FORMAT_VERSION) {
            throw new StruxFormatException("unsupported .strx format version " + version + " (this build reads version "
                    + FORMAT_VERSION + ")");
        }
        int flags = bytes[5] & 0xFF;
        byte[] body = Arrays.copyOfRange(bytes, 6, bytes.length);
        byte[] payload;
        if ((flags & FLAG_GZIP) != 0) {
            payload = gunzip(body);
        } else {
            payload = body;
        }

        try {
            return decodePayload(payload);
        } catch (EOFException eof) {
            throw new StruxFormatException("truncated .strx recording (body ended mid-record)", eof);
        }
    }

    private static byte[] gunzip(byte[] body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length * 3);
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(body))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = gz.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            throw new StruxFormatException("corrupt .strx body (gzip decompression failed)", e);
        }
        return out.toByteArray();
    }

    private static Decoded decodePayload(byte[] payload) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(payload);

        String[] table = readStringTable(in);
        RecordingSession session = new RecordingSession();
        readSessionMeta(in, session, table);
        session.setPhysicsConfig(readPhysicsConfig(in, table));
        session.setMetadata(readStringMap(in, table));
        session.setActors(readStringMap(in, table));
        session.setInitialState(readInitialState(in, table));
        session.setEvents(readEvents(in, table));
        SessionIndex index = readIndex(in);
        return new Decoded(session, index);
    }

    private static String[] readStringTable(InputStream in) throws IOException {
        long count = VarInt.readUnsigned(in);
        String[] table = new String[(int) count];
        for (int i = 0; i < count; i++) {
            int len = (int) VarInt.readUnsigned(in);
            byte[] b = readN(in, len);
            table[i] = new String(b, StandardCharsets.UTF_8);
        }
        return table;
    }

    private static void readSessionMeta(InputStream in, RecordingSession s, String[] t) throws IOException {
        s.setSchemaVersion((int) VarInt.readUnsigned(in));
        s.setSessionId(readStrRef(in, t));
        s.setStartTimeMs(VarInt.readUnsigned(in));
        s.setEndTimeMs(VarInt.readUnsigned(in));
        s.setWorldId(readStrRef(in, t));
        s.setEngineVersion(readStrRef(in, t));
    }

    private static PhysicsConfig readPhysicsConfig(InputStream in, String[] t) throws IOException {
        int present = in.read();
        if (present <= 0) {
            return null;
        }
        PhysicsConfig cfg = new PhysicsConfig();
        long count = VarInt.readUnsigned(in);
        Map<String, Field> byName = new LinkedHashMap<>();
        for (Field f : configFields()) {
            byName.put(f.getName(), f);
        }
        try {
            for (int i = 0; i < count; i++) {
                String name = readStrRef(in, t);
                int kind = in.read();
                if (kind < 0) {
                    throw new EOFException("truncated config field");
                }
                Field f = byName.get(name); // null when the file has a field this build dropped
                switch (kind) {
                    case 'd' -> {
                        double v = new DataInputStream(in).readDouble();
                        if (f != null) {
                            f.setDouble(cfg, v);
                        }
                    }
                    case 'i' -> {
                        long v = VarInt.readSigned(in);
                        if (f != null) {
                            f.setInt(cfg, (int) v);
                        }
                    }
                    case 'l' -> {
                        long v = VarInt.readSigned(in);
                        if (f != null) {
                            f.setLong(cfg, v);
                        }
                    }
                    case 'b' -> {
                        int v = in.read();
                        if (f != null) {
                            f.setBoolean(cfg, v != 0);
                        }
                    }
                    default -> throw new StruxFormatException("unknown config field kind: " + kind);
                }
            }
        } catch (IllegalAccessException e) {
            throw new IOException("cannot set PhysicsConfig field", e);
        }
        return cfg;
    }

    private static Map<String, String> readStringMap(InputStream in, String[] t) throws IOException {
        long count = VarInt.readUnsigned(in);
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String key = readStrRef(in, t);
            String val = readStrRef(in, t);
            map.put(key, val);
        }
        return map;
    }

    private static StructureData readInitialState(InputStream in, String[] t) throws IOException {
        int present = in.read();
        if (present <= 0) {
            return null;
        }
        String worldId = readStrRef(in, t);
        int version = (int) VarInt.readUnsigned(in);
        boolean explicit = in.read() != 0;
        StructureData data = new StructureData(worldId);
        data.setVersion(version);
        data.setExplicitTopology(explicit);

        long blockCount = VarInt.readUnsigned(in);
        long[] ref = {0, 0, 0};
        List<BlockData> blocks = new ArrayList<>((int) blockCount);
        DataInputStream dis = new DataInputStream(in);
        for (int i = 0; i < blockCount; i++) {
            int[] pos = readPosDelta(in, ref);
            double mass = dis.readDouble();
            double maxLoad = dis.readDouble();
            boolean grounded = in.read() != 0;
            double reinforcement = dis.readDouble();
            double damage = dis.readDouble();
            double blastResistance = dis.readDouble();
            double fireResistance = dis.readDouble();
            String materialId = readStrRef(in, t);
            String thermalName = readStrRef(in, t);
            blocks.add(new BlockData(
                    pos[0],
                    pos[1],
                    pos[2],
                    mass,
                    maxLoad,
                    grounded,
                    reinforcement,
                    damage,
                    blastResistance,
                    fireResistance,
                    materialId,
                    thermalOf(thermalName)));
        }
        data.setBlocks(blocks);

        long edgeCount = VarInt.readUnsigned(in);
        for (int i = 0; i < edgeCount; i++) {
            data.addEdge(new EdgeData(
                    (int) VarInt.readSigned(in),
                    (int) VarInt.readSigned(in),
                    (int) VarInt.readSigned(in),
                    (int) VarInt.readSigned(in),
                    (int) VarInt.readSigned(in),
                    (int) VarInt.readSigned(in)));
        }
        return data;
    }

    private static List<StruxEvent> readEvents(InputStream in, String[] t) throws IOException {
        long count = VarInt.readUnsigned(in);
        List<StruxEvent> events = new ArrayList<>((int) count);
        long[] ref = {0, 0, 0};
        StruxEvent.EventType[] types = StruxEvent.EventType.values();
        for (int i = 0; i < count; i++) {
            int typeOrdinal = in.read();
            if (typeOrdinal < 0 || typeOrdinal >= types.length) {
                throw new StruxFormatException("unknown event type ordinal: " + typeOrdinal);
            }
            long timestampMs = VarInt.readUnsigned(in);
            long sequenceId = VarInt.readUnsigned(in);
            events.add(readEvent(in, types[typeOrdinal], timestampMs, sequenceId, t, ref));
        }
        return events;
    }

    private static StruxEvent readEvent(
            InputStream in, StruxEvent.EventType type, long ts, long seq, String[] t, long[] ref) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        return switch (type) {
            case BLOCK_BREAK -> new BlockBreakEvent(
                    ts,
                    seq,
                    readPos(in, ref),
                    readStrRef(in, t),
                    readPosList(in, ref),
                    readStrRef(in, t),
                    readStress(in, ref));
            case BLOCK_PLACE -> {
                NodePos pos = readPos(in, ref);
                String materialId = readStrRef(in, t);
                double mass = dis.readDouble();
                double maxLoad = dis.readDouble();
                double blastResistance = dis.readDouble();
                double fireResistance = dis.readDouble();
                String thermal = readStrRef(in, t);
                boolean grounded = in.read() != 0;
                List<NodePos> collapsed = readPosList(in, ref);
                String actorId = readStrRef(in, t);
                yield new BlockPlaceEvent(
                        ts,
                        seq,
                        pos,
                        materialId,
                        mass,
                        maxLoad,
                        blastResistance,
                        fireResistance,
                        thermalOf(thermal),
                        grounded,
                        collapsed,
                        actorId);
            }
            case BLAST -> {
                NodePos center = readPos(in, ref);
                double power = dis.readDouble();
                String shape = readStrRef(in, t);
                List<NodePos> destroyed = readPosList(in, ref);
                List<NodePos> collapsed = readPosList(in, ref);
                Map<NodePos, Double> damaged = readDamagedMap(in, ref);
                String actorId = readStrRef(in, t);
                StressDelta stress = readStress(in, ref);
                yield new BlastEvent(ts, seq, center, power, shape, destroyed, collapsed, damaged, actorId, stress);
            }
            case IMPACT -> {
                NodePos pos = readPos(in, ref);
                String projectileId = readStrRef(in, t);
                double energy = dis.readDouble();
                double damageDealt = dis.readDouble();
                boolean destroyed = in.read() != 0;
                List<NodePos> collapsed = readPosList(in, ref);
                List<NodePos> penetrated = readPosList(in, ref);
                Map<NodePos, Double> pathDamage = readDamagedMap(in, ref);
                String actorId = readStrRef(in, t);
                yield new ImpactEvent(
                        ts,
                        seq,
                        pos,
                        projectileId,
                        energy,
                        damageDealt,
                        destroyed,
                        collapsed,
                        penetrated,
                        pathDamage,
                        actorId);
            }
            case FIRE_DAMAGE -> {
                NodePos pos = readPos(in, ref);
                String materialId = readStrRef(in, t);
                double damageDealt = dis.readDouble();
                double totalDamage = dis.readDouble();
                boolean destroyed = in.read() != 0;
                List<NodePos> collapsed = readPosList(in, ref);
                yield new FireDamageEvent(ts, seq, pos, materialId, damageDealt, totalDamage, destroyed, collapsed);
            }
            case CASCADE -> {
                NodePos trigger = readPos(in, ref);
                String reason = readStrRef(in, t);
                long stepCount = VarInt.readUnsigned(in);
                List<CascadeStep> steps = new ArrayList<>((int) stepCount);
                for (int i = 0; i < stepCount; i++) {
                    steps.add(new CascadeStep(
                            readPos(in, ref), readStrRef(in, t), (int) VarInt.readSigned(in), readStrRef(in, t)));
                }
                yield new CascadeEvent(ts, seq, trigger, reason, steps);
            }
            case MARKER -> new MarkerEvent(ts, seq, readStrRef(in, t), readStringMap(in, t));
        };
    }

    private static StressDelta readStress(InputStream in, long[] ref) throws IOException {
        int present = in.read();
        if (present <= 0) {
            return null;
        }
        Map<NodePos, Double> ratios = readDamagedMap(in, ref);
        return ratios.isEmpty() ? null : new StressDelta(ratios);
    }

    private static Map<NodePos, Double> readDamagedMap(InputStream in, long[] ref) throws IOException {
        long count = VarInt.readUnsigned(in);
        Map<NodePos, Double> map = new LinkedHashMap<>();
        DataInputStream dis = new DataInputStream(in);
        for (int i = 0; i < count; i++) {
            NodePos pos = readPos(in, ref);
            map.put(pos, dis.readDouble());
        }
        return map;
    }

    private static List<NodePos> readPosList(InputStream in, long[] ref) throws IOException {
        long count = VarInt.readUnsigned(in);
        List<NodePos> list = new ArrayList<>((int) count);
        for (int i = 0; i < count; i++) {
            list.add(readPos(in, ref));
        }
        return list;
    }

    private static NodePos readPos(InputStream in, long[] ref) throws IOException {
        int[] p = readPosDelta(in, ref);
        return new NodePos(p[0], p[1], p[2]);
    }

    private static int[] readPosDelta(InputStream in, long[] ref) throws IOException {
        long x = ref[0] + VarInt.readSigned(in);
        long y = ref[1] + VarInt.readSigned(in);
        long z = ref[2] + VarInt.readSigned(in);
        ref[0] = x;
        ref[1] = y;
        ref[2] = z;
        return new int[] {(int) x, (int) y, (int) z};
    }

    private static String readStrRef(InputStream in, String[] table) throws IOException {
        long idxPlusOne = VarInt.readUnsigned(in);
        if (idxPlusOne == 0) {
            return null;
        }
        int idx = (int) (idxPlusOne - 1);
        if (idx >= table.length) {
            throw new StruxFormatException("string reference out of range: " + idx + " / " + table.length);
        }
        return table[idx];
    }

    private static SessionIndex readIndex(InputStream in) throws IOException {
        int eventCount = (int) VarInt.readUnsigned(in);
        int typeCount = (int) VarInt.readUnsigned(in);
        StruxEvent.EventType[] types = StruxEvent.EventType.values();
        Map<StruxEvent.EventType, Integer> typeCounts = new LinkedHashMap<>();
        for (int i = 0; i < typeCount; i++) {
            int ord = in.read();
            if (ord < 0 || ord >= types.length) {
                throw new StruxFormatException("bad event-type ordinal in index: " + ord);
            }
            typeCounts.put(types[ord], (int) VarInt.readUnsigned(in));
        }
        long durationMs = VarInt.readUnsigned(in);
        int actorCount = (int) VarInt.readUnsigned(in);
        Map<String, Integer> actorCounts = new LinkedHashMap<>();
        for (int i = 0; i < actorCount; i++) {
            int len = (int) VarInt.readUnsigned(in);
            String actor = new String(readN(in, len), StandardCharsets.UTF_8);
            actorCounts.put(actor, (int) VarInt.readUnsigned(in));
        }
        int stride = (int) VarInt.readUnsigned(in);
        int checkpointCount = (int) VarInt.readUnsigned(in);
        List<Long> checkpoints = new ArrayList<>(checkpointCount);
        for (int i = 0; i < checkpointCount; i++) {
            checkpoints.add(VarInt.readUnsigned(in));
        }
        return new SessionIndex(eventCount, typeCounts, durationMs, actorCounts, stride, checkpoints);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────────────────────

    /** Declared primitive fields of PhysicsConfig, sorted by name for deterministic order. */
    private static List<Field> configFields() {
        List<Field> fields = new ArrayList<>();
        for (Field f : PhysicsConfig.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                continue;
            }
            Class<?> type = f.getType();
            if (type == double.class || type == int.class || type == boolean.class || type == long.class) {
                f.setAccessible(true);
                fields.add(f);
            }
        }
        fields.sort(Comparator.comparing(Field::getName));
        return fields;
    }

    private static ThermalClass thermalOf(String name) {
        if (name != null) {
            for (ThermalClass tc : ThermalClass.values()) {
                if (tc.name().equals(name)) {
                    return tc;
                }
            }
        }
        return ThermalClass.INERT;
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int read = 0;
        while (read < n) {
            int r = in.read(b, read, n - read);
            if (r < 0) {
                throw new EOFException("expected " + n + " bytes, got " + read);
            }
            read += r;
        }
        return b;
    }
}
