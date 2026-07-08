package dev.gesp.structural.minecraft.recording;

/**
 * Configuration for the metrics overlay.
 */
public class MetricsConfig {

    private boolean enabled = true;
    private int updateIntervalTicks = 5;

    public MetricsConfig() {}

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getUpdateIntervalTicks() {
        return updateIntervalTicks;
    }

    public void setUpdateIntervalTicks(int updateIntervalTicks) {
        this.updateIntervalTicks = Math.max(1, updateIntervalTicks);
    }
}
