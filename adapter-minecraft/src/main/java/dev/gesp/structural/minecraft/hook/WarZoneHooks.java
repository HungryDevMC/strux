package dev.gesp.structural.minecraft.hook;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

/**
 * Resolves the active {@link WarZoneService} from config + installed plugins.
 *
 * <p>If scoping is off, or no requested provider can be hooked, returns
 * {@link WarZoneService#ALLOW_ALL} (fail-open) so a misconfiguration never
 * silently kills strux — the reason is logged at startup. When more than one
 * provider hooks successfully, destruction is allowed if <em>any</em> of them
 * considers the location a war zone (union).
 */
public final class WarZoneHooks {

    private WarZoneHooks() {}

    /**
     * @param enabled         master switch for war-zone scoping
     * @param providers       requested providers, e.g. {@code {"towny","factions"}} (case-insensitive)
     * @param allowWilderness whether unclaimed land counts as a war zone during war
     */
    public static WarZoneService resolve(
            Plugin plugin, boolean enabled, Set<String> providers, boolean allowWilderness) {
        Logger log = plugin.getLogger();
        if (!enabled) {
            return WarZoneService.ALLOW_ALL;
        }

        List<WarZoneService> active = new ArrayList<>();
        for (String provider : providers) {
            String name = provider.toLowerCase(Locale.ROOT).trim();
            switch (name) {
                case "towny" -> tryHook(plugin, "Towny", () -> TownyWarZone.create(allowWilderness, log), active, log);
                case "factions" -> tryHook(
                        plugin, "Factions", () -> FactionsWarZone.create(allowWilderness, log), active, log);
                case "", "none" -> {
                    /* ignore */
                }
                default -> log.warning("Unknown war-zone provider '" + provider + "' (expected towny or factions).");
            }
        }

        if (active.isEmpty()) {
            log.warning("War-zone scoping is ON but no provider could be hooked - "
                    + "destruction will be allowed everywhere region rules permit. "
                    + "Install Towny/Factions or set regions.war-zone.enabled: false.");
            return WarZoneService.ALLOW_ALL;
        }
        WarZoneService service = active.size() == 1 ? active.get(0) : new CompositeWarZone(active);
        log.info("War-zone scoping: " + service.describe());
        return service;
    }

    /** A provider factory that may throw if the plugin's API isn't present/compatible. */
    private interface Factory {
        WarZoneService create() throws ReflectiveOperationException;
    }

    private static void tryHook(
            Plugin plugin, String pluginName, Factory factory, List<WarZoneService> out, Logger log) {
        if (plugin.getServer().getPluginManager().getPlugin(pluginName) == null) {
            log.info(pluginName + " not found - war-zone scoping via " + pluginName + " disabled.");
            return;
        }
        try {
            out.add(factory.create());
            log.info(pluginName + " war-zone hook enabled.");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            log.warning("Failed to hook " + pluginName + " for war-zone scoping (" + e.getMessage()
                    + "); this provider is disabled.");
        }
    }

    /** Union of several providers: a location is a war zone if any provider says so. */
    private static final class CompositeWarZone implements WarZoneService {
        private final List<WarZoneService> delegates;

        CompositeWarZone(List<WarZoneService> delegates) {
            this.delegates = List.copyOf(delegates);
        }

        @Override
        public boolean destructionAllowed(Location loc) {
            for (WarZoneService delegate : delegates) {
                if (delegate.destructionAllowed(loc)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String describe() {
            StringBuilder sb = new StringBuilder("any of [");
            for (int i = 0; i < delegates.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(delegates.get(i).describe());
            }
            return sb.append(']').toString();
        }
    }
}
