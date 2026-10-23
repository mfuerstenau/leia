// A library for leia-tui, which calls the methods in-process through LeiaBackend (ADR 0034).
import io.github.mfuerstenau.leia.buildlogic.RUNTIME_MODULES
import io.github.mfuerstenau.leia.buildlogic.RuntimeCatalogCheck
import io.github.mfuerstenau.leia.buildlogic.runtimeFolder
import io.github.mfuerstenau.leia.buildlogic.runtimeFolderJars

plugins {
    id("leia.java-library-conventions")
}

dependencies {
    // api: the protocol records are LeiaBackend's API. Neither leia-core nor Lucene is on the compile
    // classpath: every index is read through a runtime (ADR 0071, AGENTS.md, hard rule 6), which
    // noLuceneCheck below makes sure of.
    api(project(":leia-protocol"))
    // The interface to the runtimes, which read every index (ADR 0044, ADR 0071).
    implementation(project(":leia-runtime"))
    // Lucene 10 behind the runtime interface, which reads the indexes of Lucene 9 and 10 and those of
    // an unknown version (ADR 0071): for the tests only, since leia loads runtimes/lucene10 (ADR 0072,
    // ADR 0073). On the test classpath, for the tests that open indexes through Lucene10Runtime
    // directly and those that write indexes. The old runtimes are never on it: the tests load their
    // folders as leia does (testRuntimes below).
    testImplementation(project(":leia-lucene10"))
    testImplementation(project(":leia-core"))
    implementation(libs.dagger)
    annotationProcessor(libs.dagger.compiler)
}

tasks.test {
    useJUnitPlatform { excludeTags("jars", "runtimeLoad") }
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
    // Without its jar, for the parity of a missing codec (ADR 0070).
    "custom-codec" to coreTestFiles("testCodecIndex", "testCodecIndexElements"),
    // Written by leia-lucene3's build, read with its Lucene 3.6 (ADR 0044).
    "lucene24" to lucene3TestFiles("lucene24TestIndex", "lucene24TestIndexElements"),
    "lucene3" to lucene3TestFiles("lucene3TestIndex", "lucene3TestIndexElements"),
    // Written by leia-lucene5's build, read with its Lucene 5.5 (ADR 0046).
    "lucene4" to lucene5TestFiles("lucene4TestIndex", "lucene4TestIndexElements"),
    "lucene5" to lucene5TestFiles("lucene5TestIndex", "lucene5TestIndexElements"),
    // Written by leia-lucene7's build, read with its Lucene 7.7 (ADR 0048).
    "lucene6" to lucene7TestFiles("lucene6TestIndex", "lucene6TestIndexElements"),
    "lucene7" to lucene7TestFiles("lucene7TestIndex", "lucene7TestIndexElements"),
    // Written by leia-lucene9's build, read with its Lucene 9.12 (ADR 0049); leia-core's
    // own lucene8 stays for its tests that Lucene 10 refuses it.
    "lucene8" to lucene9TestFiles("lucene8TestIndex", "lucene8TestIndexElements"),
    "lucene8to9" to lucene9TestFiles("lucene8to9TestIndex", "lucene8to9TestIndexElements"),
)
val jarsTestIndexFiles = mapOf(
    // Opened through the loaded runtimes/lucene10, and before it is loaded, not at all (ADR 0072).
    "lucene9" to testIndexFiles.getValue("lucene9"),
    "demo" to testIndexFiles.getValue("demo"),
    "custom-codec" to testIndexFiles.getValue("custom-codec"),
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

// The runtimes' folders, as leia-tui puts them into runtimes/ beside leia.jar (ADR 0073): those of
// the old runtimes for test, whose classpath has Lucene 10, and all five for jarsTest, whose
// classpath has none.
val runtimeFolders = RUNTIME_MODULES.associateWith { runtimeFolderJars(it) }

fun runtimesSync(name: String, modules: List<String>) = tasks.register<Sync>(name) {
    description = "Puts the runtimes' folders together for the tests."
    into(layout.buildDirectory.dir(name))
    for (module in modules) {
        from(runtimeFolders.getValue(module)) { into(runtimeFolder(module)) }
    }
}

val testRuntimes = runtimesSync("testRuntimes", RUNTIME_MODULES - "leia-lucene10")
val jarsTestRuntimes = runtimesSync("jarsTestRuntimes", RUNTIME_MODULES)
// The leia-lucene10 jar with leia-core and Lucene 10, which jarsTest leaves out of its classpath, so
// that Lucene 10 comes only from the loaded runtimes/lucene10, as in leia (ADR 0072).
val lucene10World = configurations.create("lucene10World") { isCanBeConsumed = false }.also {
    dependencies.add(it.name, dependencies.project(mapOf("path" to ":leia-lucene10")))
}

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
    // docs/runtimes.md, whose feature table RuntimeFeaturesTest checks against the runtimes, and the
    // user README of the distribution, whose condensed table it checks against that one.
    testFiles(
        testIndexFiles.mapKeys { "leia.test.index.${it.key}" } +
            mapOf(
                "leia.test.doc.runtimes" to files(rootProject.file("docs/runtimes.md")),
                "leia.test.doc.distReadme" to files(rootProject.file("leia-tui/src/dist/README.md")),
                "leia.test.runtimes" to files(testRuntimes),
            ),
    )
}

