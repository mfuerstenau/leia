// A library for leia-tui, which calls the methods in-process through LeiaBackend (ADR 0034), and
// still the JSON-RPC server leia-server.jar for the Rust TUI until the port reaches parity.
plugins {
    id("leia.java-library-conventions")
    application
    alias(libs.plugins.shadow)
}

dependencies {
    // api: the protocol records are LeiaBackend's API; implementation keeps core and Lucene out of
    // the UI's compile classpath (AGENTS.md, hard rule 6).
    api(project(":leia-protocol"))
    implementation(project(":leia-core"))
    implementation(libs.dagger)
    annotationProcessor(libs.dagger.compiler)
}

// The server tests replay the shared request fixtures (see leia-protocol/build.gradle.kts).
val protocolDir = layout.settingsDirectory.dir("protocol")

tasks.test {
    inputs.dir(protocolDir).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("protocol")
    systemProperty("leia.protocol.dir", projectDir.toPath().relativize(protocolDir.asFile.toPath()).toString())
    useJUnitPlatform { excludeTags("jars") }
}

// The indexes and jars that leia-core's build writes, for the tests that go through LeiaBackend as
// the UI does: the Lucene 9 index (AGENTS.md, hard rule 5) and the demo index (docs/demo-index.md)
// for test, and the test codec jar, the indexes that need it and the analysis jars for jarsTest.
fun coreTestFiles(name: String, configuration: String) =
    configurations.create(name) { isCanBeConsumed = false }.also {
        dependencies.add(it.name, dependencies.project(mapOf("path" to ":leia-core", "configuration" to configuration)))
    }

val testIndexFiles = mapOf(
    "lucene9" to coreTestFiles("lucene9TestIndex", "lucene9TestIndexElements"),
    "demo" to coreTestFiles("demoIndex", "demoIndexElements"),
)
val jarsTestIndexFiles = mapOf(
    "custom-codec" to coreTestFiles("testCodecIndex", "testCodecIndexElements"),
    "custom-formats" to coreTestFiles("customFormatsIndex", "customFormatsIndexElements"),
)
val testCodecJar = coreTestFiles("testCodecJar", "testCodecJarElements")
val testAnalysisJars = coreTestFiles("testAnalysisJars", "testAnalysisJarsElements")

/** Passes each file as a system property, relative to the test's working directory (this project). */
fun Test.testFiles(properties: Map<String, FileCollection>) {
    for ((property, files) in properties) {
        inputs.files(files).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName(property)
    }
    val projectPath = projectDir.toPath()
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        properties.map { (property, files) -> "-D$property=" + projectPath.relativize(files.singleFile.toPath()) }
    })
}

tasks.test {
    testFiles(testIndexFiles.mapKeys { "leia.test.index.${it.key}" })
}

val jarsTest = tasks.register<Test>("jarsTest") {
    description = "Runs the tests that load jars, in a JVM of their own."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("jars") }
    testFiles(
        jarsTestIndexFiles.mapKeys { "leia.test.index.${it.key}" } +
            mapOf("leia.test.codec.jar" to testCodecJar, "leia.test.analysis.jars" to testAnalysisJars),
    )
}

tasks.check {
    dependsOn(jarsTest)
}

application {
    mainClass = "io.github.mfuerstenau.leia.server.LeiaServer"
}

// Version and build of the server, read at runtime by BuildInfo. The commit time (not the
// wall clock) keeps the output stable, so the task stays up to date between builds.
fun git(vararg args: String): Provider<String> =
    providers
        .exec {
            commandLine("git", *args)
            isIgnoreExitValue = true
        }
        .standardOutput
        .asText
        .map { it.trim().ifEmpty { "unknown" } }

val buildInfoDir = layout.buildDirectory.dir("generated/build-info")

val generateBuildInfo = tasks.register<WriteProperties>("generateBuildInfo") {
    destinationFile = buildInfoDir.map { it.file("io/github/mfuerstenau/leia/server/build-info.properties") }
    property("version", project.version.toString())
    property("commit", git("rev-parse", "--short", "HEAD"))
    property("commitTime", git("log", "-1", "--format=%cI"))
}

sourceSets.main {
    resources.srcDir(files(buildInfoDir) { builtBy(generateBuildInfo) })
}

// Lucene's analysis modules that Luke bundles and leia doesn't (ADR 0016). For a user who builds
// leia: ./gradlew :leia-server:analysisModules writes each module with its dependencies to
// build/analysis-modules/<module>/, a directory that `leia --jar` takes.
val analysisModules = listOf("icu", "kuromoji", "morfologik", "nori", "opennlp", "phonetic", "smartcn", "stempel")

val analysisModuleJars = analysisModules.associateWith { module ->
    configurations.create("${module}AnalysisModule") {
        isCanBeConsumed = false
        dependencies.add(project.dependencies.create("org.apache.lucene:lucene-analysis-$module:${libs.versions.lucene.get()}"))
        // In the server jar already.
        exclude(group = "org.apache.lucene", module = "lucene-core")
        exclude(group = "org.apache.lucene", module = "lucene-analysis-common")
    }
}

tasks.register<Sync>("analysisModules") {
    description = "Copies Lucene's analysis modules, each with its dependencies, for leia --jar."
    group = "leia"
    into(layout.buildDirectory.dir("analysis-modules"))
    for ((module, jars) in analysisModuleJars) {
        from(jars) { into(module) }
    }
}

tasks.shadowJar {
    archiveFileName = "leia-server.jar"
    // Lucene finds codecs, postings formats etc. via ServiceLoader; lucene-core and
    // lucene-backward-codecs both ship these files, so they must be merged, not overwritten.
    // Shadow only merges duplicates it gets to see, which needs INCLUDE (EXCLUDE drops codecs).
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    filesMatching(listOf("META-INF/LICENSE*", "META-INF/NOTICE*")) {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    manifest {
        attributes(
            "Multi-Release" to "true",
            // Lucene 10 calls native code (madvise) through the FFM API; without this, the JVM
            // prints a restricted-method warning to stderr.
            "Enable-Native-Access" to "ALL-UNNAMED",
        )
    }
}
