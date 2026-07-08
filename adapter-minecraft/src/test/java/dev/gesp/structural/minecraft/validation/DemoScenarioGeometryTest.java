package dev.gesp.structural.minecraft.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Geometry guard for the four StruxDemo physics scenarios that misbehaved in-game.
 *
 * <p>Each test rebuilds its scenario through the EXACT path the demo server uses — every block
 * registered bottom-to-top with {@link StructureManager#addBlockDirect}, then the overload solver
 * run from every placed block via {@link StructureManager#onBlockPlaced} with a callback that drops
 * collapsed blocks to air — under a {@link PhysicsConfig} configured exactly like the live demo
 * server's {@code docker/data/plugins/StructuralIntegrity/config.yml} ({@code moment-multiplier:
 * 2.5}, {@code beam-moment-reduction: 0.0}, default materials). So the dry-solve stress measured
 * here is identical to what the demo computes in-game, and the constants asserted below are the
 * constants the scenario classes ship.
 *
 * <pre>
 *   WHAT WAS WRONG, AND THE FIX
 *
 *   The three span scenarios (weather/temperature/mobs) hung a flat roof ONE block ABOVE two
 *   posts, then checked the geometric gap centre. With moment x2.5 such a roof has no "marginal
 *   middle" band: the unsupported span goes straight from trivially light (~3%) to a full
 *   collapse on the build (getStress = -1, the in-game symptom) as soon as the gap exceeds ~4.
 *
 *   The load-bearing block in a moment model is not the gap centre — it is the span block sitting
 *   DIRECTLY ON a post (the cantilever-arm anchor), which carries the whole arm's moment. So the
 *   fix is: rest the span ON the posts (a beam, both ends supported), tune a short overhang past
 *   the posts to set the anchor stress, and check the ANCHOR block (over a post), not the centre.
 *   That block lands in a clean, gradeable band, survives the build, and the effect tips it.
 *
 *   The cantilever scenario already snaps fine at any iron length >= 4 under moment x2.5; the
 *   length is shortened to a tidy value here.
 * </pre>
 */
@DisplayName("Demo scenario geometry lands in the right physics band")
class DemoScenarioGeometryTest {

    /** Where the arena floor sits; matches a normal overworld surface. */
    private static final int GROUND_Y = 64;

