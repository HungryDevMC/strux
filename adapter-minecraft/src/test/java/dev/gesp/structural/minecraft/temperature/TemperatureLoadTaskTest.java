package dev.gesp.structural.minecraft.temperature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.config.EffectsConfig;
import dev.gesp.structural.minecraft.fire.FireScorchTask;
import dev.gesp.structural.minecraft.hook.WarZoneService;
import dev.gesp.structural.minecraft.listener.CascadeResumeManager;
import dev.gesp.structural.minecraft.listener.DelayedCollapseManager;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.material.MaterialRegistry;
import dev.gesp.structural.minecraft.perf.TaskTimings;
import dev.gesp.structural.minecraft.protect.CollapseGuard;
import dev.gesp.structural.minecraft.protect.NoopCollapseLogger;
import dev.gesp.structural.minecraft.protect.ProtectionService;
import dev.gesp.structural.minecraft.visual.CollapseEffects;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.model.ThermalClass;
import dev.gesp.structural.solver.StressSolver;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * E2E for {@link TemperatureLoadTask}: world heat softens a stressed structure,
 * thermal mass keeps a thick wall's interior stronger than its face, heat-then-
 * douse cracks a brittle block, an actively-burning block is not double-penalised
 * (rule A), and the whole thing is inert when the flag is off.
 */
@DisplayName("TemperatureLoadTask: world heat → strength loss")
class TemperatureLoadTaskTest {

    private ServerMock server;
    private WorldMock world;
    private StructureManager structureManager;
    private DelayedCollapseManager delayedCollapseManager;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("temp_world");
        structureManager = new StructureManager(new MaterialRegistry(), new PhysicsConfig());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private CollapseGuard allowAll() {
        ProtectionService allow = new ProtectionService() {
            @Override
            public boolean physicsAllowed(Location loc) {
                return true;
            }

            @Override
            public String describe() {
                return "allow";
            }
        };
        return new CollapseGuard(allow, WarZoneService.ALLOW_ALL, NoopCollapseLogger.INSTANCE);
    }

    private PhysicsConfig config(boolean enabled) {
        PhysicsConfig cfg = new PhysicsConfig();
        cfg.setTemperatureStrengthEnabled(enabled);
        return cfg;
    }

    private TemperatureLoadTask newTask(PhysicsConfig cfg, TemperatureProvider provider) {
        Plugin plugin = MockBukkit.createMockPlugin("TempTestPlugin");
        CollapseEffects effects = new CollapseEffects(new EffectsConfig(), plugin);
        TaskTimings timings = new TaskTimings();
        CollapseGuard guard = allowAll();
        delayedCollapseManager =
                new DelayedCollapseManager(plugin, structureManager, new EffectsConfig(), effects, guard, timings);
        CascadeResumeManager resume = new CascadeResumeManager(
                plugin, structureManager, delayedCollapseManager, Logger.getLogger("TempResume"), 100, timings);
        return new TemperatureLoadTask(
                plugin,
                structureManager,
                cfg,
                delayedCollapseManager,
                resume,
                guard,
                provider,
                null, // no fire task in these scenarios unless a test supplies one
                40,
                5,
                cfg.isTemperatureStrengthEnabled(),
                1000.0,
                timings);
    }

    /**
     * A single stressed steel block on a grounded column, with a configurable
     * material. The block carries enough load to read as "stressed" (≥ 30%) so the
     * task's weak-set fast path keeps it.
     *
     * @return the stressed node's position
     */
    private NodePos buildStressedColumn(MaterialSpec topSpec) {
        StructureGraph graph = structureManager.getOrCreateGraph(world);
        graph.addGroundBlock(new NodePos(0, 0, 0));
        // Heavy stack so the bottom load-bearing node sits well above 30% stress.
        graph.addBlock(new NodePos(0, 1, 0), topSpec, false);
        for (int y = 2; y <= 6; y++) {
            graph.addBlock(new NodePos(0, y, 0), new MaterialSpec(20.0, 1000.0), false);
        }
        new StressSolver(new PhysicsConfig()).solveAll(graph);
        return new NodePos(0, 1, 0);
    }

    private static MaterialSpec steel(double maxLoad) {
        return new MaterialSpec(2.0, maxLoad, 1.0, 1.0, ThermalClass.STEEL);
    }

    private static MaterialSpec masonry(double maxLoad) {
        return new MaterialSpec(2.0, maxLoad, 1.0, 1.0, ThermalClass.MASONRY);
    }

