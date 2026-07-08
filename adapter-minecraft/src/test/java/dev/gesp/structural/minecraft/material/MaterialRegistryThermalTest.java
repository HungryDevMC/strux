package dev.gesp.structural.minecraft.material;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.gesp.structural.model.ThermalClass;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the per-material thermal-class assignment: each family follows the real
 * strength-vs-temperature curve it should, so temperature-based strength has an
 * honest default per block (steel sags, masonry is brittle, wood chars).
 */
@DisplayName("MaterialRegistry: thermal classes per material family")
class MaterialRegistryThermalTest {

    private final MaterialRegistry registry = new MaterialRegistry();

    @Test
    @DisplayName("Metals follow the steel curve")
    void metalsAreSteel() {
        assertEquals(ThermalClass.STEEL, registry.getSpec(Material.IRON_BLOCK).thermalClass());
        assertEquals(ThermalClass.STEEL, registry.getSpec(Material.GOLD_BLOCK).thermalClass());
        assertEquals(
                ThermalClass.STEEL, registry.getSpec(Material.NETHERITE_BLOCK).thermalClass());
    }

    @Test
    @DisplayName("Stone/brick/glass families follow the masonry curve")
    void stoneIsMasonry() {
        assertEquals(ThermalClass.MASONRY, registry.getSpec(Material.STONE).thermalClass());
        assertEquals(
                ThermalClass.MASONRY, registry.getSpec(Material.STONE_BRICKS).thermalClass());
        assertEquals(ThermalClass.MASONRY, registry.getSpec(Material.DEEPSLATE).thermalClass());
        assertEquals(ThermalClass.MASONRY, registry.getSpec(Material.GLASS).thermalClass());
    }

    @Test
    @DisplayName("Logs and planks follow the wood char curve")
    void woodIsWood() {
        assertEquals(ThermalClass.WOOD, registry.getSpec(Material.OAK_LOG).thermalClass());
        assertEquals(ThermalClass.WOOD, registry.getSpec(Material.OAK_PLANKS).thermalClass());
        assertEquals(ThermalClass.WOOD, registry.getSpec(Material.SPRUCE_PLANKS).thermalClass());
    }

    @Test
    @DisplayName("An admin override of thermal class survives (applyOverride keeps thermal class)")
    void groundStaysInert() {
        // Bedrock is ground; it must never be softened.
        assertEquals(ThermalClass.INERT, registry.getSpec(Material.BEDROCK).thermalClass());
    }
}
