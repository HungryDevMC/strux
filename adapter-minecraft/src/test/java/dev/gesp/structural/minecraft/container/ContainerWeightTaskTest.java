package dev.gesp.structural.minecraft.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.config.EffectsConfig;
import dev.gesp.structural.minecraft.container.ContainerWeightTask.ContainerWeightConfig;
import dev.gesp.structural.minecraft.hook.WarZoneService;
import dev.gesp.structural.minecraft.listener.CascadeResumeManager;
import dev.gesp.structural.minecraft.listener.DelayedCollapseManager;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.material.MaterialRegistry;
import dev.gesp.structural.minecraft.perf.PerfTracker;
import dev.gesp.structural.minecraft.perf.TaskTimings;
import dev.gesp.structural.minecraft.protect.ChunkVerdict;
import dev.gesp.structural.minecraft.protect.CollapseGuard;
import dev.gesp.structural.minecraft.protect.NoopCollapseLogger;
import dev.gesp.structural.minecraft.protect.ProtectionService;
import dev.gesp.structural.minecraft.visual.CollapseEffects;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Heavy storage containers add transient load to the block they rest on.
 *
 * <pre>
 *   A full chest is heavy. The block under it carries that weight TEMPORARILY,
 *   exactly like an entity standing on a floor. If the floor is already weak
 *   (stressed or cracked) and the container is full enough, the floor gives way.
 *
 *   The scan is gated behind the SAME revision-cached weak set EntityWeightTask
 *   uses: with no weak blocks the scan is skipped entirely, so a healthy world
 *   does zero container work — it never even looks at the block above a node.
 * </pre>
 */
@DisplayName("ContainerWeightTask: heavy container adds load to its weak support block")
class ContainerWeightTaskTest {

    private ServerMock server;
    private WorldMock world;
    private StructureManager structureManager;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("container_weight_world");
        structureManager = new StructureManager(new MaterialRegistry(), new PhysicsConfig());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /**
     * A task whose collapse trigger is RECORDED instead of run, so a unit test can
     * assert the tip decision without a scheduler. The block lookups still hit the
     * real mock world so getState()/Container behaviour is exercised end to end. The
     * shared {@link #timings} and {@link #dcm} let tests assert the work-count the
     * pass records and (for the real-path task below) the collapse it schedules.
     */
    private final class TestTask extends ContainerWeightTask {
        final List<NodePos> tipped = new ArrayList<>();
        final TaskTimings timings;
        final DelayedCollapseManager dcm;

        TestTask(ContainerWeightConfig config, TaskTimings timings, DelayedCollapseManager dcm) {
            super(
                    MockBukkit.createMockPlugin("ContainerWeightTestPlugin"),
                    structureManager,
                    new PhysicsConfig(),
                    dcm,
                    resumeManager(dcm),
                    allowAllGuard(),
                    config,
                    timings);
            this.timings = timings;
            this.dcm = dcm;
        }

        TestTask(ContainerWeightConfig config) {
            this(config, new TaskTimings(), delayedCollapseManager());
        }

        @Override
        protected void triggerCollapse(World w, StructureGraph graph, NodePos pos) {
            tipped.add(pos);
        }
    }

    /**
     * A real {@link ContainerWeightTask} (no overrides) wired to a DCM the test can
     * inspect — so the production {@code triggerCollapse} (cascade → schedule) runs.
     */
    private ContainerWeightTask realTask(ContainerWeightConfig config, DelayedCollapseManager dcm) {
        return new ContainerWeightTask(
                MockBukkit.createMockPlugin("ContainerWeightRealPlugin"),
                structureManager,
                new PhysicsConfig(),
                dcm,
                resumeManager(dcm),
                allowAllGuard(),
                config,
                new TaskTimings());
    }

    private DelayedCollapseManager delayedCollapseManager() {
        Plugin plugin = MockBukkit.createMockPlugin("ContainerWeightDcmPlugin");
        CollapseGuard guard = allowAllGuard();
        CollapseEffects effects = new CollapseEffects(new EffectsConfig(), plugin);
        return new DelayedCollapseManager(
                plugin, structureManager, new EffectsConfig(), effects, guard, new TaskTimings());
    }

    private CascadeResumeManager resumeManager(DelayedCollapseManager dcm) {
        return new CascadeResumeManager(
                MockBukkit.createMockPlugin("ContainerWeightResumePlugin"),
                structureManager,
                dcm,
                Logger.getLogger("ContainerWeightResume"),
                100,
                new TaskTimings());
    }