    private ServerMock server;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("geometry_world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  CANTILEVER — snaps at the root on the dry build-solve.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("cantilever: gold arm shears off the pillar on the DEFAULT-config build solve (no fudge)")
    void cantileverSnapsAtRoot() {
        // Mirrors CantileverScenario exactly: a single 1-wide STONE_BRICKS pillar (PILLAR_HEIGHT=3)
        // with a GOLD arm of BEAM_LEN=8 jutting from the pillar top over thin air. Under the SHIPPED
        // DEFAULT moment-multiplier (1.0) the arm's moment clears the joint's cap (hand-calc ratio
        // ≈ 3.6, see CantileverScenario javadoc) — the proof a geometry-driven stand needs NO config
        // fudge. This is the template the weakening stands should follow under default physics.
        int pillarHeight = 3;
        int beamLen = 8;
        int beamY = GROUND_Y + 1 + (pillarHeight - 1);

        StructureManager strux = shippedDefaultManager();
        layFloor(-1, beamLen + 2, -1, 1);
        DemoBuild b = new DemoBuild(world, strux);
        for (int dy = 0; dy < pillarHeight; dy++) {
            b.place(0, GROUND_Y + 1 + dy, 0, Material.STONE_BRICKS);
        }
        for (int x = 1; x <= beamLen; x++) {
            b.place(x, beamY, 0, Material.GOLD_BLOCK);
        }
        b.drainAll();
        b.solve();

        // CantileverScenario.verify: the beam tip falls, the pillar still stands.
        assertTrue(isAir(beamLen, beamY, 0), "the gold arm tip must fall when the joint shears");
        assertFalse(isAir(0, GROUND_Y + 1, 0), "the pillar base must hold");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  WEATHER — iron span anchor at ~0.90 so the 12% thunder cap-cut tips it.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("weather: iron span STANDS intact under default physics (no fudge-collapse)")
    void weatherSpanStandsIntactUnderDefault() {
        // WeatherScenario as shipped: gap 4, overhang 1, iron span on stone-brick posts.
        SpanResult r = buildSpanOnPosts(4, 1, 5, Material.IRON_BLOCK);
        double anchor = r.anchorStress();

        // INVARIANT (DEFAULT CONFIG): the roof stands intact and the anchor is tracked & < 100% on
        // the dry build — it never relies on fudged tuning to collapse before its trigger.
        assertTrue(r.allSurvived(), "the iron roof must STAND intact under default physics, not collapse on build");
        assertTrue(anchor > 0.0 && anchor < 1.0, "anchor must be tracked and < 100% on the dry solve, was " + anchor);

        // TIPPABILITY (open redesign, demo task #6): for the 12% thunder cap-cut to tip it the anchor
        // must sit ~>=0.89. The two-post span CANNOT reach that under default physics — the overhang
        // sweep (overhangSweepUnderDefaultConfig) shows it jumps from ~0.22 straight to collapse, with
        // no marginal middle. Reaching the band needs a true-cantilever redesign (see CantileverScenario).
    }

    // ─────────────────────────────────────────────────────────────────────
    //  TEMPERATURE — stone span anchor at ~0.63 so ~halving its cap tips it.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("temperature: stone span STANDS intact under default physics (no fudge-collapse)")
    void temperatureSpanStandsIntactUnderDefault() {
        // TemperatureScenario as shipped: gap 4, no overhang, stone-brick span on stone-brick posts.
        SpanResult r = buildSpanOnPosts(4, 0, 4, Material.STONE_BRICKS);
        double anchor = r.anchorStress();

        // INVARIANT (DEFAULT CONFIG): stands intact cold, anchor tracked & < 100%.
        assertTrue(r.allSurvived(), "the stone roof must STAND cold intact under default physics");
        assertTrue(anchor > 0.0 && anchor < 1.0, "anchor must be tracked and < 100% on the dry solve, was " + anchor);

        // TIPPABILITY (open redesign, demo task #6): heat needs the anchor >=0.30 (distress gate) and
        // near enough to cap that ~halving masonry capacity tips it. Under default physics the stone
        // span sits ~0.06 (sweep) — far too stable. Needs a true-cantilever redesign.
    }

    // ─────────────────────────────────────────────────────────────────────
    //  MOBS — gold span anchor at ~0.975 so one cow (+3.0) tips it.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("mobs/container: span STANDS intact under default physics (no fudge-collapse)")
    void mobsSpanStandsIntactUnderDefault() {
        // Mobs/Container as shipped: gap 2, no overhang, stone span on stone-brick posts (3-deep
        // in-game; the single-row proxy here is enough for the stands-intact invariant).
        SpanResult r = buildSpanOnPosts(2, 0, 5, Material.STONE_BRICKS);
        double anchor = r.anchorStress();

        // INVARIANT (DEFAULT CONFIG): stands intact empty, anchor tracked & < 100%.
        assertTrue(r.allSurvived(), "the span must STAND empty intact under default physics");
        assertTrue(anchor > 0.0 && anchor < 1.0, "anchor must be tracked and < 100% on the dry solve, was " + anchor);

        // TIPPABILITY (open redesign, demo task #6): the standing-weight / container scans only load
        // nodes already weak (stressPercent >= 0.70), and a cow (+3.0) / full chest (+~9) must then
        // push it over. Under default physics the span sits ~0.05-0.30 — never reaches the 0.70 weak
        // gate without collapsing first. Needs a true-cantilever (or vertical-preload) redesign.
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Live-config replica + demo build/solve path
    // ─────────────────────────────────────────────────────────────────────

    /**
     * A StructureManager on the SHIPPED DEFAULT config — the demo MUST work under this, never under
     * tuned physics (see demo-server README "Physics policy — DEFAULT CONFIG ONLY"). No
     * moment-multiplier / beam-moment-reduction overrides: every marginal structure's instability
     * must come from GEOMETRY, not from fudging the config. {@code new PhysicsConfig()} carries the
     * shipped defaults (moment-multiplier 1.0, beam-moment-reduction 1.0).
     */
    private StructureManager shippedDefaultManager() {
        return new StructureManager(new MaterialRegistry(), new PhysicsConfig());
    }

    private record SpanResult(double anchorStress, boolean allSurvived) {}

    /**
     * Build a span resting ON two stone-brick posts and solve it the demo way, returning the
     * loaded anchor's dry-solve stress and whether the WHOLE span survived (nothing fell empty).
     *
     * <p>Geometry (matches the span scenarios): posts at x=0 and x=gap, {@code postHeight} tall
     * with the span layer as the top course; the span runs x = -overhang .. gap+overhang so a short
     * overhang past each post dials the anchor moment. The anchor checked is x=0 — the span block
     * directly over post A, the cantilever-arm root that carries the span's moment.
     */
    private SpanResult buildSpanOnPosts(int gap, int overhang, int postHeight, Material spanMaterial) {
        return buildSpanOnPosts(gap, overhang, postHeight, spanMaterial, 0);
    }

    private SpanResult buildSpanOnPosts(int gap, int overhang, int postHeight, Material spanMaterial, int baseX) {
        StructureManager strux = shippedDefaultManager();
        int roofY = GROUND_Y + postHeight; // span endpoints sit ON the post tops
        int loX = baseX - overhang;
        int hiX = baseX + gap + overhang;
        layFloor(loX - 1, hiX + 1, -1, 1);

        DemoBuild b = new DemoBuild(world, strux);
        // Post columns rise GROUND_Y+1 .. GROUND_Y+postHeight-1; the span at GROUND_Y+postHeight
        // rests on both columns.
        for (int dy = 0; dy < postHeight - 1; dy++) {
            b.place(baseX, GROUND_Y + 1 + dy, 0, Material.STONE_BRICKS);
            b.place(baseX + gap, GROUND_Y + 1 + dy, 0, Material.STONE_BRICKS);
        }
        for (int x = loX; x <= hiX; x++) {
            b.place(x, roofY, 0, spanMaterial);
        }
        b.drainAll();
        b.solve();

        double anchor = strux.getStress(world.getBlockAt(baseX, roofY, 0));
        boolean allSurvived = true;
        for (int x = loX; x <= hiX; x++) {
            if (strux.getStress(world.getBlockAt(x, roofY, 0)) < 0) {
                allSurvived = false;
                break;
            }
        }
        return new SpanResult(anchor, allSurvived);
    }

    /**
     * Geometry-mapping sweep (DEFAULT config): for each span scenario's material+gap, print the
     * loaded-anchor dry-solve stress at a range of overhang lengths so the marginal band can be read
     * off in one run. NOT an assertion — a tuning aid for redesigning the demo geometry to be
     * unstable under shipped-default physics (no config fudging). Each build is offset on +x so the
     * shared mock world's blocks never overlap.
     */
    @Test
    @DisplayName("SWEEP: overhang → anchor stress under default config (tuning aid)")
    void overhangSweepUnderDefaultConfig() {
        Object[][] specs = {
            {"weather  (iron, gap4, h5)", Material.IRON_BLOCK, 4, 5},
            {"temp     (stone,gap4, h4)", Material.STONE_BRICKS, 4, 4},
            {"mobs/cont(gold, gap3, h5)", Material.GOLD_BLOCK, 3, 5},
            {"mobs/cont(stone,gap3, h5)", Material.STONE_BRICKS, 3, 5},
        };
        StringBuilder out = new StringBuilder("\n==== OVERHANG SWEEP (default config) ====\n");
        int baseX = 0;
        for (Object[] s : specs) {
            String name = (String) s[0];
            Material mat = (Material) s[1];
            int gap = (Integer) s[2];
            int ph = (Integer) s[3];
            for (int oh = 0; oh <= 9; oh++) {
                SpanResult r = buildSpanOnPosts(gap, oh, ph, mat, baseX);
                baseX += 60; // keep each build clear of the previous one in the shared world
                out.append(String.format(
                        "%s overhang=%d  anchor=%.3f  survived=%b%n", name, oh, r.anchorStress(), r.allSurvived()));
            }
            out.append('\n');
        }
        System.out.println(out);
    }

    /**
     * TRUE-CANTILEVER arm sweep (DEFAULT config): a single 1-wide pillar with an arm of growing
     * length, printing the arm-ROOT stress (the block that overloads) per length and material. This
     * is the marginal design the weakening stands need under shipped-default physics (the span design
     * has no marginal band — see the overhang sweep). Read off the longest length that still survives
     * with root ~0.85-0.95 → that is the "stands but one nudge tips it" geometry.
     */
    @Test
    @DisplayName("SWEEP: true-cantilever arm length → root stress under default config (tuning aid)")
    void cantileverArmSweepUnderDefaultConfig() {
        Object[][] specs = {
            {"iron  arm / stone pillar h2", Material.IRON_BLOCK, Material.STONE_BRICKS, 2},
            {"iron  arm / stone pillar h3", Material.IRON_BLOCK, Material.STONE_BRICKS, 3},
            {"iron  arm / stone pillar h4", Material.IRON_BLOCK, Material.STONE_BRICKS, 4},
            {"stone arm / stone pillar h2", Material.STONE_BRICKS, Material.STONE_BRICKS, 2},
            {"stone arm / stone pillar h3", Material.STONE_BRICKS, Material.STONE_BRICKS, 3},
        };
        StringBuilder out = new StringBuilder("\n==== TRUE-CANTILEVER ARM SWEEP (default config) ====\n");
        int baseX = 0;
        for (Object[] s : specs) {
            String name = (String) s[0];
            Material arm = (Material) s[1];
            Material pillar = (Material) s[2];
            int ph = (Integer) s[3];
            for (int len = 1; len <= 9; len++) {
                CantResult r = buildCantilever(arm, len, pillar, ph, baseX);
                baseX += 40;
                out.append(String.format(
                        "%s len=%d  root=%.3f  joint=%.3f  armSurvived=%b%n",
                        name, len, r.rootStress(), r.jointStress(), r.armSurvived()));
            }
            out.append('\n');
        }
        System.out.println(out);
    }

    private record CantResult(double rootStress, double jointStress, boolean armSurvived) {}

    /**
     * Build a true cantilever — a single 1-wide pillar of {@code pillarMat} {@code pillarHeight} tall
     * with an arm of {@code armMat} reaching {@code armLen} blocks out along +x from the pillar top —
     * and solve it the demo way. Returns the arm-root stress (x=baseX+1, the block that carries the
     * moment), the joint stress (x=baseX, the pillar top), and whether the whole arm survived.
     */
    private CantResult buildCantilever(Material armMat, int armLen, Material pillarMat, int pillarHeight, int baseX) {
        StructureManager strux = shippedDefaultManager();
        int beamY = GROUND_Y + 1 + (pillarHeight - 1);
        layFloor(baseX - 1, baseX + armLen + 2, -1, 1);
        DemoBuild b = new DemoBuild(world, strux);
        for (int dy = 0; dy < pillarHeight; dy++) {
            b.place(baseX, GROUND_Y + 1 + dy, 0, pillarMat);
        }
        for (int x = 1; x <= armLen; x++) {
            b.place(baseX + x, beamY, 0, armMat);
        }
        b.drainAll();
        b.solve();
        double root = strux.getStress(world.getBlockAt(baseX + 1, beamY, 0));
        double joint = strux.getStress(world.getBlockAt(baseX, beamY, 0));
        boolean armSurvived = true;
        for (int x = 1; x <= armLen; x++) {
            if (strux.getStress(world.getBlockAt(baseX + x, beamY, 0)) < 0) {
                armSurvived = false;
                break;
            }
        }
        return new CantResult(root, joint, armSurvived);
    }

    /**
     * Mirrors {@link dev.gesp.structural.demo.ScenarioContext}: queue blocks, place them
     * bottom-to-top with {@code addBlockDirect}, then solve from every placed block with a callback
     * that sets collapsed positions to air (the demo's world-updating dropper).
     */
    private static final class DemoBuild {
        private record Placement(int x, int y, int z, Material material) {}

        private final World world;
        private final StructureManager strux;
        private final List<Placement> plan = new ArrayList<>();
        private final List<Block> placed = new ArrayList<>();

        DemoBuild(World world, StructureManager strux) {
            this.world = world;
            this.strux = strux;
        }

        void place(int x, int y, int z, Material material) {
            plan.add(new Placement(x, y, z, material));
        }

        /** Lay the whole structure bottom-to-top (like a real build), registering each block. */
        void drainAll() {
            plan.sort(Comparator.comparingInt(Placement::y));
            for (Placement p : plan) {
                Block block = world.getBlockAt(p.x(), p.y(), p.z());
                block.setType(p.material(), false);
                strux.addBlockDirect(block);
                placed.add(block);
            }
            plan.clear();
        }

        /** Run the overload solver from every placed block; drop collapsed positions to air. */
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

    /** Lay a solid bedrock floor so the build auto-grounds exactly like it does on the surface. */
    private void layFloor(int x0, int x1, int z0, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                world.getBlockAt(x, GROUND_Y, z).setType(Material.BEDROCK, false);
            }
        }
    }

    private boolean isAir(int x, int y, int z) {
        return world.getBlockAt(x, y, z).getType().isAir();
    }
}
