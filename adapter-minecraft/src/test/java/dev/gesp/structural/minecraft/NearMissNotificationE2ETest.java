package dev.gesp.structural.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.gesp.structural.api.CollapseReason;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.NodePos;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * E2E for the "near miss" notification.
 *
 * <pre>
 *   When a block a player knocks loose collapses while it was barely holding —
 *   OVERLOADED, and over the near-miss threshold — the player sees ONE message:
 *   "Close call — that block was barely holding." A floating collapse (no support,
 *   stressAtCollapse 0.0) never qualifies; nor does any collapse whose stress sits
 *   below the threshold; nor anything when the feature is switched off.
 * </pre>
 *
 * <p>The rig is the cantilever from {@code CascadeResumeE2ETest}: a stone span between two
 * piers, built with {@code addBlockDirect} so it stands whole, then one pier is broken. The
 * span becomes an overloaded cantilever — its root joint fails at a fixed, reproducible
 * stress ({@value #OVERLOAD_STRESS}× capacity), and the rest falls as floaters.
 */
@DisplayName("E2E: near-miss notification")
class NearMissNotificationE2ETest {

    /** The message the player should see once after a qualifying near-miss collapse. */
    private static final String MESSAGE = "§eClose call — that block was barely holding.";

    /**
     * The reproducible stress at which the cantilever root fails for this rig (measured: the
     * joint collapses at 1.29× its capacity, independent of span length). Tests bracket the
     * near-miss threshold around this value.
     */
    private static final double OVERLOAD_STRESS = 1.29;

    private static final int SPAN = 6; // >=6 produces a root overload (shorter spans just hold)
    private static final int Y0 = 64;

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private WorldMock world;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        world = server.addSimpleWorld("near_miss_world");
        player = server.addPlayer("Breaker");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("A barely-overloaded collapse messages the breaker exactly once")
    void nearMissFiresOnce() {
        // Default config: near-miss on, threshold 0.98 — below the root's 1.29 collapse
        // stress, so the overloaded joint qualifies.
        Block doomedPier = buildCantilever();
        drainMessages();

        server.getPluginManager().callEvent(new BlockBreakEvent(doomedPier, player));

        // Exactly one near-miss message, even though several floaters also fell.
        assertEquals(1, countNearMissMessages(), "the near-miss message must be sent exactly once per break");
    }

    @Test
    @DisplayName("A collapse below the near-miss threshold sends no message")
    void belowThresholdDoesNotFire() {
        // Raise the threshold above the root's 1.29 collapse stress: this collapse no longer
        // counts as a near miss (the block was further from holding than the admin's band).
        plugin.getEffectsConfig().setNearMissThreshold(OVERLOAD_STRESS + 0.5);
        Block doomedPier = buildCantilever();
        drainMessages();

        server.getPluginManager().callEvent(new BlockBreakEvent(doomedPier, player));

        assertEquals(0, countNearMissMessages(), "a collapse below the threshold must not send the near-miss message");
    }

    @Test
    @DisplayName("A pure floating collapse never sends the near-miss message")
    void floatingCollapseDoesNotFire() {
        // A simple column: breaking the base leaves everything above floating (no overload),
        // so stressAtCollapse is 0.0 for every fallen block — never a near miss.
        addDirect(0, Y0, 0, Material.BEDROCK);
        for (int y = 1; y <= 4; y++) {
            addDirect(0, Y0 + y, 0, Material.STONE);
        }
        Block base = world.getBlockAt(0, Y0 + 1, 0);
        drainMessages();

        server.getPluginManager().callEvent(new BlockBreakEvent(base, player));

        assertEquals(
                0, countNearMissMessages(), "a floating collapse fell for lack of support, not load — no near miss");
    }

    @Test
    @DisplayName("With the feature off, a barely-overloaded collapse sends no message")
    void toggleOffSuppressesMessage() {
        plugin.getEffectsConfig().setNearMissNotificationEnabled(false);
        Block doomedPier = buildCantilever();
        drainMessages();

        server.getPluginManager().callEvent(new BlockBreakEvent(doomedPier, player));

        assertEquals(0, countNearMissMessages(), "with the toggle off no near-miss message is sent");
        // Sanity: the collapse still happened (the doomed pier's span block is gone).
        assertEquals(
                Material.AIR,
                world.getBlockAt(SPAN, Y0 + 1, 0).getType(),
                "physics still ran with the feature off — the cantilever still collapsed");
    }

    @Test
    @DisplayName("A collapse exactly at the threshold does not fire (strictly greater-than)")
    void exactlyAtThresholdDoesNotFire() {
        // The comparison is strict: stressAtCollapse must EXCEED the threshold. Measure the
        // exact collapse stress for this rig, set the threshold to precisely that value, and
        // the same collapse must NOT fire (it equals, not exceeds). This pins the boundary.
        double exactStress = measureRootCollapseStress();
        plugin.getEffectsConfig().setNearMissThreshold(exactStress);
        Block doomedPier = buildCantilever();
        drainMessages();

        server.getPluginManager().callEvent(new BlockBreakEvent(doomedPier, player));

        assertEquals(0, countNearMissMessages(), "a collapse exactly at the threshold must not fire (strict >)");
    }

    @Test
    @DisplayName("Placing a block that barely overloads the structure messages the placer once")
    void placeNearMissFiresOnce() {
        // A 5-long cantilever off a single pier stands; placing the 6th block past the pier
        // pushes the root joint just over its limit (measured 1.29×), so the placed block's
        // overload collapse is a near miss.
        addDirect(0, Y0, 0, Material.BEDROCK);
        for (int x = 0; x <= 5; x++) {
            addDirect(x, Y0 + 1, 0, Material.STONE);
        }
        Block placed = world.getBlockAt(6, Y0 + 1, 0);
        placed.setType(Material.STONE);
        drainMessages();

        server.getPluginManager()
                .callEvent(new BlockPlaceEvent(
                        placed,
                        placed.getState(),
                        world.getBlockAt(5, Y0 + 1, 0),
                        new ItemStack(Material.STONE),
                        player,
                        true));

        assertEquals(1, countNearMissMessages(), "placing a barely-overloading block fires the near-miss once");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Build a stone span between two grounded piers and return the base block of the doomed
     * pier (break it to turn the span into an overloaded cantilever off the survivor).
     */
    private Block buildCantilever() {
        for (int px : new int[] {0, SPAN}) {
            addDirect(px, Y0, 0, Material.BEDROCK);
            addDirect(px, Y0 + 1, 0, Material.STONE);
        }
        for (int x = 1; x < SPAN; x++) {
            addDirect(x, Y0 + 1, 0, Material.STONE);
        }
        return world.getBlockAt(SPAN, Y0, 0);
    }

    private void addDirect(int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(material);
        plugin.getStructureManager().addBlockDirect(block);
    }

    /**
     * Build the same cantilever rig at an out-of-the-way column, break it through the manager
     * with a capturing callback, and return the exact stressAtCollapse of the overloaded root.
     * Used to pin the near-miss threshold to the precise boundary value.
     */
    private double measureRootCollapseStress() {
        int z = 50; // a separate column so it doesn't disturb the test's own rig
        for (int px : new int[] {0, SPAN}) {
            addDirect(px, Y0, z, Material.BEDROCK);
            addDirect(px, Y0 + 1, z, Material.STONE);
        }
        for (int x = 1; x < SPAN; x++) {
            addDirect(x, Y0 + 1, z, Material.STONE);
        }
        double[] captured = {Double.NaN};
        plugin.getStructureManager().onBlockBroken(world.getBlockAt(SPAN, Y0, z), new SolverCallback() {
            @Override
            public void onStressUpdated(Map<NodePos, Double> stressMap) {}

            @Override
            public void onCascadeStep(CollapsedNode collapsed, int stepNumber, CollapseReason reason) {
                if (reason == CollapseReason.OVERLOADED && Double.isNaN(captured[0])) {
                    captured[0] = collapsed.stressAtCollapse();
                }
            }

            @Override
            public void onCascadeComplete(List<CollapsedNode> allCollapsed) {}
        });
        return captured[0];
    }

    /** Discard any messages queued during the build so assertions see only break output. */
    private void drainMessages() {
        while (player.nextMessage() != null) {
            // drain
        }
    }

    /** How many near-miss messages the player received (drains the queue). */
    private int countNearMissMessages() {
        int count = 0;
        String message;
        while ((message = player.nextMessage()) != null) {
            if (MESSAGE.equals(message)) {
                count++;
            }
        }
        return count;
    }
}
