package dev.gesp.structural.minecraft.material;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MaterialRegistry#isNaturalTerrain} — the set of blocks the foundation
 * depth grounding treats as solid world ground. World-gen terrain counts;
 * player-build materials (planks, glass, wool, bricks) do not.
 */
@DisplayName("MaterialRegistry.isNaturalTerrain: terrain vs player builds")
class NaturalTerrainTest {

    private final MaterialRegistry registry = new MaterialRegistry();

    @Test
    @DisplayName("Stone / dirt / sand / gravel are natural terrain")
    void coreTerrainIsNatural() {
        assertTrue(registry.isNaturalTerrain(Material.STONE));
        assertTrue(registry.isNaturalTerrain(Material.DIRT));
        assertTrue(registry.isNaturalTerrain(Material.SAND));
        assertTrue(registry.isNaturalTerrain(Material.GRAVEL));
        assertTrue(registry.isNaturalTerrain(Material.DEEPSLATE));
        assertTrue(registry.isNaturalTerrain(Material.BEDROCK));
    }

    @Test
    @DisplayName("Ores (generated family) are natural terrain")
    void oresAreNatural() {
        assertTrue(registry.isNaturalTerrain(Material.IRON_ORE));
        assertTrue(registry.isNaturalTerrain(Material.DEEPSLATE_DIAMOND_ORE));
    }

    @Test
    @DisplayName("Player-build materials are NOT natural terrain")
    void playerBuildsAreNotNatural() {
        assertFalse(registry.isNaturalTerrain(Material.OAK_PLANKS));
        assertFalse(registry.isNaturalTerrain(Material.GLASS));
        assertFalse(registry.isNaturalTerrain(Material.WHITE_WOOL));
        assertFalse(registry.isNaturalTerrain(Material.BRICKS));
        assertFalse(registry.isNaturalTerrain(Material.STONE_BRICKS));
    }

    @Test
    @DisplayName("null is not natural terrain")
    void nullIsNotNatural() {
        assertFalse(registry.isNaturalTerrain(null));
    }
}
