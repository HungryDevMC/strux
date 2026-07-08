package dev.gesp.structural.minecraft.recording;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.persistence.BlockData;
import dev.gesp.structural.persistence.StructureConverter;
import dev.gesp.structural.persistence.StructureData;
import dev.gesp.structural.recording.EventRecorder;
import dev.gesp.structural.recording.MarkerEvent;
import dev.gesp.structural.recording.RecordingSession;
import dev.gesp.structural.recording.ReplayEngine;
import dev.gesp.structural.recording.ReplayEngine.Divergence;
import dev.gesp.structural.recording.ReplayEngine.InvariantViolation;
import dev.gesp.structural.recording.ReplayEngine.ReplayResult;
import dev.gesp.structural.recording.StruxEvent;
import dev.gesp.structural.recording.io.StruxBinaryCodec;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * Minecraft-specific event recorder with buffered async file writing.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                    MINECRAFT EVENT RECORDER                        │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Design goals:                                                      │
 *   │                                                                     │
 *   │  1. ZERO MAIN-THREAD BLOCKING                                      │
 *   │     All disk I/O happens on a dedicated daemon thread              │
 *   │                                                                     │
 *   │  2. LOCK-FREE EVENT CAPTURE                                        │
 *   │     ConcurrentLinkedQueue for ~50ns add operations                 │
 *   │                                                                     │
 *   │  3. BACKPRESSURE PROTECTION                                        │
 *   │     Per-tick throttling prevents lag spikes                        │
 *   │     Old events dropped if buffer overflows                         │
 *   │                                                                     │
 *   │  4. AUTO-CLEANUP                                                   │
 *   │     Old sessions deleted when max-sessions exceeded                │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class MinecraftEventRecorder implements EventRecorder {

    private static final String JSON_EXTENSION = ".json";
    private static final String BINARY_EXTENSION = StruxBinaryCodec.FILE_EXTENSION;
    private static final DateTimeFormatter SESSION_NAME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss").withZone(ZoneId.systemDefault());

    private final Path recordingsDir;
    private final RecordingConfig config;
    private final PhysicsConfig physicsConfig;
    private final String engineVersion;
    private final Logger logger;
    private final ScheduledExecutorService executor;

    private final ConcurrentLinkedQueue<StruxEvent> buffer = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final AtomicLong sequenceCounter = new AtomicLong(0);
    private final AtomicInteger eventsThisTick = new AtomicInteger(0);
    private final AtomicInteger droppedEvents = new AtomicInteger(0);

    private volatile RecordingSession currentSession;
    private volatile String worldId;
    private volatile StructureData initialState;
    private volatile boolean verifyCurrentOnStop = true;
    // Whether the ACTIVE session captures schema-v3 StressDelta payloads. Starts from the
    // config default each session; the RecordingService raises it for "match" recordings.
    private volatile boolean captureStress;
    // Whether the ACTIVE session writes binary (.strx). Starts from the config default each
    // session; a debug caller can force JSON output for a single session.
    private volatile boolean binaryFormat;

    public MinecraftEventRecorder(Path dataFolder, RecordingConfig config, PhysicsConfig physicsConfig, Logger logger) {
        this(dataFolder, config, physicsConfig, null, logger);
    }

    /**
     * @param engineVersion the plugin version stamped onto each session (so a replay can detect
     *                      drift between the engine that recorded and the one replaying); may be
     *                      {@code null} when unknown
     */
    public MinecraftEventRecorder(
            Path dataFolder, RecordingConfig config, PhysicsConfig physicsConfig, String engineVersion, Logger logger) {
        this.recordingsDir = dataFolder.resolve("recordings");
        this.config = config;
        this.physicsConfig = physicsConfig;
        this.engineVersion = engineVersion;
        this.logger = logger;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "StructuralIntegrity-Recorder");
            t.setDaemon(true);
            return t;
        });

        try {
            Files.createDirectories(recordingsDir);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to create recordings directory", e);
        }

        // Schedule periodic buffer flush. Guarded: a thrown task body silently
        // cancels ALL future runs of a scheduleAtFixedRate schedule, so one bad flush
        // must never kill the periodic drain for the recorder's whole lifetime.
        executor.scheduleAtFixedRate(this::flushTick, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Start recording for a world. If a recording is already in progress it is
     * stopped first. The session lands flat under {@code recordings/} with a
     * {@code session-<timestamp>} name and is determinism-verified on stop.
     *
     * @param worldId the world identifier
     * @param graph   the current structure graph (for initial state snapshot)
     * @return the session ID
     */
    public String startRecording(String worldId, StructureGraph graph) {
        if (recording.get()) {
            stopRecording();
        }
        String sessionId = "session-" + SESSION_NAME_FORMAT.format(Instant.now());
        return beginSession(sessionId, worldId, graph, true);
    }

    /**
     * Start a recording under an explicit session id (which may contain a single
     * subfolder, e.g. {@code "match/match-arena3-<timestamp>"}). Unlike
     * {@link #startRecording(String, StructureGraph)} this does <em>not</em>
     * auto-stop a running recording; callers must check {@link #isRecording()}
     * first. This is the path the programmatic {@code RecordingService} uses so
     * that overlapping sessions are rejected rather than silently replacing one
     * another.
     *
     * @param sessionId   the full session id, optionally prefixed with one subfolder
     * @param worldId     the world identifier
     * @param graph       the current structure graph (for initial state snapshot)
     * @param verifyOnStop whether to replay-verify determinism when the session stops
     * @return the session ID, or {@code null} if a recording is already in progress
     */
    public String startRecording(String sessionId, String worldId, StructureGraph graph, boolean verifyOnStop) {
        if (recording.get()) {
            return null;
        }
        return beginSession(sessionId, worldId, graph, verifyOnStop);
    }

    private String beginSession(String sessionId, String worldId, StructureGraph graph, boolean verifyOnStop) {
        this.worldId = worldId;
        // A world with no tracked structure yet (a brand-new world, or auto-record at
        // boot before any block is registered) has no graph — snapshot an empty one
        // rather than NPE inside StructureConverter.toData(graph).getAllNodes(). The
        // session then simply starts from an empty initial state.
        StructureGraph safeGraph = graph != null ? graph : new StructureGraph();
        // The core converter has no block-state strings (it only sees the graph). Enrich
        // each block's materialId from the live world so a replay can render the exact
        // texture/orientation, not just a default cube.
        this.initialState = withBlockStates(StructureConverter.toData(safeGraph, worldId), worldId);
        this.verifyCurrentOnStop = verifyOnStop;
        // Default to the config setting; a host (RecordingService) may override per session.
        this.captureStress = config.isCaptureStress();
        this.binaryFormat = config.isBinaryFormat();

        long startTime = System.currentTimeMillis();
        currentSession = new RecordingSession(sessionId, startTime, worldId, initialState);
        // Record the engine + physics the session ran under, so a replay re-simulates with
        // the recording's config (not the server's current one) and can flag version drift.
        currentSession.setPhysicsConfig(physicsConfig);
        currentSession.setEngineVersion(engineVersion);
        sequenceCounter.set(0);
        droppedEvents.set(0);
        recording.set(true);

        logger.info("Started recording session: " + sessionId);
        cleanupOldSessions();

        return sessionId;
    }

    /**
     * Attach opaque host context (match id, rosters, actor display names) to the
     * active session. Called right after {@link #startRecording} by a host that has
     * extra context to record; a no-op when nothing is recording. The maps are
     * copied onto the session, so the engine never interprets them.
     */
    public void setSessionContext(Map<String, String> metadata, Map<String, String> actors) {
        RecordingSession session = currentSession;
        if (session == null) {
            return;
        }
        if (metadata != null && !metadata.isEmpty()) {
            session.setMetadata(metadata);
        }
        if (actors != null && !actors.isEmpty()) {
            session.setActors(actors);
        }
    }

    /**
     * Whether the active session captures schema-v3 {@link dev.gesp.structural.recording.StressDelta}
     * payloads. Listeners check this before building a stress collector, so the extra work
     * only happens when capture is on. False when nothing is recording.
     */
    public boolean isCaptureStress() {
        return recording.get() && captureStress;
    }

    /**
     * Override stress capture for the active session (a host raises it for "match"
     * recordings even when the global config default is off). No-op when nothing is
     * recording.
     */
    public void setCaptureStress(boolean captureStress) {
        if (recording.get()) {
            this.captureStress = captureStress;
        }
    }

    /**
     * Force the active session to write JSON instead of the default binary {@code .strx}
     * (a debug/diff aid — the {@code --json} flag). No-op when nothing is recording.
     */
    public void setWriteJson(boolean writeJson) {
        if (recording.get()) {
            this.binaryFormat = !writeJson;
        }
    }

    /**
     * Record a named {@link dev.gesp.structural.recording.MarkerEvent} on the active
     * session. A no-op when nothing is recording. Used by hosts/tests to drop jump
     * targets ("round start", "wall breached") onto the timeline.
     */
    public void mark(String name, Map<String, String> meta) {
        if (!recording.get()) {
            return;
        }
        record(new MarkerEvent(System.currentTimeMillis(), nextSequenceId(), name, meta));
    }

    /**
     * Copy {@code data}, stamping each block with its live block-state string (e.g.
     * {@code "minecraft:oak_planks[axis=y]"}). Returns the data unchanged when the world
     * can't be resolved (a non-UUID id, or it was unloaded) — every block then keeps a
     * {@code null} materialId, which the replay renderer falls back from to a default cube.
     */
    private StructureData withBlockStates(StructureData data, String worldId) {
        World world = resolveWorld(worldId);
        if (world == null) {
            return data;
        }
        List<BlockData> enriched = new ArrayList<>(data.getBlocks().size());
        for (BlockData b : data.getBlocks()) {
            String state = world.getBlockAt(b.x(), b.y(), b.z()).getBlockData().getAsString();
            enriched.add(new BlockData(
                    b.x(),
                    b.y(),
                    b.z(),
                    b.mass(),
                    b.maxLoad(),
                    b.grounded(),
                    b.reinforcement(),
                    b.damage(),
                    b.blastResistance(),
                    b.fireResistance(),
                    state,
                    b.thermalClass()));
        }
        data.setBlocks(enriched);
        return data;
    }

    /** Resolve a world from its UUID-string id, or {@code null} if it isn't a loaded world. */
    private World resolveWorld(String worldId) {
        if (worldId == null) {
            return null;
        }
        try {
            return Bukkit.getWorld(UUID.fromString(worldId));
        } catch (IllegalArgumentException notAUuid) {
            return Bukkit.getWorld(worldId);
        }
    }

    /**
     * Stop recording and save the session.
     *
     * @return the saved session ID, or null if not recording
     */
    public String stopRecording() {
        if (!recording.compareAndSet(true, false)) {
            return null;
        }

        RecordingSession session = currentSession;
        if (session == null) {
            return null;
        }

        // Clear the live reference up front so the periodic flush no longer touches
        // this session; events already buffered are drained by the final pass below.
        String sessionId = session.getSessionId();
        boolean verify = verifyCurrentOnStop;
        currentSession = null;
        initialState = null;

        // Finalize on the executor — the ONLY thread that mutates the session — so the
        // final drain never races the periodic flush (concurrent ArrayList.add) and can
        // never NPE on a field nulled by another thread. record() already drops events
        // now that recording is false, so the buffer's contents are fixed.
        executor.execute(() -> {
            drainInto(session);
            session.setEndTimeMs(System.currentTimeMillis());
            logger.info("Stopped recording session: " + sessionId + " (" + session.eventCount() + " events)");
            saveSession(session, verify);
        });

        int dropped = droppedEvents.get();
        if (dropped > 0) {
            logger.warning("Dropped " + dropped + " events during recording (buffer overflow)");
        }
        return sessionId;
    }

    @Override
    public void record(StruxEvent event) {
        if (!recording.get()) {
            return;
        }

        // Per-tick throttling
        if (eventsThisTick.get() >= config.getMaxEventsPerTick()) {
            droppedEvents.incrementAndGet();
            return;
        }
        eventsThisTick.incrementAndGet();

        // Buffer overflow protection: the 2x cap is only a last-resort guard.
        int size = buffer.size();
        if (size >= config.getBufferSize() * 2) {
            buffer.poll(); // Drop oldest
            droppedEvents.incrementAndGet();
        }

        buffer.add(event);

        // Documented behavior (config.yml: "flush every <buffer-size> events or 1 second,
        // whichever is first"). Without a size-triggered flush, only the 1s periodic flush
        // and stop drain the buffer — so a burst that emits more than the 2x cap between two
        // periodic flushes (a large TNT cascade easily does) silently loses the oldest
        // events, which then shows up as a determinism divergence on stop. Route through the
        // executor — the one thread that mutates the session — exactly like the periodic flush.
        if (size + 1 >= config.getBufferSize()) {
            executor.execute(this::flushBuffer);
        }
    }

    @Override
    public boolean isRecording() {
        return recording.get();
    }

    /**
     * The id of the session currently being recorded, or {@code null} if no
     * recording is in progress.
     */
    public String currentSessionId() {
        RecordingSession session = currentSession;
        return session != null ? session.getSessionId() : null;
    }

    /**
     * The start time (millis since epoch) of the active session, or {@code 0} when
     * nothing is recording. This is the shared timebase a dependent plugin (the Siege
     * gamemode) stamps its own parallel track against.
     */
    public long currentSessionStartMs() {
        RecordingSession session = currentSession;
        return session != null ? session.getStartTimeMs() : 0L;
    }

    @Override
    public void flush() {
        // Route through the executor so the session is only ever mutated on that one
        // thread (never concurrently with the periodic flush or the stop drain).
        executor.execute(this::flushBuffer);
    }

    @Override
    public void close() {
        stopRecording();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Called at the start of each server tick to reset the per-tick counter.
     */
    public void onTickStart() {
        eventsThisTick.set(0);
    }

    /**
     * Get the next sequence ID for event ordering.
     */
    public long nextSequenceId() {
        return sequenceCounter.incrementAndGet();
    }

    /**
     * List all saved recording sessions.
     */
    public List<String> listSessions() {
        // Flat sessions (recordings/<name>.strx|.json) plus typed sessions one folder
        // deep (recordings/<type>/<name>.strx|.json). The returned id keeps the folder
        // prefix AND drops the extension, e.g. "match/match-arena3-...", so it
        // round-trips through loadSession / deleteSession (which try both extensions and
        // resolve relative to recordingsDir). Ordered newest-first by real mtime.
        Map<String, Path> fileOf = new LinkedHashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(recordingsDir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    String folder = entry.getFileName().toString();
                    try (DirectoryStream<Path> sub = Files.newDirectoryStream(entry)) {
                        for (Path path : sub) {
                            String id = recordingId(path);
                            if (id != null) {
                                fileOf.put(folder + "/" + id, path);
                            }
                        }
                    }
                } else {
                    String id = recordingId(entry);
                    if (id != null) {
                        fileOf.put(id, entry);
                    }
                }
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to list recordings", e);
        }
        return orderedByRecency(fileOf);
    }

    /**
     * The recording ids of {@code fileOf}, newest first by real modification time — NOT by
     * name. Flat "session-&lt;ts&gt;" ids and tagged "match/"|"build/"|"manual/" ids sort
     * alphabetically by prefix, which is not chronological, so a name sort made
     * cleanupOldSessions delete freshly recorded tagged sessions while keeping arbitrarily
     * old flat ones. Package-private for a deterministic ordering test.
     */
    static List<String> orderedByRecency(Map<String, Path> fileOf) {
        List<String> sessions = new ArrayList<>(fileOf.keySet());
        sessions.sort(Comparator.comparing((String id) -> lastModified(fileOf.get(id)))
                .reversed());
        return sessions;
    }

    /** Last-modified time of a recording file, epoch on any read error (sorts it oldest). */
    private static FileTime lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException e) {
            return FileTime.fromMillis(0L);
        }
    }

    /** The session id (name without extension) for a recording file, or null if it isn't one. */
    private static String recordingId(Path path) {
        String name = path.getFileName().toString();
        if (name.endsWith(BINARY_EXTENSION)) {
            return name.substring(0, name.length() - BINARY_EXTENSION.length());
        }
        if (name.endsWith(JSON_EXTENSION)) {
            return name.substring(0, name.length() - JSON_EXTENSION.length());
        }
        return null;
    }

    /**
     * Resolve the on-disk file for a session id, preferring binary {@code .strx} but
     * falling back to {@code .json}. Returns null if neither exists.
     */
    private Path resolveSessionFile(String sessionId) {
        Path binary = confinedRecording(sessionId, BINARY_EXTENSION);
        if (binary != null && Files.exists(binary)) {
            return binary;
        }
        Path json = confinedRecording(sessionId, JSON_EXTENSION);
        return json != null && Files.exists(json) ? json : null;
    }

    /**
     * Load a recording session by name. Reads {@code .strx} or {@code .json}, whichever
     * exists (binary preferred).
     */
    public RecordingSession loadSession(String sessionId) throws IOException {
        Path path = resolveSessionFile(sessionId);
        if (path == null) {
            return null;
        }
        return path.getFileName().toString().endsWith(BINARY_EXTENSION)
                ? StruxBinaryCodec.read(path)
                : SessionIO.read(path);
    }

    /**
     * Delete a recording session (both the binary and JSON form, if present).
     */
    public boolean deleteSession(String sessionId) {
        boolean deleted = false;
        for (String ext : new String[] {BINARY_EXTENSION, JSON_EXTENSION}) {
            Path path = confinedRecording(sessionId, ext);
            if (path == null) {
                continue;
            }
            try {
                deleted |= Files.deleteIfExists(path);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Failed to delete recording: " + sessionId, e);
            }
        }
        return deleted;
    }

    /**
     * Resolve a session file under {@link #recordingsDir} for one extension, or {@code null}
     * if the id escapes that directory. {@code sessionId} is raw player input (e.g. {@code
     * /strux record replay <name>}), so a name like {@code ../../../../ops} must not reach a
     * file outside the recordings folder. {@code Path.resolve} happily walks {@code ..} and
     * returns an absolute argument unchanged, so we normalize and require containment.
     */
    private Path confinedRecording(String sessionId, String ext) {
        Path base = recordingsDir.toAbsolutePath().normalize();
        Path resolved = base.resolve(sessionId + ext).normalize();
        return resolved.startsWith(base) ? resolved : null;
    }

    /**
     * Delete all recording sessions.
     */
    public int clearAllSessions() {
        int deleted = 0;
        for (String sessionId : listSessions()) {
            if (deleteSession(sessionId)) {
                deleted++;
            }
        }
        return deleted;
    }

    /**
     * Get the path to the recordings directory.
     */
    public Path getRecordingsDir() {
        return recordingsDir;
    }

    /**
     * Verify determinism of a saved session.
     *
     * @param sessionId the session ID to verify
     * @return a human-readable result string
     */
    public String verifySession(String sessionId) {
        RecordingSession session;
        try {
            session = loadSession(sessionId);
        } catch (IOException e) {
            return "Failed to load session: " + e.getMessage();
        }

        if (session == null) {
            return "Session not found: " + sessionId;
        }

        if (session.eventCount() == 0) {
            return "Session " + sessionId + " has no events to verify";
        }

        StringBuilder result = new StringBuilder();
        try {
            logger.info("Verifying session " + sessionId + " (" + session.eventCount() + " events, "
                    + session.getInitialState().getBlocks().size() + " blocks)...");

            ReplayEngine engine = new ReplayEngine(physicsConfig);
            ReplayResult replayResult = engine.replay(session, new ReplayEngine.ReplayListener() {
                @Override
                public void onEventStart(StruxEvent event, int index, int total) {
                    if (index % 10 == 0 || index == total - 1) {
                        logger.info("  Replaying event " + (index + 1) + "/" + total + "...");
                    }
                }
            });

            if (replayResult.isFullyValid()) {
                result.append("Session ").append(sessionId).append(" verified VALID (");
                result.append(replayResult.eventsReplayed()).append(" events, deterministic, no invariant violations)");
            } else {
                if (!replayResult.divergences().isEmpty()) {
                    result.append("Session ").append(sessionId).append(" has ");
                    result.append(replayResult.divergences().size()).append(" DIVERGENCE(s):\n");
                    for (Divergence d : replayResult.divergences()) {
                        result.append("  [").append(d.eventType()).append(" #").append(d.sequenceId());
                        result.append("] ").append(d.message()).append("\n");
                    }
                }
                if (!replayResult.violations().isEmpty()) {
                    result.append("Session ").append(sessionId).append(" has ");
                    result.append(replayResult.violations().size()).append(" INVARIANT VIOLATION(s):\n");
                    for (InvariantViolation v : replayResult.violations()) {
                        result.append("  [").append(v.type()).append(" #").append(v.sequenceId());
                        result.append("] ").append(v.message()).append("\n");
                    }
                }
            }
        } catch (Exception e) {
            result.append("Verification failed: ").append(e.getMessage());
            logger.log(Level.WARNING, "Failed to verify session " + sessionId, e);
        }

        // Also log to console
        logger.info(result.toString().replace("\n", " | "));

        return result.toString();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  INTERNAL
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Periodic + on-demand flush: drain the buffer into the CURRENT session if one is
     * active. Reads {@code currentSession} into a local ONCE so a concurrent
     * {@code stopRecording()} nulling the field cannot turn a non-null check into a
     * null dereference mid-loop. Runs only on the executor thread.
     */
    private void flushBuffer() {
        RecordingSession session = currentSession;
        if (session != null) {
            drainInto(session);
        }
    }

    /** Drain every buffered event into {@code session}. Executor-thread only. */
    private void drainInto(RecordingSession session) {
        StruxEvent event;
        while ((event = buffer.poll()) != null) {
            session.addEvent(event);
        }
    }

    /**
     * One periodic flush, guarded. A task that throws out of {@code
     * scheduleAtFixedRate} silently cancels every future run of that schedule, so a
     * single failure would permanently and silently disable the periodic drain — after
     * which long sessions keep only their newest ~bufferSize*2 events. Swallow and log
     * instead. Package-private so a test can drive one tick directly.
     */
    void flushTick() {
        try {
            flushBuffer();
        } catch (RuntimeException e) {
            logger.warning("Periodic recording flush failed (continuing): " + e);
        }
    }

    private void saveSession(RecordingSession session, boolean verify) {
        boolean binary = binaryFormat;
        String ext = binary ? BINARY_EXTENSION : JSON_EXTENSION;
        Path path = recordingsDir.resolve(session.getSessionId() + ext);
        try {
            // Typed sessions (e.g. "match/match-arena3-...") live in a subfolder.
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (binary) {
                StruxBinaryCodec.write(session, path);
            } else {
                SessionIO.write(session, path);
            }
            logger.info("Saved recording: " + session.getSessionId());
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to save recording: " + session.getSessionId(), e);
            return;
        }

        // Verify determinism by replaying the session
        if (verify) {
            verifyDeterminism(session);
        }
    }

    private void verifyDeterminism(RecordingSession session) {
        if (session.eventCount() == 0) {
            logger.info("Session " + session.getSessionId() + " has no events, skipping verification");
            return;
        }

        try {
            ReplayEngine engine = new ReplayEngine(physicsConfig);
            ReplayResult result = engine.replay(session, ReplayEngine.ReplayListener.NONE);

            if (result.isFullyValid()) {
                logger.info("Session " + session.getSessionId() + " verified VALID (" + result.eventsReplayed()
                        + " events, deterministic, no invariant violations)");
            } else {
                if (!result.divergences().isEmpty()) {
                    logger.warning("Session " + session.getSessionId() + " has "
                            + result.divergences().size() + " divergence(s)!");
                    for (Divergence d : result.divergences()) {
                        logger.warning("  [" + d.eventType() + " #" + d.sequenceId() + "] " + d.message());
                    }
                }
                if (!result.violations().isEmpty()) {
                    logger.warning("Session " + session.getSessionId() + " has "
                            + result.violations().size() + " invariant violation(s)!");
                    for (InvariantViolation v : result.violations()) {
                        logger.warning("  [" + v.type() + " #" + v.sequenceId() + "] " + v.message());
                    }
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to verify session " + session.getSessionId(), e);
        }
    }

    private void cleanupOldSessions() {
        List<String> sessions = listSessions();
        int maxSessions = config.getMaxSessions();

        if (sessions.size() <= maxSessions) {
            return;
        }

        // Delete oldest sessions (list is sorted newest-first)
        for (int i = maxSessions; i < sessions.size(); i++) {
            String sessionId = sessions.get(i);
            if (deleteSession(sessionId)) {
                logger.info("Auto-deleted old recording: " + sessionId);
            }
        }
    }
}
