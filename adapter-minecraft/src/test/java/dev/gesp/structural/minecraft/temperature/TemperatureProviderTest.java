package dev.gesp.structural.minecraft.temperature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.model.NodePos;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Pins {@link TemperatureProvider}'s world→°C mapping and the thermal-mass
 * (intervening-solid) falloff that makes a thick wall's interior cooler.
 */
@DisplayName("TemperatureProvider: world heat → °C with thermal-mass falloff")
class TemperatureProviderTest {

    private ServerMock server;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("provider_world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void setBlock(int x, int y, int z, Material m) {
        world.getBlockAt(x, y, z).setType(m);
    }

    @Test
    @DisplayName("A block with no heat source nearby sits at biome ambient")
    void ambientWhenNoSource() {
        TemperatureProvider provider = new TemperatureProvider(20.0, 4, 3.0);
        double t = provider.temperatureAt(world, new NodePos(0, 70, 0), 5);
        // Default temperate biome → comfort; we only assert it is NOT hot.
        assertTrue(t < 100.0, "no source → near-ambient, not hot: " + t);
    }

    @Test
    @DisplayName("Adjacent lava reads as hot (well above ambient, approaching the lava temperature)")
    void adjacentLavaIsHot() {
        setBlock(1, 70, 0, Material.LAVA);
        TemperatureProvider provider = new TemperatureProvider(20.0, 4, 3.0);
        double t = provider.temperatureAt(world, new NodePos(0, 70, 0), 5);
        assertTrue(t > 500.0, "a block touching lava is very hot: " + t);
        assertTrue(t <= TemperatureProvider.LAVA_C, "never hotter than the source: " + t);
    }

    @Test
    @DisplayName("Thermal mass: a solid wall between block and lava keeps the far side much cooler")
    void solidBlocksInsulate() {
        // Lava at x=4; the target at x=0. With solid blocks filling x=1..3 the heat
        // must conduct through three blocks; with air between, it does not.
        TemperatureProvider provider = new TemperatureProvider(20.0, 3, 5.0);

        setBlock(4, 70, 0, Material.LAVA);
        double throughAir = provider.temperatureAt(world, new NodePos(0, 70, 0), 5);

        setBlock(1, 70, 0, Material.STONE);
        setBlock(2, 70, 0, Material.STONE);
        setBlock(3, 70, 0, Material.STONE);
        double throughWall = provider.temperatureAt(world, new NodePos(0, 70, 0), 5);

        assertTrue(
                throughWall < throughAir,
                "intervening solid blocks insulate: wall " + throughWall + " < air " + throughAir);
    }

    @Test
    @DisplayName("Falloff: a more distant lava source warms a block less than an adjacent one")
    void distanceFalloff() {
        TemperatureProvider provider = new TemperatureProvider(20.0, 2, 0.0);

        setBlock(1, 70, 0, Material.LAVA);
        double near = provider.temperatureAt(world, new NodePos(0, 70, 0), 5);
        setBlock(1, 70, 0, Material.AIR);

        setBlock(4, 70, 0, Material.LAVA);
        double far = provider.temperatureAt(world, new NodePos(0, 70, 0), 5);

        assertTrue(far < near, "the more distant source warms it less: far " + far + " < near " + near);
    }

    @Test
    @DisplayName("Adjacent ice pulls a temperate block's ambient down toward freezing")
    void adjacentIceIsCold() {
        TemperatureProvider provider = new TemperatureProvider(20.0, 4, 3.0);
        double warm = provider.temperatureAt(world, new NodePos(0, 70, 0), 1);
        setBlock(1, 70, 0, Material.ICE);
        double cold = provider.temperatureAt(world, new NodePos(0, 70, 0), 1);
        assertTrue(cold <= 0.0, "ice drops the local ambient to freezing: " + cold);
        assertTrue(cold < warm, "colder than the ice-free reading: " + cold + " < " + warm);
    }

    @Test
    @DisplayName("A magma block is a (milder) heat source")
    void magmaIsAMildSource() {
        setBlock(1, 70, 0, Material.MAGMA_BLOCK);
        TemperatureProvider provider = new TemperatureProvider(20.0, 4, 3.0);
        double t = provider.temperatureAt(world, new NodePos(0, 70, 0), 5);
        assertTrue(t > 100.0 && t < TemperatureProvider.LAVA_C, "magma warms it, milder than lava: " + t);
    }

    @Test
    @DisplayName("Fire reads hotter than magma but cooler than lava at the same distance")
    void fireBetweenMagmaAndLava() {
        TemperatureProvider provider = new TemperatureProvider(20.0, 4, 0.0);
        setBlock(1, 70, 0, Material.MAGMA_BLOCK);
        double magma = provider.temperatureAt(world, new NodePos(0, 70, 0), 1);
        setBlock(1, 70, 0, Material.FIRE);
        double fire = provider.temperatureAt(world, new NodePos(0, 70, 0), 1);
        setBlock(1, 70, 0, Material.LAVA);
        double lava = provider.temperatureAt(world, new NodePos(0, 70, 0), 1);
        assertTrue(magma < fire && fire < lava, "magma " + magma + " < fire " + fire + " < lava " + lava);
    }

    @Test
    @DisplayName("The documented source temperatures are the published mapping (lava hottest, then fire, then magma)")
    void sourceMappingOrder() {
        assertEquals(1100.0, TemperatureProvider.LAVA_C, 0.0);
        assertEquals(800.0, TemperatureProvider.FIRE_C, 0.0);
        assertEquals(400.0, TemperatureProvider.MAGMA_C, 0.0);
        assertTrue(TemperatureProvider.LAVA_C > TemperatureProvider.FIRE_C);
        assertTrue(TemperatureProvider.FIRE_C > TemperatureProvider.MAGMA_C);
    }
}
