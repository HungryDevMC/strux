package dev.gesp.structural.minecraft.hook;

import java.lang.reflect.Method;
import java.util.logging.Logger;
import org.bukkit.Location;

/**
 * War-zone provider backed by Towny, via reflection.
 *
 * <p>We do not compile against Towny: its Maven coordinates and war API vary
 * between versions/forks, and a wrong guess would break the build. Instead we
 * resolve the small, stable surface we need at construction time and fail fast
 * if it isn't there — {@link WarZoneHooks} then simply skips this provider.
 *
 * <p>Semantics: destruction is allowed when a Towny war is active AND the
 * location sits inside a claimed town plot. Unclaimed wilderness and peacetime
 * are left alone (optionally, wilderness can be permitted during war).
 */
final class TownyWarZone implements WarZoneService {

    private final Object api; // com.palmergames.bukkit.towny.TownyAPI instance
    private final Method isWarTime; // boolean isWarTime()
    private final Method getTownBlock; // TownBlock getTownBlock(Location)
    private final boolean allowWilderness;
    private final Logger log;
    private boolean loggedCallFailure = false;

    private TownyWarZone(Object api, Method isWarTime, Method getTownBlock, boolean allowWilderness, Logger log) {
        this.api = api;
        this.isWarTime = isWarTime;
        this.getTownBlock = getTownBlock;
        this.allowWilderness = allowWilderness;
        this.log = log;
    }

    /**
     * Resolve the Towny API surface, or throw if Towny isn't present / its API
     * doesn't match. Throwing here (not at call time) keeps a broken provider
     * from ever being registered.
     */
    static TownyWarZone create(boolean allowWilderness, Logger log) throws ReflectiveOperationException {
        Class<?> townyApi = Class.forName("com.palmergames.bukkit.towny.TownyAPI");
        Object api = townyApi.getMethod("getInstance").invoke(null);
        Method isWarTime = townyApi.getMethod("isWarTime");
        Method getTownBlock = townyApi.getMethod("getTownBlock", Location.class);
        return new TownyWarZone(api, isWarTime, getTownBlock, allowWilderness, log);
    }

    @Override
    public boolean destructionAllowed(Location loc) {
        try {
            if (!(boolean) isWarTime.invoke(api)) {
                return false; // peacetime: nothing besieges
            }
            Object townBlock = getTownBlock.invoke(api, loc);
            return townBlock != null || allowWilderness;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Init succeeded, so a call-time failure is unexpected; warn once and
            // fail safe (treat as not-a-warzone) rather than spamming the log.
            if (!loggedCallFailure) {
                log.warning("Towny war-zone check failed (" + e.getMessage() + "); treating as not-a-warzone.");
                loggedCallFailure = true;
            }
            return false;
        }
    }

    @Override
    public String describe() {
        return "Towny (during war" + (allowWilderness ? ", incl. wilderness)" : ", claimed plots only)");
    }
}
