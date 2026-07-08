plugins {
    java
    jacoco
    id("com.diffplug.spotless") version "7.0.2"
    id("info.solidsoft.pitest") version "1.19.0" apply false
}

allprojects {
    group = "dev.gesp"
    version = "1.0.0"

    repositories { mavenCentral() }
}

// Root-level formatting: Kotlin DSL build scripts, plain text/config files, hand-authored JSON.
// Run `./gradlew spotlessApply` to fix; `spotlessCheck` runs as part of `build`.
spotless {
    kotlinGradle {
        target("*.gradle.kts", "**/*.gradle.kts")
        ktfmt().kotlinlangStyle() // 4-space indent — matches the rest of the codebase
    }
    format("misc") {
        target(
            "*.md",
            "**/*.md",
            "*.yml",
            "**/*.yml",
            "*.yaml",
            "**/*.yaml",
            ".gitignore",
            ".gitattributes",
            ".editorconfig",
        )
        targetExclude("**/build/**", "**/.gradle/**", "**/node_modules/**", "docker/data/**")
        trimTrailingWhitespace()
        endWithNewline()
    }
    json {
        // Hand-authored JSON only. JMH writes `benchmarks/baseline.json`; the docker/
        // tree is server runtime state, not ours to format.
        target("adapter-*/src/**/*.json")
        targetExclude("**/build/**", "**/.gradle/**")
        gson().indentWithSpaces(2)
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "jacoco")

    // 4-space, blame-friendly Java formatting (matches the existing codebase).
    // `toggleOffOn()` lets you exempt sections with `// spotless:off ... // spotless:on`.
    // Palantir enforces its own fixed import order, so we don't add `importOrder(...)`.
    // The wildcard-import ban is enforced by the `checkNoWildcardImports` task below
    // (spotless's `custom { ... }` step needs a Serializable lambda, which Kotlin DSL
    // closures aren't — a standalone task is cleaner).
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            target("src/**/*.java")
            toggleOffOn()
            removeUnusedImports()
            palantirJavaFormat()
        }
    }

    // Forbid non-static wildcard imports. Static wildcards (e.g.
    // `import static org.junit.jupiter.api.Assertions.*;`) are allowed — they're
    // idiomatic for test assertions.
    tasks.register("checkNoWildcardImports") {
        group = "verification"
        description = "Fails if any Java source contains a non-static wildcard import."
        // Capture plain Files at configuration time — calling fileTree()/rootDir inside
        // doLast captures the Project, which the configuration cache cannot serialize.
        val srcDir = projectDir.resolve("src")
        val rootPath = rootDir
        onlyIf { srcDir.exists() }
        doLast {
            val regex = Regex("""^import\s+(?!static\s+)[\w.]+\.\*;""", RegexOption.MULTILINE)
            val offenders = mutableListOf<String>()
            srcDir
                .walkTopDown()
                .filter { it.isFile && it.extension == "java" }
                .forEach { file ->
                    regex.findAll(file.readText()).forEach { match ->
                        offenders.add("  ${file.relativeTo(rootPath).path}: ${match.value.trim()}")
                    }
                }
            if (offenders.isNotEmpty()) {
                throw GradleException(
                    "Wildcard imports are forbidden — expand to specific class imports.\n" +
                        offenders.joinToString("\n")
                )
            }
        }
    }
    tasks.matching { it.name == "check" }.configureEach { dependsOn("checkNoWildcardImports") }

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    tasks.withType<JavaCompile> { options.encoding = "UTF-8" }

    tasks.withType<Test> {
        useJUnitPlatform()

        // Regenerate golden snapshots in-place when run with -Pupdate-snapshots
        // (see dev.gesp.structural.scenario.Snapshots).
        systemProperty("strux.updateSnapshots", project.hasProperty("update-snapshots").toString())

        // Surface the [perf]/[snapshot] INFO lines our scenario tests print.
        testLogging {
            showStandardStreams = true
            events("passed", "skipped", "failed")
        }
    }

    // JaCoCo coverage. `jacocoTestReport` runs after tests; we wire it into `check`
    // so `./gradlew check` produces an HTML + XML report under build/reports/jacoco/.
    tasks.withType<JacocoReport>().configureEach {
        dependsOn(tasks.withType<Test>())
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }
    tasks
        .matching { it.name == "check" }
        .configureEach { dependsOn(tasks.matching { it.name == "jacocoTestReport" }) }
}

