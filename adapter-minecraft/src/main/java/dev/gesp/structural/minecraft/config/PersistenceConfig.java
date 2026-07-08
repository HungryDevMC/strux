package dev.gesp.structural.minecraft.config;

/**
 * Configuration for structure persistence.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                      PERSISTENCE CONFIG                            │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Controls how and when structure data is saved.                    │
 *   │                                                                     │
 *   │  Two storage types available:                                      │
 *   │                                                                     │
 *   │    FILE:  plugins/StructuralIntegrity/structures/                  │
 *   │           └── world.dat                                            │
 *   │           └── world_nether.dat                                     │
 *   │                                                                     │
 *   │    API:   https://api.example.com/api/v1/structures/               │
 *   │           (for server clusters)                                    │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class PersistenceConfig {

    public enum StorageType {
        FILE,
        API
    }

    private boolean enabled = true;
    private StorageType type = StorageType.FILE;
    private int autoSaveIntervalSeconds = 300;

    // API settings
    private String apiUrl = "http://localhost:8080";
    private String apiKey = "";
    private int apiTimeoutSeconds = 30;

    // Getters and setters

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public StorageType getType() {
        return type;
    }

    public void setType(StorageType type) {
        this.type = type;
    }

    public int getAutoSaveIntervalSeconds() {
        return autoSaveIntervalSeconds;
    }

    public void setAutoSaveIntervalSeconds(int autoSaveIntervalSeconds) {
        this.autoSaveIntervalSeconds = autoSaveIntervalSeconds;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public int getApiTimeoutSeconds() {
        return apiTimeoutSeconds;
    }

    public void setApiTimeoutSeconds(int apiTimeoutSeconds) {
        this.apiTimeoutSeconds = apiTimeoutSeconds;
    }

    @Override
    public String toString() {
        return "PersistenceConfig{" + "enabled="
                + enabled + ", type="
                + type + ", autoSaveInterval="
                + autoSaveIntervalSeconds + "s" + (type == StorageType.API ? ", apiUrl='" + apiUrl + "'" : "")
                + '}';
    }
}
