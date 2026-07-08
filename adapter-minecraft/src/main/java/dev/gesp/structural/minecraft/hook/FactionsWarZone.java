package dev.gesp.structural.minecraft.hook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.logging.Logger;
import org.bukkit.Location;

/**
 * War-zone provider backed by Factions (FactionsUUID / SaberFactions family),
 * via reflection.
 *
 * <p>As with {@link TownyWarZone}, we avoid a compile-time dependency because
 * Factions has many forks with diverging coordinates. We resolve the classic,
 * widely-shared surface — {@code Board.getInstance().getFactionAt(FLocation)} —
 * at construction and fail fast if absent.
 *
 * <p>Semantics: destruction is allowed inside territory belonging to the special
 * "WarZone" system faction (the usual siege arena), and optionally in
 * unclaimed wilderness.
 */
final class FactionsWarZone implements WarZoneService {

    private final Object board; // com.massivecraft.factions.Board instance
    private final Constructor<?> flocationCtor; // FLocation(Location)
    private final Method getFactionAt; // Faction getFactionAt(FLocation)
    private final Method isWarZone; // boolean Faction.isWarZone()
    private final Method isWilderness; // boolean Faction.isWilderness()
    private final boolean allowWilderness;
    private final Logger log;
    private boolean loggedCallFailure = false;

    private FactionsWarZone(
            Object board,
            Constructor<?> flocationCtor,
            Method getFactionAt,
            Method isWarZone,
            Method isWilderness,
            boolean allowWilderness,
            Logger log) {
        this.board = board;
        this.flocationCtor = flocationCtor;
        this.getFactionAt = getFactionAt;
        this.isWarZone = isWarZone;
        this.isWilderness = isWilderness;
        this.allowWilderness = allowWilderness;
        this.log = log;
    }

    static FactionsWarZone create(boolean allowWilderness, Logger log) throws ReflectiveOperationException {
        Class<?> boardClass = Class.forName("com.massivecraft.factions.Board");
        Object board = boardClass.getMethod("getInstance").invoke(null);

        Class<?> flocationClass = Class.forName("com.massivecraft.factions.FLocation");
        Constructor<?> flocationCtor = flocationClass.getConstructor(Location.class);

        Method getFactionAt = boardClass.getMethod("getFactionAt", flocationClass);
        Class<?> factionClass = Class.forName("com.massivecraft.factions.Faction");
        Method isWarZone = factionClass.getMethod("isWarZone");
        Method isWilderness = factionClass.getMethod("isWilderness");

        return new FactionsWarZone(board, flocationCtor, getFactionAt, isWarZone, isWilderness, allowWilderness, log);
    }

    @Override
    public boolean destructionAllowed(Location loc) {
        try {
            Object flocation = flocationCtor.newInstance(loc);
            Object faction = getFactionAt.invoke(board, flocation);
            if (faction == null) {
                return allowWilderness;
            }
            if ((boolean) isWarZone.invoke(faction)) {
                return true;
            }
            return allowWilderness && (boolean) isWilderness.invoke(faction);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!loggedCallFailure) {
                log.warning("Factions war-zone check failed (" + e.getMessage() + "); treating as not-a-warzone.");
                loggedCallFailure = true;
            }
            return false;
        }
    }

    @Override
    public String describe() {
        return "Factions (WarZone territory" + (allowWilderness ? " + wilderness)" : " only)");
    }
}