// ─────────────────────────────────────────────────────────────────────────────
//  AGGREGATED COVERAGE
//
//  `./gradlew codeCoverageReport` merges every subproject's JaCoCo execution
//  data into a single report under build/reports/jacoco/aggregate/. CI converts
//  the XML to Cobertura so the GitLab MR widget can show coverage % + per-file
//  diff vs main; the regex in .gitlab-ci.yml scrapes the total for the badge.
// ─────────────────────────────────────────────────────────────────────────────
val testedSubprojects = subprojects.filter { it.file("src/test/java").exists() }

tasks.register<JacocoReport>("codeCoverageReport") {
    group = "verification"
    description = "Aggregated JaCoCo coverage across all subprojects that have tests."

    dependsOn(testedSubprojects.map { it.tasks.named("test") })

    executionData.setFrom(
        files(testedSubprojects.map { it.layout.buildDirectory.file("jacoco/test.exec") })
    )
    sourceDirectories.setFrom(files(testedSubprojects.map { it.file("src/main/java") }))
    classDirectories.setFrom(
        files(testedSubprojects.map { it.layout.buildDirectory.dir("classes/java/main") })
    )

    reports {
        xml.required.set(true)
        html.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/aggregate/jacoco.xml"))
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/aggregate/html"))
    }
}

// `check` also produces the AGGREGATE coverage XML
// (build/reports/jacoco/aggregate/jacoco.xml) used for diff-coverage. Wire the root
// `check` to produce it, so a plain `check` leaves the report available.
tasks.named("check") { dependsOn("codeCoverageReport") }

// PITest mutation testing. When run per-change, pass -Ppit.targetClasses with only
// the classes the working tree changed; the threshold defaults to 0 here so Gradle
// never aborts mid-report.
//
// Applied only to the TESTED modules (those with src/test/java): core and
// adapter-minecraft. benchmarks has no tests, so there is nothing to mutate.
val pitestModules =
    mapOf("core" to "dev.gesp.structural", "adapter-minecraft" to "dev.gesp.structural.minecraft")

pitestModules.forEach { (moduleName, basePackage) ->
    project(":$moduleName") {
        apply(plugin = "info.solidsoft.pitest")
        configure<info.solidsoft.gradle.pitest.PitestPluginExtension> {
            junit5PluginVersion.set("1.2.1")
            // PIT minions do NOT inherit the test task's heap; mirror it here or the
            // coverage phase dies with a dead minion.
            jvmArgs.set(listOf("-Xmx2g"))
            // Incremental analysis: only re-test mutants invalidated by code/test
            // changes. First run pays full price; reruns drop to seconds.
            enableDefaultIncrementalAnalysis.set(true)
            targetClasses.set(
                (project.findProperty("pit.targetClasses") as String?)?.split(",")?.filter {
                    it.isNotBlank()
                } ?: listOf("$basePackage.*")
            )
            // PIT derives targetTests from targetClasses by default; when a per-change run
            // narrows targetClasses to a few FQCNs, no tests match and every mutant is
            // NO_COVERAGE. Pin the whole suite eligible so the mutants get exercised.
            // Must match TEST classes only (*Test*): a bare package wildcard makes PIT's
            // JUnit5 discovery try main classes as test containers and the coverage
            // minion dies.
            targetTests.set(listOf("$basePackage.*Test*"))
            // Cap minion parallelism by MEMORY, not cores: each minion gets -Xmx2g,
            // so 12 at once (~24g+) drove the machine into swap and a normally
            // seconds-long mutation pass took hours (one run even died with
            // MEMORY_ERROR). 4 minions (~8g) fit comfortably and run flat out.
            threads.set(minOf(4, Runtime.getRuntime().availableProcessors()))
            mutationThreshold.set((project.findProperty("pit.threshold") as String?)?.toInt() ?: 0)
            timestampedReports.set(false)
            outputFormats.set(listOf("XML", "HTML"))
        }
    }
}
