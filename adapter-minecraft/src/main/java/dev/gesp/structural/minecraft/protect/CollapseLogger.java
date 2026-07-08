package dev.gesp.structural.minecraft.protect;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/**
 * Records a block destroyed by a strux collapse, so it shows up in a block-log
 * plugin and can be rolled back.
 *
 * <pre>
 *   Why server owners care:
 *     • Accountability - "what flattened my base?" → /co inspect shows #strux
 *     • Rollback       - /co rollback u:#strux t:5m  restores a bad collapse
 *
 *   Logging happens BEFORE the block is set to AIR, so the original block
 *   type + data are captured for an accurate restore.
 * </pre>
 */
public interface CollapseLogger {

    /** Log that {@code type}/{@code data} at {@code loc} is being removed by a collapse. */
    void logRemoval(Location loc, Material type, BlockData data);
}
