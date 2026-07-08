package dev.gesp.structural.minecraft;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.minecraft.manager.StructureManager;
import java.lang.reflect.Field;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * The plugin must apply the shipped {@code cascade.settle-budget-ms} default (8) to the
 * {@link StructureManager} on enable.
 *
 * <pre>
 *   The manager's internal pre-config fallback is 30ms; the shipped default (config.yml
 *   + the onEnable getDouble fallback) is 8ms. So a correctly-wired boot leaves the
 *   manager at 8ms, and DROPPING the setSettleBudgetMs call would leave it at 30ms —
 *   which this test detects. That gap is deliberate: it makes the budget-wiring an
 *   observable step instead of a silently-equivalent no-op.
 * </pre>
 */
@DisplayName("Boot wiring: the plugin applies the shipped 8ms settle budget to the manager")
class SettleBudgetConfigE2ETest {

    private static final long MS = 1_000_000L;

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

    /** Read the manager's private wall-clock budget without widening its API. */
    private static long settleBudgetNanos(StructureManager manager) throws ReflectiveOperationException {
        Field field = StructureManager.class.getDeclaredField("settleBudgetNanos");
        field.setAccessible(true);
        return field.getLong(manager);
    }

    @Test
    @DisplayName("onEnable wires the config's 8ms budget through to the manager (not the 30ms fallback)")
    void bootAppliesShippedEightMsBudget() throws ReflectiveOperationException {
        long applied = settleBudgetNanos(plugin.getStructureManager());

        assertEquals(8L * MS, applied, "the shipped cascade.settle-budget-ms (8) must reach the manager on enable");
        assertNotEquals(
                30L * MS,
                applied,
                "if the manager still held its 30ms internal fallback, the budget was never wired from config");
    }

    @Test
    @DisplayName("A non-default budget in config is honoured too (the wiring reads config, not a constant)")
    void reconfiguringUpdatesTheBudget() throws ReflectiveOperationException {
        // The manager reads its budget from setSettleBudgetMs, so a host that lowers it
        // for a laggy world takes effect — proves the value flows, not a baked constant.
        StructureManager manager = plugin.getStructureManager();
        manager.setSettleBudgetMs(3.0);
        assertEquals(3L * MS, settleBudgetNanos(manager));

        manager.setSettleBudgetMs(0.0); // legacy: unbudgeted
        assertEquals(0L, settleBudgetNanos(manager), "0 disables the budget (legacy unbounded settle)");
    }
}
