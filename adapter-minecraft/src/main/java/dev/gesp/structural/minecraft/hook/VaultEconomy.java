package dev.gesp.structural.minecraft.hook;

import java.lang.reflect.Method;
import java.util.logging.Logger;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Thin reflection wrapper over a Vault {@code Economy} provider.
 *
 * <p>Vault's economy interface has been stable for a decade, but we still hook
 * it reflectively to avoid adding a compile-time dependency (and an extra
 * repository) for an optional feature. When Vault — or an economy plugin behind
 * it — is absent, {@link #tryHook} returns {@code null} and callers treat every
 * action as free.
 *
 * <pre>
 *   charge flow:  has(player, amount) ? withdraw(player, amount) : decline
 * </pre>
 */
public final class VaultEconomy {

    private final Object economy; // net.milkbowl.vault.economy.Economy
    private final Method hasMethod; // boolean has(OfflinePlayer, double)
    private final Method withdrawMethod; // EconomyResponse withdrawPlayer(OfflinePlayer, double)
    private final Method formatMethod; // String format(double)
    private final Method transactionSuccess; // boolean EconomyResponse.transactionSuccess()
    private final Logger log;

    private VaultEconomy(
            Object economy,
            Method hasMethod,
            Method withdrawMethod,
            Method formatMethod,
            Method transactionSuccess,
            Logger log) {
        this.economy = economy;
        this.hasMethod = hasMethod;
        this.withdrawMethod = withdrawMethod;
        this.formatMethod = formatMethod;
        this.transactionSuccess = transactionSuccess;
        this.log = log;
    }

    /**
     * Resolve the registered Vault economy provider, or null if Vault / an
     * economy plugin is not available.
     */
    public static VaultEconomy tryHook(Plugin plugin) {
        Logger log = plugin.getLogger();
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            log.info("Vault not found - economy charges disabled (all actions free).");
            return null;
        }
        try {
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> rsp =
                    plugin.getServer().getServicesManager().getRegistration(economyClass);
            if (rsp == null || rsp.getProvider() == null) {
                log.warning("Vault is installed but no economy provider is registered - charges disabled.");
                return null;
            }
            Object economy = rsp.getProvider();
            Method has = economyClass.getMethod("has", OfflinePlayer.class, double.class);
            Method withdraw = economyClass.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            Method format = economyClass.getMethod("format", double.class);
            Method txnSuccess =
                    Class.forName("net.milkbowl.vault.economy.EconomyResponse").getMethod("transactionSuccess");
            log.info("Vault economy hooked (" + economyClass.getSimpleName() + ").");
            return new VaultEconomy(economy, has, withdraw, format, txnSuccess, log);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            log.warning("Failed to hook Vault economy (" + e.getMessage() + "); charges disabled.");
            return null;
        }
    }

    /** Whether the player can afford {@code amount}. */
    public boolean has(OfflinePlayer player, double amount) {
        try {
            return (boolean) hasMethod.invoke(economy, player, amount);
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warning("Vault balance check failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Withdraw {@code amount} from the player.
     *
     * @return true if the transaction succeeded.
     */
    public boolean withdraw(OfflinePlayer player, double amount) {
        try {
            Object response = withdrawMethod.invoke(economy, player, amount);
            return (boolean) transactionSuccess.invoke(response);
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warning("Vault withdrawal failed: " + e.getMessage());
            return false;
        }
    }

    /** Format an amount using the economy's currency formatting (e.g. "$1,000"). */
    public String format(double amount) {
        try {
            return (String) formatMethod.invoke(economy, amount);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return String.valueOf(amount);
        }
    }
}