    private CollapseGuard allowAllGuard() {
        ProtectionService allow = new ProtectionService() {
            @Override
            public boolean physicsAllowed(Location loc) {
                return true;
            }

            @Override
            public ChunkVerdict chunkVerdict(World w, int cx, int cz) {
                return ChunkVerdict.ALL_ALLOWED;
            }

            @Override
            public String describe() {
                return "allow-all";
            }
        };
        return new CollapseGuard(allow, WarZoneService.ALLOW_ALL, NoopCollapseLogger.INSTANCE);
    }

    /** Default config: base-mass 1, content-weight 8, production thresholds. */
    private ContainerWeightConfig defaultConfig() {
        return new ContainerWeightConfig(true, 20, 1.0, 8.0, 0.7, 0.5);
    }

    /**
     * Add a floating support node at {@code pos} backed by a real solid block.
     * Returns the node.
     */
    private Node addFloatingNode(NodePos pos, double maxLoad) {
        StructureGraph graph = structureManager.getOrCreateGraph(world);
        graph.addNode(pos, new MaterialSpec(1.0, maxLoad), false);
        // A real server keeps a chunk holding tracked blocks loaded; MockBukkit does not
        // auto-load it, so load it here — otherwise the task's new "skip unloaded chunks"
        // gate would skip every weak node in these tests.
        world.loadChunk(pos.x() >> 4, pos.z() >> 4);
        world.getBlockAt(pos.x(), pos.y(), pos.z()).setType(Material.STONE);
        return graph.getNode(pos);
    }

    private void makeStressFraction(Node node, double stressFraction) {
        node.setVerticalStress(stressFraction * node.effectiveMaxLoad());
    }

    /**
     * Place a chest on the block directly above {@code support}, and fill {@code fill}
     * of its slots with a stack so the fill fraction is fill/size.
     */
    private void placeChest(NodePos support, int fill) {
        Block above = world.getBlockAt(support.x(), support.y() + 1, support.z());
        above.setType(Material.CHEST);
        Container chest = (Container) above.getState();
        Inventory inv = chest.getInventory();
        for (int i = 0; i < fill && i < inv.getSize(); i++) {
            inv.setItem(i, new ItemStack(Material.STONE, 1));
        }
    }

    // ── A full chest on a near-capacity (weak) block triggers collapse ─────────

    @Test
    @DisplayName("a FULL chest on a weak block tips it → collapse")
    void fullChestOnWeakBlockTips() {
        // Weak node: damaged past threshold, tiny capacity. A full chest
        // (base 1.0 + 1.0*8.0 = 9.0) easily exceeds effectiveMaxLoad.
        Node weak = addFloatingNode(new NodePos(1, 80, 1), 2.0);
        weak.addDamage(0.6); // damage 0.6 >= 0.5 → weak; effectiveMaxLoad = 0.8
        structureManager.markDirty(world);

        placeChest(new NodePos(1, 80, 1), 27); // every slot filled → fillFraction 1.0
        assertTrue(weak.stressValue() + 9.0 > weak.effectiveMaxLoad(), "a full chest overloads the weak block");

        TestTask task = new TestTask(defaultConfig());
        task.run();

        assertEquals(1, task.lastPassContainerWork(), "the one weak support node is examined");
        assertEquals(List.of(new NodePos(1, 80, 1)), task.tipped, "the full chest tipped its weak support block");
    }

    // ── An EMPTY chest does NOT trigger collapse ───────────────────────────────

    @Test
    @DisplayName("an EMPTY chest on a weak block does not tip it (fill fraction 0)")
    void emptyChestDoesNotTip() {
        // Weak node with enough headroom that only the heavy CONTENTS could tip it.
        // effectiveMaxLoad = 4.0; base-mass 1.0 alone (empty) does not exceed it,
        // but a full chest (9.0) would.
        Node weak = addFloatingNode(new NodePos(2, 80, 2), 10.0);
        weak.addDamage(0.6); // weak by damage; effectiveMaxLoad = 10 * 0.4 = 4.0
        makeStressFraction(weak, 0.0);
        structureManager.markDirty(world);
        assertTrue(weak.damage() >= 0.5, "node is weak by damage threshold");

        placeChest(new NodePos(2, 80, 2), 0); // empty → load = base-mass 1.0 only
        assertFalse(weak.stressValue() + 1.0 > weak.effectiveMaxLoad(), "an empty chest cannot tip 4.0 capacity");

        TestTask task = new TestTask(defaultConfig());
        task.run();

        assertEquals(1, task.lastPassContainerWork(), "weak set non-empty → the support node is examined");
        assertTrue(task.tipped.isEmpty(), "an empty chest on a weak block does not trigger collapse");
    }

