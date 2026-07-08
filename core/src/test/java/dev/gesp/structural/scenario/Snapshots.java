package dev.gesp.structural.scenario;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Golden-master harness for scenario outcomes.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                      HOW THE SAFETY NET WORKS                      │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  1. A scenario runs and produces a deterministic text fingerprint  │
 *   │     (which blocks were destroyed / collapsed / survived).          │
 *   │                                                                     │
 *   │  2. The first time (or with -Pupdate-snapshots), that text is      │
 *   │     WRITTEN to src/test/resources/snapshots/<name>.txt and the     │
 *   │     test passes. You commit that file — it is the "known good".    │
 *   │                                                                     │
 *   │  3. Every run afterwards, the fresh fingerprint is DIFFED against  │
 *   │     the committed file. If a refactor changes what collapses, the  │
 *   │     diff fails the test and points at the exact block that changed │
 *   │     fate — so you never silently break the physics.                │
 *   │                                                                     │
 *   │  When you change the physics ON PURPOSE, regenerate with:          │
 *   │     ./gradlew :core:test -Pupdate-snapshots                        │
 *   │  then eyeball the git diff of the snapshot files before committing.│
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public final class Snapshots {

    /** Set true by the build when {@code -Pupdate-snapshots} is passed. */
    private static final boolean UPDATE = Boolean.getBoolean("strux.updateSnapshots");

    /** Resolved relative to the test working dir, which Gradle sets to the module dir. */
    private static final Path DIR = Paths.get("src", "test", "resources", "snapshots");

    private Snapshots() {} // No instances

    /**
     * Assert that {@code outcome} matches the committed snapshot named {@code name}.
     * Writes the snapshot instead if it is missing or the update flag is set.
     */
    public static void assertMatches(String name, ScenarioOutcome outcome) {
        Path file = DIR.resolve(name + ".txt");
        String actual = outcome.toSnapshotText();

        boolean missing = !Files.exists(file);
        if (UPDATE || missing) {
            write(file, actual);
            System.out.println("[snapshot] " + (missing ? "created" : "updated") + " " + file + " ("
                    + outcome.removedCount() + " removed, " + outcome.survivorCount() + " survive)");
            return;
        }

        String expected = read(file);
        if (!expected.equals(actual)) {
            fail(diffMessage(name, file, expected, actual));
        }
    }

    // ─────────────────────────────────────────────────────────────────────

    private static String diffMessage(String name, Path file, String expected, String actual) {
        List<String> exp = expected.lines().toList();
        List<String> act = actual.lines().toList();
        int max = Math.max(exp.size(), act.size());
        StringBuilder sb = new StringBuilder();
        sb.append("Scenario '")
                .append(name)
                .append("' diverged from snapshot ")
                .append(file)
                .append('\n');
        sb.append("If this change is intentional, regenerate with: ./gradlew :core:test -Pupdate-snapshots\n");
        int shown = 0;
        for (int i = 0; i < max && shown < 12; i++) {
            String e = i < exp.size() ? exp.get(i) : "<missing>";
            String a = i < act.size() ? act.get(i) : "<missing>";
            if (!e.equals(a)) {
                sb.append("  line ").append(i + 1).append(":\n");
                sb.append("    - expected: ").append(e).append('\n');
                sb.append("    + actual:   ").append(a).append('\n');
                shown++;
            }
        }
        if (shown == 0) {
            sb.append("  (line count differs: expected ")
                    .append(exp.size())
                    .append(", actual ")
                    .append(act.size())
                    .append(")\n");
        }
        return sb.toString();
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Could not write snapshot " + file + " (cwd="
                            + Paths.get("").toAbsolutePath() + ")",
                    e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read snapshot " + file, e);
        }
    }
}
