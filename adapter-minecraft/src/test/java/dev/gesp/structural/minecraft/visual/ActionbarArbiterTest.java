package dev.gesp.structural.minecraft.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.minecraft.visual.ActionbarArbiter.Priority;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Tests for the {@link ActionbarArbiter}: who wins the action bar on a tick.
 *
 * <p>The tick clock is a test-controlled {@link AtomicLong} so "same tick" vs
 * "next tick" is deterministic and never depends on the scheduler advancing.
 */
@DisplayName("ActionbarArbiter: priority + per-tick suppression")
class ActionbarArbiterTest {

    private ServerMock server;
    private final AtomicLong tick = new AtomicLong(100);

    private ActionbarArbiter arbiter() {
        return new ActionbarArbiter(tick::get);
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("test_world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PlayerMock player(String name) {
        return server.addPlayer(name);
    }

    /** The plain text of the most recent action bar message sent to the player (null if none). */
    private static String lastActionBar(PlayerMock p) {
        Component last = null;
        Component next;
        while ((next = p.nextActionBar()) != null) {
            last = next;
        }
        return last == null ? null : PlainTextComponentSerializer.plainText().serialize(last);
    }

    @Test
    @DisplayName("a lone summary writer sends and is shown")
    void loneSummarySends() {
        PlayerMock p = player("Solo");
        ActionbarArbiter arbiter = arbiter();

        assertTrue(arbiter.send(p, Priority.SUMMARY, Component.text("summary")));
        assertEquals("summary", lastActionBar(p));
    }

    @Test
    @DisplayName("a critical warning beats a summary on the SAME tick (warning wins, summary suppressed)")
    void warningBeatsSummarySameTick() {
        PlayerMock p = player("Contended");
        ActionbarArbiter arbiter = arbiter();

        // Warning fires first this tick.
        assertTrue(arbiter.send(p, Priority.CRITICAL_WARNING, Component.text("WARN")));

        // Summary then loses the same tick — not sent.
        assertFalse(arbiter.send(p, Priority.SUMMARY, Component.text("summary")));
        assertTrue(arbiter.isSuppressed(p, Priority.SUMMARY), "summary is suppressed once the warning won the tick");
        // The only thing that reached the player was the warning.
        assertEquals("WARN", lastActionBar(p));
    }

    @Test
    @DisplayName("a summary that ran first does NOT block a higher-priority warning on the same tick")
    void warningOverridesEarlierSummarySameTick() {
        PlayerMock p = player("Override");
        ActionbarArbiter arbiter = arbiter();

        assertTrue(arbiter.send(p, Priority.SUMMARY, Component.text("summary")));
        // The warning is higher priority, so it still gets through this tick.
        assertTrue(arbiter.send(p, Priority.CRITICAL_WARNING, Component.text("WARN")));
        assertEquals("WARN", lastActionBar(p), "the warning is the most recent message");
    }

    @Test
    @DisplayName("on the NEXT tick the summary is free again")
    void summaryFreeNextTick() {
        PlayerMock p = player("NextTick");
        ActionbarArbiter arbiter = arbiter();

        arbiter.send(p, Priority.CRITICAL_WARNING, Component.text("WARN"));
        assertTrue(arbiter.isSuppressed(p, Priority.SUMMARY), "suppressed on the warning's tick");

        tick.incrementAndGet(); // a new tick
        assertFalse(arbiter.isSuppressed(p, Priority.SUMMARY), "no longer suppressed next tick");
        assertTrue(arbiter.send(p, Priority.SUMMARY, Component.text("summary")));
        assertEquals("summary", lastActionBar(p));
    }

    @Test
    @DisplayName("two summary writers on the same tick: the second is dropped (no double-send)")
    void equalPrioritySameTickDropsSecond() {
        PlayerMock p = player("Double");
        ActionbarArbiter arbiter = arbiter();

        assertTrue(arbiter.send(p, Priority.SUMMARY, Component.text("first")));
        assertFalse(arbiter.send(p, Priority.SUMMARY, Component.text("second")));
        assertEquals("first", lastActionBar(p), "only the first writer's message reached the player");
    }

    @Test
    @DisplayName("suppression is per-player: one player's warning does not gag another")
    void suppressionIsPerPlayer() {
        PlayerMock a = player("Alice");
        PlayerMock b = player("Bob");
        ActionbarArbiter arbiter = arbiter();

        arbiter.send(a, Priority.CRITICAL_WARNING, Component.text("WARN"));
        assertTrue(arbiter.isSuppressed(a, Priority.SUMMARY));
        assertFalse(arbiter.isSuppressed(b, Priority.SUMMARY), "Bob is unaffected by Alice's warning");
        assertTrue(arbiter.send(b, Priority.SUMMARY, Component.text("summary")));
        assertEquals("summary", lastActionBar(b));
        assertNull(lastActionBar(b), "Bob's queue is now drained — no further messages");
    }

    @Test
    @DisplayName("criticalStressWarning renders the ⚠ CRITICAL STRESS — NN% ⚠ text")
    void criticalWarningText() {
        String text = PlainTextComponentSerializer.plainText().serialize(ActionbarArbiter.criticalStressWarning(97));
        assertEquals("⚠ CRITICAL STRESS — 97% ⚠", text);
    }

    @Test
    @DisplayName("forget() resets a player so the next tick's first writer wins cleanly")
    void forgetResetsPlayer() {
        Player p = player("Forgotten");
        ActionbarArbiter arbiter = arbiter();

        arbiter.send(p, Priority.CRITICAL_WARNING, Component.text("WARN"));
        assertTrue(arbiter.isSuppressed(p, Priority.SUMMARY));

        arbiter.forget(p);
        assertFalse(arbiter.isSuppressed(p, Priority.SUMMARY), "a forgotten player has no held tick");
    }
}
