package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.model.ThermalClass;
import dev.gesp.structural.persistence.FilePersistenceAdapter;
import dev.gesp.structural.persistence.StructureConverter;
import dev.gesp.structural.persistence.StructureData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Persistence must round-trip EVERYTHING the physics depends on. Every field
 * it silently drops is a lie told to the next server session:
 *
 * <pre>
 *   dropped damage           → a restart heals every siege scar
 *   dropped blast/fire res.  → materials change behavior after a reload
 *   dropped explicit edges   → prefab/truss topologies snap back to grid
 *   resurrected disconnect() → severed joints quietly re-fuse
 * </pre>
 */
@DisplayName("Persistence round-trips the full structural state")
class PersistenceRoundTripTest {

    @TempDir
    Path tempDir;

    private static final NodePos GND = new NodePos(0, 0, 0);
    private static final NodePos A = new NodePos(0, 1, 0);
    private static final NodePos B = new NodePos(0, 2, 0);

    /** Save + load through the real file adapter (synchronously). */
    private StructureGraph roundTrip(StructureGraph graph) throws Exception {
        FilePersistenceAdapter adapter = new FilePersistenceAdapter(tempDir);
        adapter.initialize();
        try {
            adapter.saveAsync("test", StructureConverter.toData(graph, "test")).get();
            Optional<StructureData> loaded = adapter.loadAsync("test").get();
            assertTrue(loaded.isPresent(), "saved world must load back");
            return StructureConverter.toGraph(loaded.get());
        } finally {
            adapter.shutdown();
        }
    }

    @Test
    @DisplayName("Damage survives a save/load (a restart must not heal siege scars)")
    void damageRoundTrips() throws Exception {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(GND);
        g.addBlock(A, TestMaterials.LIGHT, false);
        g.getNode(A).addDamage(0.45);

        StructureGraph loaded = roundTrip(g);
        assertEquals(0.45, loaded.getNode(A).damage(), 1e-9, "cracked walls must stay cracked across restarts");
    }

    @Test
    @DisplayName("Reinforcement survives a save/load")
    void reinforcementRoundTrips() throws Exception {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(GND);
        g.addBlock(A, TestMaterials.LIGHT, false);
        g.getNode(A).setReinforcement(2.5);

        StructureGraph loaded = roundTrip(g);
        assertEquals(2.5, loaded.getNode(A).reinforcement(), 1e-9);
    }

    @Test
    @DisplayName("Blast and fire resistance survive a save/load (materials must not change behavior)")
    void resistancesRoundTrip() throws Exception {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(GND);
        MaterialSpec bunker = new MaterialSpec(3.0, 100.0, 4.0, 8.0);
        g.addBlock(A, bunker, false);

        Node loaded = roundTrip(g).getNode(A);
        assertEquals(4.0, loaded.spec().blastResistance(), 1e-9, "blast resistance is physics, not decoration");
        assertEquals(8.0, loaded.spec().fireResistance(), 1e-9, "fire resistance is physics, not decoration");
    }

    @Test
    @DisplayName("Thermal class survives a save/load (temperature softening must not die on restart)")
    void thermalClassRoundTrips() throws Exception {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(GND);
        MaterialSpec steel = new MaterialSpec(3.0, 100.0, 4.0, 8.0, ThermalClass.STEEL);
        g.addBlock(A, steel, false);

        Node loaded = roundTrip(g).getNode(A);
        assertEquals(
                ThermalClass.STEEL,
                loaded.spec().thermalClass(),
                "thermal class is physics: a steel beam must still weaken in fire after a restart");
    }

    @Test
    @DisplayName("Explicit (non-grid) edges survive a save/load")
    void explicitEdgesRoundTrip() throws Exception {
        // A truss-style topology: nodes 2 apart, connected explicitly. Grid
        // re-derivation would leave them all disconnected (and floating).
        StructureGraph g = new StructureGraph();
        g.addNode(new NodePos(0, 0, 0), MaterialSpec.GROUND, true);
        g.addNode(new NodePos(0, 2, 0), TestMaterials.LIGHT, false);
        g.addNode(new NodePos(2, 2, 0), TestMaterials.LIGHT, false);
        g.connect(new NodePos(0, 0, 0), new NodePos(0, 2, 0));
        g.connect(new NodePos(0, 2, 0), new NodePos(2, 2, 0));

        StructureGraph loaded = roundTrip(g);
        assertEquals(
                g.getNeighbors(new NodePos(0, 2, 0)),
                loaded.getNeighbors(new NodePos(0, 2, 0)),
                "truss edges must survive: a prefab world that loads back as grid is corrupted");
        assertTrue(loaded.getFloatingBlocks().isEmpty(), "the loaded truss is still supported");
    }

