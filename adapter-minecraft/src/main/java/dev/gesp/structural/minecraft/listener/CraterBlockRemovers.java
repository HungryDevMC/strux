package dev.gesp.structural.minecraft.listener;

import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Chooses which {@link CraterBlockRemover} to use.
 *
 * <pre>
 *   With FastAsyncWorldEdit (or WorldEdit) installed AND blast.fawe-acceleration on,
 *   crater AIR-writes go through {@link FaweCraterRemover} — one bulk async EditSession
 *   instead of N per-block setType calls. Otherwise (the default, and the only path
 *   MockBukkit can test) the streamed-Bukkit writer is used.
 * </pre>
 *
 * <p>{@link FaweCraterRemover} is untestable runtime glue: WorldEdit is absent from the
 * MockBukkit classpath, so it can be neither loaded nor unit-tested in the gate. It is
 * referenced ONLY after {@link #fawePluginPresent()} confirms WorldEdit is installed, so
 * the JVM never resolves its WorldEdit imports on a server without it — and it is verified
 * live on a FAWE server, mirroring the replay adapter's {@code WorldEditStageWriter}.
 */
public final class CraterBlockRemovers {

    private CraterBlockRemovers() {}

    /**
     * @param faweEnabled the {@code blast.fawe-acceleration} config flag (use FAWE if present)
     * @param logger      plugin logger for the startup note
     * @return the crater block-writer to use (currently always the streamed-Bukkit
     *     writer; never null)
     */
    public static CraterBlockRemover create(boolean faweEnabled, Logger logger) {
        if (faweEnabled && fawePluginPresent()) {
            logger.info("FastAsyncWorldEdit detected; strux blast.fawe-acceleration is on — "
                    + "crater writes use bulk async EditSessions.");
            return new FaweCraterRemover();
        }
        return new StreamedBukkitCraterRemover();
    }

    /**
     * @return true if FastAsyncWorldEdit (or WorldEdit, which FAWE replaces in place)
     *     is installed and enabled. Checks by plugin name only — no WorldEdit class is
     *     touched here, so this is safe to call on a server without it.
     */
    private static boolean fawePluginPresent() {
        Plugin fawe = Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit");
        if (fawe != null && fawe.isEnabled()) {
            return true;
        }
        // FAWE installs as a drop-in replacement for WorldEdit and exposes the same
        // bulk-edit API, so a plain WorldEdit also satisfies the EditSession path.
        Plugin worldEdit = Bukkit.getPluginManager().getPlugin("WorldEdit");
        return worldEdit != null && worldEdit.isEnabled();
    }
}
