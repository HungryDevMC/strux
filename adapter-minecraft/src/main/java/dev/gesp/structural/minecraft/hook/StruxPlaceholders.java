package dev.gesp.structural.minecraft.hook;

import dev.gesp.structural.assess.StructureReport;
import dev.gesp.structural.minecraft.manager.StructureManager;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * PlaceholderAPI expansion exposing strux's structural grade for the requesting
 * player's world.
 *
 * <pre>
 *   %strux_grade%        S / A / B / C / F
 *   %strux_peak_stress%  highest stress %, whole number (e.g. 87)
 *   %strux_avg_stress%   average stress %
 *   %strux_overloaded%   count of overloaded blocks
 *   %strux_tracked%      count of load-bearing blocks assessed
 * </pre>
 *
 * <p>Each request would otherwise re-solve the world graph, so results are
 * cached per world for a short window — scoreboards/holograms can poll freely.
 */
public final class StruxPlaceholders extends PlaceholderExpansion {

    private static final long CACHE_TTL_MILLIS = 1000;

    private final Plugin plugin;
    private final StructureManager structureManager;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    public StruxPlaceholders(Plugin plugin, StructureManager structureManager) {
        this.plugin = plugin;
        this.structureManager = structureManager;
    }

    @Override
    public String getIdentifier() {
        return "strux";
    }

    @Override
    public String getAuthor() {
        return "gesp";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // survive a PlaceholderAPI reload
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (!(player instanceof Player online)) {
            return "";
        }
        StructureReport report = reportFor(online);
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "grade" -> report.grade().name();
            case "peak_stress" -> String.valueOf(report.peakPercent());
            case "avg_stress" -> String.valueOf(report.avgPercent());
            case "overloaded" -> String.valueOf(report.overloadedCount());
            case "tracked" -> String.valueOf(report.assessedNodes());
            default -> null; // unknown placeholder
        };
    }

    private StructureReport reportFor(Player player) {
        UUID worldId = player.getWorld().getUID();
        long now = System.currentTimeMillis();
        Cached cached = cache.get(worldId);
        if (cached != null && cached.expiresAt > now) {
            return cached.report;
        }
        // assessWorld() solves the LIVE world graph with the shared, stateful
        // StressSolver — both main-thread-only. PlaceholderAPI resolves placeholders on
        // whatever thread asks (async chat / scoreboard / TAB threads), so off the main
        // thread we must NEVER solve: hand back the most recent cached report, or an
        // empty one if this world has not been assessed yet. The main thread refreshes
        // the cache on the next on-main request once the TTL has expired.
        if (!Bukkit.isPrimaryThread()) {
            return cached != null ? cached.report : StructureReport.empty();
        }
        StructureReport report = structureManager.assessWorld(player.getWorld());
        cache.put(worldId, new Cached(report, now + CACHE_TTL_MILLIS));
        return report;
    }

    private record Cached(StructureReport report, long expiresAt) {}
}
