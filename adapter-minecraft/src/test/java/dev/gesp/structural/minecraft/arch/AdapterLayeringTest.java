package dev.gesp.structural.minecraft.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Architecture invariants for the Minecraft adapter.
 *
 * <p>Layering rule: game adapters depend on {@code core} but <b>not on each other</b>.
 * This locks that boundary mechanically so
 * a later refactor that accidentally reaches into a sibling adapter fails on the next test run
 * instead of slipping through code review.
 *
 * <p>The rule matches by the <em>referenced</em> package name in the bytecode, so it holds even
 * though the sibling adapters are not on this module's test classpath (they never are — that is
 * the whole point of the boundary).
 *
 * <p>Production sources only — tests are scanned via {@link ImportOption.DoNotIncludeTests}.
 */
@AnalyzeClasses(packages = "dev.gesp.structural.minecraft", importOptions = ImportOption.DoNotIncludeTests.class)
class AdapterLayeringTest {

    @ArchTest
    static final ArchRule minecraft_must_not_depend_on_sibling_adapters = noClasses()
            .that()
            .resideInAPackage("dev.gesp.structural.minecraft..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "dev.gesp.structural.hytale..", // sibling adapter — Hytale
                    "dev.gesp.structural.prefab..") // sibling adapter — prefab
            .because("adapters depend on core, never on each other; shared logic belongs in core "
                    + "(adapters must stay independent of each other).");
}
