package dev.gesp.structural.minecraft.entity;

import java.util.EnumMap;
import java.util.Map;
import org.bukkit.entity.EntityType;

/**
 * Maps entity types to their mass values for structural load calculations.
 *
 * <p>Mass affects both standing weight (continuous load on stressed blocks) and fall
 * impact energy (E = ½mv²). Different entities have different weights — an iron golem
 * puts more stress on a damaged floor than a chicken.
 */
public class EntityMassRegistry {

    private final Map<EntityType, Double> masses = new EnumMap<>(EntityType.class);
    private double defaultMass = 2.0;

    public EntityMassRegistry() {
        // Sensible defaults — humanoids ~2, large mobs higher, small mobs lower
        masses.put(EntityType.PLAYER, 2.0);
        masses.put(EntityType.ZOMBIE, 2.0);
        masses.put(EntityType.SKELETON, 1.5);
        masses.put(EntityType.CREEPER, 1.8);
        masses.put(EntityType.SPIDER, 1.2);
        masses.put(EntityType.ENDERMAN, 2.5);
        masses.put(EntityType.VILLAGER, 2.0);
        masses.put(EntityType.PILLAGER, 2.0);
        masses.put(EntityType.VINDICATOR, 2.2);
        masses.put(EntityType.RAVAGER, 6.0);
        masses.put(EntityType.IRON_GOLEM, 8.0);
        masses.put(EntityType.SNOW_GOLEM, 1.5);
        masses.put(EntityType.HORSE, 4.0);
        masses.put(EntityType.DONKEY, 3.5);
        masses.put(EntityType.MULE, 3.5);
        masses.put(EntityType.PIG, 1.5);
        masses.put(EntityType.COW, 3.0);
        masses.put(EntityType.SHEEP, 2.0);
        masses.put(EntityType.WOLF, 1.2);
        masses.put(EntityType.CAT, 0.5);
        masses.put(EntityType.CHICKEN, 0.3);
        masses.put(EntityType.RABBIT, 0.2);
        masses.put(EntityType.BAT, 0.1);
        masses.put(EntityType.PARROT, 0.2);
        masses.put(EntityType.WARDEN, 10.0);
        masses.put(EntityType.WITHER, 8.0);
        masses.put(EntityType.ENDER_DRAGON, 15.0);
    }

    /**
     * Get the mass for an entity type.
     */
    public double getMass(EntityType type) {
        return masses.getOrDefault(type, defaultMass);
    }

    /**
     * Set the mass for a specific entity type.
     */
    public void setMass(EntityType type, double mass) {
        if (mass > 0) {
            masses.put(type, mass);
        }
    }

    /**
     * Set the default mass for unregistered entity types.
     */
    public void setDefaultMass(double mass) {
        if (mass > 0) {
            this.defaultMass = mass;
        }
    }

    /**
     * Get the default mass for unregistered entity types.
     */
    public double getDefaultMass() {
        return defaultMass;
    }

    /**
     * Clear all custom overrides and reset to defaults.
     */
    public void clear() {
        masses.clear();
    }
}