    private void setBlock(int x, int y, int z, Material m) {
        world.getBlockAt(x, y, z).setType(m);
    }

    // ── softening + collapse ─────────────────────────────────────────────

    @Test
    @DisplayName("A steel block beside lava is softened (lower factor) and collapses under a load it survives cool")
    void lavaWeakensAndCollapses() {
        // Steel column carrying ~100 load; cool capacity 200 → ~50% stress (stressed,
        // survives). Hot at ~1100°C steel keeps ~2% → it can no longer stand. Softening now
        // ramps in over a few scans, so run the scan until it has heated through.
        NodePos hot = buildStressedColumn(steel(200.0));
        StructureGraph graph = structureManager.getGraph(world);
        Node node = graph.getNode(hot);
        double coolStress = node.stressPercent();
        assertTrue(coolStress >= 0.3 && coolStress < 1.0, "cool block is stressed but standing: " + coolStress);

        setBlock(1, 1, 0, Material.LAVA); // lava right beside it

        TemperatureLoadTask task = newTask(config(true), new TemperatureProvider(20.0, 4, 3.0));
        for (int i = 0; i < 12 && node.stressPercent() <= 1.0; i++) {
            task.run(); // gradual softening: heat through over several scans
        }

        assertTrue(
                node.temperatureCapacityFactor() < 1.0,
                "lava-adjacent steel is softened: " + node.temperatureCapacityFactor());
        assertTrue(node.stressPercent() > 1.0, "softened, the block is now overloaded: " + node.stressPercent());
    }

    @Test
    @DisplayName("Softening ramps gradually: it keeps descending over successive scans, not a one-scan snap")
    void softeningRampsGraduallyNotInstantly() {
        // High-maxLoad steel so heat softens it but it never collapses; a little damage keeps
        // it in the weak set. With a gradual ramp the factor descends across scans rather than
        // snapping to its hot target in a single scan.
        int y0 = 70;
        StructureGraph graph = structureManager.getOrCreateGraph(world);
        graph.addGroundBlock(new NodePos(0, y0 - 1, 0));
        NodePos pos = new NodePos(0, y0, 0);
        graph.addBlock(pos, steel(100_000.0), false);
        graph.addBlock(new NodePos(0, y0 + 1, 0), new MaterialSpec(20.0, 1000.0), false);
        new StressSolver(new PhysicsConfig()).solveAll(graph);
        Node node = graph.getNode(pos);
        node.addDamage(0.4);
        structureManager.markDirty(world);
        setBlock(1, y0, 0, Material.LAVA);

        TemperatureLoadTask task = newTask(config(true), new TemperatureProvider(20.0, 4, 3.0));
        task.run();
        double f1 = node.temperatureCapacityFactor();
        task.run();
        double f2 = node.temperatureCapacityFactor();

        assertTrue(f1 < 1.0, "the block starts softening after the first scan: " + f1);
        assertTrue(
                f2 < f1, "and keeps descending toward the hot target — gradual, not instant: f1=" + f1 + " f2=" + f2);
    }

    @Test
    @DisplayName("A healthy (un-stressed) world does zero work and is never softened")
    void healthyWorldIsZeroWork() {
        // A short, lightly-loaded column: nothing reaches the 30% distress threshold,
        // so the weak-set fast path keeps it out entirely.
        StructureGraph graph = structureManager.getOrCreateGraph(world);
        graph.addGroundBlock(new NodePos(0, 70, 0));
        graph.addBlock(new NodePos(0, 71, 0), steel(100_000.0), false);
        new StressSolver(new PhysicsConfig()).solveAll(graph);
        structureManager.markDirty(world);
        setBlock(1, 71, 0, Material.LAVA); // hot, but nothing here is stressed

        TemperatureLoadTask task = newTask(config(true), new TemperatureProvider(20.0, 4, 3.0));
        task.run();

        assertEquals(
                1.0,
                graph.getNode(new NodePos(0, 71, 0)).temperatureCapacityFactor(),
                1e-9,
                "an un-stressed block is skipped, so it is never softened");
    }

    @Test
    @DisplayName("start() and stop() are safe to call; stop() clears tracked peak temperatures")
    void startStopLifecycle() {
        TemperatureLoadTask task = newTask(config(true), new TemperatureProvider(20.0, 4, 3.0));
        task.start(); // enabled → schedules
        task.stop(); // cancels + clears
        task.stop(); // idempotent — never-started branch must not throw
    }

