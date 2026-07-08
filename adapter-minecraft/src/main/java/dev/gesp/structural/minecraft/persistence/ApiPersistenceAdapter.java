package dev.gesp.structural.minecraft.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gesp.structural.persistence.PersistenceAdapter;
import dev.gesp.structural.persistence.PersistenceException;
import dev.gesp.structural.persistence.StructureData;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP API-based persistence adapter for scalable deployments.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                      API PERSISTENCE ADAPTER                       │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Sends structure data to an external API (e.g., Spring backend).   │
 *   │                                                                     │
 *   │  This allows:                                                      │
 *   │    • Multiple Minecraft servers sharing the same data              │
 *   │    • Centralized backup and management                             │
 *   │    • Real-time sync across server clusters                         │
 *   │                                                                     │
 *   │                                                                     │
 *   │    ┌─────────────┐     HTTP      ┌─────────────────┐              │
 *   │    │  Minecraft  │ ────────────► │   Spring API    │              │
 *   │    │   Server    │ ◄──────────── │   (Backend)     │              │
 *   │    └─────────────┘               └─────────────────┘              │
 *   │                                                                     │
 *   │  EXPECTED API ENDPOINTS:                                           │
 *   │                                                                     │
 *   │    POST   /api/v1/structures/{worldId}    Save structure           │
 *   │    GET    /api/v1/structures/{worldId}    Load structure           │
 *   │    DELETE /api/v1/structures/{worldId}    Delete structure         │
 *   │    HEAD   /api/v1/structures/{worldId}    Check if exists          │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>Lives in {@code adapter-minecraft} (rather than {@code core}) because it depends on
 * Jackson for JSON serialization. {@code core} keeps its zero-runtime-deps invariant; if
 * another adapter ever needs HTTP persistence, extract this into a shared
 * {@code adapter-persistence-http} module at that point.
 */
public class ApiPersistenceAdapter implements PersistenceAdapter {

    /** Shared, thread-safe ObjectMapper. Tolerates unknown fields for forward-compat. */
    private static final ObjectMapper MAPPER =
            new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;
    private final Duration timeout;

    /**
     * Create an API persistence adapter.
     *
     * @param baseUrl the base URL of the API (e.g., "https://api.example.com")
     * @param apiKey  optional API key for authentication (null if not needed)
     */
    public ApiPersistenceAdapter(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, Duration.ofSeconds(30));
    }

    /**
     * Create an API persistence adapter with custom timeout.
     *
     * @param baseUrl the base URL of the API
     * @param apiKey  optional API key for authentication
     * @param timeout request timeout
     */
    public ApiPersistenceAdapter(String baseUrl, String apiKey, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public String getName() {
        return "ApiPersistence[" + baseUrl + "]";
    }

    @Override
    public void initialize() throws PersistenceException {
        // Verify connectivity by making a health check request.
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/health"))
                    .timeout(timeout)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new PersistenceException("API health check failed with status: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            throw new PersistenceException("Failed to connect to API at: " + baseUrl, e);
        }
    }

    @Override
    public CompletableFuture<Void> saveAsync(String worldId, StructureData data) {
        String url = baseUrl + "/api/v1/structures/" + encodeWorldId(worldId);
        String body = serializeToJson(data);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        addAuth(builder);

        return httpClient
                .sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() >= 400) {
                        throw new PersistenceException(
                                "API save failed with status " + response.statusCode() + ": " + response.body());
                    }
                });
    }

    @Override
    public CompletableFuture<Optional<StructureData>> loadAsync(String worldId) {
        String url = baseUrl + "/api/v1/structures/" + encodeWorldId(worldId);

        HttpRequest.Builder builder =
                HttpRequest.newBuilder().uri(URI.create(url)).timeout(timeout).GET();

        addAuth(builder);

        return httpClient
                .sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 404) {
                        return Optional.empty();
                    }
                    if (response.statusCode() >= 400) {
                        throw new PersistenceException(
                                "API load failed with status " + response.statusCode() + ": " + response.body());
                    }
                    return Optional.of(deserializeFromJson(response.body()));
                });
    }

    @Override
    public CompletableFuture<Void> deleteAsync(String worldId) {
        String url = baseUrl + "/api/v1/structures/" + encodeWorldId(worldId);

        HttpRequest.Builder builder =
                HttpRequest.newBuilder().uri(URI.create(url)).timeout(timeout).DELETE();

        addAuth(builder);

        return httpClient
                .sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() >= 400 && response.statusCode() != 404) {
                        throw new PersistenceException(
                                "API delete failed with status " + response.statusCode() + ": " + response.body());
                    }
                });
    }

    @Override
    public CompletableFuture<Boolean> existsAsync(String worldId) {
        String url = baseUrl + "/api/v1/structures/" + encodeWorldId(worldId);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .method("HEAD", HttpRequest.BodyPublishers.noBody());

        addAuth(builder);

        return httpClient
                .sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> response.statusCode() == 200);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  JSON (Jackson) + auth helper
    // ─────────────────────────────────────────────────────────────────────

    private String encodeWorldId(String worldId) {
        return URLEncoder.encode(worldId, StandardCharsets.UTF_8);
    }

    private void addAuth(HttpRequest.Builder builder) {
        if (apiKey != null && !apiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
    }

    private String serializeToJson(StructureData data) {
        try {
            return MAPPER.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new PersistenceException("Failed to serialize structure data", e);
        }
    }

    private StructureData deserializeFromJson(String json) {
        try {
            return MAPPER.readValue(json, StructureData.class);
        } catch (IOException e) {
            throw new PersistenceException("Failed to deserialize structure data: " + json, e);
        }
    }
}
