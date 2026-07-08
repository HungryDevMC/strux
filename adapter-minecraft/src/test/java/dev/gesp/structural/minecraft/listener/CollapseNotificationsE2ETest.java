package dev.gesp.structural.minecraft.listener;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.minecraft.StructuralIntegrityPlugin;
import dev.gesp.structural.minecraft.config.EffectsConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * E2E for the two collapse chat-feedback features:
 *
 * <ul>
 *   <li>big-collapse broadcast — a collapse bigger than the threshold tells the
 *       whole server, with the triggering player's name and the block count;
 *   <li>first-collapse hint — the first collapse a player triggers sends a
 *       one-time /engineer tip and remembers it in their player data.
 * </ul>
 */
@DisplayName("E2E: collapse chat notifications")
class CollapseNotificationsE2ETest {

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private WorldMock world;
    private PlayerMock breaker;
    private PlayerMock bystander;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        world = server.addSimpleWorld("test_world");
        breaker = server.addPlayer("Steve");
        bystander = server.addPlayer("Alex");
        // These tests exercise the broadcast, not the screen shake. The settling shake now
        // fires at the configured threshold (was a hardcoded 30), and its sendHurtAnimation
        // is unimplemented in MockBukkit (it aborts the test); disable shake to isolate.
        plugin.getEffectsConfig().setScreenShakeEnabled(false);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Build a vertical stone column of the given height on bedrock at (x,*,z). */
    private void buildColumn(int x, int z, int stoneHeight) {
        Block ground = world.getBlockAt(x, 0, z);
        ground.setType(Material.BEDROCK);
        plugin.getStructureManager().onBlockPlaced(ground);
        for (int y = 1; y <= stoneHeight; y++) {
            Block stone = world.getBlockAt(x, y, z);
            stone.setType(Material.STONE);
            plugin.getStructureManager().onBlockPlaced(stone);
        }
    }

    /** Drain a player's message queue into plain text, ignoring the legacy "N blocks collapsed!" line. */
    private String findBroadcast(PlayerMock p) {
        PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
        Component msg;
        String broadcast = null;
        while ((msg = p.nextComponentMessage()) != null) {
            String text = plain.serialize(msg);
            if (text.contains("structure collapsed!")) {
                broadcast = text;
            }
        }
        return broadcast;
    }

    @Test
    @DisplayName("A collapse over the threshold broadcasts the player's name and count to everyone")
    void bigCollapseBroadcastsToAllPlayers() {
        // Threshold is 15 by default; break the base of a 20-stone column so 20 fall (> 15).
        buildColumn(0, 0, 20);
        Block base = world.getBlockAt(0, 1, 0);
        server.getPluginManager().callEvent(new BlockBreakEvent(base, breaker));

        // 19 blocks above the broken base lose support and come down (> 15 threshold).
        String toBreaker = findBroadcast(breaker);
        String toBystander = findBroadcast(bystander);

        assertNotNull(toBreaker, "the breaker should receive the broadcast");
        assertNotNull(toBystander, "a bystander should receive the broadcast too (server-wide)");
        assertEquals(toBreaker, toBystander, "both players see the same broadcast");
        assertTrue(toBreaker.contains("Steve"), "broadcast names the triggering player: " + toBreaker);
        assertTrue(toBreaker.contains("(19 blocks)"), "broadcast states the collapsed count: " + toBreaker);
        assertTrue(toBreaker.startsWith("💥"), "broadcast leads with the collapse emoji: " + toBreaker);
    }

    @Test
    @DisplayName("A collapse at or under the threshold does NOT broadcast")
    void smallCollapseDoesNotBroadcast() {
        // Break the base of a 4-stone column → 3 collapse, well under the 15 threshold.
        buildColumn(0, 0, 4);
        Block base = world.getBlockAt(0, 1, 0);
        server.getPluginManager().callEvent(new BlockBreakEvent(base, breaker));

        assertNull(findBroadcast(breaker), "a small collapse must not reach chat");
        assertNull(findBroadcast(bystander), "a bystander must hear nothing for a small collapse");
    }