val jarsTest = tasks.register<Test>("jarsTest") {
    description = "Runs the tests that load jars, in a JVM of their own."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath - lucene10World
    // The other test classes name Lucene types, which this classpath doesn't have.
    include("**/JarsBackendTest.class")
    useJUnitPlatform { includeTags("jars") }
    testFiles(
        jarsTestIndexFiles.mapKeys { "leia.test.index.${it.key}" } +
            mapOf(
                "leia.test.codec.jar" to testCodecJar,
                "leia.test.analysis.jars" to testAnalysisJars,
                "leia.test.runtimes" to files(jarsTestRuntimes),
            ),
    )
}

val runtimeLoadTest = tasks.register<Test>("runtimeLoadTest") {
    description = "Runs the tests that load runtimes while leia runs, each class in a JVM of its own."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath - lucene10World
    // The runtimes' folders are loaded once per JVM (ADR 0080).
    forkEvery = 1
    include("**/RuntimeLoadBackendTest.class", "**/RuntimeRestartBackendTest.class")
    useJUnitPlatform { includeTags("runtimeLoad") }
    testFiles(
        mapOf(
            "leia.test.index.lucene8" to testIndexFiles.getValue("lucene8"),
            "leia.test.index.lucene9" to testIndexFiles.getValue("lucene9"),
            "leia.test.runtimes" to files(jarsTestRuntimes),
        ),
    )
}

// Hard rule 6 of AGENTS.md: the server reaches Lucene only through the runtime interface (ADR 0071),
// so neither leia-core nor a Lucene artifact may be on its compile classpath; nor on its runtime
// classpath, since leia.jar takes every Lucene from runtimes/ (ADR 0072, ADR 0073).
val noLuceneCheck = tasks.register("noLuceneCheck") {
    description = "Checks that leia-core and Lucene are on neither the compile nor the runtime classpath."
    group = "verification"
    val classpaths = listOf(configurations.compileClasspath, configurations.runtimeClasspath)
    val resolved = classpaths.map { classpath ->
        classpath.name to classpath.flatMap { it.incoming.resolutionResult.rootComponent }
    }
    classpaths.forEach { inputs.files(it) }
    doLast {
        fun walk(
            component: org.gradle.api.artifacts.result.ResolvedComponentResult,
            seen: MutableSet<Any>,
            found: MutableList<String>,
        ) {
            if (!seen.add(component.id)) return
            val id = component.id
            val name = when (id) {
                is org.gradle.api.artifacts.component.ProjectComponentIdentifier -> id.projectPath
                is org.gradle.api.artifacts.component.ModuleComponentIdentifier -> "${id.group}:${id.module}"
                else -> id.displayName
            }
            if (name == ":leia-core" || name == ":leia-lucene10" || name.startsWith("org.apache.lucene:")) {
                found += name
            }
            component.dependencies
                .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                .forEach { walk(it.selected, seen, found) }
        }
        for ((classpath, root) in resolved) {
            val found = mutableListOf<String>()
            walk(root.get(), mutableSetOf(), found)
            check(found.isEmpty()) { "leia-server's $classpath has $found (AGENTS.md, hard rule 6)" }
        }
    }
}

// The runtime catalog names the folders that the build puts into runtimes/, Lucene 10's first (ADR
// 0078).
val runtimeCatalogCheck = tasks.register<RuntimeCatalogCheck>("runtimeCatalogCheck") {
    description = "Checks that the runtime catalog names the folders of the runtime modules."
    group = "verification"
    catalog = layout.projectDirectory.file("src/main/resources/META-INF/leia/runtimes.json")
    folders = RUNTIME_MODULES.map { runtimeFolder(it) }
    latest = runtimeFolder("leia-lucene10")
    report = layout.buildDirectory.file("reports/runtimeCatalogCheck.txt")
}

tasks.check {
    dependsOn(jarsTest, runtimeLoadTest, noLuceneCheck, runtimeCatalogCheck)
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
        // In runtimes/lucene10 already (ADR 0072, ADR 0073).
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
