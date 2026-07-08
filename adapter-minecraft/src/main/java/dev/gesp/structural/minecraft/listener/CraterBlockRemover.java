package dev.gesp.structural.minecraft.listener;

import dev.gesp.structural.model.NodePos;
import java.util.List;
import org.bukkit.World;

/**
 * The seam that performs ONLY the AIR block-writes of a settled crater.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                     CRATER BLOCK REMOVER                           │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  A crater is applied in two halves:                                │
 *   │    1. strux logic — protection gate, CoreProtect log, sampled       │
 *   │       collapse effect, budgeted debris (the CraterApplier does this)│
 *   │    2. the raw "turn these blocks to AIR" world-write — THIS seam     │
 *   │                                                                     │
 *   │  Splitting the write out lets a server with FastAsyncWorldEdit       │
 *   │  installed do the writes in ONE bulk/async edit instead of N         │
 *   │  per-block setType calls, while the strux logic above is identical   │
 *   │  either way. With no FAWE present, the default streamed-Bukkit impl  │
 *   │  loops plain {@code setType(AIR)} — the only path MockBukkit tests.  │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p><b>Contract:</b> an implementation does NOTHING but write AIR. It must NOT run
 * protection, logging, effects, or debris — the {@link CraterApplier} already did all
 * of that for exactly the positions passed in, which is why the positions handed here
 * are the ones already approved for removal this drain pass.
 */
public interface CraterBlockRemover {

    /**
     * Turn every position in {@code approved} to AIR in {@code world}. The positions
     * have already passed the protection gate and been logged/effected by the caller;
     * this only performs the block write.
     *
     * @param world    the world to edit
     * @param approved the positions to set to AIR (may be empty — then a no-op)
     */
    void removeToAir(World world, List<NodePos> approved);

    /** Short human-readable description for startup logging. */
    String describe();
}
