package dev.gesp.structural.minecraft.material;

import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.ThermalClass;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;

/**
 * Maps Minecraft materials to structural properties.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                     MATERIAL REGISTRY                              │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Every block type has physical properties:                         │
 *   │                                                                     │
 *   │    STONE       → mass=3.0, maxLoad=100.0  (heavy, very strong)    │
 *   │    WOOD        → mass=1.0, maxLoad=40.0   (light, moderate)       │
 *   │    DIRT        → mass=2.0, maxLoad=20.0   (medium, weak)          │
 *   │    GLASS       → mass=0.5, maxLoad=5.0    (light, fragile)        │
 *   │                                                                     │
 *   │  Ground blocks (BEDROCK, etc.) have infinite strength.            │
 *   │                                                                     │
 *   │  Blocks not registered get DEFAULT properties.                     │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class MaterialRegistry {

    private final Map<Material, MaterialSpec> registry = new EnumMap<>(Material.class);
    private MaterialSpec defaultSpec = new MaterialSpec(2.0, 30.0);

    // Materials that count as NATURAL world terrain (not player builds) for the
    // depth-based foundation grounding. A build resting on N of these in a column
    // is treated as standing on solid ground. Deliberately conservative: only the
    // blocks world-gen actually fills the underground/surface with — stone family,
    // dirt/earth, sand/gravel, ores, deepslate, terracotta, nether/end terrain.
    // Player-placed masonry (bricks, planks, glass, wool…) is NOT terrain, so a
    // hollow brick tower won't auto-ground itself off its own walls.
    private static final Set<Material> NATURAL_TERRAIN = buildNaturalTerrain();

    public MaterialRegistry() {
        registerDefaults();
    }

    /**
     * Get the structural properties for a Minecraft material.
     */
    public MaterialSpec getSpec(Material material) {
        return registry.getOrDefault(material, defaultSpec);
    }

    /**
     * Every material that has an explicit {@link MaterialSpec} registered (i.e.
     * not just falling back to the default). Consumers like the Siege gamemode
     * use this to offer "every block the physics engine understands" as a
     * buildable palette, with budget cost derived from each spec's max load.
     *
     * @return an unmodifiable snapshot of the registered materials
     */
    public Set<Material> getRegisteredMaterials() {
        return Collections.unmodifiableSet(registry.keySet());
    }

    /**
     * Check if a material is a ground/foundation block.
     */
    public boolean isGround(Material material) {
        MaterialSpec spec = getSpec(material);
        return spec.isGround();
    }

    /**
     * Whether this material is NATURAL world terrain — the dirt/stone/sand/ore
     * blocks world generation fills the ground with, as opposed to a player's
     * build. Used by the foundation system's depth grounding: a tracked block is
     * treated as standing on solid ground when enough of these sit in a column
     * straight below it.
     */
    public boolean isNaturalTerrain(Material material) {
        return material != null && NATURAL_TERRAIN.contains(material);
    }

    /**
     * Build the natural-terrain set version-safely (each name added only if the
     * running server knows it) and by suffix for the big generated families
     * (ores, terracotta) so new colours/variants are covered automatically.
     */
    private static Set<Material> buildNaturalTerrain() {
        Set<Material> set = EnumSet.noneOf(Material.class);
        String[] names = {
            // Stone family + underground variants
            "STONE",
            "GRANITE",
            "DIORITE",
            "ANDESITE",
            "COBBLESTONE",
            "DEEPSLATE",
            "COBBLED_DEEPSLATE",
            "TUFF",
            "CALCITE",
            "DRIPSTONE_BLOCK",
            "BEDROCK",
            "OBSIDIAN",
            "MAGMA_BLOCK",
            // Dirt / earth
            "DIRT",
            "COARSE_DIRT",
            "ROOTED_DIRT",
            "GRASS_BLOCK",
            "PODZOL",
            "MYCELIUM",
            "MUD",
            "CLAY",
            "DIRT_PATH",
            "FARMLAND",
            // Sand / gravel
            "SAND",
            "RED_SAND",
            "GRAVEL",
            "SANDSTONE",
            "RED_SANDSTONE",
            // Nether / end terrain
            "NETHERRACK",
            "SOUL_SAND",
            "SOUL_SOIL",
            "BASALT",
            "BLACKSTONE",
            "END_STONE",
            "CRIMSON_NYLIUM",
            "WARPED_NYLIUM",
            // Ice / packed
            "ICE",
            "PACKED_ICE",
            "BLUE_ICE",
            "SNOW_BLOCK",
            "PACKED_MUD",
        };
        for (String name : names) {
            try {
                set.add(Material.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                // Material absent in this MC version / mock — skip.
            }
        }
        // Generated families: every ore and (plain/coloured) terracotta is natural terrain.
        // The 16 *_GLAZED_TERRACOTTA blocks are player-crafted (smelted), never world-gen,
        // so they must NOT count as natural terrain despite the matching suffix.
        for (Material mat : Material.values()) {
            String n = mat.name();
            if (n.endsWith("_ORE") || (n.endsWith("TERRACOTTA") && !n.contains("GLAZED"))) {
                set.add(mat);
            }
        }
        return Collections.unmodifiableSet(set);
    }

    /**
     * Register a custom material specification.
     */
    public void register(Material material, MaterialSpec spec) {
        registry.put(material, spec);
    }

    /**
     * Register a custom material with mass and maxLoad.
     */
    public void register(Material material, double mass, double maxLoad) {
        registry.put(material, new MaterialSpec(mass, maxLoad));
    }

    /**
     * Try to register a material by name. Silently ignores if the material
     * doesn't exist (for compatibility with different MC versions and MockBukkit).
     */
    private void tryRegister(String materialName, double mass, double maxLoad) {
        try {
            Material mat = Material.valueOf(materialName);
            register(mat, mass, maxLoad);
        } catch (IllegalArgumentException e) {
            // Material doesn't exist in this version/mock - skip silently
        }
    }

    /**
     * Version-safe full-spec registration (mass, maxLoad, blast, fire). Used by the
     * masonry section so each premium stone/brick gets all four axes in one place
     * instead of a mass/load registration plus separate blast/fire passes.
     */
    private void tryRegister(String materialName, double mass, double maxLoad, double blast, double fire) {
        try {
            register(Material.valueOf(materialName), new MaterialSpec(mass, maxLoad, blast, fire));
        } catch (IllegalArgumentException e) {
            // Material doesn't exist in this version/mock - skip silently
        }
    }

    /**
     * Set the default spec for unregistered materials.
     */
    public void setDefault(MaterialSpec spec) {
        this.defaultSpec = spec;
    }

    /**
     * Get the current default spec (for unregistered materials).
     */
    public MaterialSpec getDefault() {
        return defaultSpec;
    }

    /**
     * Override one or more axes of a material's spec, keeping the rest. A null
     * argument leaves that axis at its current value. Used to apply admin
     * overrides from config on top of the built-in defaults.
     *
     * @throws IllegalArgumentException if the resulting spec is invalid
     *         (e.g. non-positive maxLoad) — see {@link MaterialSpec}
     */
    public void applyOverride(
            Material material, Double mass, Double maxLoad, Double blastResistance, Double fireResistance) {
        MaterialSpec base = getSpec(material);
        double m = mass != null ? mass : base.mass();
        double load = maxLoad != null ? maxLoad : base.maxLoad();
        double blast = blastResistance != null ? blastResistance : base.blastResistance();
        double fire = fireResistance != null ? fireResistance : base.fireResistance();
        // Keep the material's thermal class — an admin overriding mass/blast must
        // not silently reset how the block responds to heat.
        register(material, new MaterialSpec(m, load, blast, fire, base.thermalClass()));
    }

    // ─────────────────────────────────────────────────────────────────────
    //  DEFAULT REGISTRATIONS
    // ─────────────────────────────────────────────────────────────────────

    private void registerDefaults() {
        // ═══════════════════════════════════════════════════════════════
        // GROUND BLOCKS (infinite strength - never break)
        // ═══════════════════════════════════════════════════════════════
        register(Material.BEDROCK, MaterialSpec.GROUND);
        register(Material.BARRIER, MaterialSpec.GROUND);

        // ═══════════════════════════════════════════════════════════════
        // STONE FAMILY (heavy, very strong)
        // ═══════════════════════════════════════════════════════════════
        double stoneMass = 3.0;
        double stoneLoad = 100.0;

        register(Material.STONE, stoneMass, stoneLoad);
        register(Material.COBBLESTONE, stoneMass, stoneLoad);
        register(Material.STONE_BRICKS, stoneMass, stoneLoad * 1.2); // reinforced
        register(Material.DEEPSLATE, stoneMass * 1.2, stoneLoad * 1.3);
        register(Material.GRANITE, stoneMass, stoneLoad);
        register(Material.DIORITE, stoneMass, stoneLoad);
        register(Material.ANDESITE, stoneMass, stoneLoad);
        register(Material.OBSIDIAN, stoneMass * 1.5, stoneLoad * 2.0);

        // ═══════════════════════════════════════════════════════════════
        // MASONRY VARIANTS — the premium stone/brick families a builder uses
        // for walls and foundations. Registered explicitly so they get REAL
        // structural specs instead of falling back to the weak default(2,30).
        // Blast values are BASE; the Siege gamemode multiplies the tough stone
        // families ×3 on top (so deepslate bricks read 3.0 here → 9.0 in a
        // match — tougher than stone bricks' 6.0, as deepslate should be).
        // ═══════════════════════════════════════════════════════════════

        // Deepslate — vanilla's tough underground stone; bricks/tiles are the
        // premium wall block, a notch above stone bricks (120) just like vanilla.
        double dsMass = stoneMass * 1.2; // 3.6
        double dsLoad = stoneLoad * 1.4; // 140
        tryRegister("DEEPSLATE_BRICKS", dsMass, dsLoad, 3.0, 9.0);
        tryRegister("DEEPSLATE_TILES", dsMass, dsLoad, 3.0, 9.0);
        tryRegister("POLISHED_DEEPSLATE", dsMass, dsLoad, 3.0, 9.0);
        tryRegister("CHISELED_DEEPSLATE", dsMass, dsLoad, 3.0, 9.0);
        tryRegister("COBBLED_DEEPSLATE", dsMass, stoneLoad * 1.3, 3.0, 9.0); // 130, rough-cut
        tryRegister("CRACKED_DEEPSLATE_BRICKS", dsMass, stoneLoad * 1.1, 2.5, 9.0); // 110, weakened
        tryRegister("CRACKED_DEEPSLATE_TILES", dsMass, stoneLoad * 1.1, 2.5, 9.0);
        // Reinforced deepslate — vanilla-indestructible; foundation-grade. Strong,
        // but below the keep's own FOUNDATION_SPEC (1000/25) so the designed base
        // still outlasts a player-placed slab of it.
        tryRegister("REINFORCED_DEEPSLATE", 4.0, 350.0, 8.0, 14.0); // ×3 boost → 24 blast

        // Stone-brick variants — match STONE_BRICKS (120); cracked a touch weaker.
        tryRegister("MOSSY_STONE_BRICKS", stoneMass, stoneLoad * 1.2, 2.0, 7.0);
        tryRegister("CHISELED_STONE_BRICKS", stoneMass, stoneLoad * 1.2, 2.0, 7.0);
        tryRegister("CRACKED_STONE_BRICKS", stoneMass, stoneLoad, 1.8, 7.0); // 100
        tryRegister("SMOOTH_STONE", stoneMass, stoneLoad, 1.5, 6.0);
        tryRegister("MOSSY_COBBLESTONE", stoneMass, stoneLoad, 1.5, 6.0);
        tryRegister("POLISHED_GRANITE", stoneMass, stoneLoad, 1.5, 6.0);
        tryRegister("POLISHED_DIORITE", stoneMass, stoneLoad, 1.5, 6.0);
        tryRegister("POLISHED_ANDESITE", stoneMass, stoneLoad, 1.5, 6.0);

        // Tuff family — stone-like.
        tryRegister("TUFF", stoneMass, stoneLoad, 1.8, 6.0);
        tryRegister("TUFF_BRICKS", stoneMass, stoneLoad * 1.1, 1.8, 6.0);
        tryRegister("POLISHED_TUFF", stoneMass, stoneLoad, 1.8, 6.0);
        tryRegister("CHISELED_TUFF", stoneMass, stoneLoad, 1.8, 6.0);
        tryRegister("CHISELED_TUFF_BRICKS", stoneMass, stoneLoad, 1.8, 6.0);

        // Blackstone — nether masonry, tough.
        double bsMass = 3.2;
        double bsLoad = stoneLoad * 1.1; // 110
        tryRegister("BLACKSTONE", bsMass, bsLoad, 2.5, 8.0);
        tryRegister("POLISHED_BLACKSTONE", bsMass, bsLoad, 2.5, 8.0);
        tryRegister("POLISHED_BLACKSTONE_BRICKS", bsMass, bsLoad, 2.5, 8.0);
        tryRegister("CHISELED_POLISHED_BLACKSTONE", bsMass, bsLoad, 2.5, 8.0);
        tryRegister("CRACKED_POLISHED_BLACKSTONE_BRICKS", bsMass, stoneLoad, 2.2, 8.0);
        tryRegister("GILDED_BLACKSTONE", bsMass, bsLoad, 2.5, 8.0);

        // Nether-brick variants (NETHER_BRICKS already registered at 90).
        tryRegister("RED_NETHER_BRICKS", 2.5, 90.0, 1.5, 8.0);
        tryRegister("CHISELED_NETHER_BRICKS", 2.5, 90.0, 1.5, 8.0);
        tryRegister("CRACKED_NETHER_BRICKS", 2.5, 80.0, 1.4, 8.0);

        // End stone — vanilla is extremely blast-resistant.
        tryRegister("END_STONE", stoneMass, stoneLoad * 1.2, 9.0, 9.0);
        tryRegister("END_STONE_BRICKS", stoneMass, stoneLoad * 1.2, 9.0, 9.0);

        // Basalt — dense volcanic stone.
        tryRegister("BASALT", stoneMass, stoneLoad, 2.0, 8.0);
        tryRegister("POLISHED_BASALT", stoneMass, stoneLoad, 2.0, 8.0);
        tryRegister("SMOOTH_BASALT", stoneMass, stoneLoad, 2.0, 8.0);

        // Sandstone family — softer masonry (lower load, low blast).
        double ssMass = 2.5;
        double ssLoad = stoneLoad * 0.7; // 70
        for (String n : new String[] {
            "SANDSTONE", "CUT_SANDSTONE", "SMOOTH_SANDSTONE", "CHISELED_SANDSTONE",
            "RED_SANDSTONE", "CUT_RED_SANDSTONE", "SMOOTH_RED_SANDSTONE", "CHISELED_RED_SANDSTONE"
        }) {
            tryRegister(n, ssMass, ssLoad, 1.2, 5.0);
        }

        // Prismarine — sea masonry.
        tryRegister("PRISMARINE", 2.8, 90.0, 1.5, 6.0);
        tryRegister("PRISMARINE_BRICKS", 2.8, 95.0, 1.5, 6.0);
        tryRegister("DARK_PRISMARINE", 2.8, 95.0, 1.5, 6.0);

        // Quartz & purpur — decorative but solid full blocks.
        for (String n :
                new String[] {"QUARTZ_BLOCK", "SMOOTH_QUARTZ", "QUARTZ_BRICKS", "QUARTZ_PILLAR", "CHISELED_QUARTZ_BLOCK"
                }) {
            tryRegister(n, 2.5, 80.0, 1.2, 5.0);
        }
        tryRegister("PURPUR_BLOCK", 2.5, 80.0, 1.5, 6.0);
        tryRegister("PURPUR_PILLAR", 2.5, 80.0, 1.5, 6.0);

        // Earthen & misc natural stone.
        tryRegister("MUD_BRICKS", 2.0, 50.0, 1.0, 4.0);
        tryRegister("PACKED_MUD", 2.0, 40.0, 0.8, 4.0);
        tryRegister("CALCITE", 2.5, 70.0, 1.0, 5.0);
        tryRegister("DRIPSTONE_BLOCK", stoneMass, 90.0, 1.5, 6.0);
        tryRegister("AMETHYST_BLOCK", 2.0, 40.0, 0.8, 4.0); // brittle crystal
        tryRegister("MAGMA_BLOCK", stoneMass, 80.0, 1.5, 12.0);
        tryRegister("NETHERRACK", 2.0, 30.0, 0.4, 2.0); // crumbly, flammable
        tryRegister("CRYING_OBSIDIAN", stoneMass * 1.5, stoneLoad * 2.0, 8.0, 14.0);

        // ═══════════════════════════════════════════════════════════════
        // WOOD FAMILY (light, moderate strength)
        // ═══════════════════════════════════════════════════════════════
        double woodMass = 1.0;
        double woodLoad = 40.0;

        register(Material.OAK_LOG, woodMass, woodLoad);
        register(Material.SPRUCE_LOG, woodMass, woodLoad);
        register(Material.BIRCH_LOG, woodMass, woodLoad);
        register(Material.JUNGLE_LOG, woodMass, woodLoad);
        register(Material.ACACIA_LOG, woodMass, woodLoad);
        register(Material.DARK_OAK_LOG, woodMass, woodLoad);
        register(Material.MANGROVE_LOG, woodMass, woodLoad);
        register(Material.CHERRY_LOG, woodMass, woodLoad);

        register(Material.OAK_PLANKS, woodMass * 0.8, woodLoad * 0.7);
        register(Material.SPRUCE_PLANKS, woodMass * 0.8, woodLoad * 0.7);
        register(Material.BIRCH_PLANKS, woodMass * 0.8, woodLoad * 0.7);
        register(Material.JUNGLE_PLANKS, woodMass * 0.8, woodLoad * 0.7);
        register(Material.ACACIA_PLANKS, woodMass * 0.8, woodLoad * 0.7);
        register(Material.DARK_OAK_PLANKS, woodMass * 0.8, woodLoad * 0.7);

        // ═══════════════════════════════════════════════════════════════
        // DIRT/EARTH FAMILY (medium weight, weak)
        // ═══════════════════════════════════════════════════════════════
        double dirtMass = 2.0;
        double dirtLoad = 20.0;

        register(Material.DIRT, dirtMass, dirtLoad);
        register(Material.GRASS_BLOCK, dirtMass, dirtLoad);
        register(Material.COARSE_DIRT, dirtMass, dirtLoad);
        register(Material.PODZOL, dirtMass, dirtLoad);
        register(Material.MUD, dirtMass * 1.2, dirtLoad * 0.5); // heavy, weak
        register(Material.CLAY, dirtMass, dirtLoad * 0.8);

        register(Material.SAND, dirtMass, dirtLoad * 0.3); // very weak
        register(Material.GRAVEL, dirtMass, dirtLoad * 0.4);

        // ═══════════════════════════════════════════════════════════════
        // METAL/ORE FAMILY (heavy, very strong)
        // ═══════════════════════════════════════════════════════════════
        register(Material.IRON_BLOCK, 5.0, 150.0);
        register(Material.GOLD_BLOCK, 6.0, 80.0); // heavy but soft
        register(Material.DIAMOND_BLOCK, 4.0, 200.0);
        register(Material.NETHERITE_BLOCK, 7.0, 300.0);
        register(Material.COPPER_BLOCK, 4.5, 120.0);

        // ═══════════════════════════════════════════════════════════════
        // GLASS/FRAGILE (light, very weak)
        // ═══════════════════════════════════════════════════════════════
        double glassMass = 0.5;
        double glassLoad = 5.0;

        register(Material.GLASS, glassMass, glassLoad);
        register(Material.GLASS_PANE, glassMass * 0.3, glassLoad * 0.5);
        register(Material.TINTED_GLASS, glassMass, glassLoad);

        // ═══════════════════════════════════════════════════════════════
        // BRICK/CONCRETE (medium-heavy, strong)
        // ═══════════════════════════════════════════════════════════════
        register(Material.BRICKS, 2.5, 80.0);
        register(Material.NETHER_BRICKS, 2.5, 90.0);

        // Concrete - heavy and strong
        double concreteMass = 3.5;
        double concreteLoad = 120.0;
        for (Material mat : Material.values()) {
            if (mat.name().endsWith("_CONCRETE")) {
                register(mat, concreteMass, concreteLoad);
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // LIGHT/DECORATIVE (very light, weak)
        // ═══════════════════════════════════════════════════════════════
        // Wool - all colors
        for (Material mat : Material.values()) {
            if (mat.name().endsWith("_WOOL")) {
                register(mat, 0.3, 3.0);
            }
        }
        register(Material.SPONGE, 0.2, 2.0);
        register(Material.HAY_BLOCK, 0.4, 5.0);
        register(Material.SLIME_BLOCK, 0.5, 10.0);
        register(Material.HONEY_BLOCK, 0.6, 8.0);

        // Leaves - very weak, decorative
        for (Material mat : Material.values()) {
            if (mat.name().endsWith("_LEAVES")) {
                register(mat, 0.1, 1.0);
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // DECORATIVE/ACCESSORY BLOCKS (light, low strength but buildable)
        // ═══════════════════════════════════════════════════════════════
        double decoMass = 0.5;
        double decoLoad = 15.0;

        // Slabs (half blocks)
        for (Material mat : Material.values()) {
            if (mat.name().endsWith("_SLAB")) {
                register(mat, decoMass, decoLoad);
            }
        }

        // Stairs
        for (Material mat : Material.values()) {
            if (mat.name().endsWith("_STAIRS")) {
                register(mat, decoMass * 1.5, decoLoad * 1.2);
            }
        }

        // Trapdoors (hinged hatches)
        for (Material mat : Material.values()) {
            if (mat.name().endsWith("_TRAPDOOR")) {
                register(mat, decoMass * 0.5, decoLoad * 0.8);
            }
        }

        // Fences and fence gates
        for (Material mat : Material.values()) {
            if (mat.name().endsWith("_FENCE") || mat.name().endsWith("_FENCE_GATE")) {
                register(mat, decoMass * 0.6, decoLoad);
            }
        }

        // Walls (stone/brick walls)
        for (Material mat : Material.values()) {
            if (mat.name().endsWith("_WALL")) {
                register(mat, decoMass * 1.5, decoLoad * 1.5);
            }
        }

        // Doors
        for (Material mat : Material.values()) {
            if (mat.name().endsWith("_DOOR") && !mat.name().contains("TRAP")) {
                register(mat, decoMass * 0.8, decoLoad);
            }
        }

        // Pressure plates
        for (Material mat : Material.values()) {
            if (mat.name().endsWith("_PRESSURE_PLATE")) {
                register(mat, decoMass * 0.2, decoLoad * 0.5);
            }
        }

        // Buttons
        for (Material mat : Material.values()) {
            if (mat.name().endsWith("_BUTTON")) {
                register(mat, decoMass * 0.1, decoLoad * 0.3);
            }
        }

        // Signs (standing and wall)
        for (Material mat : Material.values()) {
            String n = mat.name();
            if (n.endsWith("_SIGN") || n.endsWith("_HANGING_SIGN")) {
                register(mat, decoMass * 0.2, decoLoad * 0.3);
            }
        }

        // Utility/decorative singles (use tryRegister for version compatibility)
        tryRegister("LADDER", decoMass * 0.3, decoLoad * 0.5);
        tryRegister("CHAIN", decoMass * 0.4, decoLoad * 0.6);
        tryRegister("FLOWER_POT", decoMass * 0.2, decoLoad * 0.2);
        tryRegister("TORCH", decoMass * 0.1, decoLoad * 0.1);
        tryRegister("SOUL_TORCH", decoMass * 0.1, decoLoad * 0.1);
        tryRegister("WALL_TORCH", decoMass * 0.1, decoLoad * 0.1);
        tryRegister("SOUL_WALL_TORCH", decoMass * 0.1, decoLoad * 0.1);
        tryRegister("LANTERN", decoMass * 0.3, decoLoad * 0.4);
        tryRegister("SOUL_LANTERN", decoMass * 0.3, decoLoad * 0.4);

        // Carpets (all colors)
        for (Material mat : Material.values()) {
            if (mat.name().endsWith("_CARPET")) {
                register(mat, decoMass * 0.1, decoLoad * 0.2);
            }
        }

        // Iron bars (grating/windows)
        tryRegister("IRON_BARS", decoMass * 0.5, decoLoad);

        // Cauldrons (including water/lava variants)
        tryRegister("CAULDRON", decoMass * 1.5, decoLoad * 2.0);
        tryRegister("WATER_CAULDRON", decoMass * 2.5, decoLoad * 2.0);
        tryRegister("LAVA_CAULDRON", decoMass * 3.0, decoLoad * 2.0);
        tryRegister("POWDER_SNOW_CAULDRON", decoMass * 2.0, decoLoad * 2.0);

        // Barrels and chests
        tryRegister("BARREL", decoMass * 1.0, decoLoad * 1.5);
        tryRegister("CHEST", decoMass * 1.0, decoLoad * 1.2);
        tryRegister("TRAPPED_CHEST", decoMass * 1.0, decoLoad * 1.2);
        tryRegister("ENDER_CHEST", decoMass * 2.0, decoLoad * 2.0);

        // ═══════════════════════════════════════════════════════════════
        // BLAST RESISTANCE (1.0 = baseline; higher shrugs off explosions)
        // Keeps the mass/maxLoad set above; only adjusts blast toughness.
        // ═══════════════════════════════════════════════════════════════
        withResistance(Material.OBSIDIAN, 8.0); // bunker walls
        withResistance(Material.NETHERITE_BLOCK, 10.0);
        withResistance(Material.DIAMOND_BLOCK, 5.0);
        withResistance(Material.IRON_BLOCK, 4.0);
        withResistance(Material.DEEPSLATE, 3.0);
        withResistance(Material.STONE_BRICKS, 2.0);
        withResistance(Material.STONE, 1.5);
        withResistance(Material.COBBLESTONE, 1.5);
        withResistance(Material.BRICKS, 1.5);
        withResistance(Material.NETHER_BRICKS, 1.5);

        withResistance(Material.GLASS, 0.3); // glass cannons
        withResistance(Material.TINTED_GLASS, 0.4);
        withResistance(Material.GLASS_PANE, 0.2);
        withResistance(Material.SAND, 0.5);
        withResistance(Material.GRAVEL, 0.5);

        // ═══════════════════════════════════════════════════════════════
        // FIRE RESISTANCE (1.0 = baseline). Higher = slower to lose capacity
        // to heat. Separate axis from blast: wood resists blasts decently but
        // burns fast; glass is blast-fragile but doesn't combust. Non-flammable
        // blocks still degrade slowly via radiant heat (lower value = sooner).
        // ═══════════════════════════════════════════════════════════════
        // Wood — combustible, chars away fast.
        for (Material mat : Material.values()) {
            String n = mat.name();
            if (n.endsWith("_LOG") || n.endsWith("_PLANKS") || n.endsWith("_WOOD")) {
                withFireResistance(mat, 0.5);
            }
        }
        // Tinder — very flammable decoration.
        for (Material mat : Material.values()) {
            if (mat.name().endsWith("_LEAVES") || mat.name().endsWith("_WOOL")) {
                withFireResistance(mat, 0.15);
            }
        }
        withFireResistance(Material.HAY_BLOCK, 0.2);

        // Stone & masonry — effectively fireproof, only the longest fires tell.
        withFireResistance(Material.STONE, 6.0);
        withFireResistance(Material.COBBLESTONE, 6.0);
        withFireResistance(Material.STONE_BRICKS, 7.0);
        withFireResistance(Material.BRICKS, 6.0);
        withFireResistance(Material.NETHER_BRICKS, 8.0);
        withFireResistance(Material.DEEPSLATE, 9.0);
        withFireResistance(Material.GRANITE, 6.0);
        withFireResistance(Material.DIORITE, 6.0);
        withFireResistance(Material.ANDESITE, 6.0);
        withFireResistance(Material.OBSIDIAN, 14.0);
        for (Material mat : Material.values()) {
            if (mat.name().endsWith("_CONCRETE")) {
                withFireResistance(mat, 6.0);
            }
        }

        // Metal — never burns, but loses strength when hot, so radiant heat can
        // cook a metal frame down over a long fire (lower than stone on purpose).
        withFireResistance(Material.IRON_BLOCK, 2.5);
        withFireResistance(Material.GOLD_BLOCK, 1.8); // soft, low melting point
        withFireResistance(Material.COPPER_BLOCK, 2.5);
        withFireResistance(Material.DIAMOND_BLOCK, 5.0);
        withFireResistance(Material.NETHERITE_BLOCK, 12.0); // fireproof in vanilla

        // Glass & earth — don't combust; moderate radiant tolerance.
        withFireResistance(Material.GLASS, 3.0);
        withFireResistance(Material.TINTED_GLASS, 3.0);
        withFireResistance(Material.GLASS_PANE, 2.5);
        withFireResistance(Material.DIRT, 5.0);
        withFireResistance(Material.GRASS_BLOCK, 5.0);
        withFireResistance(Material.SAND, 5.0);
        withFireResistance(Material.GRAVEL, 5.0);
        withFireResistance(Material.CLAY, 5.0);

        assignThermalClasses();
    }

    /**
     * Tag every registered material with the real strength-vs-temperature curve
     * family it follows ({@link ThermalClass}), so temperature-based strength has
     * an honest default per material class. This is a no-op for behaviour unless
     * the temperature feature is enabled (the solver only consults the curve when
     * the adapter sets a non-comfort temperature, which it only does when on).
     *
     * <pre>
     *   STEEL    — metals: hold strength, then sag past ~400°C (Eurocode-3)
     *   MASONRY  — stone/brick/concrete/glass/terracotta: brittle, Eurocode-2
     *   WOOD     — logs/planks and other combustibles: char away by ~300°C
     *   INERT    — everything else stays at the backward-compatible default
     * </pre>
     *
     * <p>Classified by material name so new vanilla variants fall into the right
     * family automatically (e.g. any {@code *_CONCRETE}, {@code *_PLANKS}).
     */
    private void assignThermalClasses() {
        for (Material mat : getRegisteredMaterials().toArray(new Material[0])) {
            MaterialSpec spec = getSpec(mat);
            if (spec.isGround()) {
                continue; // ground is never softened
            }
            ThermalClass cls = classify(mat.name());
            if (cls != ThermalClass.INERT) {
                register(mat, spec.withThermalClass(cls));
            }
        }
    }

    /** Pick the thermal-curve family for a material from its name. */
    private static ThermalClass classify(String name) {
        if (isMetal(name)) {
            return ThermalClass.STEEL;
        }
        if (isWood(name)) {
            return ThermalClass.WOOD;
        }
        if (isMasonry(name)) {
            return ThermalClass.MASONRY;
        }
        return ThermalClass.INERT;
    }

    private static boolean isMetal(String name) {
        return name.equals("IRON_BLOCK")
                || name.equals("GOLD_BLOCK")
                || name.equals("COPPER_BLOCK")
                || name.equals("DIAMOND_BLOCK")
                || name.equals("NETHERITE_BLOCK")
                || name.endsWith("_COPPER")
                || name.startsWith("WAXED_");
    }

    private static boolean isWood(String name) {
        return name.endsWith("_LOG")
                || name.endsWith("_PLANKS")
                || name.endsWith("_WOOD")
                || name.endsWith("_STEM")
                || name.endsWith("_HYPHAE");
    }

    private static boolean isMasonry(String name) {
        return name.contains("STONE")
                || name.contains("DEEPSLATE")
                || name.contains("BRICK")
                || name.contains("CONCRETE")
                || name.contains("TERRACOTTA")
                || name.contains("GLASS")
                || name.contains("GRANITE")
                || name.contains("DIORITE")
                || name.contains("ANDESITE")
                || name.contains("BASALT")
                || name.contains("BLACKSTONE")
                || name.contains("TUFF")
                || name.contains("SANDSTONE")
                || name.contains("PRISMARINE")
                || name.contains("QUARTZ")
                || name.contains("PURPUR")
                || name.contains("OBSIDIAN")
                || name.contains("CALCITE")
                || name.contains("DRIPSTONE")
                || name.contains("AMETHYST")
                || name.contains("END_STONE")
                || name.contains("NETHERRACK")
                || name.contains("MUD");
    }

    /**
     * Re-register a material keeping its mass/maxLoad and fire resistance but
     * setting blast resistance.
     */
    private void withResistance(Material material, double blastResistance) {
        MaterialSpec spec = getSpec(material);
        register(material, new MaterialSpec(spec.mass(), spec.maxLoad(), blastResistance, spec.fireResistance()));
    }

    /**
     * Re-register a material keeping its mass/maxLoad and blast resistance but
     * setting fire resistance.
     */
    private void withFireResistance(Material material, double fireResistance) {
        MaterialSpec spec = getSpec(material);
        register(material, new MaterialSpec(spec.mass(), spec.maxLoad(), spec.blastResistance(), fireResistance));
    }
}
