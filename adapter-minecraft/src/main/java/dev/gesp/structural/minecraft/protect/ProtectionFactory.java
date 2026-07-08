package dev.gesp.structural.minecraft.protect;

import dev.gesp.structural.minecraft.hook.WarZoneService;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Wires the {@link CollapseGuard} from config + whatever optional plugins are
 * installed. Resolves at startup; logs what it found.
 *
 * <p>WorldGuard / CoreProtect classes are only touched in the branches taken
 * when those plugins are actually present, so this works on servers with
 * neither installed (and under MockBukkit, where neither is on the classpath).
 */
public final class ProtectionFactory {

    private ProtectionFactory() {}

    /**
     * Register the WorldGuard {@code strux-physics} flag. MUST be called from the
     * plugin's {@code onLoad()}, and only after confirming WorldGuard is present
     * (this touches WorldGuard classes).
     */
    public static void registerWorldGuardFlag(Logger log) {
        StruxFlags.register(log);
    }

    /**
     * @param worldGuardAvailable whether {@code onLoad} confirmed WorldGuard is
     *                            on the classpath and registered the flag.
     */
    public static CollapseGuard create(
            JavaPlugin plugin,
            boolean enabled,
            Set<String> disabledWorlds,
            boolean respectWorldGuard,
            boolean coreProtectLogging,
            boolean worldGuardAvailable,
            WarZoneService warZone) {
        Logger log = plugin.getLogger();

        ProtectionService protection;
        if (respectWorldGuard && worldGuardAvailable) {
            protection = new WorldGuardProtection(enabled, disabledWorlds);
        } else {
            protection = new NoopProtection(enabled, disabledWorlds);
        }
        log.info("Region protection: " + protection.describe());

        CollapseLogger logger = createLogger(plugin, coreProtectLogging);

        return new CollapseGuard(protection, warZone, logger);
    }

    private static CollapseLogger createLogger(JavaPlugin plugin, boolean enabled) {
        Logger log = plugin.getLogger();
        if (!enabled) {
            return NoopCollapseLogger.INSTANCE;
        }
        Plugin cp = plugin.getServer().getPluginManager().getPlugin("CoreProtect");
        if (cp == null) {
            log.info("CoreProtect not found - collapse logging disabled.");
            return NoopCollapseLogger.INSTANCE;
        }
        try {
            CollapseLogger logger = CoreProtectLogger.tryCreate(cp);
            if (logger != null) {
                log.info("Collapse logging enabled via CoreProtect (user '#strux').");
                return logger;
            }
            log.warning("CoreProtect present but its API is not ready; collapse logging disabled.");
        } catch (Throwable t) {
            log.warning("Failed to hook CoreProtect: " + t.getMessage());
        }
        return NoopCollapseLogger.INSTANCE;
    }
}
