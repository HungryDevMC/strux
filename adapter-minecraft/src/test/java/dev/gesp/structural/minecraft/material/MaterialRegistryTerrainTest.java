package dev.gesp.structural.minecraft.material;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MaterialRegistry: natural-terrain classification")
class MaterialRegistryTerrainTest {

    private final MaterialRegistry registry = new MaterialRegistry();

    @Test
    @DisplayName("glazed terracotta is player-crafted, not natural terrain — plain/coloured terracotta is")
    void glazedTerracottaIsNotNaturalTerrain() {
        assertTrue(registry.isNaturalTerrain(Material.TERRACOTTA), "plain terracotta is badlands world-gen");
        assertTrue(registry.isNaturalTerrain(Material.WHITE_TERRACOTTA), "coloured terracotta generates too");

        assertFalse(
                registry.isNaturalTerrain(Material.WHITE_GLAZED_TERRACOTTA),
                "glazed terracotta is only ever smelted by players");
        assertFalse(registry.isNaturalTerrain(Material.BLUE_GLAZED_TERRACOTTA), "...all 16 glazed variants");
    }

    @Test
    @DisplayName("ores remain natural terrain (the other suffix branch still works)")
    void oresAreStillNaturalTerrain() {
        assertTrue(registry.isNaturalTerrain(Material.IRON_ORE));
        assertTrue(registry.isNaturalTerrain(Material.DEEPSLATE_DIAMOND_ORE));
    }
}