    @Test
    @DisplayName("Flag OFF: the same lava-adjacent block is never softened (factor stays 1.0)")
    void flagOffIsInert() {
        NodePos hot = buildStressedColumn(steel(200.0));
        StructureGraph graph = structureManager.getGraph(world);
        Node node = graph.getNode(hot);
        setBlock(1, 1, 0, Material.LAVA);

        TemperatureLoadTask task = newTask(config(false), new TemperatureProvider(20.0, 4, 3.0));
        task.run();

        assertEquals(1.0, node.temperatureCapacityFactor(), 1e-9, "flag off → no softening");
        assertFalse(node.stressPercent() > 1.0, "flag off → no heat-driven overload");
    }

    // ── thermal mass (thick wall) ────────────────────────────────────────

    @Test
    @DisplayName("Thermal mass: a wall's exposed face is softened more than its shielded interior")
    void thickWallInteriorStaysStronger() {
        // Two stressed steel nodes in line away from lava: the near one (face) has no
        // solid block between it and the lava; the far one (interior) is shielded by
        // the near one. Same temperature source, different reach. Tough load so
        // neither collapses — we are comparing the softening factor, not survival.
        int y0 = 70;
        StructureGraph graph = structureManager.getOrCreateGraph(world);
        graph.addGroundBlock(new NodePos(0, y0 - 1, 0));
        graph.addGroundBlock(new NodePos(1, y0 - 1, 0));
        graph.addBlock(new NodePos(0, y0, 0), steel(100_000.0), false); // interior (far from lava)
        graph.addBlock(new NodePos(1, y0, 0), steel(100_000.0), false); // face (near lava)
        for (int x = 0; x <= 1; x++) {
            for (int yy = y0 + 1; yy <= y0 + 5; yy++) {
                graph.addBlock(new NodePos(x, yy, 0), new MaterialSpec(20.0, 1000.0), false);
            }
        }
        new StressSolver(new PhysicsConfig()).solveAll(graph);
        // Force both faces into the weak set without any collapse risk.
        graph.getNode(new NodePos(0, y0, 0)).addDamage(0.4);
        graph.getNode(new NodePos(1, y0, 0)).addDamage(0.4);
        structureManager.markDirty(world);

        Node faceNode = graph.getNode(new NodePos(1, y0, 0));
        Node interiorNode = graph.getNode(new NodePos(0, y0, 0));

        // Solid wall blocks so the provider sees the structure as real blocks.
        setBlock(0, y0, 0, Material.IRON_BLOCK);
        setBlock(1, y0, 0, Material.IRON_BLOCK);
        setBlock(2, y0, 0, Material.LAVA); // lava on the +x face

        TemperatureLoadTask task = newTask(config(true), new TemperatureProvider(20.0, 3, 4.0));
        task.run();

        double faceFactor = faceNode.temperatureCapacityFactor();
        double interiorFactor = interiorNode.temperatureCapacityFactor();
        assertTrue(faceFactor < 1.0, "the exposed face is softened: " + faceFactor);
        assertTrue(
                interiorFactor > faceFactor,
                "the shielded interior keeps more strength: interior " + interiorFactor + " > face " + faceFactor);
    }

    // ── thermal shock (heat then douse) ──────────────────────────────────

    @Test
    @DisplayName("Thermal shock: heating a brittle masonry block then cooling it cracks it (persistent damage)")
    void heatThenDouseCracks() {
        // Huge load so heating never collapses it — we are testing the shock crack,
        // not capacity. A little starting damage puts it in the weak set.
        int y0 = 70;
        StructureGraph graph = structureManager.getOrCreateGraph(world);
        graph.addGroundBlock(new NodePos(0, y0 - 1, 0));
        NodePos pos = new NodePos(0, y0, 0);
        graph.addBlock(pos, masonry(100_000.0), false);
        graph.addBlock(new NodePos(0, y0 + 1, 0), new MaterialSpec(20.0, 1000.0), false);
        new StressSolver(new PhysicsConfig()).solveAll(graph);
        Node node = graph.getNode(pos);
        node.addDamage(0.4); // cracked enough to be tracked, far from destroyed
        structureManager.markDirty(world);
        double damageAfterHeat = 0.4;

        TemperatureProvider provider = new TemperatureProvider(20.0, 4, 3.0);
        TemperatureLoadTask task = newTask(config(true), provider);

        // Heat: lava beside it, run a scan to record the peak temperature.
        setBlock(1, y0, 0, Material.LAVA);
        task.run();
        assertEquals(damageAfterHeat, node.damage(), 1e-9, "heating alone does not crack (softening, not shock)");

        // Douse: remove the lava, run again → sudden drop → shock crack adds damage.
        setBlock(1, y0, 0, Material.AIR);
        task.run();
        assertTrue(
                node.damage() > damageAfterHeat,
                "the sudden cooling cracked the masonry further: damage=" + node.damage());
    }

