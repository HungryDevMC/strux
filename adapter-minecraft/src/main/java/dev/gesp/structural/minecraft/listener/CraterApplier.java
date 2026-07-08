package dev.gesp.structural.minecraft.listener;

import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.protect.ChunkVerdict;
import dev.gesp.structural.minecraft.protect.CollapseGuard;
import dev.gesp.structural.minecraft.visual.CollapseEffects;
import dev.gesp.structural.model.NodePos;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Streams a settled blast's crater into the world a few blocks per tick, instead of
 * the old all-at-once loop that turned thousands of blocks to AIR in a single frozen
 * tick.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                        CRATER APPLIER                             │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  finalizeBlast no longer applies the crater inline. It enqueues a  │
 *   │  CraterJob (the destroyed positions + this blast's debris budget +  │
 *   │  its centre) here. Each drain pass turns at most                    │
 *   │  max-crater-removals-per-tick of them to AIR, so a 2000-block        │
 *   │  crater forms over ~30 ticks rather than freezing one.              │
 *   │                                                                     │
 *   │  Per removal it still: gates protection, logs to CoreProtect, plays  │
 *   │  a (sampled) collapse effect, spawns (budgeted) debris — then hands  │
 *   │  the approved slice to a CraterBlockRemover for the raw AIR write.   │
 *   │                                                                     │
 *   │  Cheaper per pass via two seams:                                    │
 *   │   • chunk verdict resolved ONCE per chunk (skip per-block WorldGuard │
 *   │     when the whole chunk is ALL_ALLOWED)                            │
 *   │   • per-block collapse effect SAMPLED (1 in N) — sound/particle      │
 *   │     spikes were a server AND client cost at thousands of blocks      │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p><b>Per-blast, not per-tick.</b> The debris budget rides on the {@link CraterJob},
 * so it is carried across however many ticks that one blast's crater takes to drain —
 * a blast still spawns at most its configured debris, no matter how the stream slices.
 *
 * <p><b>Stale-world guard.</b> A queued position may already be AIR by the time its
 * turn comes (another collapse cleared it). Those are skipped gracefully.
 *
 * <p>Main-thread only — all state is touched from the single {@link BlastProcessor}
 * tick that drives {@link #drainUpTo}.
 */
public final class CraterApplier {

    private final CollapseGuard guard;
    private final CollapseEffects effects;
    private final DebrisVisuals debris;
    private final CraterBlockRemover remover;

    /** Play the per-block collapse effect for 1 in {@code effectSampleRate} removals. */
    private final int effectSampleRate;

    /** FIFO of craters still draining. Each job streams its own positions + debris budget. */
    private final Deque<CraterJob> jobs = new ArrayDeque<>();

    public CraterApplier(
            CollapseGuard guard,
            CollapseEffects effects,
            DebrisVisuals debris,
            CraterBlockRemover remover,
            int effectSampleRate) {
        this.guard = guard;
        this.effects = effects;
        this.debris = debris;
        this.remover = remover;
        this.effectSampleRate = Math.max(1, effectSampleRate);
    }

    /**
     * Enqueue a finished blast's crater for streamed application.
     *
     * @param world       the world to crater
     * @param destroyed   the positions the core blast model destroyed
     * @param debrisBudget how many debris pieces this ONE blast may still spawn
     */
    public void enqueue(World world, List<NodePos> destroyed, int debrisBudget) {
        if (destroyed.isEmpty()) {
            return;
        }
        jobs.addLast(new CraterJob(world, new ArrayDeque<>(destroyed), debrisBudget));
    }

    /** True while at least one crater still has blocks waiting to be turned to AIR. */
    public boolean hasPending() {
        return !jobs.isEmpty();
    }

    /** How many craters are still draining (for tests/diagnostics). */
    public int pendingJobs() {
        return jobs.size();
    }

    /** Drop everything still queued (plugin disable / stop). */
    public void clear() {
        jobs.clear();
    }

    /**
     * Apply up to {@code maxRemovals} crater blocks to the world this pass, draining
     * the oldest job first and moving on to the next when one empties.
     *
     * <p>Removals approved this pass are grouped by chunk: the protection verdict is
     * resolved ONCE per chunk via {@link CollapseGuard#physicsAllowedInChunk}, so an
     * ALL_ALLOWED chunk skips the per-block WorldGuard/war-zone queries entirely. The
     * raw AIR writes for the whole approved slice go through the {@link
     * CraterBlockRemover} in one call (one bulk edit with FAWE; a plain loop without).
     *
     * @param maxRemovals the per-tick cap ({@code blast.max-crater-removals-per-tick})
     * @return how many blocks were actually turned to AIR this pass
     */
    public int drainUpTo(int maxRemovals) {
        if (jobs.isEmpty() || maxRemovals <= 0) {
            return 0;
        }
        // Candidates considered this pass, grouped by chunk so each chunk's verdict is
        // resolved once. Index into the flat candidate list lets us pair each with its
        // owning job (for the debris budget + effect sampling) after the verdict step.
        List<Candidate> candidates = new ArrayList<>();
        int considered = 0;
        while (considered < maxRemovals && !jobs.isEmpty()) {
            CraterJob job = jobs.peekFirst();
            NodePos pos = job.positions.pollFirst();
            if (job.positions.isEmpty()) {
                jobs.pollFirst(); // this job is exhausted
            }
            considered++;
            Location loc = StructureManager.toLocation(pos, job.world);
            Block block = loc.getBlock();
            // Stale-world guard: another collapse may have already cleared this block.
            if (block.getType() == Material.AIR) {
                continue;
            }
            candidates.add(new Candidate(job, pos, block));
        }
        if (candidates.isEmpty()) {
            return 0;
        }

        // Resolve each chunk's verdict once (a candidate's chunk = block x/z >> 4), and
        // collect the approved AIR writes grouped by world so a per-world remover (FAWE
        // opens one edit per world) gets a coherent batch. The verdict cache is keyed by
        // world AND chunk: a single drain pass can pull candidates from jobs in different
        // worlds, and physicsAllowedInChunk is world-specific — sharing a chunk-only key
        // would reuse world A's verdict for world B's blocks (protection bypass).
        Map<World, Map<Long, ChunkVerdict>> verdictByChunk = new HashMap<>();
        Map<World, List<NodePos>> approvedByWorld = new HashMap<>();
        int applied = 0;
        for (Candidate c : candidates) {
            World world = c.job.world;
            int chunkX = c.pos.x() >> 4;
            int chunkZ = c.pos.z() >> 4;
            long chunkKey = (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
            ChunkVerdict verdict = verdictByChunk
                    .computeIfAbsent(world, w -> new HashMap<>())
                    .computeIfAbsent(chunkKey, k -> guard.physicsAllowedInChunk(world, chunkX, chunkZ));

            // Protection gate (chunk-verdict aware): logs the removal when allowed.
            if (!guard.claimRemoval(c.block, verdict)) {
                continue; // protected — leave it standing
            }
            approvedByWorld.computeIfAbsent(world, w -> new ArrayList<>()).add(c.pos);
            applied++;

            // Sampled per-block collapse effect — 1 in N, so a giant crater doesn't
            // fire thousands of sounds/particles (a server AND client spike).
            if (applied % effectSampleRate == 0) {
                effects.playBlockCollapse(c.block.getLocation(), c.block.getType());
            }

            // Budgeted debris, charged against THIS blast's carried budget.
            if (c.job.debrisBudget > 0) {
                debris.spawn(world, c.block.getLocation(), c.block.getType().createBlockData());
                c.job.debrisBudget--;
            }
        }

        // The raw AIR write for everything approved this pass — one call per world (a
        // single call in the common case; bulk on FAWE).
        approvedByWorld.forEach(remover::removeToAir);
        return applied;
    }

    /** One finished blast's crater, streaming its positions out under the per-tick cap. */
    private static final class CraterJob {
        final World world;
        final Deque<NodePos> positions;
        int debrisBudget;

        CraterJob(World world, Deque<NodePos> positions, int debrisBudget) {
            this.world = world;
            this.positions = positions;
            this.debrisBudget = debrisBudget;
        }
    }

    /** A position pulled this pass, still solid, paired with its owning job. */
    private record Candidate(CraterJob job, NodePos pos, Block block) {}
}
