plugins { java }

dependencies {
    // fastutil: primitive/open-addressing collections for the solver hot path.
    // Object2IntOpenHashMap removes the Integer boxing on the per-node distance
    // lookups (the solver's single hottest read), and the open-addressing maps
    // drop the per-entry node allocation of java.util.HashMap. Adapters that
    // shade core relocate+minimize this so the plugin jar stays small.
    implementation("it.unimi.dsi:fastutil-core:8.5.18")

    // Testing only
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // ArchUnit enforces architectural invariants (e.g. no game-specific types in core).
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}