    @Test
    @DisplayName("peakTemp is pruned for a node that leaves the stressed set (no stale phantom peak)")
    void peakTempPrunedWhenNodeLeavesStressedSet() throws Exception {
        int y0 = 70;
        StructureGraph graph = structureManager.getOrCreateGraph(world);
        // A: tracked via damage with a huge maxLoad so heat softens but never collapses it.
        graph.addGroundBlock(new NodePos(0, y0 - 1, 0));
        NodePos a = new NodePos(0, y0, 0);
        graph.addBlock(a, masonry(100_000.0), false);
        // B: an always-stressed anchor so the stressed set is never empty when A leaves
        // (the per-pass cleanup runs only when there is still work this pass).
        graph.addGroundBlock(new NodePos(10, y0 - 1, 0));
        NodePos b = new NodePos(10, y0, 0);
        graph.addBlock(b, masonry(100_000.0), false);
        new StressSolver(new PhysicsConfig()).solveAll(graph);
        graph.getNode(a).addDamage(0.4);
        graph.getNode(b).addDamage(0.4);
        structureManager.markDirty(world);

        TemperatureLoadTask task = newTask(config(true), new TemperatureProvider(20.0, 4, 3.0));

        setBlock(1, y0, 0, Material.LAVA); // heat A only → it records a peak temperature
        task.run();
        assertNotNull(peakOf(task, a), "a heated, stressed node has a recorded peak");

        // Heal A so its distress drops below the threshold: it leaves the stressed set.
        graph.getNode(a).repair();
        structureManager.markDirty(world);
        task.run();
        assertNull(peakOf(task, a), "A's stale peak is pruned once it leaves the stressed set");
    }

    private Double peakOf(TemperatureLoadTask task, NodePos pos) throws Exception {
        Field f = TemperatureLoadTask.class.getDeclaredField("peakTemp");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, Map<NodePos, Double>> peaks = (Map<UUID, Map<NodePos, Double>>) f.get(task);
        Map<NodePos, Double> worldPeaks = peaks.get(world.getUID());
        return worldPeaks == null ? null : worldPeaks.get(pos);
    }

    // ── rule A: no double effect with active fire ────────────────────────

    @Test
    @DisplayName("Rule A: a block fire is actively damaging is NOT also softened by the temperature model")
    void activeFireSuppressesSoftening() {
        NodePos pos = buildStressedColumn(steel(200.0));
        StructureGraph graph = structureManager.getGraph(world);
        Node node = graph.getNode(pos);

        setBlock(1, 1, 0, Material.LAVA); // a heat source the provider would otherwise read

        // A fire task that reports this block as actively burning.
        Plugin plugin = MockBukkit.createMockPlugin("TempRuleAPlugin");
        CollapseEffects effects = new CollapseEffects(new EffectsConfig(), plugin);
        TaskTimings timings = new TaskTimings();
        CollapseGuard guard = allowAll();
        delayedCollapseManager =
                new DelayedCollapseManager(plugin, structureManager, new EffectsConfig(), effects, guard, timings);
        CascadeResumeManager resume = new CascadeResumeManager(
                plugin, structureManager, delayedCollapseManager, Logger.getLogger("TempRuleAResume"), 100, timings);
        FireActiveStub fireStub = new FireActiveStub();
        TemperatureLoadTask task = new TemperatureLoadTask(
                plugin,
                structureManager,
                config(true),
                delayedCollapseManager,
                resume,
                guard,
                new TemperatureProvider(20.0, 4, 3.0),
                fireStub,
                40,
                5,
                true,
                1000.0,
                timings);

        task.run();

        assertEquals(
                1.0,
                node.temperatureCapacityFactor(),
                1e-9,
                "fire owns this block's heat-weakening; temperature must not also soften it");
    }

    /** A FireScorchTask stand-in that always reports its target as actively burning. */
    private static final class FireActiveStub extends FireScorchTask {
        FireActiveStub() {
            super(
                    MockBukkit.createMockPlugin("FireStubPlugin"),
                    null,
                    null,
                    new PhysicsConfig(),
                    null,
                    null,
                    null,
                    20,
                    600,
                    false,
                    10.0,
                    new TaskTimings());
        }

        @Override
        public boolean isFireActiveAt(World w, NodePos pos) {
            return true;
        }
    }
}