    @Test
    @DisplayName("A disconnect()ed grid pair stays disconnected after save/load")
    void severedGridEdgeStaysSevered() throws Exception {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(GND);
        g.addBlock(A, TestMaterials.LIGHT, false);
        g.addBlock(B, TestMaterials.LIGHT, false);
        g.disconnect(A, B); // a severed joint

        StructureGraph loaded = roundTrip(g);
        assertFalse(loaded.getNeighbors(A).contains(B), "grid re-derivation must not quietly re-fuse a severed joint");
    }

    @Test
    @DisplayName("Legacy v3/v4 files (10-field blocks, no thermal class) parse damage + resistances and default INERT")
    void tenFieldLegacyFilesLoad() throws Exception {
        Path file = tempDir.resolve("structures").resolve("v4.dat");
        Files.createDirectories(file.getParent());
        // A pre-thermal (10-field) block carrying non-default damage and
        // blast/fire resistance: those must still parse, and the absent thermal
        // class must default to INERT.
        Files.writeString(
                file,
                """
                version:4
                worldId:v4
                block:0,0,0,0.0,1.7976931348623157E308,true,1.0,0.0,1.0,1.0
                block:0,1,0,2.0,30.0,false,1.0,0.4,4.0,8.0
                """);

        FilePersistenceAdapter adapter = new FilePersistenceAdapter(tempDir);
        adapter.initialize();
        try {
            Optional<StructureData> loaded = adapter.loadAsync("v4").get();
            assertTrue(loaded.isPresent());
            StructureGraph g = StructureConverter.toGraph(loaded.get());
            Node a = g.getNode(A);
            assertEquals(0.4, a.damage(), 1e-9, "v4 damage still parses from the 10-field block");
            assertEquals(4.0, a.spec().blastResistance(), 1e-9, "v4 blast resistance still parses");
            assertEquals(8.0, a.spec().fireResistance(), 1e-9, "v4 fire resistance still parses");
            assertEquals(ThermalClass.INERT, a.spec().thermalClass(), "a 10-field block has no thermal class → INERT");
        } finally {
            adapter.shutdown();
        }
    }

    @Test
    @DisplayName("An unknown thermal-class token loads as INERT instead of failing the world")
    void unknownThermalClassFallsBackToInert() throws Exception {
        Path file = tempDir.resolve("structures").resolve("future.dat");
        Files.createDirectories(file.getParent());
        // An 11-field block whose thermal token is a class this version doesn't
        // know (a newer file, or corruption) must not abort the whole load.
        Files.writeString(
                file,
                """
                version:5
                worldId:future
                block:0,0,0,0.0,1.7976931348623157E308,true,1.0,0.0,1.0,1.0,STEEL
                block:0,1,0,2.0,30.0,false,1.0,0.0,1.0,1.0,PLASMA
                """);

        FilePersistenceAdapter adapter = new FilePersistenceAdapter(tempDir);
        adapter.initialize();
        try {
            Optional<StructureData> loaded = adapter.loadAsync("future").get();
            assertTrue(loaded.isPresent());
            StructureGraph g = StructureConverter.toGraph(loaded.get());
            assertEquals(ThermalClass.STEEL, g.getNode(GND).spec().thermalClass(), "a known token still parses");
            assertEquals(
                    ThermalClass.INERT,
                    g.getNode(A).spec().thermalClass(),
                    "an unknown thermal class degrades to INERT, not a crash");
        } finally {
            adapter.shutdown();
        }
    }

    @Test
    @DisplayName("Legacy v1/v2 files (no damage, no edges) still load with sane defaults")
    void legacyFilesStillLoad() throws Exception {
        Path file = tempDir.resolve("structures").resolve("legacy.dat");
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                """
                version:1
                worldId:legacy
                block:0,0,0,0.0,1.7976931348623157E308,true
                block:0,1,0,2.0,30.0,false
                block:0,2,0,2.0,30.0,false,1.5
                """);

        FilePersistenceAdapter adapter = new FilePersistenceAdapter(tempDir);
        adapter.initialize();
        try {
            Optional<StructureData> loaded = adapter.loadAsync("legacy").get();
            assertTrue(loaded.isPresent());
            StructureGraph g = StructureConverter.toGraph(loaded.get());
            assertEquals(3, g.size());
            assertEquals(0.0, g.getNode(A).damage(), 1e-9, "v1 blocks default to pristine");
            assertEquals(
                    ThermalClass.INERT,
                    g.getNode(A).spec().thermalClass(),
                    "a legacy block with no thermal field loads as INERT (no temperature softening)");
            assertEquals(1.5, g.getNode(B).reinforcement(), 1e-9, "v2 trailing reinforcement still parses");
            assertTrue(g.getNeighbors(A).contains(GND), "legacy files grid-derive their adjacency");
        } finally {
            adapter.shutdown();
        }
    }
}
