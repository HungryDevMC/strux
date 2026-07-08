package dev.gesp.structural.minecraft.protect;

import dev.gesp.structural.minecraft.protect.CoreProtectRestoreDetector.RestoreLookup;
import dev.gesp.structural.model.NodePos;
import java.util.List;
import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The CoreProtect-backed {@link RestoreLookup}: the ONLY code here that touches the
 * {@code net.coreprotect} API. It is runtime-only glue — CoreProtect's concrete classes
 * are a {@code compileOnly} soft-dependency and cannot be loaded on the gate's MockBukkit
 * classpath (loading one throws {@code NoClassDefFoundError}), so this class is exempt from
 * coverage/mutation the same way {@code FaweCraterRemover} is. All decision logic
 * (throttle, cursor, busy-gate) lives in the pure, fully-tested
 * {@link CoreProtectRestoreDetector}; keep this seam thin.
 */
final class CoreProtectLookup implements RestoreLookup {

    private static final int MIN_API_VERSION = 9;
    /** How far back (in seconds) to query CoreProtect. */
    private static final int LOOKUP_SECONDS = 60;
    /** Action ID for block placement in CoreProtect. */
    private static final int ACTION_PLACE = 1;

    private final CoreProtectAPI api;

    private CoreProtectLookup(CoreProtectAPI api) {
        this.api = api;
    }

    /** A CoreProtect-backed lookup, or {@code null} if CoreProtect is not installed/usable. */
    static RestoreLookup tryCreate(JavaPlugin plugin) {
        Plugin cp = plugin.getServer().getPluginManager().getPlugin("CoreProtect");
        if (!(cp instanceof CoreProtect coreProtect)) {
            return null;
        }
        CoreProtectAPI cpApi = coreProtect.getAPI();
        if (cpApi == null || !cpApi.isEnabled() || cpApi.APIVersion() < MIN_API_VERSION) {
            return null;
        }
        return new CoreProtectLookup(cpApi);
    }

    @Override
    public boolean wasRestoredAfter(World world, NodePos pos, long afterTimeMillis) {
        Location loc = new Location(world, pos.x(), pos.y(), pos.z());
        List<String[]> results = api.blockLookup(loc.getBlock(), LOOKUP_SECONDS);
        if (results == null || results.isEmpty()) {
            return false;
        }
        for (String[] row : results) {
            CoreProtectAPI.ParseResult result = api.parseResult(row);
            if (result.getActionId() == ACTION_PLACE) {
                long placementTime = result.getTimestamp() * 1000L; // seconds → millis
                if (placementTime > afterTimeMillis) {
                    return true;
                }
            }
        }
        return false;
    }
}
