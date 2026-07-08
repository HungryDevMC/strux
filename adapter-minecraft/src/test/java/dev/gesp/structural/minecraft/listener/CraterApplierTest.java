package dev.gesp.structural.minecraft.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.minecraft.StructuralIntegrityPlugin;
import dev.gesp.structural.minecraft.hook.WarZoneService;
import dev.gesp.structural.minecraft.protect.ChunkVerdict;
import dev.gesp.structural.minecraft.protect.CollapseGuard;
import dev.gesp.structural.minecraft.protect.NoopCollapseLogger;
import dev.gesp.structural.minecraft.protect.NoopProtection;
import dev.gesp.structural.minecraft.visual.CollapseEffects;
import dev.gesp.structural.model.NodePos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

@DisplayName("CraterApplier: per-chunk verdicts are resolved per world")
class CraterApplierTest {

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** A war-zone service that denies one world's chunks and allows everything else. */
    private static WarZoneService deniesWorld(String deniedName) {
        return new WarZoneService() {
            @Override
            public boolean destructionAllowed(Location loc) {
                return true;
            }

            @Override
            public ChunkVerdict chunkVerdict(World world, int chunkX, int chunkZ) {
                return world.getName().equals(deniedName) ? ChunkVerdict.ALL_DENIED : ChunkVerdict.ALL_ALLOWED;
            }

            @Override
            public String describe() {
                return "test";
            }
        };
    }

    @Test
    @DisplayName("a denied world's blocks are not removed by an allowed world's cached chunk verdict")
    void crossWorldVerdictsDoNotLeak() {
        WorldMock allowed = server.addSimpleWorld("allowed");
        WorldMock denied = server.addSimpleWorld("denied");
        NodePos pos = new NodePos(5, 64, 5); // same packed chunk key (0,0) in BOTH worlds
        allowed.getBlockAt(5, 64, 5).setType(Material.STONE);
        denied.getBlockAt(5, 64, 5).setType(Material.STONE);

        // physicsAllowedInChunk → ALL_ALLOWED for "allowed", ALL_DENIED for "denied".
        CollapseGuard guard = new CollapseGuard(
                new NoopProtection(true, Set.of()), deniesWorld("denied"), NoopCollapseLogger.INSTANCE);

        Map<World, List<NodePos>> removed = new HashMap<>();
        CraterBlockRemover remover = new CraterBlockRemover() {
            @Override
            public void removeToAir(World world, List<NodePos> approved) {
                removed.computeIfAbsent(world, w -> new ArrayList<>()).addAll(approved);
            }

            @Override
            public String describe() {
                return "capturing test remover";
            }
        };

        CraterApplier applier = new CraterApplier(
                guard, new CollapseEffects(plugin.getEffectsConfig(), plugin), new DebrisVisuals(plugin), remover, 8);

        // Both blasts share chunk (0,0); they drain in one pass. A chunk-only verdict cache
        // would reuse the first-seen world's verdict for the other (protection bypass).
        applier.enqueue(allowed, List.of(pos), 0);
        applier.enqueue(denied, List.of(pos), 0);
        applier.drainUpTo(100);

        assertEquals(List.of(pos), removed.get(allowed), "the allowed world's block is removed");
        assertTrue(
                removed.getOrDefault(denied, List.of()).isEmpty(),
                "the denied world's block is NOT removed — its own ALL_DENIED verdict applies, not the allowed world's");
    }
}