    // ── A full chest on a HEALTHY block does NOT trigger collapse ──────────────

    @Test
    @DisplayName("a FULL chest on a HEALTHY block does not tip it, and the scan is skipped")
    void fullChestOnHealthyBlockIsFree() {
        // Sound node well below both thresholds — never in the weak set.
        Node sound = addFloatingNode(new NodePos(3, 80, 3), 100.0);
        makeStressFraction(sound, 0.1);
        structureManager.markDirty(world);
        assertFalse(
                sound.stressPercent() >= 0.7 || sound.damage() >= 0.5,
                "precondition: the support node is sound (below both thresholds)");

        placeChest(new NodePos(3, 80, 3), 27); // full chest, but on a healthy floor

        TestTask task = new TestTask(defaultConfig());
        task.run();

        assertEquals(0, task.lastPassContainerWork(), "no weak blocks → zero container work (fast path)");
        assertTrue(task.tipped.isEmpty(), "a full chest on a healthy block tips nothing");
    }

    // ── A weak block with a NON-container block above is ignored ───────────────

    @Test
    @DisplayName("a weak block with a non-container block above tips nothing")
    void weakBlockWithoutContainerAboveDoesNotTip() {
        Node weak = addFloatingNode(new NodePos(4, 80, 4), 2.0);
        weak.addDamage(0.6);
        structureManager.markDirty(world);

        // A plain stone block above — not a container.
        world.getBlockAt(4, 81, 4).setType(Material.STONE);

        TestTask task = new TestTask(defaultConfig());
        task.run();

        assertEquals(1, task.lastPassContainerWork(), "the weak support node is examined");
        assertTrue(task.tipped.isEmpty(), "no container above → nothing tips");
    }

    // ── A barrel (another Container) behaves identically to a chest ────────────

