package dev.gesp.structural.minecraft.recording;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.recording.BlockBreakEvent;
import dev.gesp.structural.recording.StruxEvent;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * The recorder must flush when the buffer reaches {@code buffer-size}, not only on the 1s
 * periodic schedule — otherwise a burst between two periodic flushes silently drops events
 * once it passes the 2x cap (config.yml promises "flush every N events or 1 second").
 */
@DisplayName("MinecraftEventRecorder: a full buffer triggers a flush, not just the 1s timer")
class MinecraftEventRecorderSizeFlushTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock(); // the recorder resolves worlds via Bukkit.getWorld
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static BlockBreakEvent breakEvent(int seq) {
        return new BlockBreakEvent(seq, seq, new NodePos(0, seq, 0), "STONE", List.of(), null);
    }

    @Test
    @DisplayName("reaching buffer-size drains the buffer well before the 1s periodic flush")
    void reachingBufferSizeFlushes(@TempDir Path dir) throws Exception {
        RecordingConfig config = new RecordingConfig();
        config.setBufferSize(10); // the loader clamps buffer-size to a minimum of 10
        config.setMaxEventsPerTick(1000); // keep the per-tick throttle out of the way
        int bufferSize = config.getBufferSize();
        MinecraftEventRecorder recorder =
                new MinecraftEventRecorder(dir, config, new PhysicsConfig(), Logger.getLogger("size-flush-test"));
        try {
            recorder.startRecording("flush/size", UUID.randomUUID().toString(), new StructureGraph(), false);
            for (int i = 0; i < bufferSize; i++) {
                recorder.record(breakEvent(i));
            }

            // The size trigger queues a drain on the executor (runs in ms). The periodic
            // flush is a full second away, so a drain well inside that window can only be the
            // size-triggered one — proving the trigger fired (it drained at ~1s before).
            boolean drained = false;
            long firstDrainMs = -1;
            long t0 = System.nanoTime();
            for (int i = 0; i < 50 && !drained; i++) {
                if (bufferSize(recorder) == 0) {
                    drained = true;
                    firstDrainMs = (System.nanoTime() - t0) / 1_000_000;
                    break;
                }
                Thread.sleep(10);
            }
            assertTrue(drained && firstDrainMs < 500, "a full buffer flushes fast (drainMs=" + firstDrainMs + ")");
        } finally {
            recorder.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static int bufferSize(MinecraftEventRecorder recorder) throws Exception {
        Field f = MinecraftEventRecorder.class.getDeclaredField("buffer");
        f.setAccessible(true);
        return ((Collection<StruxEvent>) f.get(recorder)).size();
    }
}
