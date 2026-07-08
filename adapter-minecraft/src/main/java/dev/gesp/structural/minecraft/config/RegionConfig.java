package dev.gesp.structural.minecraft.config;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Configuration for where strux physics is allowed to run.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                        REGION CONFIG                              │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Keeps griefers from collapsing spawn.                             │
 *   │                                                                     │
 *   │  enabled          master switch (false = physics everywhere)        │
 *   │  disabledWorlds   worlds where physics never runs                   │
 *   │  respectWorldGuard honor the strux-physics region flag if present   │
 *   │  coreProtectLogging log collapses to CoreProtect for rollback       │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class RegionConfig {

    private boolean enabled = true;
    private Set<String> disabledWorlds = Collections.emptySet();
    private boolean respectWorldGuard = true;
    private boolean coreProtectLogging = true;

    // War-zone scoping: when enabled, destruction is only allowed inside an
    // active war zone (Towny/Factions). Off by default so existing servers are
    // unaffected.
    private boolean warZoneEnabled = false;
    private Set<String> warZoneProviders = Collections.emptySet();
    private boolean warZoneAllowWilderness = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Set<String> getDisabledWorlds() {
        return disabledWorlds;
    }

    /** Stores world names lower-cased for case-insensitive matching. */
    public void setDisabledWorlds(List<String> worlds) {
        Set<String> normalized = new LinkedHashSet<>();
        if (worlds != null) {
            for (String w : worlds) {
                if (w != null && !w.isBlank()) {
                    normalized.add(w.toLowerCase(Locale.ROOT));
                }
            }
        }
        this.disabledWorlds = Collections.unmodifiableSet(normalized);
    }

    public boolean isRespectWorldGuard() {
        return respectWorldGuard;
    }

    public void setRespectWorldGuard(boolean respectWorldGuard) {
        this.respectWorldGuard = respectWorldGuard;
    }

    public boolean isCoreProtectLogging() {
        return coreProtectLogging;
    }

    public void setCoreProtectLogging(boolean coreProtectLogging) {
        this.coreProtectLogging = coreProtectLogging;
    }

    public boolean isWarZoneEnabled() {
        return warZoneEnabled;
    }

    public void setWarZoneEnabled(boolean warZoneEnabled) {
        this.warZoneEnabled = warZoneEnabled;
    }

    public Set<String> getWarZoneProviders() {
        return warZoneProviders;
    }

    /** Stores provider names lower-cased for case-insensitive matching. */
    public void setWarZoneProviders(List<String> providers) {
        Set<String> normalized = new LinkedHashSet<>();
        if (providers != null) {
            for (String p : providers) {
                if (p != null && !p.isBlank()) {
                    normalized.add(p.toLowerCase(Locale.ROOT));
                }
            }
        }
        this.warZoneProviders = Collections.unmodifiableSet(normalized);
    }

    public boolean isWarZoneAllowWilderness() {
        return warZoneAllowWilderness;
    }

    public void setWarZoneAllowWilderness(boolean warZoneAllowWilderness) {
        this.warZoneAllowWilderness = warZoneAllowWilderness;
    }

    @Override
    public String toString() {
        return "RegionConfig{" + "enabled="
                + enabled + ", disabledWorlds="
                + disabledWorlds + ", respectWorldGuard="
                + respectWorldGuard + ", coreProtectLogging="
                + coreProtectLogging + ", warZoneEnabled="
                + warZoneEnabled + ", warZoneProviders="
                + warZoneProviders + '}';
    }
}
