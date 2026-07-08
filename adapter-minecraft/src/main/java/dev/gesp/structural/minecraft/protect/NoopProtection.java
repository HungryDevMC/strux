package dev.gesp.structural.minecraft.protect;

import java.util.Set;
import org.bukkit.Location;

/**
 * Protection without a region plugin: only the per-world disable list applies.
 * Region checks always allow. Used when WorldGuard is absent or disabled.
 */
public final class NoopProtection extends AbstractProtectionService {

    public NoopProtection(boolean enabled, Set<String> disabledWorlds) {
        super(enabled, disabledWorlds);
    }

    @Override
    protected boolean regionAllows(Location loc) {
        return true;
    }

    @Override
    public String describe() {
        return hasWorldRules() ? "per-world toggles only (no region plugin)" : "none (physics everywhere)";
    }
}
