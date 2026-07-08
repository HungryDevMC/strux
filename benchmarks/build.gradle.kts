plugins { java }

// ─────────────────────────────────────────────────────────────────────────────
//  JMH WALL-CLOCK BENCHMARKS  (the "tune this" half of the perf story)
//
//  Deterministic counters in :core's PerformanceGateTest catch ALGORITHMIC
//  regressions (more passes / more node visits) on any machine. JMH measures the
//  real ns/op cost PER pass — the thing you tune. Run it on demand, compare to a
//  committed baseline:
//
//      ./gradlew :benchmarks:jmh                 # all benchmarks -> build/results/jmh/results.json
//      ./gradlew :benchmarks:jmh -PjmhArgs="Cascade"   # only matching benchmarks
//
//  We wire JMH by hand (jmh-core + its annotation processor + a JavaExec runner)
//  rather than via the me.champeau.jmh Gradle plugin, because this repo is on
//  Gradle 9 where that plugin's compatibility is not guaranteed. Same JMH, fewer
//  moving parts.
// ─────────────────────────────────────────────────────────────────────────────

val jmhVersion = "1.37"

dependencies {
    implementation(project(":core"))
    implementation("org.openjdk.jmh:jmh-core:$jmhVersion")
    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:$jmhVersion")
}

tasks.register<JavaExec>("jmh") {
    group = "benchmark"
    description = "Run JMH benchmarks (optional regex filter via -PjmhArgs=\"...\")."
    mainClass.set("org.openjdk.jmh.Main")
    classpath = sourceSets["main"].runtimeClasspath

    val resultsFile = layout.buildDirectory.file("results/jmh/results.json").get().asFile
    doFirst { resultsFile.parentFile.mkdirs() }

    // JSON output so a run can be diffed against benchmarks/baseline.json.
    args("-rf", "json", "-rff", resultsFile.absolutePath)
    (project.findProperty("jmhArgs") as String?)
        ?.takeIf { it.isNotBlank() }
        ?.let { args(it.trim().split("\\s+".toRegex())) }
}

// ─────────────────────────────────────────────────────────────────────────────
//  HUMANIZE  — rewrite the JMH result names into plain English before publishing
//  the chart. Also self-tested here so `check` guards the script (no python test
//  infra in the repo; the script ships a stdlib-only --self-test mode).
// ─────────────────────────────────────────────────────────────────────────────
// Resolve the interpreter at configuration time from fixed locations — a bare
// "python3" Exec dies with "Cannot run program" on JDK-only environments (docker
// images, some CI daemons) and takes the whole `check` down with it. No python →
// the self-test skips with a warning; the benchmark pipeline that actually runs
// the script provides its own python.
val python3: String? =
    listOf("/usr/bin/python3", "/opt/homebrew/bin/python3", "/usr/local/bin/python3").firstOrNull {
        file(it).canExecute()
    }

if (python3 != null) {
    tasks.register<Exec>("humanizeBenchmarkNamesSelfTest") {
        group = "verification"
        description = "Runs humanize-results.py --self-test (guards the JMH name-humanizer)."
        val script = layout.projectDirectory.file("scripts/humanize-results.py").asFile
        inputs.file(script)
        inputs.file(layout.projectDirectory.file("display-names.json"))
        commandLine(python3, script.absolutePath, "--self-test")
    }
    tasks.named("check") { dependsOn("humanizeBenchmarkNamesSelfTest") }
} else {
    logger.warn("benchmarks: python3 not found — skipping the humanize-results self-test wiring")
}
