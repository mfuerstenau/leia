import io.github.mfuerstenau.leia.buildlogic.registerRuntimeAdapters
import io.github.mfuerstenau.leia.buildlogic.registerTestLucene10Folder
import io.github.mfuerstenau.leia.buildlogic.registerTestRuntimes
import io.github.mfuerstenau.leia.buildlogic.runtimeFolders

// The headless client (ADR 0126): what any frontend of leia needs that isn't drawing or keys — the
// platform's folders, the log, the history, the runtimes' install, the document export and the
// decoding of values. Neither TamboUI nor Lucene: every index is read through LeiaBackend (AGENTS.md,
// hard rule 2).
plugins {
    id("leia.java-library-conventions")
    // FakeRepository, a Maven repository on loopback, for the install's tests here and in the
    // frontends.
    `java-test-fixtures`
}

dependencies {
    // LeiaBackend, the runtime catalog and the runtimes' folders, and through its api the protocol
    // records.
    api(project(":leia-server"))
    api(project(":leia-protocol"))
    // The document export writes its JSON with it (ADR 0030).
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    // @Nullable instead of Optional parameters and fields; only at compile time, for leia-client and
    // its frontends, so not in leia.jar.
    compileOnlyApi(libs.jspecify)

    testFixturesImplementation(project(":leia-server"))
    testFixturesImplementation(project(":leia-protocol"))
    // Lucene 10 for the demo index, which the export's tests read through the backend.
    testRuntimeOnly(project(":leia-lucene10"))
}

// The demo index that leia-core's build writes (docs/demo-index.md).
val demoIndex = configurations.create("demoIndex") { isCanBeConsumed = false }

dependencies {
    demoIndex(project(path = ":leia-core", configuration = "demoIndexElements"))
}

// The install's tests serve the runtimes' Lucene jars from FakeRepository and write leia's own jars
// from the class path, as leia.jar embeds them (ADR 0073).
val runtimeFolders = runtimeFolders()
val runtimeAdapters = registerRuntimeAdapters(runtimeFolders)
val testRuntimes = registerTestRuntimes(runtimeFolders)
val testLucene10Folder = registerTestLucene10Folder(runtimeFolders)

sourceSets.test {
    resources.srcDir(runtimeAdapters)
}

tasks.test {
    val index: FileCollection = demoIndex
    inputs.files(index).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("demoIndex")
    val runtimes: FileCollection = files(testRuntimes)
    val lucene10: FileCollection = files(testLucene10Folder)
    inputs.files(runtimes).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("runtimes")
    inputs.files(lucene10).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("lucene10")
    // For IsolationTest: what a frontend gets of leia-client, without the tests' own jars.
    val mainClasspath: FileCollection = sourceSets.main.get().runtimeClasspath
    inputs.files(mainClasspath).withPropertyName("mainClasspath")
    // Relative to the test's working directory (this project), so the build cache stays relocatable.
    val projectPath = projectDir.toPath()
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-Dleia.test.index.demo=" + projectPath.relativize(index.singleFile.toPath()),
            "-Dleia.test.runtimes=" + projectPath.relativize(runtimes.singleFile.toPath()),
            "-Dleia.test.lucene10=" + projectPath.relativize(lucene10.singleFile.toPath()),
            "-Dleia.test.mainClasspath=" + mainClasspath.files.joinToString(File.pathSeparator) { it.name },
        )
    })
}
