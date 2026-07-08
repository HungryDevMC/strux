package dev.gesp.structural.minecraft.validation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.api.CollapseReason;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.material.MaterialRegistry;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.NodePos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Geometry guard for the demo sandbox's two physics elements ({@code SandboxArena} in the demo-server
 * module). It rebuilds ONE pavilion cantilever bay and ONE demolition tower through the EXACT demo path
 * ({@link StructureManager#addBlockDirect} bottom-to-top, then a dry {@link StructureManager#onBlockPlaced}
 * solve) under the SHIPPED DEFAULT {@link PhysicsConfig} — no moment-multiplier / beam-moment-reduction
 * fudge — so the stress measured here is what the demo computes in-game.
 *
 * <p>These constants MIRROR the private constants in {@code SandboxArena}. Keep them in lockstep:
 *
 * <ul>
 *   <li>{@code PAV_PILLAR_HEIGHT} == {@code SandboxArena.PAV_PILLAR_HEIGHT} (5)
 *   <li>{@code PAV_ARM_LEN} == {@code SandboxArena.PAV_ARM_LEN} (5)
 *   <li>pavilion material == STONE_BRICKS (pillar and arm), tower material == STONE_BRICKS
 *   <li>{@code TOWER_H} == {@code SandboxArena.TOWER_H} (12)
 * </ul>
 *
 * <p>If a physics/material change moves the pavilion joint out of the marginal band this test fails here
 * (and {@code SandboxArena}'s startup self-check logs a WARNING in-game) — the two fire together.
 */
@DisplayName("Sandbox demo geometry lands in the right physics band under default config")
class SandboxPavilionGeometryTest {

    private static final int GROUND_Y = 64;

    // ── Mirror of SandboxArena Element 2 (pavilion) constants ──
    private static final int PAV_PILLAR_HEIGHT = 5;
    private static final int PAV_ARM_LEN = 5;
    private static final Material PAV_MATERIAL = Material.STONE_BRICKS;

    // ── Mirror of SandboxArena Element 1 (tower) constants ──
    private static final int TOWER_H = 12;

    private ServerMock server;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("sandbox_geometry_world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("pavilion cantilever bay: joint solves in the marginal band and the arm stands intact")
    void pavilionCantileverJointIsMarginalAndStands() {
        // One bay: a STONE_BRICKS pillar PAV_PILLAR_HEIGHT tall with a one-sided STONE_BRICKS arm of
        // PAV_ARM_LEN blocks jutting off its top course — the exact SandboxArena bay geometry.
        int beamY = GROUND_Y + PAV_PILLAR_HEIGHT; // pillar rises GROUND_Y+1 .. GROUND_Y+PAV_PILLAR_HEIGHT
        StructureManager strux = shippedDefaultManager();
        layFloor(-1, PAV_ARM_LEN + 2);
        DemoBuild b = new DemoBuild(world, strux);
        for (int dy = 0; dy < PAV_PILLAR_HEIGHT; dy++) {
            b.place(0, GROUND_Y + 1 + dy, PAV_MATERIAL);
        }
        for (int dx = 1; dx <= PAV_ARM_LEN; dx++) {
            b.place(dx, beamY, PAV_MATERIAL);
        }
        b.drainAll();
        b.solve();

        // The joint is the pillar-top block (x=0, beamY): it carries the whole arm's moment. This is the
        // block SandboxArena samples in its boot log + [0.5, 0.95] self-check.
        double joint = strux.getStress(world.getBlockAt(0, beamY, 0));

        // Whole arm survives the dry build (nothing shears off) — the demo needs a STANDING marginal
        // pavilion, tipped only by a visitor's TNT, never a build-time collapse.
        boolean armSurvived = true;
        for (int dx = 1; dx <= PAV_ARM_LEN; dx++) {
            if (strux.getStress(world.getBlockAt(dx, beamY, 0)) < 0) {
                armSurvived = false;
                break;
            }
        }

        assertTrue(armSurvived, "the cantilever arm must STAND intact on the dry build, not shear off");
        // Marginal band: the same [0.5, 0.95] window SandboxArena's startup self-check enforces. The
        // shipped tuning targets ~0.775 (dead centre of the 0.70–0.85 design band).
        assertTrue(
                joint >= 0.5 && joint <= 0.95,
                "pavilion cantilever joint must solve in the marginal band [0.5, 0.95], was " + joint);
        assertTrue(
                joint >= 0.70 && joint <= 0.85,
                "pavilion cantilever joint should land in the tight design band 0.70–0.85, was " + joint);
    }

    @Test
    @DisplayName("demolition tower: a grounded 12-tall spire base carries real axial load (~0.3, not ~0.0)")
    void towerBaseCarriesRealAxialLoad() {
        // A 1×1 STONE_BRICKS spire, grounded on bedrock — Element 1. Its base is NOT marginal, but it is
        // NOT ~0.0 either: it bears the whole column's weight. This documents the honest boot-log framing.
        StructureManager strux = shippedDefaultManager();
        layFloor(-1, 1);
        DemoBuild b = new DemoBuild(world, strux);
        for (int dy = 0; dy < TOWER_H; dy++) {
            b.place(0, GROUND_Y + 1 + dy, Material.STONE_BRICKS);
        }
        b.drainAll();
        b.solve();

        double base = strux.getStress(world.getBlockAt(0, GROUND_Y + 1, 0));
        assertTrue(base > 0.1, "a grounded 12-tall spire base must carry real axial load (> 0.1), was " + base);
        assertTrue(base < 0.6, "the spire base is loaded but not marginal (< 0.6), was " + base);
    }

    private StructureManager shippedDefaultManager() {
        return new StructureManager(new MaterialRegistry(), new PhysicsConfig());
    }

    /** Lay a bedrock floor along x = x0..x1 at z=0 so builds auto-ground exactly like on the surface. */
    private void layFloor(int x0, int x1) {
        for (int x = x0; x <= x1; x++) {
            world.getBlockAt(x, GROUND_Y, 0).setType(Material.BEDROCK, false);
        }
    }

    /**
     * Mirrors {@code SandboxArena}'s build+solve: queue blocks, place them bottom-to-top with
     * {@code addBlockDirect}, then solve from every placed block with a callback that drops collapsed
     * positions to air. All blocks live at z=0.
     */
    private static final class DemoBuild {
        private record Placement(int x, int y, Material material) {}

        private final World world;
        private final StructureManager strux;
        private final List<Placement> plan = new ArrayList<>();
        private final List<Block> placed = new ArrayList<>();

        DemoBuild(World world, StructureManager strux) {
            this.world = world;
            this.strux = strux;
        }

        void place(int x, int y, Material material) {
            plan.add(new Placement(x, y, material));
        }

        void drainAll() {
            plan.sort(Comparator.comparingInt(Placement::y));
            for (Placement p : plan) {
                Block block = world.getBlockAt(p.x(), p.y(), 0);
                block.setType(p.material(), false);
                strux.addBlockDirect(block);
                placed.add(block);
            }
            plan.clear();
        }

        void solve() {
            SolverCallback dropper = new SolverCallback() {
                @Override
                public void onStressUpdated(Map<NodePos, Double> stressMap) {}

                @Override
                public void onCascadeStep(CollapsedNode collapsed, int stepNumber, CollapseReason reason) {
                    StructureManager.toLocation(collapsed.pos(), world)
                            .getBlock()
                            .setType(Material.AIR, false);
                }

                @Override
                public void onCascadeComplete(List<CollapsedNode> allCollapsed) {}
            };
            for (Block block : placed) {
                if (!block.getType().isAir()) {
                    strux.onBlockPlaced(block, dropper);
                }
            }
        }
    }
}
