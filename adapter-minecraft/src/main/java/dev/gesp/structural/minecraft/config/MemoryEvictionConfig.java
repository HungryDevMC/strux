package dev.gesp.structural.minecraft.config;

/**
 * Configuration for component memory eviction (SCALING.md §5 "Pro durability").
 *
 * <pre>
 *   memory:
 *     eviction:
 *       enabled: false      # master switch — new machinery, off by default
 *       grace-ticks: 200    # wait after a chunk unloads before evicting (churn guard)
 * </pre>
 *
 * <p>When {@link #isEnabled() enabled}, a structure whose every chunk column is unloaded is
 * parked in an in-memory sidecar and dropped from the live graph, then restored
 * bit-identically when any of its chunks loads again — bounding RAM to the working set
 * instead of total lifetime builds.
 */
public final class MemoryEvictionConfig {

    private boolean enabled = false;
    private int graceTicks = 200;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Ticks to wait after a chunk unloads before its structures become eviction-eligible. */
    public int getGraceTicks() {
        return graceTicks;
    }

    public void setGraceTicks(int graceTicks) {
        this.graceTicks = Math.max(0, graceTicks);
    }

    @Override
    public String toString() {
        return "MemoryEvictionConfig{enabled=" + enabled + ", graceTicks=" + graceTicks + '}';
    }
}
