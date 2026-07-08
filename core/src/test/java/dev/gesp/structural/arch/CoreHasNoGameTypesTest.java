package dev.gesp.structural.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Architecture invariants for the core module.
 *
 * <p>The hard rule from DESIGN.md is that {@code core/} is pure physics — it never depends on
 * any game-specific type. Adapters depend on core; core never depends on an adapter. This
 * test makes that mechanical: if anyone ever adds e.g. {@code io.papermc.paper:paper-api}
 * to core's runtime classpath and references {@code org.bukkit.X} from a core class, this
 * test fails on the very next run.
 *
 * <p>Production sources only — tests are scanned via {@link ImportOption.DoNotIncludeTests}.
 */
@AnalyzeClasses(packages = "dev.gesp.structural", importOptions = ImportOption.DoNotIncludeTests.class)
class CoreHasNoGameTypesTest {

    @ArchTest
    static final ArchRule core_classes_must_not_depend_on_game_specific_types = noClasses()
            .that()
            .resideInAPackage("dev.gesp.structural..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.bukkit..", // Minecraft / Bukkit API
                    "io.papermc..", // Paper API
                    "com.destroystokyo.paper..", // Paper legacy
                    "com.hypixel.hytale..", // Hytale server API
                    "com.sk89q..", // WorldEdit / WorldGuard
                    "net.coreprotect..", // CoreProtect
                    "org.openjdk.jmh.." // JMH (benchmarks only)
                    )
            .because("core is pure physics — adapters depend on core; core never depends on an adapter. "
                    + "If you need this dependency, the code belongs in an adapter module instead.");

    /**
     * Complements the game-types ban above: core must not reach into an adapter <em>module</em>
     * either. The game-types rule catches {@code org.bukkit..} etc.; this one catches a core class
     * importing {@code dev.gesp.structural.minecraft..} (or any sibling adapter package) — a
     * dependency direction that would invert the whole architecture even though no third-party
     * game type is involved.
     */
    @ArchTest
    static final ArchRule core_classes_must_not_depend_on_any_adapter = noClasses()
            .that()
            .resideInAPackage("dev.gesp.structural..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "dev.gesp.structural.minecraft..", // Minecraft adapter
                    "dev.gesp.structural.hytale..", // Hytale adapter
                    "dev.gesp.structural.prefab..") // prefab adapter
            .because("adapters depend on core; core never depends on an adapter. "
                    + "The dependency arrow points one way only.");
}
