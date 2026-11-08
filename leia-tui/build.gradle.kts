import io.github.mfuerstenau.leia.buildlogic.registerRuntimeAdapters
import io.github.mfuerstenau.leia.buildlogic.registerTestLucene10Folder
import io.github.mfuerstenau.leia.buildlogic.registerTestRuntimes
import io.github.mfuerstenau.leia.buildlogic.runtimeFolders

// The TUI (ADR 0034): the screens, on TamboUI, over leia-client and the backend (ADR 0126). leia-app
// starts it, and builds leia.jar.
plugins {
    id("leia.java-library-conventions")
}

dependencies {
    // The backend, called in-process (ADR 0034). Neither core nor Lucene is on the classpath: Lucene
    // 10 comes from runtimes/lucene10 like every other runtime (ADR 0072, ADR 0073).
    implementation(project(":leia-server"))
    implementation(project(":leia-protocol"))
    // What every frontend shares: the values' decoding and more (ADR 0126).
    implementation(project(":leia-client"))
    // The viewer checks JSON with it, and the document export writes it (ADR 0034 §10).
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    implementation(platform(libs.tamboui.bom))
    implementation(libs.tamboui.tui)
    implementation(libs.tamboui.widgets)
    implementation(libs.tamboui.jline3.backend)
    // Tried first, with JLine as the fallback (ADR 0034 §1); leia-app puts it into leia.jar.
    testRuntimeOnly(libs.tamboui.panama.backend)

    // FakeRepository, for the Pilot tests of the download window.
    testImplementation(testFixtures(project(":leia-client")))
    // The Pilot tests write the indexes they open, as leia-server's tests do.
    testImplementation(libs.lucene.core)
    testImplementation(platform(libs.tamboui.bom))
    testImplementation(testFixtures(libs.tamboui.tui))
    testImplementation(testFixtures(libs.tamboui.core))
    // Lucene 10 for the indexes of 9 and 10, with leia-core and Lucene, as leia-server's tests have it;
    // the old indexes of the Pilot tests need the old runtimes, which the tests load from their folders
    // (testRuntimes below).
    testRuntimeOnly(project(":leia-lucene10"))
}

// The old indexes that the runtimes' builds write, which Pilot tests open (ADR 0044): Lucene 2.4 and
// 3.x from leia-lucene3, 4.x and 5.x from leia-lucene5, 6.x and 7.x from leia-lucene7, 8.x and an
// index created by 8.x and written by 9.x from leia-lucene9.
val oldTestIndexes = mapOf(
    "lucene24" to "leia-lucene3",
    "lucene3" to "leia-lucene3",
    "lucene4" to "leia-lucene5",
    "lucene5" to "leia-lucene5",
    "lucene6" to "leia-lucene7",
    "lucene7" to "leia-lucene7",
    "lucene8" to "leia-lucene9",
    "lucene8to9" to "leia-lucene9",
).mapValues { (name, runtime) ->
    configurations.create("${name}TestIndex") { isCanBeConsumed = false }.also {
        dependencies.add(it.name, dependencies.project(mapOf("path" to ":$runtime", "configuration" to "${name}TestIndexElements")))
    }
}

// The runtimes' folders, as leia-app's leiaFolder puts them into runtimes/ beside leia.jar (ADR 0073),
// for the Pilot tests that open old indexes or download runtimes.
val runtimeFolders = runtimeFolders()

// leia's own jars of each runtime's folder, as leia.jar embeds them, for the Pilot tests of the
// window of D (ADR 0073).
val runtimeAdapters = registerRuntimeAdapters(runtimeFolders)

sourceSets.test {
    resources.srcDir(runtimeAdapters)
}

// NOTICE at a path of leia's own for the about window: shadow keeps only the first META-INF/NOTICE*,
// which could be a dependency's.
tasks.processResources {
    from(rootProject.file("NOTICE")) {
        into("io/github/mfuerstenau/leia/tui/about")
    }
}

// The old runtimes' folders for the tests, and Lucene 10's apart, for the tests of the install.
val testRuntimes = registerTestRuntimes(runtimeFolders)
val testLucene10Folder = registerTestLucene10Folder(runtimeFolders)

