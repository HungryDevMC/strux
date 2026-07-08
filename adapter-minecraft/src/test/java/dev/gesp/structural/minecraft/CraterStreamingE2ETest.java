package dev.gesp.structural.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.minecraft.config.EffectsConfig;
import dev.gesp.structural.minecraft.hook.WarZoneService;
import dev.gesp.structural.minecraft.listener.CraterApplier;
import dev.gesp.structural.minecraft.listener.CraterBlockRemover;
import dev.gesp.structural.minecraft.listener.CraterBlockRemovers;
import dev.gesp.structural.minecraft.listener.DebrisVisuals;
import dev.gesp.structural.minecraft.listener.StreamedBukkitCraterRemover;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.protect.ChunkVerdict;
import dev.gesp.structural.minecraft.protect.CollapseGuard;
import dev.gesp.structural.minecraft.protect.CollapseLogger;
import dev.gesp.structural.minecraft.protect.NoopProtection;
import dev.gesp.structural.minecraft.protect.ProtectionService;
import dev.gesp.structural.minecraft.visual.CollapseEffects;
import dev.gesp.structural.model.NodePos;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.FallingBlock;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Pins the streamed crater applier (the fix for "big explosions hang the server"):
 * the crater is turned to AIR a few blocks per tick under a per-tick cap, NOT all at
 * once. Drives {@link CraterApplier#drainUpTo} directly so each behaviour is exact and
 * deterministic — the per-tick cap, the per-blast debris budget carried across the
 * stream, the chunk-verdict shortcut, the per-block protection veto, the sampled
 * effect, and the stale-AIR skip.
 */
@DisplayName("E2E: streamed crater application (a few blocks per tick, not all at once)")
class CraterStreamingE2ETest {

    private ServerMock server;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("crater_world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Counting helpers (deterministic spies on the collaborators)
    // ─────────────────────────────────────────────────────────────────────

    /** A logger that just counts how many removals it was asked to record. */
    private static final class CountingLogger implements CollapseLogger {
        final AtomicInteger logged = new AtomicInteger();

        @Override
        public void logRemoval(Location loc, Material type, BlockData data) {
            logged.incrementAndGet();
        }
    }

    /** A protection service that counts per-block queries and can deny by Y level. */
    private static final class CountingProtection implements ProtectionService {
        final AtomicInteger perBlockQueries = new AtomicInteger();
        final ChunkVerdict verdict;
        final int denyBelowY;

        CountingProtection(ChunkVerdict verdict, int denyBelowY) {
            this.verdict = verdict;
            this.denyBelowY = denyBelowY;
        }

        @Override
        public boolean physicsAllowed(Location loc) {
            perBlockQueries.incrementAndGet();
            return loc.getBlockY() >= denyBelowY;
        }

        @Override
        public ChunkVerdict chunkVerdict(World w, int cx, int cz) {
            return verdict;
        }

        @Override
        public String describe() {
            return "counting";
        }
    }

    /** Effects spy: counts every per-block collapse effect played. */
    private static final class CountingEffects extends CollapseEffects {
        final AtomicInteger blockEffects = new AtomicInteger();

        CountingEffects(EffectsConfig config, Plugin plugin) {
            super(config, plugin);
        }

        @Override
        public void playBlockCollapse(Location location, Material material) {
            blockEffects.incrementAndGet();
        }
    }

    private CollapseGuard guard(ProtectionService protection, CollapseLogger logger) {
        return new CollapseGuard(protection, WarZoneService.ALLOW_ALL, logger);
    }

    private CollapseEffects realEffects() {
        return new CollapseEffects(new EffectsConfig(), MockBukkit.createMockPlugin());
    }

    /** A vertical stone column at x=0,z=0 over [yLo,yHi]; returns its positions. */
    private List<NodePos> stoneColumn(int yLo, int yHi) {
        List<NodePos> positions = new ArrayList<>();
        for (int y = yLo; y <= yHi; y++) {
            world.getBlockAt(0, y, 0).setType(Material.STONE);
            positions.add(new NodePos(0, y, 0));
        }
        return positions;
    }

    private int airCount(List<NodePos> positions) {
        int air = 0;
        for (NodePos p : positions) {
            if (world.getBlockAt(p.x(), p.y(), p.z()).getType() == Material.AIR) {
                air++;
            }
        }
        return air;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  (A) The crater forms over MULTIPLE passes, not one — capped per pass.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A 20-block crater at a cap of 5 drains over exactly 4 passes, never all at once")
    void craterDrainsOverMultiplePasses() {
        List<NodePos> crater = stoneColumn(64, 83); // 20 blocks
        CraterApplier applier = new CraterApplier(
                guard(new NoopProtection(true, Set.of()), new CountingLogger()),
                realEffects(),
                new DebrisVisuals(MockBukkit.createMockPlugin()),
                new StreamedBukkitCraterRemover(),
                100);
        applier.enqueue(world, crater, 0);

        // Pass 1: exactly the cap (5), not the whole crater.
        assertEquals(5, applier.drainUpTo(5), "a pass removes at most the cap");
        assertEquals(5, airCount(crater), "only the capped slice is air after one pass");
        assertTrue(applier.hasPending(), "the rest of the crater is still pending");

        // Passes 2-4 finish it: 5 + 5 + 5 + 5 = 20.
        assertEquals(5, applier.drainUpTo(5));
        assertEquals(5, applier.drainUpTo(5));
        assertEquals(5, applier.drainUpTo(5));
        assertEquals(20, airCount(crater), "the whole crater is air after four capped passes");
        assertFalse(applier.hasPending(), "nothing pending once the crater fully drained");
        assertEquals(0, applier.drainUpTo(5), "draining an empty applier is a no-op");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  (B) The per-EXPLOSION debris budget is carried across the stream.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Debris budget is per-blast: a budget of 3 over a 10-block crater spawns exactly 3 across passes")
    void debrisBudgetCarriedAcrossPasses() {
        List<NodePos> crater = stoneColumn(64, 73); // 10 blocks
        int before = world.getEntitiesByClass(FallingBlock.class).size();
        CraterApplier applier = new CraterApplier(
                guard(new NoopProtection(true, Set.of()), new CountingLogger()),
                realEffects(),
                new DebrisVisuals(MockBukkit.createMockPlugin()),
                new StreamedBukkitCraterRemover(),
                100);
        applier.enqueue(world, crater, 3); // only 3 debris pieces allowed for this blast

        // Drain in slices of 4 — the budget must not reset between passes.
        applier.drainUpTo(4);
        applier.drainUpTo(4);
        applier.drainUpTo(4);
        assertFalse(applier.hasPending(), "the 10-block crater fully drained over three passes of 4");

        int spawned = world.getEntitiesByClass(FallingBlock.class).size() - before;
        assertEquals(3, spawned, "exactly the per-blast debris budget spawned across the whole stream");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  (C) ALL_ALLOWED chunk verdict skips the per-block protection query.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("An ALL_ALLOWED chunk skips every per-block protection query but still logs + removes")
    void allAllowedChunkSkipsPerBlockQuery() {
        List<NodePos> crater = stoneColumn(64, 71); // 8 blocks
        CountingProtection protection = new CountingProtection(ChunkVerdict.ALL_ALLOWED, Integer.MIN_VALUE);
        CountingLogger logger = new CountingLogger();
        CraterApplier applier = new CraterApplier(
                guard(protection, logger),
                realEffects(),
                new DebrisVisuals(MockBukkit.createMockPlugin()),
                new StreamedBukkitCraterRemover(),
                100);
        applier.enqueue(world, crater, 0);

        applier.drainUpTo(100);

        assertEquals(8, airCount(crater), "every block in an allowed chunk is removed");
        assertEquals(8, logger.logged.get(), "every removal is still logged for rollback");
        assertEquals(
                0,
                protection.perBlockQueries.get(),
                "an ALL_ALLOWED chunk must skip the per-block WorldGuard query entirely");
    }

    @Test
    @DisplayName("A PER_BLOCK chunk verdict falls back to a per-block protection query for every block")
    void perBlockVerdictFallsBackPerBlock() {
        List<NodePos> crater = stoneColumn(64, 68); // 5 blocks
        CountingProtection protection = new CountingProtection(ChunkVerdict.PER_BLOCK, Integer.MIN_VALUE);
        CraterApplier applier = new CraterApplier(
                guard(protection, new CountingLogger()),
                realEffects(),
                new DebrisVisuals(MockBukkit.createMockPlugin()),
                new StreamedBukkitCraterRemover(),
                100);
        applier.enqueue(world, crater, 0);

        applier.drainUpTo(100);

        assertEquals(5, airCount(crater), "all blocks removed when per-block says allow");
        assertEquals(5, protection.perBlockQueries.get(), "a PER_BLOCK chunk asks once per block");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  (D) Protection still blocks: a denied block is left standing.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A per-block protection veto leaves the protected block standing while the rest crater")
    void protectionLeavesDeniedBlockStanding() {
        List<NodePos> crater = stoneColumn(64, 68); // y=64..68
        // PER_BLOCK verdict, and the protection denies anything below y=66.
        CountingProtection protection = new CountingProtection(ChunkVerdict.PER_BLOCK, 66);
        CraterApplier applier = new CraterApplier(
                guard(protection, new CountingLogger()),
                realEffects(),
                new DebrisVisuals(MockBukkit.createMockPlugin()),
                new StreamedBukkitCraterRemover(),
                100);
        applier.enqueue(world, crater, 0);

        applier.drainUpTo(100);

        // y=64,65 are protected (denied) → still stone; y=66,67,68 → air.
        assertEquals(Material.STONE, world.getBlockAt(0, 64, 0).getType(), "protected y=64 stays standing");
        assertEquals(Material.STONE, world.getBlockAt(0, 65, 0).getType(), "protected y=65 stays standing");
        assertEquals(Material.AIR, world.getBlockAt(0, 66, 0).getType(), "allowed y=66 is cratered");
        assertEquals(Material.AIR, world.getBlockAt(0, 67, 0).getType(), "allowed y=67 is cratered");
        assertEquals(Material.AIR, world.getBlockAt(0, 68, 0).getType(), "allowed y=68 is cratered");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  (E) Per-block collapse effects are SAMPLED (1 in N), not one per block.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Effect sampling: a 20-block crater at sample-rate 5 plays the per-block effect only 4 times")
    void effectsAreSampled() {
        List<NodePos> crater = stoneColumn(64, 83); // 20 blocks
        CountingEffects effects = new CountingEffects(new EffectsConfig(), MockBukkit.createMockPlugin());
        CraterApplier applier = new CraterApplier(
                guard(new NoopProtection(true, Set.of()), new CountingLogger()),
                effects,
                new DebrisVisuals(MockBukkit.createMockPlugin()),
                new StreamedBukkitCraterRemover(),
                5); // 1 in 5
        applier.enqueue(world, crater, 0);

        applier.drainUpTo(100);

        assertEquals(20, airCount(crater), "all 20 blocks still cratered regardless of sampling");
        assertEquals(4, effects.blockEffects.get(), "20 removals at 1-in-5 sampling plays the effect 4 times, not 20");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  (F) Stale-world guard: a block already AIR when its turn comes is skipped.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A queued block already turned to AIR by another collapse is skipped gracefully")
    void staleAirBlockSkipped() {
        List<NodePos> crater = stoneColumn(64, 68); // 5 blocks
        // Something else cleared y=66 before the crater's turn (e.g. a delayed collapse).
        world.getBlockAt(0, 66, 0).setType(Material.AIR);
        CountingLogger logger = new CountingLogger();
        CraterApplier applier = new CraterApplier(
                guard(new NoopProtection(true, Set.of()), logger),
                realEffects(),
                new DebrisVisuals(MockBukkit.createMockPlugin()),
                new StreamedBukkitCraterRemover(),
                100);
        applier.enqueue(world, crater, 0);

        int applied = applier.drainUpTo(100);

        assertEquals(4, applied, "the already-air block is skipped, the other four are removed");
        assertEquals(4, logger.logged.get(), "a skipped (already-air) block is never logged");
        assertEquals(5, airCount(crater), "all five positions end up air (one was already)");
        assertFalse(applier.hasPending(), "the stale block still consumed its queue slot — nothing left pending");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  (G) An empty crater enqueues nothing.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Enqueuing an empty destroyed set is a no-op")
    void emptyCraterIsNoOp() {
        CraterApplier applier = new CraterApplier(
                guard(new NoopProtection(true, Set.of()), new CountingLogger()),
                realEffects(),
                new DebrisVisuals(MockBukkit.createMockPlugin()),
                new StreamedBukkitCraterRemover(),
                8);
        applier.enqueue(world, List.of(), 100);
        assertFalse(applier.hasPending(), "an empty crater adds no job");
        assertEquals(0, applier.drainUpTo(100), "and drains nothing");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  (H) The default block-write seam is the streamed Bukkit fallback.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("StreamedBukkitCraterRemover turns the approved positions to air via setType")
    void streamedBukkitRemoverWritesAir() {
        List<NodePos> positions = stoneColumn(70, 72); // 3 blocks
        StreamedBukkitCraterRemover remover = new StreamedBukkitCraterRemover();

        remover.removeToAir(world, positions);

        assertEquals(3, airCount(positions), "the fallback remover sets every approved position to air");
        assertTrue(
                remover.describe().toLowerCase().contains("bukkit"),
                "the fallback describes itself as the no-FAWE path");
        // Reusing the helper's location math indirectly proves toLocation round-trips.
        assertEquals(Material.AIR, world.getBlockAt(0, 71, 0).getType());
        // touch StructureManager.toLocation directly so the seam's contract is explicit.
        Location loc = StructureManager.toLocation(new NodePos(0, 71, 0), world);
        assertEquals(world, loc.getWorld());
    }

    // ─────────────────────────────────────────────────────────────────────
    //  (I) Diagnostics + the empty-candidate fast path.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("pendingJobs reports the number of craters still draining; a pass of all-stale blocks applies nothing")
    void pendingJobsAndAllStalePass() {
        List<NodePos> craterA = stoneColumn(64, 66);
        List<NodePos> craterB = new ArrayList<>(List.of(new NodePos(5, 64, 5), new NodePos(5, 65, 5)));
        for (NodePos p : craterB) {
            world.getBlockAt(p.x(), p.y(), p.z()).setType(Material.STONE);
        }
        CraterApplier applier = new CraterApplier(
                guard(new NoopProtection(true, Set.of()), new CountingLogger()),
                realEffects(),
                new DebrisVisuals(MockBukkit.createMockPlugin()),
                new StreamedBukkitCraterRemover(),
                100);
        applier.enqueue(world, craterA, 0);
        applier.enqueue(world, craterB, 0);
        assertEquals(2, applier.pendingJobs(), "two enqueued craters are two pending jobs");

        // Pre-clear craterA's blocks so the next pass (cap 3, draining craterA first)
        // finds ONLY stale AIR — the all-stale fast path applies nothing this pass.
        for (NodePos p : craterA) {
            world.getBlockAt(p.x(), p.y(), p.z()).setType(Material.AIR);
        }
        assertEquals(0, applier.drainUpTo(3), "a pass of only already-air blocks applies nothing");
        assertEquals(1, applier.pendingJobs(), "craterA was consumed by that pass; craterB remains");

        // craterB still applies normally on the next pass.
        assertEquals(2, applier.drainUpTo(100), "the still-solid second crater applies on the next pass");
        assertFalse(applier.hasPending());
    }

    // ─────────────────────────────────────────────────────────────────────
    //  (J) A whole-chunk DENY verdict leaves every block in the chunk standing.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("An ALL_DENIED chunk verdict leaves the whole crater standing and logs nothing")
    void allDeniedChunkLeavesCraterStanding() {
        List<NodePos> crater = stoneColumn(64, 68);
        // A war zone that denies the whole chunk → CollapseGuard yields ALL_DENIED, which
        // claimRemoval(block, verdict) must reject without consulting the logger.
        WarZoneService denyAll = new WarZoneService() {
            @Override
            public boolean destructionAllowed(Location loc) {
                return false;
            }

            @Override
            public ChunkVerdict chunkVerdict(World w, int cx, int cz) {
                return ChunkVerdict.ALL_DENIED;
            }

            @Override
            public String describe() {
                return "deny-all";
            }
        };
        CountingProtection protection = new CountingProtection(ChunkVerdict.ALL_ALLOWED, Integer.MIN_VALUE);
        CountingLogger logger = new CountingLogger();
        CraterApplier applier = new CraterApplier(
                new CollapseGuard(protection, denyAll, logger),
                realEffects(),
                new DebrisVisuals(MockBukkit.createMockPlugin()),
                new StreamedBukkitCraterRemover(),
                100);
        applier.enqueue(world, crater, 0);

        applier.drainUpTo(100);

        assertEquals(0, airCount(crater), "a denied chunk leaves every crater block standing");
        assertEquals(0, logger.logged.get(), "a denied removal is never logged");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  (K) Block-write seam selection (the soft-dep FAWE guard).
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("With no WorldEdit/FAWE present, the factory returns the streamed Bukkit fallback")
    void selectionFallsBackWithoutFawe() {
        // MockBukkit has no WorldEdit plugin, so even with fawe-acceleration on we get
        // the streamed Bukkit remover — the default, unit-tested path.
        CraterBlockRemover withFlag =
                CraterBlockRemovers.create(true, MockBukkit.createMockPlugin().getLogger());
        CraterBlockRemover withoutFlag =
                CraterBlockRemovers.create(false, MockBukkit.createMockPlugin().getLogger());
        assertTrue(withFlag instanceof StreamedBukkitCraterRemover, "no FAWE → fallback even when enabled");
        assertTrue(withoutFlag instanceof StreamedBukkitCraterRemover, "disabled → fallback");
    }

    @Test
    @DisplayName("The flag off keeps the fallback even when a WorldEdit-named plugin is present")
    void selectionRespectsDisabledFlagEvenWithFawe() {
        MockBukkit.createMockPlugin("FastAsyncWorldEdit");
        CraterBlockRemover remover =
                CraterBlockRemovers.create(false, MockBukkit.createMockPlugin().getLogger());
        assertTrue(
                remover instanceof StreamedBukkitCraterRemover,
                "fawe-acceleration off → fallback even with FAWE installed");
    }

    // NOTE: the FAWE-present branch of CraterBlockRemovers.create() returns FaweCraterRemover,
    // which cannot be exercised here — instantiating it links WorldEdit classes that are absent
    // from the MockBukkit test classpath. It is runtime-only glue, verified live on a FAWE server
    // (mirrors the replay adapter's WorldEditStageWriter). The testable fallback branches are
    // covered above.
}
