package dev.gesp.structural.minecraft.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.gesp.structural.minecraft.config.EffectsConfig;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

@DisplayName("CollapseEffects: the settling shake honours the configured threshold")
class CollapseEffectsShakeTest {

    private ServerMock server;
    private Plugin plugin;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("ShakeTestPlugin");
        world = server.addSimpleWorld("shake_world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Counts settling shakes; leaves the rest of the effect real. */
    private static final class ShakeSpy extends CollapseEffects {
        int shakes = 0;

        ShakeSpy(EffectsConfig config, Plugin plugin) {
            super(config, plugin);
        }

        @Override
        public void shakeNearbyPlayers(World world, Location center, float intensity) {
            shakes++;
        }
    }

    @Test
    @DisplayName("the settling shake fires at/above effects.screen-shake-threshold, not a hardcoded 30")
    void settlingShakeUsesConfiguredThreshold() {
        EffectsConfig config = new EffectsConfig();
        config.setScreenShakeEnabled(true);
        config.setScreenShakeThreshold(15);
        config.setDustCloudsEnabled(false); // isolate the shake from other effects
        ShakeSpy effects = new ShakeSpy(config, plugin);
        Location origin = new Location(world, 0, 64, 0);

        // 10 < 15 → no shake (the old hardcoded "> 30" would also not shake here).
        effects.playCascadeComplete(world, origin, 10);
        assertEquals(0, effects.shakes, "below the threshold: no shake");

        // 15 ≥ 15 → shake. The old "> 30" would have stayed silent at 15 and 20 — that was the bug.
        effects.playCascadeComplete(world, origin, 15);
        assertEquals(1, effects.shakes, "at the threshold: shake");

        effects.playCascadeComplete(world, origin, 20);
        assertEquals(2, effects.shakes, "above the threshold: shake");
    }
}
