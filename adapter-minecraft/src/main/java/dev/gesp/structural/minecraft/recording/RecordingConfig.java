package dev.gesp.structural.minecraft.recording;

/**
 * Configuration for event recording.
 */
public class RecordingConfig {

    // Off by default, matching the bundled config.yml: a boot-time auto session
    // occupies the single global recorder slot and silently blocks every scoped
    // recording a host plugin (e.g. Siege per-match/per-build) tries to start.
    // Servers that want the boot-long capture opt in with recording.auto-record.
    private boolean autoRecord = false;
    private int bufferSize = 100;
    private int maxSessions = 20;
    private boolean includeStressUpdates = false;
    private int maxEventsPerTick = 50;
    private boolean asyncWrite = true;
    // Schema v3 stress snapshots (StressDelta) on break/blast events. Off globally so an
    // ad-hoc /strux record stays cheap; the RecordingService turns it on for the "match"
    // tag, where the debug stress view is worth the extra capture.
    private boolean captureStress = false;
    // Write recordings as the compact binary .strx container by default. JSON is still
    // always readable, and can be written for debugging/diffing by turning this off.
    private boolean binaryFormat = true;

    public RecordingConfig() {}

    public boolean isAutoRecord() {
        return autoRecord;
    }

    public void setAutoRecord(boolean autoRecord) {
        this.autoRecord = autoRecord;
    }

    public int getBufferSize() {
        return bufferSize;
    }

    public void setBufferSize(int bufferSize) {
        this.bufferSize = Math.max(10, bufferSize);
    }

    public int getMaxSessions() {
        return maxSessions;
    }

    public void setMaxSessions(int maxSessions) {
        this.maxSessions = Math.max(1, maxSessions);
    }

    public boolean isIncludeStressUpdates() {
        return includeStressUpdates;
    }

    public void setIncludeStressUpdates(boolean includeStressUpdates) {
        this.includeStressUpdates = includeStressUpdates;
    }

    public int getMaxEventsPerTick() {
        return maxEventsPerTick;
    }

    public void setMaxEventsPerTick(int maxEventsPerTick) {
        this.maxEventsPerTick = Math.max(1, maxEventsPerTick);
    }

    public boolean isAsyncWrite() {
        return asyncWrite;
    }

    public void setAsyncWrite(boolean asyncWrite) {
        this.asyncWrite = asyncWrite;
    }

    public boolean isCaptureStress() {
        return captureStress;
    }

    public void setCaptureStress(boolean captureStress) {
        this.captureStress = captureStress;
    }

    public boolean isBinaryFormat() {
        return binaryFormat;
    }

    public void setBinaryFormat(boolean binaryFormat) {
        this.binaryFormat = binaryFormat;
    }
}
