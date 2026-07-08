package dev.gesp.structural.minecraft.protect;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/** Collapse logger used when no block-logging plugin is available: does nothing. */
public final class NoopCollapseLogger implements CollapseLogger {

    public static final NoopCollapseLogger INSTANCE = new NoopCollapseLogger();

    private NoopCollapseLogger() {}

    @Override
    public void logRemoval(Location loc, Material type, BlockData data) {
        // no-op
    }
}
