// A library for leia-tui, which calls the methods in-process through LeiaBackend (ADR 0034).
plugins {
    id("leia.java-library-conventions")
}

dependencies {
    // api: the protocol records are LeiaBackend's API; implementation keeps core and Lucene out of
    // the UI's compile classpath (AGENTS.md, hard rule 6).
    api(project(":leia-protocol"))
    implementation(project(":leia-core"))
    // The interface to the runtimes for indexes that Lucene 10 can't read (ADR 0044).
    implementation(project(":leia-runtime"))
    // Lucene 3.6, 5.5, 7.7 and 9.12, relocated, for indexes created by Lucene 2.x to 8.x: found through
    // ServiceLoader, and the shadow jars only, so an unrelocated old Lucene never meets Lucene 10 on
    // the classpath. For the tests only: test reaches them through the classpath, jarsTest as loaded
    // jars, and leia through runtimes/ beside leia.jar, where leia-tui puts them (ADR 0044).
    testRuntimeOnly(project(mapOf("path" to ":leia-lucene3", "configuration" to "shadowRuntimeElements")))
    testRuntimeOnly(project(mapOf("path" to ":leia-lucene5", "configuration" to "shadowRuntimeElements")))
    testRuntimeOnly(project(mapOf("path" to ":leia-lucene7", "configuration" to "shadowRuntimeElements")))
    testRuntimeOnly(project(mapOf("path" to ":leia-lucene9", "configuration" to "shadowRuntimeElements")))
    implementation(libs.dagger)
    annotationProcessor(libs.dagger.compiler)
}

tasks.test {
    useJUnitPlatform { excludeTags("jars") }
}

fun runtimeTestFiles(runtime: String, name: String, configuration: String) =
    configurations.create(name) { isCanBeConsumed = false }.also {
        dependencies.add(it.name, dependencies.project(mapOf("path" to ":$runtime", "configuration" to configuration)))
    }

fun lucene3TestFiles(name: String, configuration: String) = runtimeTestFiles("leia-lucene3", name, configuration)

fun lucene5TestFiles(name: String, configuration: String) = runtimeTestFiles("leia-lucene5", name, configuration)

fun lucene7TestFiles(name: String, configuration: String) = runtimeTestFiles("leia-lucene7", name, configuration)

fun lucene9TestFiles(name: String, configuration: String) = runtimeTestFiles("leia-lucene9", name, configuration)

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
    // Written by leia-lucene3's build, read with the relocated Lucene 3.6 (ADR 0044).
    "lucene24" to lucene3TestFiles("lucene24TestIndex", "lucene24TestIndexElements"),
    "lucene3" to lucene3TestFiles("lucene3TestIndex", "lucene3TestIndexElements"),
    // Written by leia-lucene5's build, read with the relocated Lucene 5.5 (ADR 0046).
    "lucene4" to lucene5TestFiles("lucene4TestIndex", "lucene4TestIndexElements"),
    "lucene5" to lucene5TestFiles("lucene5TestIndex", "lucene5TestIndexElements"),
    // Written by leia-lucene7's build, read with the relocated Lucene 7.7 (ADR 0048).
    "lucene6" to lucene7TestFiles("lucene6TestIndex", "lucene6TestIndexElements"),
    "lucene7" to lucene7TestFiles("lucene7TestIndex", "lucene7TestIndexElements"),
    // Written by leia-lucene9's build, read with the relocated Lucene 9.12 (ADR 0049); leia-core's
    // own lucene8 stays for its tests that Lucene 10 refuses it.
    "lucene8" to lucene9TestFiles("lucene8TestIndex", "lucene8TestIndexElements"),
    "lucene8to9" to lucene9TestFiles("lucene8to9TestIndex", "lucene8to9TestIndexElements"),
)
val jarsTestIndexFiles = mapOf(
    "custom-codec" to coreTestFiles("testCodecIndex", "testCodecIndexElements"),
    "custom-formats" to coreTestFiles("customFormatsIndex", "customFormatsIndexElements"),
    "lucene24" to testIndexFiles.getValue("lucene24"),
    "lucene3" to testIndexFiles.getValue("lucene3"),
    "lucene4" to testIndexFiles.getValue("lucene4"),
    "lucene5" to testIndexFiles.getValue("lucene5"),
    "lucene6" to testIndexFiles.getValue("lucene6"),
    "lucene7" to testIndexFiles.getValue("lucene7"),
    "lucene8" to testIndexFiles.getValue("lucene8"),
    "lucene8to9" to testIndexFiles.getValue("lucene8to9"),
)
val testCodecJar = coreTestFiles("testCodecJar", "testCodecJarElements")
val testAnalysisJars = coreTestFiles("testAnalysisJars", "testAnalysisJarsElements")
// The runtime jars, which jarsTest loads as leia loads runtimes/, instead of from the classpath.
val lucene3RuntimeJar = lucene3TestFiles("lucene3RuntimeJar", "shadowRuntimeElements").apply { isTransitive = false }
val lucene5RuntimeJar = lucene5TestFiles("lucene5RuntimeJar", "shadowRuntimeElements").apply { isTransitive = false }
val lucene7RuntimeJar = lucene7TestFiles("lucene7RuntimeJar", "shadowRuntimeElements").apply { isTransitive = false }
val lucene9RuntimeJar = lucene9TestFiles("lucene9RuntimeJar", "shadowRuntimeElements").apply { isTransitive = false }

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
    classpath = sourceSets.test.get().runtimeClasspath - lucene3RuntimeJar - lucene5RuntimeJar - lucene7RuntimeJar -
        lucene9RuntimeJar
    useJUnitPlatform { includeTags("jars") }
    testFiles(
        jarsTestIndexFiles.mapKeys { "leia.test.index.${it.key}" } +
            mapOf(
                "leia.test.codec.jar" to testCodecJar,
                "leia.test.analysis.jars" to testAnalysisJars,
                "leia.test.runtime.lucene3" to lucene3RuntimeJar,
                "leia.test.runtime.lucene5" to lucene5RuntimeJar,
                "leia.test.runtime.lucene7" to lucene7RuntimeJar,
                "leia.test.runtime.lucene9" to lucene9RuntimeJar,
            ),
    )
}

tasks.check {
    dependsOn(jarsTest)
}

// Version and build of leia, read at runtime by BuildInfo. The commit time (not the
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
        // In leia.jar already.
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
