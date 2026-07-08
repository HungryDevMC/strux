package dev.gesp.structural.minecraft.listener;

import dev.gesp.structural.minecraft.config.EffectsConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Chat feedback for collapses, shared by the block-break and block-place listeners.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                     COLLAPSE NOTIFIER                               │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Two friendly chat touches when a build comes down:                │
 *   │                                                                     │
 *   │  • BIG COLLAPSE → tell the whole server                            │
 *   │       💥 Steve's structure collapsed! (23 blocks)                  │
 *   │    Only when the collapse is at least the configured threshold.    │
 *   │                                                                     │
 *   │  • FIRST COLLAPSE → a one-time tip just for that player            │
 *   │       "Try /engineer to see what's holding your build up."         │
 *   │    Remembered forever in the player's data, so it fires once.      │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public final class CollapseNotifier {

    private final Plugin plugin;
    private final EffectsConfig config;
    private final NamespacedKey seenHintKey;

    public CollapseNotifier(Plugin plugin, EffectsConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.seenHintKey = new NamespacedKey(plugin, "seen_collapse_hint");
    }

    /**
     * React to a collapse a player just triggered.
     *
     * <p>For a cascade truncated by the per-event step cap and finished over the
     * following ticks, {@code collapsedCount} is the first-tick cascade size — the
     * blocks that actually came down inside this event — not the eventual total.
     *
     * @param player the player whose action caused the collapse
     * @param collapsedCount how many blocks collapsed in this action
     */
    public void onCollapse(Player player, int collapsedCount) {
        if (collapsedCount <= 0) {
            return;
        }
        maybeBroadcastBigCollapse(player, collapsedCount);
        maybeSendFirstCollapseHint(player);
    }

    private void maybeBroadcastBigCollapse(Player player, int collapsedCount) {
        if (!config.isBigCollapseBroadcastEnabled()) {
            return;
        }
        if (collapsedCount <= config.getBigCollapseBroadcastThreshold()) {
            return;
        }
        Component message = Component.text()
                .append(Component.text("💥 ", NamedTextColor.RED))
                .append(Component.text(player.getName(), NamedTextColor.YELLOW))
                .append(Component.text("'s structure collapsed! ", NamedTextColor.RED))
                .append(Component.text("(" + collapsedCount + " blocks)", NamedTextColor.GRAY))
                .build();
        plugin.getServer().broadcast(message);
    }

    private void maybeSendFirstCollapseHint(Player player) {
        if (!config.isFirstCollapseHintEnabled()) {
            return;
        }
        if (player.getPersistentDataContainer().has(seenHintKey, PersistentDataType.BYTE)) {
            return;
        }
        player.getPersistentDataContainer().set(seenHintKey, PersistentDataType.BYTE, (byte) 1);
        player.sendMessage(Component.text()
                .append(Component.text("Tip: ", NamedTextColor.AQUA, TextDecoration.BOLD))
                .append(Component.text("run ", NamedTextColor.GRAY))
                .append(Component.text("/engineer", NamedTextColor.YELLOW))
                .append(Component.text(" to see what's holding your build up.", NamedTextColor.GRAY))
                .build());
    }
}
