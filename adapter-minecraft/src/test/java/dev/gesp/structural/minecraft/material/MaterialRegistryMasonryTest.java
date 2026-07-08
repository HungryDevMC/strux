package dev.gesp.structural.minecraft.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.model.MaterialSpec;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the masonry registrations so the premium stone/brick families get real
 * structural specs instead of silently falling back to the weak default — the bug
 * that made player-built deepslate bricks (default maxLoad 30) collapse far earlier
 * than stone bricks (120).
 */
@DisplayName("MaterialRegistry: masonry families are registered, logical, and strong")
class MaterialRegistryMasonryTest {

    private final MaterialRegistry registry = new MaterialRegistry();

    @Test
    @DisplayName("Deepslate bricks are at least as strong as stone bricks, not the weak default")
    void deepslateBricksAreStrong() {
        MaterialSpec deepslate = registry.getSpec(Material.DEEPSLATE_BRICKS);
        MaterialSpec stoneBricks = registry.getSpec(Material.STONE_BRICKS);
        assertEquals(140.0, deepslate.maxLoad(), 1e-9, "deepslate bricks should carry 140 load");
        assertTrue(
                deepslate.maxLoad() >= stoneBricks.maxLoad(),
                "deepslate bricks (" + deepslate.maxLoad() + ") must be >= stone bricks (" + stoneBricks.maxLoad()
                        + ")");
    }

    @Test
    @DisplayName("No premium masonry variant falls through to the weak default spec")
    void premiumMasonryIsNotDefault() {
        double defaultLoad = registry.getDefault().maxLoad(); // 30
        Material[] premium = {
            Material.DEEPSLATE_BRICKS,
            Material.DEEPSLATE_TILES,
            Material.POLISHED_DEEPSLATE,
            Material.CHISELED_DEEPSLATE,
            Material.COBBLED_DEEPSLATE,
            Material.CRACKED_DEEPSLATE_BRICKS,
            Material.REINFORCED_DEEPSLATE,
            Material.MOSSY_STONE_BRICKS,
            Material.CHISELED_STONE_BRICKS,
            Material.BLACKSTONE,
            Material.POLISHED_BLACKSTONE_BRICKS,
            Material.END_STONE,
            Material.TUFF_BRICKS,
        };
        for (Material m : premium) {
            assertTrue(
                    registry.getSpec(m).maxLoad() > defaultLoad,
                    m + " must be explicitly registered, not the weak default (" + defaultLoad + ")");
        }
    }

    @Test
    @DisplayName("Reinforced deepslate is foundation-grade strong")
    void reinforcedDeepslateIsFoundationGrade() {
        MaterialSpec spec = registry.getSpec(Material.REINFORCED_DEEPSLATE);
        assertTrue(spec.maxLoad() >= 300.0, "reinforced deepslate should be foundation-grade (>= 300 load)");
        assertTrue(spec.blastResistance() >= 8.0, "and very blast-resistant");
    }

    @Test
    @DisplayName("Deepslate blast base feeds the siege ×3 boost to a logical 9.0")
    void deepslateBlastBaseIsThree() {
        // base 3.0 here → 9.0 in a siege match, tougher than stone bricks' 6.0 (2.0 ×3).
        assertEquals(3.0, registry.getSpec(Material.DEEPSLATE_BRICKS).blastResistance(), 1e-9);
        assertEquals(2.0, registry.getSpec(Material.STONE_BRICKS).blastResistance(), 1e-9);
    }
}
