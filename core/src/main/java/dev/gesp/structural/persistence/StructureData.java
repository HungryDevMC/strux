package dev.gesp.structural.persistence;

import java.util.ArrayList;
import java.util.List;

/**
 * Serializable representation of all structures in a world.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                       STRUCTURE DATA (DTO)                         │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Contains ALL blocks for a single world/dimension.                 │
 *   │                                                                     │
 *   │  When saved to disk, it might look like:                           │
 *   │                                                                     │
 *   │  {                                                                  │
 *   │    "worldId": "world",                                             │
 *   │    "version": 1,                                                   │
 *   │    "blocks": [                                                     │
 *   │      {"x": 0, "y": 0, "z": 0, "mass": 0, "maxLoad": INF, ...},    │
 *   │      {"x": 0, "y": 1, "z": 0, "mass": 2, "maxLoad": 30, ...},     │
 *   │      ...                                                           │
 *   │    ]                                                               │
 *   │  }                                                                  │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class StructureData {

    /**
     * Current format version for forward compatibility.
     *
     * <p>v2 added a per-block {@code reinforcement} multiplier. v3 added
     * per-block {@code damage} and blast/fire resistance, plus explicit edges
     * for non-grid topologies. v5 added the per-block {@code thermalClass} so
     * temperature softening survives a restart. Older files load cleanly:
     * missing fields deserialize to their neutral defaults (see {@link
     * BlockData}; a missing thermal class is INERT) and a missing topology
     * section means "derive adjacency from the grid".
     */
    public static final int CURRENT_VERSION = 5;

    private String worldId;
    private int version;
    private List<BlockData> blocks;
    private List<EdgeData> edges;

    /**
     * True when adjacency must be restored from {@link #edges} instead of
     * being re-derived from block positions. Set for graphs built through the
     * generic node/connect API (prefabs, trusses) or whose grid edges were
     * deliberately severed — re-deriving those from positions would silently
     * rewire the structure.
     */
    private boolean explicitTopology;

    /**
     * Default constructor for deserialization.
     */
    public StructureData() {
        this.version = CURRENT_VERSION;
        this.blocks = new ArrayList<>();
        this.edges = new ArrayList<>();
    }

    /**
     * Create structure data for a specific world.
     */
    public StructureData(String worldId) {
        this();
        this.worldId = worldId;
    }

    /**
     * Create structure data with all fields.
     */
    public StructureData(String worldId, int version, List<BlockData> blocks) {
        this.worldId = worldId;
        this.version = version;
        this.blocks = blocks != null ? new ArrayList<>(blocks) : new ArrayList<>();
        this.edges = new ArrayList<>();
    }

    public String getWorldId() {
        return worldId;
    }

    public void setWorldId(String worldId) {
        this.worldId = worldId;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public List<BlockData> getBlocks() {
        return blocks;
    }

    public void setBlocks(List<BlockData> blocks) {
        this.blocks = blocks != null ? new ArrayList<>(blocks) : new ArrayList<>();
    }

    public void addBlock(BlockData block) {
        this.blocks.add(block);
    }

    public int blockCount() {
        return blocks.size();
    }

    public List<EdgeData> getEdges() {
        return edges;
    }

    public void setEdges(List<EdgeData> edges) {
        this.edges = edges != null ? new ArrayList<>(edges) : new ArrayList<>();
    }

    public void addEdge(EdgeData edge) {
        this.edges.add(edge);
    }

    public boolean isExplicitTopology() {
        return explicitTopology;
    }

    public void setExplicitTopology(boolean explicitTopology) {
        this.explicitTopology = explicitTopology;
    }

    @Override
    public String toString() {
        return "StructureData{worldId='" + worldId + "', version=" + version + ", blocks=" + blocks.size() + "}";
    }
}
