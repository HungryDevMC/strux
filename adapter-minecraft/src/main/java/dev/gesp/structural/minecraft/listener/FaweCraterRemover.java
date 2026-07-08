package dev.gesp.structural.minecraft.listener;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.EditSession.ReorderMode;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockTypes;
import dev.gesp.structural.model.NodePos;
import java.util.List;
import org.bukkit.World;

/**
 * FastAsyncWorldEdit-accelerated crater writer: turns an approved batch of positions to
 * AIR through a single WorldEdit {@link EditSession} instead of N per-block setType calls.
 * Under FAWE the edit is queued and flushed off the main thread, so a large crater the
 * per-block writer streams over many ticks instead applies in one cheap bulk edit.
 *
 * <p><b>Untestable runtime glue, kept deliberately thin.</b> WorldEdit is a runtime-only
 * soft dependency (absent from the MockBukkit test classpath), so this class can be
 * neither loaded nor unit-tested in the gate — exactly mirroring the replay adapter's
 * {@code WorldEditStageWriter}. All decision logic (which writer to use, the protection
 * gate, effects, debris) lives in pure tested code; this class does nothing but translate
 * approved positions into WorldEdit AIR writes. It is instantiated ONLY after
 * {@link CraterBlockRemovers} confirms FAWE/WorldEdit is installed, so the JVM never
 * resolves these imports on a server without it. Verified live on a FAWE server.
 *
 * <p>A crater write is a destructive engine edit, not a user edit: history tracking is off
 * (no undo buffer), reordering is off, and fast mode is on — skipping work that only
 * matters for interactive edits, and (with {@code false} physics semantics) leaving the
 * structural physics to strux.
 */
public final class FaweCraterRemover implements CraterBlockRemover {

    @Override
    public void removeToAir(World world, List<NodePos> approved) {
        if (approved.isEmpty()) {
            return;
        }
        // AIR is resolved here (not in a field) so the class carries no WorldEdit-typed
        // field — loading it for the writer-selection test resolves no WorldEdit classes;
        // only this method body (run live with FAWE present) touches them.
        BlockState air = BlockTypes.AIR.getDefaultState();
        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
        try (EditSession session = WorldEdit.getInstance()
                .newEditSessionBuilder()
                .world(weWorld)
                .maxBlocks(-1)
                .build()) {
            session.setTrackingHistory(false);
            session.setReorderMode(ReorderMode.NONE);
            session.setFastMode(true);
            for (NodePos pos : approved) {
                session.rawSetBlock(BlockVector3.at(pos.x(), pos.y(), pos.z()), air);
            }
            session.flushSession();
        }
    }

    @Override
    public String describe() {
        return "FastAsyncWorldEdit / WorldEdit (async bulk EditSession)";
    }
}
