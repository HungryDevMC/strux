package dev.gesp.structural.persistence;

import dev.gesp.structural.model.ThermalClass;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * File-based persistence adapter using a simple JSON-like format.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                     FILE PERSISTENCE ADAPTER                       │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Saves structure data to files on disk.                            │
 *   │                                                                     │
 *   │  Directory structure:                                              │
 *   │                                                                     │
 *   │    dataFolder/                                                     │
 *   │    └── structures/                                                 │
 *   │        ├── world.json                                              │
 *   │        ├── world_nether.json                                       │
 *   │        └── world_the_end.json                                      │
 *   │                                                                     │
 *   │  File format (simple text, easy to read/debug):                    │
 *   │                                                                     │
 *   │    version:5                                                       │
 *   │    worldId:world                                                   │
 *   │    topology:explicit            ← only for non-grid topologies     │
 *   │    block:x,y,z,mass,maxLoad,grounded,reinf,damage,blastR,fireR,    │
 *   │          thermalClass                                              │
 *   │    edge:x1,y1,z1,x2,y2,z2       ← only for non-grid topologies     │
 *   │    ...                                                             │
 *   │                                                                     │
 *   │  v1 (6-field), v2 (7-field) and v3/v4 (10-field) blocks still      │
 *   │  load; missing fields default to pristine/neutral (a missing       │
 *   │  thermal class is INERT), missing topology section means "derive   │
 *   │  adjacency from the grid".                                         │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class FilePersistenceAdapter implements PersistenceAdapter {

    private static final String FILE_EXTENSION = ".dat";
    private static final String VERSION_PREFIX = "version:";
    private static final String WORLD_PREFIX = "worldId:";
    private static final String BLOCK_PREFIX = "block:";
    private static final String TOPOLOGY_PREFIX = "topology:";
    private static final String TOPOLOGY_EXPLICIT = "explicit";
    private static final String EDGE_PREFIX = "edge:";

    private final Path dataFolder;
    private final ExecutorService executor;

    /**
     * Create a file persistence adapter.
     *
     * @param dataFolder base directory for storing structure files
     */
    public FilePersistenceAdapter(Path dataFolder) {
        this.dataFolder = dataFolder.resolve("structures");
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "StructuralIntegrity-Persistence");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public String getName() {
        return "FilePersistence";
    }

    @Override
    public void initialize() throws PersistenceException {
        try {
            Files.createDirectories(dataFolder);
        } catch (IOException e) {
            throw new PersistenceException("Failed to create data directory: " + dataFolder, e);
        }
    }

    @Override
    public void shutdown() {
        executor.shutdown();
    }

    @Override
    public CompletableFuture<Void> saveAsync(String worldId, StructureData data) {
        return CompletableFuture.runAsync(() -> saveInternal(worldId, data), executor);
    }

    @Override
    public CompletableFuture<Optional<StructureData>> loadAsync(String worldId) {
        return CompletableFuture.supplyAsync(() -> loadInternal(worldId), executor);
    }

    @Override
    public CompletableFuture<Void> deleteAsync(String worldId) {
        return CompletableFuture.runAsync(() -> deleteInternal(worldId), executor);
    }

    @Override
    public CompletableFuture<Boolean> existsAsync(String worldId) {
        return CompletableFuture.supplyAsync(() -> existsInternal(worldId), executor);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  INTERNAL IMPLEMENTATION
    // ─────────────────────────────────────────────────────────────────────

    private Path getFilePath(String worldId) {
        // Sanitize worldId to prevent path traversal
        String safeId = worldId.replaceAll("[^a-zA-Z0-9_-]", "_");
        return dataFolder.resolve(safeId + FILE_EXTENSION);
    }

    private void saveInternal(String worldId, StructureData data) {
        Path filePath = getFilePath(worldId);
        // Same sanitized name as the real file — a hostile worldId must not
        // escape the data folder through the temp path either.
        Path tempPath = dataFolder.resolve(filePath.getFileName() + ".tmp");

        try (BufferedWriter writer = Files.newBufferedWriter(tempPath)) {
            // Write header
            writer.write(VERSION_PREFIX + data.getVersion());
            writer.newLine();
            writer.write(WORLD_PREFIX + data.getWorldId());
            writer.newLine();
            if (data.isExplicitTopology()) {
                writer.write(TOPOLOGY_PREFIX + TOPOLOGY_EXPLICIT);
                writer.newLine();
            }

            // Write blocks
            for (BlockData block : data.getBlocks()) {
                writer.write(formatBlock(block));
                writer.newLine();
            }

            // Write edges (only present for explicit topologies)
            for (EdgeData edge : data.getEdges()) {
                writer.write(formatEdge(edge));
                writer.newLine();
            }

            writer.flush();

            // Atomic move (rename) for safety
            Files.move(tempPath, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        } catch (IOException e) {
            // Clean up temp file on failure
            try {
                Files.deleteIfExists(tempPath);
            } catch (IOException ignored) {
            }
            throw new PersistenceException("Failed to save structure data for world: " + worldId, e);
        }
    }

    private Optional<StructureData> loadInternal(String worldId) {
        Path filePath = getFilePath(worldId);

        if (!Files.exists(filePath)) {
            return Optional.empty();
        }

        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            StructureData data = new StructureData();
            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue; // Skip empty lines and comments
                }

                if (line.startsWith(VERSION_PREFIX)) {
                    data.setVersion(Integer.parseInt(line.substring(VERSION_PREFIX.length())));
                } else if (line.startsWith(WORLD_PREFIX)) {
                    data.setWorldId(line.substring(WORLD_PREFIX.length()));
                } else if (line.startsWith(TOPOLOGY_PREFIX)) {
                    data.setExplicitTopology(TOPOLOGY_EXPLICIT.equals(
                            line.substring(TOPOLOGY_PREFIX.length()).trim()));
                } else if (line.startsWith(BLOCK_PREFIX)) {
                    data.addBlock(parseBlock(line.substring(BLOCK_PREFIX.length())));
                } else if (line.startsWith(EDGE_PREFIX)) {
                    data.addEdge(parseEdge(line.substring(EDGE_PREFIX.length())));
                }
            }

            return Optional.of(data);

        } catch (IOException e) {
            throw new PersistenceException("Failed to load structure data for world: " + worldId, e);
        } catch (NumberFormatException e) {
            throw new PersistenceException("Corrupted structure data for world: " + worldId, e);
        }
    }

    private void deleteInternal(String worldId) {
        Path filePath = getFilePath(worldId);
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            throw new PersistenceException("Failed to delete structure data for world: " + worldId, e);
        }
    }

    private boolean existsInternal(String worldId) {
        return Files.exists(getFilePath(worldId));
    }

    // ─────────────────────────────────────────────────────────────────────
    //  SERIALIZATION HELPERS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Format: x,y,z,mass,maxLoad,grounded,reinforcement,damage,blastResistance,fireResistance,thermalClass
     */
    private String formatBlock(BlockData block) {
        return BLOCK_PREFIX + block.x()
                + "," + block.y()
                + "," + block.z()
                + "," + block.mass()
                + "," + block.maxLoad()
                + "," + block.grounded()
                + "," + block.reinforcement()
                + "," + block.damage()
                + "," + block.blastResistance()
                + "," + block.fireResistance()
                + "," + block.thermalClass().name();
    }

    /**
     * Parse: x,y,z,mass,maxLoad,grounded[,reinforcement[,damage,blastRes,fireRes[,thermalClass]]].
     * Trailing fields are optional so v1 (6 fields), v2 (7 fields) and v3/v4
     * (10 fields) files still load with neutral defaults — a missing thermal
     * class is {@link ThermalClass#INERT}.
     */
    private BlockData parseBlock(String line) {
        String[] parts = line.split(",");
        if (parts.length != 6 && parts.length != 7 && parts.length != 10 && parts.length != 11) {
            throw new PersistenceException("Invalid block format: " + line);
        }

        boolean hasFullState = parts.length >= 10;
        double reinforcement = parts.length >= 7 ? Double.parseDouble(parts[6].trim()) : 1.0;
        double damage = hasFullState ? Double.parseDouble(parts[7].trim()) : 0.0;
        double blastResistance = hasFullState ? Double.parseDouble(parts[8].trim()) : 1.0;
        double fireResistance = hasFullState ? Double.parseDouble(parts[9].trim()) : 1.0;
        ThermalClass thermalClass = parts.length >= 11 ? parseThermalClass(parts[10].trim()) : ThermalClass.INERT;
        return new BlockData(
                Integer.parseInt(parts[0].trim()),
                Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim()),
                Double.parseDouble(parts[3].trim()),
                Double.parseDouble(parts[4].trim()),
                Boolean.parseBoolean(parts[5].trim()),
                reinforcement,
                damage,
                blastResistance,
                fireResistance,
                thermalClass);
    }

    /**
     * Map a stored thermal-class name back to the enum, treating anything we
     * don't recognise (a future class, a corrupt token) as {@link
     * ThermalClass#INERT} rather than failing the whole world load — neutral
     * strength is the safe default, matching every other legacy field here.
     */
    private static ThermalClass parseThermalClass(String name) {
        for (ThermalClass tc : ThermalClass.values()) {
            if (tc.name().equals(name)) {
                return tc;
            }
        }
        return ThermalClass.INERT;
    }

    /**
     * Format: x1,y1,z1,x2,y2,z2 (one undirected edge, endpoints ordered)
     */
    private String formatEdge(EdgeData edge) {
        return EDGE_PREFIX + edge.x1()
                + "," + edge.y1()
                + "," + edge.z1()
                + "," + edge.x2()
                + "," + edge.y2()
                + "," + edge.z2();
    }

    private EdgeData parseEdge(String line) {
        String[] parts = line.split(",");
        if (parts.length != 6) {
            throw new PersistenceException("Invalid edge format: " + line);
        }
        return new EdgeData(
                Integer.parseInt(parts[0].trim()),
                Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim()),
                Integer.parseInt(parts[3].trim()),
                Integer.parseInt(parts[4].trim()),
                Integer.parseInt(parts[5].trim()));
    }
}