tasks.test {
    // ReadmeThemeTest parses the example theme of both READMEs.
    val readmes = mapOf(
        "leia.test.doc.readme" to rootProject.file("README.md"),
        "leia.test.doc.distReadme" to rootProject.file("leia-app/src/dist/README.md"),
    )
    for ((property, readme) in readmes) {
        inputs.files(readme).withPropertyName(property)
        systemProperty(property, readme.absolutePath)
    }
    useJUnitPlatform { excludeTags("jars", "server") }
    val indexes: Map<String, FileCollection> = oldTestIndexes
    for ((name, index) in indexes) {
        inputs.files(index).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("${name}Index")
    }
    val runtimes: FileCollection = files(testRuntimes)
    val lucene10: FileCollection = files(testLucene10Folder)
    inputs.files(runtimes).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("runtimes")
    inputs.files(lucene10).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("lucene10")
    val projectPath = projectDir.toPath()
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        indexes.map { (name, index) -> "-Dleia.test.index.$name=" + projectPath.relativize(index.singleFile.toPath()) } +
            listOf(
                "-Dleia.test.runtimes=" + projectPath.relativize(runtimes.singleFile.toPath()),
                "-Dleia.test.lucene10=" + projectPath.relativize(lucene10.singleFile.toPath()),
            )
    })
}

// The Pilot tests that add a jar (ADR 0024). Loading one changes Lucene's global lists of codecs
// and formats, so they run in a JVM of their own, as leia-core's jarsTest does. They use the core's
// test codec jar and the index that needs it.
val testCodecJar = configurations.create("testCodecJar") { isCanBeConsumed = false }
val testCodecIndex = configurations.create("testCodecIndex") { isCanBeConsumed = false }

dependencies {
    testCodecJar(project(path = ":leia-core", configuration = "testCodecJarElements"))
    testCodecIndex(project(path = ":leia-core", configuration = "testCodecIndexElements"))
}

val jarsTest = tasks.register<Test>("jarsTest") {
    description = "Runs the Pilot tests that add jars, in a JVM of their own."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("jars") }
    // Locals, so that the configuration cache doesn't capture the script.
    val codecJar: FileCollection = testCodecJar
    val codecIndex: FileCollection = testCodecIndex
    val runtimes: FileCollection = files(testRuntimes)
    inputs.files(codecJar).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("codecJar")
    inputs.files(codecIndex).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("codecIndex")
    inputs.files(runtimes).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("runtimes")
    // Relative to the test's working directory (this project), so the build cache stays relocatable.
    val projectPath = projectDir.toPath()
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-Dleia.test.codec.jar=" + projectPath.relativize(codecJar.singleFile.toPath()),
            "-Dleia.test.index.custom-codec=" + projectPath.relativize(codecIndex.singleFile.toPath()),
            "-Dleia.test.runtimes=" + projectPath.relativize(runtimes.singleFile.toPath()),
        )
    })
}

// The Pilot test of --server (ADR 0090), with the fake server for Lucene 9 and its index. A server is
// loaded once per JVM, so each class runs in a JVM of its own.
val testServer9 = configurations.create("testServer9") { isCanBeConsumed = false }
val testServer9Index = configurations.create("testServer9Index") { isCanBeConsumed = false }

dependencies {
    testServer9(project(path = ":leia-test-servers", configuration = "fakeServer9Elements"))
    testServer9Index(project(path = ":leia-test-servers", configuration = "fakeServer9IndexElements"))
}

val serverTest = tasks.register<Test>("serverTest") {
    description = "Runs the Pilot tests of the fake servers, each class in a JVM of its own."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    forkEvery = 1
    useJUnitPlatform { includeTags("server") }
    val server: FileCollection = testServer9
    val serverIndex: FileCollection = testServer9Index
    val runtimes: FileCollection = files(testRuntimes)
    inputs.files(server).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("server")
    inputs.files(serverIndex).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("serverIndex")
    inputs.files(runtimes).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("runtimes")
    val projectPath = projectDir.toPath()
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-Dleia.test.server9=" + projectPath.relativize(server.singleFile.toPath()),
            "-Dleia.test.server9.index=" + projectPath.relativize(serverIndex.singleFile.toPath()),
            "-Dleia.test.runtimes=" + projectPath.relativize(runtimes.singleFile.toPath()),
        )
    })
}

tasks.check {
    dependsOn(jarsTest, serverTest)
}
