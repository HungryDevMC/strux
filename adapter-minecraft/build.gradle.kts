plugins {
    java
    id("com.gradleup.shadow") version "9.0.0-beta12"
}

// Ensure shadowJar runs as part of the standard build
tasks.build { dependsOn(tasks.shadowJar) }

// Relocate shaded Jackson so it can't clash with another plugin's copy on the same server.
tasks.shadowJar {
    // Public release artifact: StructuralIntegrity-<version>.jar (not
    // adapter-minecraft-...-all.jar)
    archiveBaseName.set("StructuralIntegrity")
    archiveClassifier.set("")
    // Keep the release jar lean: byte-buddy/asm ride in transitively (via Jackson's
    // graph) but the plugin's runtime — standard Jackson databind — never uses them.
    // Also drop bundled build metadata (poms) that has no business in a plugin jar.
    exclude("net/bytebuddy/**")
    exclude("META-INF/maven/**")
    relocate("com.fasterxml.jackson", "dev.gesp.structural.minecraft.shaded.jackson")
    // Relocate fastutil (pulled in transitively from :core) so it can't clash with
    // the server's or another plugin's copy. Pure rename — fastutil uses no reflection.
    relocate("it.unimi.dsi.fastutil", "dev.gesp.structural.minecraft.shaded.fastutil")
    // Merge duplicate service files from shaded dependencies
    mergeServiceFiles()
    // Exclude duplicate license/notice files that cause Paper's remapper to fail
    exclude("META-INF/LICENSE")
    exclude("META-INF/LICENSE.txt")
    exclude("META-INF/NOTICE")
    exclude("META-INF/NOTICE.txt")
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/") // WorldEdit + WorldGuard
    maven("https://maven.playpro.com") // CoreProtect
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/") // PlaceholderAPI
}

// Paper API version - use same version as MockBukkit for test compatibility
val paperVersion = "1.21.4-R0.1-SNAPSHOT"
val mockBukkitPaperVersion = "1.21.11-R0.1-SNAPSHOT"

dependencies {
    implementation(project(":core"))
    compileOnly("io.papermc.paper:paper-api:$paperVersion")
    // WorldEdit API (provided by the server at runtime; optional soft-dependency)
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.19")
    // WorldGuard - region/world physics gating (optional soft-dependency)
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.9")
    // CoreProtect - collapse logging for inspect/rollback (optional soft-dependency)
    compileOnly("net.coreprotect:coreprotect:22.4")
    // PlaceholderAPI - we subclass PlaceholderExpansion, so this one needs a compile dep
    // (optional soft-dependency; registered only when PlaceholderAPI is installed).
    compileOnly("me.clip:placeholderapi:2.11.6")
    // NOTE: Towny, Factions and Vault are hooked REFLECTIVELY (see minecraft.hook.*),
    // so they intentionally have no compile-time dependency here.
    // Jackson for the API persistence adapter. Shaded + relocated below so it never
    // collides with anything Paper or a sibling plugin may bundle.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")

    // Testing with MockBukkit (from Maven Central)
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.0")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.110.0")
    testImplementation("io.papermc.paper:paper-api:$mockBukkitPaperVersion")
    // PlaceholderAPI is compileOnly above, but StruxPlaceholders subclasses
    // PlaceholderExpansion, so its tests need the class on the test classpath.
    testImplementation("me.clip:placeholderapi:2.11.6")
    // ArchUnit enforces architectural invariants (adapters never depend on each other).
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}
