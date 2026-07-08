package dev.gesp.structural.minecraft.recording;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.bukkit.World;

/**
 * A request to start a programmatic recording, used by host plugins (e.g. the
 * Siege gamemode) that want to auto-record arena matches and build sessions as
 * separate, distinguishable recordings.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                       RECORDING REQUEST                            │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  tag    free-form category, lower-cased, used as the on-disk        │
 *   │         subfolder. Well-known constants MATCH / BUILD are provided  │
 *   │         but any tag is allowed so hosts aren't boxed in.            │
 *   │                                                                     │
 *   │  label  a human-friendly name for this particular session          │
 *   │         (e.g. an arena id "arena3"); becomes part of the filename.  │
 *   │                                                                     │
 *   │  world  the world to snapshot + record (recordings are global —     │
 *   │         only one may be active at a time, mirroring the underlying  │
 *   │         single-session recorder).                                   │
 *   │                                                                     │
 *   │  verifyOnStop  replay-verify determinism when the session stops;    │
 *   │                on by default, opt out for throwaway captures.       │
 *   │                                                                     │
 *   │  Resulting on-disk layout:                                          │
 *   │    recordings/match/match-arena3-2026-06-04-14-32-15.json          │
 *   │    recordings/build/build-lobby-2026-06-04-14-40-02.json           │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public final class RecordingRequest {

    /** Well-known tag for an arena match recording. */
    public static final String MATCH = "match";

    /** Well-known tag for a build-session recording. */
    public static final String BUILD = "build";

    private final String tag;
    private final String label;
    private final World world;
    private final boolean verifyOnStop;
    private final Map<String, String> metadata;
    private final Map<String, String> actors;

    private RecordingRequest(
            String tag,
            String label,
            World world,
            boolean verifyOnStop,
            Map<String, String> metadata,
            Map<String, String> actors) {
        this.tag = tag;
        this.label = label;
        this.world = world;
        this.verifyOnStop = verifyOnStop;
        this.metadata = Map.copyOf(metadata);
        this.actors = Map.copyOf(actors);
    }

    /**
     * Start building a request for the given tag/category. Use {@link #MATCH} or
     * {@link #BUILD}, or pass any free-form tag.
     */
    public static Builder of(String tag, World world) {
        return new Builder(tag, world);
    }

    public String tag() {
        return tag;
    }

    public String label() {
        return label;
    }

    public World world() {
        return world;
    }

    public boolean verifyOnStop() {
        return verifyOnStop;
    }

    /**
     * Opaque game context written onto the recording session (match id, arena, team
     * rosters, …). The engine never interprets it; a replay/analytics consumer does.
     * Never null (empty when unset).
     */
    public Map<String, String> metadata() {
        return metadata;
    }

    /**
     * Opaque actorId → display-name map, so a replay can label actors (the player
     * UUIDs the events carry) without a live server lookup. Never null.
     */
    public Map<String, String> actors() {
        return actors;
    }

    /** Mutable builder for {@link RecordingRequest}. */
    public static final class Builder {
        private final String tag;
        private final World world;
        private String label = "session";
        private boolean verifyOnStop = true;
        private Map<String, String> metadata = new LinkedHashMap<>();
        private Map<String, String> actors = new LinkedHashMap<>();

        private Builder(String tag, World world) {
            this.tag = sanitize(Objects.requireNonNull(tag, "tag"), "tag");
            this.world = Objects.requireNonNull(world, "world");
        }

        /** A human-friendly name for this session (e.g. an arena id). */
        public Builder label(String label) {
            this.label = sanitize(Objects.requireNonNull(label, "label"), "label");
            return this;
        }

        /** Whether to replay-verify determinism when the session stops (default true). */
        public Builder verifyOnStop(boolean verifyOnStop) {
            this.verifyOnStop = verifyOnStop;
            return this;
        }

        /** Attach opaque game context (match id, arena, rosters) to the recording. */
        public Builder metadata(Map<String, String> metadata) {
            this.metadata = metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();
            return this;
        }

        /** Attach an actorId → display-name map for the recording. */
        public Builder actors(Map<String, String> actors) {
            this.actors = actors != null ? new LinkedHashMap<>(actors) : new LinkedHashMap<>();
            return this;
        }

        public RecordingRequest build() {
            return new RecordingRequest(tag, label, world, verifyOnStop, metadata, actors);
        }
    }

    /**
     * Lower-case and strip anything that isn't safe in a file/folder name so a
     * label like "Arena #3" can't escape the recordings directory or break the
     * filename scheme.
     */
    private static String sanitize(String raw, String what) {
        String cleaned = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-");
        cleaned = cleaned.replaceAll("(^-+)|(-+$)", "");
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException(what + " must contain at least one [a-z0-9_-] character: " + raw);
        }
        return cleaned;
    }
}