    @Test
    @DisplayName("a FULL barrel on a weak block tips it too")
    void fullBarrelOnWeakBlockTips() {
        Node weak = addFloatingNode(new NodePos(5, 80, 5), 2.0);
        weak.addDamage(0.6);
        structureManager.markDirty(world);

        Block above = world.getBlockAt(5, 81, 5);
        above.setType(Material.BARREL);
        Container barrel = (Container) above.getState();
        Inventory inv = barrel.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, new ItemStack(Material.STONE, 1));
        }

        TestTask task = new TestTask(defaultConfig());
        task.run();

        assertEquals(List.of(new NodePos(5, 80, 5)), task.tipped, "a full barrel tips its weak support block");
    }

    // ── Disabled config does no work at all ────────────────────────────────────

    @Test
    @DisplayName("disabled config: the scan does nothing even with a full chest on a weak block")
    void disabledDoesNothing() {
        Node weak = addFloatingNode(new NodePos(6, 80, 6), 2.0);
        weak.addDamage(0.6);
        structureManager.markDirty(world);
        placeChest(new NodePos(6, 80, 6), 27);

        TestTask task = new TestTask(new ContainerWeightConfig(false, 20, 1.0, 8.0, 0.7, 0.5));
        task.run();

        assertEquals(0, task.lastPassContainerWork(), "disabled → zero work");
        assertTrue(task.tipped.isEmpty(), "disabled → nothing tips");
    }

    // ── A partially-full chest scales its load by fill fraction ────────────────

    @Test
    @DisplayName("a HALF-full chest adds less than full and may not tip a block a full chest would")
    void partialFillScalesLoad() {
        // effectiveMaxLoad tuned so a full chest (9.0) tips but a near-empty one does not.
        // 27-slot single chest: 1 filled slot → fillFraction = 1/27, load ≈ 1.30.
        Node weak = addFloatingNode(new NodePos(7, 80, 7), 5.0);
        weak.addDamage(0.6); // effectiveMaxLoad = 5.0 * 0.4 = 2.0
        makeStressFraction(weak, 0.0);
        structureManager.markDirty(world);

        placeChest(new NodePos(7, 80, 7), 1); // barely filled
        double nearlyEmptyLoad = 1.0 + (1.0 / 27.0) * 8.0;
        assertFalse(
                weak.stressValue() + nearlyEmptyLoad > weak.effectiveMaxLoad(), "a near-empty chest stays under 2.0");

        TestTask task = new TestTask(defaultConfig());
        task.run();
        assertTrue(task.tipped.isEmpty(), "a near-empty chest does not tip a block a full one would");
    }

    // ── Exact-capacity boundary: equal does NOT tip (strict >) ─────────────────

    @Test
    @DisplayName("a load EXACTLY equal to capacity does not tip (collapse needs strictly greater)")
    void exactlyAtCapacityDoesNotTip() {
        // effectiveMaxLoad chosen so a full chest's load equals it exactly:
        // full load = base 1.0 + 1.0*8.0 = 9.0. effectiveMaxLoad = maxLoad*(1-damage).
        // With damage 0.5, maxLoad 18.0 → effectiveMaxLoad = 9.0, and stress 0.
        Node weak = addFloatingNode(new NodePos(8, 80, 8), 18.0);
        weak.addDamage(0.5); // exactly at the damage threshold → weak; effectiveMaxLoad = 9.0
        makeStressFraction(weak, 0.0);
        structureManager.markDirty(world);
        placeChest(new NodePos(8, 80, 8), 27);
        assertEquals(9.0, weak.effectiveMaxLoad(), 1e-9, "capacity is exactly the full-chest load");
        assertEquals(0.0, weak.stressValue(), 1e-9, "no baseline stress");

        TestTask task = new TestTask(defaultConfig());
        task.run();

        assertEquals(1, task.lastPassContainerWork(), "the weak support node is examined");
        assertTrue(task.tipped.isEmpty(), "load == capacity does not tip; only strictly-greater does");
    }

    @Test
    @DisplayName("one extra slot pushes the same block past capacity → tips")
    void justOverCapacityTips() {
        // Same setup as the boundary test, but capacity a hair below the full load
        // so the chest tips — proves the boundary is the only thing holding it.
        Node weak = addFloatingNode(new NodePos(9, 80, 9), 17.0);
        weak.addDamage(0.5); // effectiveMaxLoad = 8.5 < full load 9.0
        makeStressFraction(weak, 0.0);
        structureManager.markDirty(world);
        placeChest(new NodePos(9, 80, 9), 27);

        TestTask task = new TestTask(defaultConfig());
        task.run();
        assertEquals(List.of(new NodePos(9, 80, 9)), task.tipped, "load just over capacity tips");
    }

    // ── Fill fraction scales the load monotonically (kills the fill math) ──────

    @Test
    @DisplayName("a fuller chest tips a block an emptier chest leaves standing")
    void fullerChestTipsWhereEmptierDoesNot() {
        // effectiveMaxLoad = 10*0.5 = 5.0. A 1/27-full chest (≈1.30) is safe; a
        // 27/27-full chest (9.0) tips. Proves load grows with filled slots.
        double cap = 5.0;

        Node light = addFloatingNode(new NodePos(20, 80, 20), 10.0);
        light.addDamage(0.5);
        makeStressFraction(light, 0.0);
        Node heavy = addFloatingNode(new NodePos(21, 80, 21), 10.0);
        heavy.addDamage(0.5);
        makeStressFraction(heavy, 0.0);
        structureManager.markDirty(world);
        assertEquals(cap, light.effectiveMaxLoad(), 1e-9, "both blocks share the same capacity");

        placeChest(new NodePos(20, 80, 20), 1); // ≈1.30 load — under cap
        placeChest(new NodePos(21, 80, 21), 27); // 9.0 load — over cap

        TestTask task = new TestTask(defaultConfig());
        task.run();

        assertEquals(
                List.of(new NodePos(21, 80, 21)),
                task.tipped,
                "only the FULL chest tips; the nearly-empty one is safe on identical capacity");
    }

    // ── The pass records its work count to TaskTimings ─────────────────────────

    @Test
    @DisplayName("the pass records the weak-node count it examined to TaskTimings")
    void passRecordsWorkCount() {
        // Three weak nodes (with containers) → the pass examines all three.
        for (int i = 0; i < 3; i++) {
            Node weak = addFloatingNode(new NodePos(30 + i, 80, 30), 2.0);
            weak.addDamage(0.6);
            placeChest(new NodePos(30 + i, 80, 30), 27);
        }
        structureManager.markDirty(world);

        TestTask task = new TestTask(defaultConfig());
        task.run();

        assertEquals(3, task.lastPassContainerWork(), "all three weak support nodes examined");
        PerfTracker tracker = task.timings.snapshot().get(TaskTimings.CONTAINER_WEIGHT);
        assertNotNull(tracker, "the pass recorded a CONTAINER_WEIGHT timing sample");
        assertEquals(1, tracker.sampleCount(), "exactly one pass recorded");
        assertEquals(3.0, tracker.averageWork(), 1e-9, "the recorded work count is the weak-node count");
    }

    @Test
    @DisplayName("a healthy world records a zero-work pass to TaskTimings")
    void healthyWorldRecordsZeroWork() {
        Node sound = addFloatingNode(new NodePos(40, 80, 40), 100.0);
        makeStressFraction(sound, 0.1);
        structureManager.markDirty(world);
        placeChest(new NodePos(40, 80, 40), 27);

        TestTask task = new TestTask(defaultConfig());
        task.run();

        PerfTracker tracker = task.timings.snapshot().get(TaskTimings.CONTAINER_WEIGHT);
        assertNotNull(tracker, "even a skipped pass records a (zero-work) timing sample");
        assertEquals(0.0, tracker.averageWork(), 1e-9, "a healthy world records zero container work");
    }

    // ── The REAL collapse path schedules the support block ─────────────────────

    @Test
    @DisplayName("the real trigger cascades and schedules the orphaned dependent for collapse")
    void realTriggerSchedulesCollapse() {
        StructureGraph graph = structureManager.getOrCreateGraph(world);
        // Ground G — the only anchor.
        NodePos ground = new NodePos(50, 79, 50);
        graph.addNode(ground, new MaterialSpec(1.0, 100.0), true);
        world.getBlockAt(50, 79, 50).setType(Material.STONE);
        // Weak support W on the ground, with a chest above it at y+1.
        NodePos weakPos = new NodePos(50, 80, 50);
        Node weak = addFloatingNode(weakPos, 2.0);
        weak.addDamage(0.6);
        graph.connect(ground, weakPos);
        // Outboard O hangs off W only (no path to ground except through W).
        NodePos outboard = new NodePos(51, 80, 50);
        graph.addNode(outboard, new MaterialSpec(1.0, 100.0), false);
        world.getBlockAt(51, 80, 50).setType(Material.STONE);
        graph.connect(weakPos, outboard);
        structureManager.markDirty(world);
        placeChest(weakPos, 27); // full chest tips W

        DelayedCollapseManager dcm = delayedCollapseManager();
        ContainerWeightTask task = realTask(defaultConfig(), dcm);
        task.run();

        // W is removed by the cascade; O loses its only support and is scheduled.
        assertTrue(
                dcm.isPendingCollapse(world, outboard),
                "the real cascade scheduled the orphaned dependent for collapse");
    }

    @Test
    @DisplayName("the real trigger schedules nothing when no container overloads a weak block")
    void realTriggerSchedulesNothingWhenSafe() {
        Node weak = addFloatingNode(new NodePos(51, 80, 51), 100.0);
        weak.addDamage(0.6); // weak, but huge capacity
        makeStressFraction(weak, 0.0);
        structureManager.markDirty(world);
        placeChest(new NodePos(51, 80, 51), 0); // empty chest, cannot overload

        DelayedCollapseManager dcm = delayedCollapseManager();
        ContainerWeightTask task = realTask(defaultConfig(), dcm);
        task.run();

        assertFalse(
                dcm.isPendingCollapse(world, new NodePos(51, 80, 51)),
                "nothing scheduled: the empty chest never tipped the block");
    }

    // ── The defaults() factory has the documented values ───────────────────────

    @Test
    @DisplayName("defaults() returns the documented container-weight settings")
    void defaultsAreAsDocumented() {
        ContainerWeightConfig d = ContainerWeightConfig.defaults();
        // Container weight is disabled by default until fully integrated
        assertFalse(d.enabled(), "disabled by default");
        assertEquals(20, d.scanIntervalTicks(), "scan once a second by default");
        assertEquals(1.0, d.baseMass(), 1e-9, "base mass default");
        assertEquals(8.0, d.contentWeight(), 1e-9, "content weight default");
        assertEquals(0.7, d.stressThreshold(), 1e-9, "stress threshold default");
        assertEquals(0.5, d.damageThreshold(), 1e-9, "damage threshold default");
    }
}
