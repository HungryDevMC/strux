package dev.gesp.structural.minecraft.protect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.gesp.structural.minecraft.hook.WarZoneService;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/** The per-chunk protection seam that lets the weather sweep ask once per chunk. */
@DisplayName("Per-chunk protection verdict")
class ChunkVerdictTest {

    private ServerMock server;
    private WorldMock allowed;
    private WorldMock disabled;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        allowed = server.addSimpleWorld("on_world");
        disabled = server.addSimpleWorld("off_world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("and(): one veto denies; both-allow allows; otherwise per-block")
    void verdictAnd() {
        assertEquals(ChunkVerdict.ALL_DENIED, ChunkVerdict.ALL_DENIED.and(ChunkVerdict.ALL_ALLOWED));
        assertEquals(ChunkVerdict.ALL_DENIED, ChunkVerdict.ALL_ALLOWED.and(ChunkVerdict.ALL_DENIED));
        assertEquals(ChunkVerdict.ALL_ALLOWED, ChunkVerdict.ALL_ALLOWED.and(ChunkVerdict.ALL_ALLOWED));
        assertEquals(ChunkVerdict.PER_BLOCK, ChunkVerdict.ALL_ALLOWED.and(ChunkVerdict.PER_BLOCK));
        assertEquals(ChunkVerdict.PER_BLOCK, ChunkVerdict.PER_BLOCK.and(ChunkVerdict.ALL_ALLOWED));
    }

    @Test
    @DisplayName("master switch off → whole chunk allowed, no region work")
    void masterSwitchOff() {
        ProtectionService p = new NoopProtection(false, Set.of());
        assertEquals(ChunkVerdict.ALL_ALLOWED, p.chunkVerdict(allowed, 0, 0));
    }

    @Test
    @DisplayName("disabled world → whole chunk denied")
    void disabledWorldDenied() {
        ProtectionService p = new NoopProtection(true, Set.of("off_world"));
        assertEquals(ChunkVerdict.ALL_DENIED, p.chunkVerdict(disabled, 3, -7));
        // a world NOT in the list, with no region engine, is uniformly allowed
        assertEquals(ChunkVerdict.ALL_ALLOWED, p.chunkVerdict(allowed, 3, -7));
    }

    @Test
    @DisplayName("ALLOW_ALL war zone yields a uniform ALL_ALLOWED chunk")
    void warZonePassThrough() {
        assertEquals(ChunkVerdict.ALL_ALLOWED, WarZoneService.ALLOW_ALL.chunkVerdict(allowed, 1, 1));
    }

    @Test
    @DisplayName("CollapseGuard combines war + region: war veto short-circuits the region query")
    void guardCombines() {
        // A war zone that denies the whole chunk; the protection must NOT be consulted.
        boolean[] regionAsked = {false};
        WarZoneService deniedWar = new WarZoneService() {
            @Override
            public boolean destructionAllowed(Location loc) {
                return false;
            }

            @Override
            public ChunkVerdict chunkVerdict(World world, int cx, int cz) {
                return ChunkVerdict.ALL_DENIED;
            }

            @Override
            public String describe() {
                return "deny-all";
            }
        };
        ProtectionService probe = new ProtectionService() {
            @Override
            public boolean physicsAllowed(Location loc) {
                return true;
            }

            @Override
            public ChunkVerdict chunkVerdict(World world, int cx, int cz) {
                regionAsked[0] = true;
                return ChunkVerdict.ALL_ALLOWED;
            }

            @Override
            public String describe() {
                return "probe";
            }
        };
        CollapseGuard guard = new CollapseGuard(probe, deniedWar, NoopCollapseLogger.INSTANCE);

        assertEquals(ChunkVerdict.ALL_DENIED, guard.physicsAllowedInChunk(allowed, 0, 0));
        assertFalse(regionAsked[0], "a war-zone veto must short-circuit the region query");
    }

    @Test
    @DisplayName("CollapseGuard: ALLOW_ALL war + ALL_ALLOWED region → ALL_ALLOWED")
    void guardBothAllow() {
        ProtectionService allowAll = new NoopProtection(true, Set.of());
        CollapseGuard guard = new CollapseGuard(allowAll, WarZoneService.ALLOW_ALL, NoopCollapseLogger.INSTANCE);
        assertEquals(ChunkVerdict.ALL_ALLOWED, guard.physicsAllowedInChunk(allowed, 5, 9));
    }
}
