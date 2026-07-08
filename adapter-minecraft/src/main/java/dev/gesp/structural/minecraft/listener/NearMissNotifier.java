package dev.gesp.structural.minecraft.listener;

import dev.gesp.structural.minecraft.config.EffectsConfig;
import dev.gesp.structural.model.CollapsedNode;
import java.util.List;
import org.bukkit.entity.Player;

/**
 * Sends the "near miss" message when a block a player triggered collapses while it was barely
 * holding.
 *
 * <p>A near miss is an {@code OVERLOADED} collapse whose {@link CollapsedNode#stressAtCollapse()}
 * was only just past the limit — the block held right up until it failed. A block that was
 * hopelessly overloaded (far above its capacity) or one that fell because it was floating (no
 * support, {@code stressAtCollapse == 0.0}) is not a near miss.
 *
 * <pre>
 *   stressAtCollapse:
 *     0.00  ────────────────  floating collapse (no message)
 *     1.20  ───── overloaded, way past limit (no message)
 *     1.01  ───── barely holding → "Close call"   ← near miss
 * </pre>
 *
 * <p>At most one message is sent per break/place, no matter how many blocks fell.
 */
final class NearMissNotifier {

    /** The message a player sees after a near-miss collapse. */
    static final String MESSAGE = "§eClose call — that block was barely holding.";

    private NearMissNotifier() {}

    /**
     * Message {@code player} once if any of {@code overloadedCollapsed} was a near miss.
     *
     * @param player             who triggered the collapse
     * @param overloadedCollapsed the nodes that collapsed from overload (NOT floating ones)
     * @param config             effect settings (enable flag + threshold)
     */
    static void notifyIfNearMiss(Player player, List<CollapsedNode> overloadedCollapsed, EffectsConfig config) {
        if (!config.isNearMissNotificationEnabled()) {
            return;
        }
        double threshold = config.getNearMissThreshold();
        for (CollapsedNode collapsed : overloadedCollapsed) {
            if (collapsed.stressAtCollapse() > threshold) {
                player.sendMessage(MESSAGE);
                return; // once per event, no matter how many barely-held blocks fell
            }
        }
    }
}