    @Test
    @DisplayName("Disabling the broadcast suppresses it even for a big collapse")
    void disabledBroadcastIsSilent() {
        plugin.getEffectsConfig().setBigCollapseBroadcastEnabled(false);
        buildColumn(0, 0, 20);
        server.getPluginManager().callEvent(new BlockBreakEvent(world.getBlockAt(0, 1, 0), breaker));

        assertNull(findBroadcast(breaker), "broadcast disabled → no chat even for a big collapse");
    }

    @Test
    @DisplayName("Exactly-threshold collapse stays quiet; one more block broadcasts (strict >)")
    void thresholdIsStrictlyGreaterThan() {
        EffectsConfig cfg = plugin.getEffectsConfig();
        cfg.setBigCollapseBroadcastThreshold(3);

        // Column of 4 stone: breaking the base drops exactly 3 → equals threshold, no broadcast.
        buildColumn(0, 0, 4);
        server.getPluginManager().callEvent(new BlockBreakEvent(world.getBlockAt(0, 1, 0), breaker));
        assertNull(findBroadcast(breaker), "a collapse equal to the threshold must not broadcast");

        // Column of 5 stone: breaking the base drops 4 → exceeds threshold, broadcasts.
        buildColumn(10, 10, 5);
        server.getPluginManager().callEvent(new BlockBreakEvent(world.getBlockAt(10, 1, 10), breaker));
        String broadcast = findBroadcast(breaker);
        assertNotNull(broadcast, "a collapse over the threshold must broadcast");
        assertTrue(broadcast.contains("(4 blocks)"), broadcast);
    }

    @Test
    @DisplayName("First collapse sends the /engineer hint and sets the seen flag; a second collapse does not")
    void firstCollapseSendsHintOnce() {
        NamespacedKey seenKey = new NamespacedKey(plugin, "seen_collapse_hint");
        PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();

        assertFalse(
                breaker.getPersistentDataContainer().has(seenKey, PersistentDataType.BYTE),
                "no seen flag before the first collapse");

        // First collapse (small is fine; the hint is independent of the broadcast threshold).
        buildColumn(0, 0, 4);
        server.getPluginManager().callEvent(new BlockBreakEvent(world.getBlockAt(0, 1, 0), breaker));

        boolean gotHint = false;
        Component msg;
        while ((msg = breaker.nextComponentMessage()) != null) {
            if (plain.serialize(msg).contains("/engineer")) {
                gotHint = true;
            }
        }
        assertTrue(gotHint, "the first collapse must send the /engineer hint");
        assertTrue(
                breaker.getPersistentDataContainer().has(seenKey, PersistentDataType.BYTE),
                "the seen flag must be set after the first collapse");

        // Second collapse → no hint (flag already set).
        buildColumn(10, 10, 4);
        server.getPluginManager().callEvent(new BlockBreakEvent(world.getBlockAt(10, 1, 10), breaker));

        boolean gotHintAgain = false;
        while ((msg = breaker.nextComponentMessage()) != null) {
            if (plain.serialize(msg).contains("/engineer")) {
                gotHintAgain = true;
            }
        }
        assertFalse(gotHintAgain, "the hint must fire only once per player");
    }

    @Test
    @DisplayName("Disabling the first-collapse hint sends nothing and leaves the flag unset")
    void disabledHintDoesNotFireOrPersist() {
        plugin.getEffectsConfig().setFirstCollapseHintEnabled(false);
        NamespacedKey seenKey = new NamespacedKey(plugin, "seen_collapse_hint");
        PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();

        buildColumn(0, 0, 4);
        server.getPluginManager().callEvent(new BlockBreakEvent(world.getBlockAt(0, 1, 0), breaker));

        Component msg;
        while ((msg = breaker.nextComponentMessage()) != null) {
            assertFalse(plain.serialize(msg).contains("/engineer"), "disabled hint must not be sent");
        }
        assertFalse(
                breaker.getPersistentDataContainer().has(seenKey, PersistentDataType.BYTE),
                "disabled hint must not set the seen flag");
    }
}
