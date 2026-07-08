package dev.gesp.structural.minecraft.hook;

import org.bukkit.entity.Player;

/**
 * Applies configured currency costs to strux actions (reinforce, repair, paid
 * engineer mode) through an optional {@link VaultEconomy}.
 *
 * <p>Degrades cleanly: with no Vault hook, or a cost of zero, every action is
 * free and {@link #charge} returns true without touching any balance. This lets
 * the rest of the plugin call {@code charge(...)} unconditionally.
 */
public final class EconomyCharges {

    private final VaultEconomy economy; // nullable: no economy plugin

    public EconomyCharges(VaultEconomy economy) {
        this.economy = economy;
    }

    /** Whether currency charging is actually active (Vault present + hooked). */
    public boolean isActive() {
        return economy != null;
    }

    /**
     * Attempt to charge the player {@code cost} for {@code action}.
     *
     * @return true if the player may proceed (free action, no economy, or a
     *         successful withdrawal); false if they could not afford it (a
     *         message is sent to the player in that case).
     */
    public boolean charge(Player player, double cost, String action) {
        if (economy == null || cost <= 0.0) {
            return true; // free
        }
        if (!economy.has(player, cost)) {
            player.sendMessage("§cYou can't afford to " + action + " §7(" + economy.format(cost) + "§7).");
            return false;
        }
        if (!economy.withdraw(player, cost)) {
            player.sendMessage("§cPayment failed - " + action + " cancelled.");
            return false;
        }
        player.sendMessage("§7Charged §e" + economy.format(cost) + " §7to " + action + ".");
        return true;
    }
}
