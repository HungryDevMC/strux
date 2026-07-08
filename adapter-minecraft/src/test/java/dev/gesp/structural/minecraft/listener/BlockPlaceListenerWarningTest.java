package dev.gesp.structural.minecraft.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.minecraft.visual.ActionbarArbiter;
import dev.gesp.structural.model.NodePos;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Tests the place-time "⚠ CRITICAL STRESS" decision in
 * {@link BlockPlaceListener#maybeWarnCriticalStress}: it warns only when the
 * most-stressed block exceeds the threshold, and routes through the arbiter.
 */
@DisplayName("BlockPlaceListener: critical-stress warning decision")
class BlockPlaceListenerWarningTest {

    private ServerMock server;
    private final AtomicLong tick = new AtomicLong(7);

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("test_world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private ActionbarArbiter arbiter() {
        return new ActionbarArbiter(tick::get);
    }

    private static String lastActionBar(PlayerMock p) {
        Component last = null;
        Component next;
        while ((next = p.nextActionBar()) != null) {
            last = next;
        }
        return last == null ? null : PlainTextComponentSerializer.plainText().serialize(last);
    }

    @Test
    @DisplayName("peak stress over the threshold sends the warning with the rounded percent")
    void warnsAboveThreshold() {
        PlayerMock p = server.addPlayer("Builder");
        ActionbarArbiter arbiter = arbiter();
        Map<NodePos, Double> stress = Map.of(new NodePos(0, 1, 0), 0.40, new NodePos(0, 2, 0), 0.965);

        boolean sent = BlockPlaceListener.maybeWarnCriticalStress(arbiter, p, stress, 0.90);

        assertTrue(sent, "0.965 > 0.90 must warn");
        assertEquals("⚠ CRITICAL STRESS — 97% ⚠", lastActionBar(p), "uses the peak stress, rounded to a percent");
    }

    @Test
    @DisplayName("peak stress at or below the threshold sends nothing")
    void quietAtOrBelowThreshold() {
        PlayerMock p = server.addPlayer("Builder");
        ActionbarArbiter arbiter = arbiter();

        // Strictly below.
        assertFalse(BlockPlaceListener.maybeWarnCriticalStress(arbiter, p, Map.of(new NodePos(0, 1, 0), 0.50), 0.90));
        // Exactly at the threshold is NOT a warning (strict >).
        assertFalse(BlockPlaceListener.maybeWarnCriticalStress(arbiter, p, Map.of(new NodePos(0, 1, 0), 0.90), 0.90));
        assertNull(lastActionBar(p), "nothing sent when not over the threshold");
    }

    @Test
    @DisplayName("an empty stress map never warns")
    void emptyMapQuiet() {
        PlayerMock p = server.addPlayer("Builder");
        assertFalse(BlockPlaceListener.maybeWarnCriticalStress(arbiter(), p, Map.of(), 0.90));
        assertNull(lastActionBar(p));
    }
}
