package dev.gesp.structural.recording;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.persistence.StructureConverter;
import dev.gesp.structural.persistence.StructureData;
import dev.gesp.structural.recording.ReplayEngine.ReplayListener;
import dev.gesp.structural.recording.ReplayEngine.ReplayResult;
import dev.gesp.structural.recording.io.StruxBinaryCodec;
import dev.gesp.structural.scenario.Structures;
import dev.gesp.structural.solver.CascadeEngine;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The data-driven companion to the scenario snapshot net: every recorded {@code .strx} fixture under
 * {@code src/test/resources/recordings/} must (a) re-simulate <em>faithfully</em> (no divergence) and
 * (b) produce the outcome pinned in its golden {@code .txt}. Drop a fixture in via
 * {@code replay-cli export-test} and a physics change that shifts its collapse is caught here.
 *
 * <p>The golden {@code .txt} is regenerated with {@code ./gradlew :core:test -Pupdate-snapshots}
 * (same flow as {@link dev.gesp.structural.scenario.Snapshots}). A sample fixture is bootstrapped on
 * first run so the suite is never empty.
 */
class RecordedSessionRegressionTest {

    private static final boolean UPDATE = Boolean.getBoolean("strux.updateSnapshots");
    private static final Path DIR = Paths.get("src", "test", "resources", "recordings");

    @BeforeAll
    static void ensureSampleFixture() {
        if (listFixtures().isEmpty()) {
            RecordingSession sample = columnBreakSession();
            writeFixture("sample-column-break", sample);
        }
    }

    @Test
    void everyRecordedFixtureIsFaithfulAndMatchesItsGolden() {
        List<Path> fixtures = listFixtures();
        assertFalse(fixtures.isEmpty(), "no .strx fixtures under " + DIR.toAbsolutePath());

        for (Path fixture : fixtures) {
            RecordingSession session = read(fixture);

            // (a) faithful: the engine still reproduces what was recorded.
            PhysicsConfig config = session.getPhysicsConfig();
            ReplayEngine engine = config != null ? new ReplayEngine(config) : new ReplayEngine();
            ReplayResult result = engine.replay(session, ReplayListener.NONE);
            assertTrue(
                    result.isDeterministic(),
                    () -> "recorded fixture " + fixture.getFileName() + " no longer replays faithfully: "
                            + result.divergences());

            // (b) golden: its re-simulated outcome matches the committed snapshot.
            String name = fixtureName(fixture);
            Path golden = DIR.resolve(name + ".txt");
            String actual = RecordedSessionSnapshot.toText(session);
            boolean missing = !Files.exists(golden);
            if (UPDATE || missing) {
                writeString(golden, actual);
                System.out.println("[recording-snapshot] " + (missing ? "created " : "updated ") + golden);
            } else {
                String expected = readString(golden);
                if (!expected.equals(actual)) {
                    fail("recorded fixture " + name + " outcome changed.\n--- expected (" + golden + ") ---\n"
                            + expected + "\n--- actual ---\n" + actual);
                }
            }
        }
    }

    // ── fixture discovery / IO ────────────────────────────────────────────

    private static List<Path> listFixtures() {
        if (!Files.isDirectory(DIR)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(DIR)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".strx"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("could not list " + DIR, e);
        }
    }

    private static String fixtureName(Path fixture) {
        String file = fixture.getFileName().toString();
        return file.substring(0, file.length() - ".strx".length());
    }

    private static RecordingSession read(Path fixture) {
        try {
            return StruxBinaryCodec.read(fixture);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read fixture " + fixture, e);
        }
    }

    private static void writeFixture(String name, RecordingSession session) {
        try {
            Files.createDirectories(DIR);
            StruxBinaryCodec.write(session, DIR.resolve(name + ".strx"));
        } catch (IOException e) {
            throw new UncheckedIOException("could not write fixture " + name, e);
        }
    }

    private static void writeString(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + path, e);
        }
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + path, e);
        }
    }

    // ── sample fixture builder (a faithful column-break recording) ────────

    private static RecordingSession columnBreakSession() {
        StructureGraph graph = Structures.column(6);
        StructureData initial = StructureConverter.toData(graph, "w");

        NodePos base = new NodePos(0, 1, 0);
        graph.removeBlock(base);
        List<NodePos> collapsed = new ArrayList<>();
        for (CollapsedNode cn : new CascadeEngine(new PhysicsConfig()).settle(graph, SolverCallback.NONE)) {
            collapsed.add(cn.pos());
        }

        RecordingSession session = new RecordingSession("sample-column-break", 0L, "w", initial);
        session.setPhysicsConfig(new PhysicsConfig());
        session.setEngineVersion("sample");
        session.addEvent(new BlockBreakEvent(1L, 1L, base, "STONE", collapsed, "tester"));
        return session;
    }
}
