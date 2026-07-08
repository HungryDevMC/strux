package dev.gesp.structural.minecraft.protect;

import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;

/**
 * Logs collapse removals to CoreProtect under the user {@code #strux}.
 *
 * <p>The {@code #} prefix marks it as a non-player source in CoreProtect, so
 * collapses are inspectable (<code>/co inspect</code>) and reversible
 * (<code>/co rollback u:#strux</code>) separately from player edits.
 *
 * <p>Only instantiate via {@link #tryCreate(Plugin)} after confirming CoreProtect
 * is present — referencing this class loads CoreProtect types.
 */
public final class CoreProtectLogger implements CollapseLogger {

    private static final String USER = "#strux";
    private static final int MIN_API_VERSION = 9;

    private final CoreProtectAPI api;

    private CoreProtectLogger(CoreProtectAPI api) {
        this.api = api;
    }

    /**
     * @return a logger if {@code plugin} is a usable CoreProtect instance, else null.
     */
    static CoreProtectLogger tryCreate(Plugin plugin) {
        if (!(plugin instanceof CoreProtect coreProtect)) {
            return null;
        }
        CoreProtectAPI api = coreProtect.getAPI();
        if (api == null || !api.isEnabled() || api.APIVersion() < MIN_API_VERSION) {
            return null;
        }
        return new CoreProtectLogger(api);
    }

    /**
     * Per-block, but cheap: CoreProtect's {@code logRemoval} only ENQUEUES the edit to
     * its own background consumer thread (it ends in {@code consumer.Queue.queueBlockBreak});
     * the SQLite write happens off the main thread. So the per-call main-thread cost is
     * a {@code BlockData.getAsString()}, a container check, and the enqueue — small. The
     * streamed crater applier already spreads these calls across ticks, so there is no
     * need to batch them here (verified against CoreProtect 22.4's API bytecode).
     */
    @Override
    public void logRemoval(Location loc, Material type, BlockData data) {
        api.logRemoval(USER, loc, type, data);
    }
}
