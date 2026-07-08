package dev.gesp.structural.minecraft.visual;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

/**
 * Decides who gets to write to a player's action bar on any given tick.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                       ACTIONBAR ARBITER                            │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  The action bar is the one strip of text above the hotbar. Two      │
 *   │  features want it:                                                  │
 *   │                                                                     │
 *   │    • the "⚠ CRITICAL STRESS" warning when you place a risky block   │
 *   │    • the always-on live stress summary                             │
 *   │                                                                     │
 *   │  If both write on the same tick the text flickers. So everything    │
 *   │  routes through here: each message carries a PRIORITY, and on a     │
 *   │  given tick the highest priority wins. A lower-priority writer that │
 *   │  arrives after a higher one on the same tick is dropped — the       │
 *   │  critical warning beats the summary, never the other way round.     │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>The "current tick" comes from a {@link LongSupplier} so production can pass
 * {@code server.getCurrentTick()} while tests drive a deterministic clock.
 */
public final class ActionbarArbiter {

    /**
     * Message priority. Higher ordinal wins on a tick. The live summary is the
     * floor; the critical-stress warning sits above it so a real warning always
     * beats the ambient readout.
     */
    public enum Priority {
        /** The always-on live stress readout (lowest — yields to everything else). */
        SUMMARY,
        /** The placement "⚠ CRITICAL STRESS" warning (beats the summary). */
        CRITICAL_WARNING
    }

    private final LongSupplier tickSupplier;

    /** Per-player: the tick of the last winning send, and the priority that won it. */
    private final Map<UUID, Long> lastTick = new HashMap<>();

    private final Map<UUID, Priority> lastPriority = new HashMap<>();

    public ActionbarArbiter(LongSupplier tickSupplier) {
        this.tickSupplier = tickSupplier;
    }

    /**
     * The "⚠ CRITICAL STRESS — NN% ⚠" warning component shown when placing a block
     * pushes a structure near failure. A pure factory (no game state) so the exact
     * wording/colours are unit-testable; the place listener sends it at
     * {@link Priority#CRITICAL_WARNING}.
     *
     * @param percent the peak stress as a whole-number percentage
     */
    public static Component criticalStressWarning(int percent) {
        return Component.text()
                .append(Component.text("⚠ ", NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text("CRITICAL STRESS", NamedTextColor.RED, TextDecoration.BOLD))
                .append(Component.text(" — ", NamedTextColor.GRAY))
                .append(Component.text(percent + "%", NamedTextColor.RED, TextDecoration.BOLD))
                .append(Component.text(" ⚠", NamedTextColor.GOLD, TextDecoration.BOLD))
                .build();
    }

    /**
     * Try to write {@code message} to {@code player}'s action bar at {@code
     * priority}. Sends — and returns {@code true} — unless a message of equal or
     * higher priority already won this player's action bar on the current tick,
     * in which case nothing is sent and this returns {@code false}.
     *
     * <p>Equal priority on the same tick is also dropped: two summary writers (or
     * two warnings) should not double-send, and the first writer of a tick wins.
     *
     * @return whether the message was actually sent
     */
    public boolean send(Player player, Priority priority, Component message) {
        UUID id = player.getUniqueId();
        long now = tickSupplier.getAsLong();
        Long wonAt = lastTick.get(id);
        if (wonAt != null && wonAt == now) {
            Priority held = lastPriority.get(id);
            if (held != null && held.ordinal() >= priority.ordinal()) {
                return false; // already taken this tick by an equal-or-higher writer
            }
        }
        lastTick.put(id, now);
        lastPriority.put(id, priority);
        player.sendActionBar(message);
        return true;
    }

    /**
     * Whether a write of {@code priority} would be suppressed for {@code player}
     * on the current tick (i.e. an equal-or-higher message already won this tick).
     * Lets a lower-priority producer skip building its message at all when it has
     * already lost the tick.
     */
    public boolean isSuppressed(Player player, Priority priority) {
        UUID id = player.getUniqueId();
        Long wonAt = lastTick.get(id);
        if (wonAt == null || wonAt != tickSupplier.getAsLong()) {
            return false;
        }
        Priority held = lastPriority.get(id);
        return held != null && held.ordinal() >= priority.ordinal();
    }

    /** Forget a player's state (e.g. on quit), so the maps don't grow unbounded. */
    public void forget(Player player) {
        UUID id = player.getUniqueId();
        lastTick.remove(id);
        lastPriority.remove(id);
    }
}
