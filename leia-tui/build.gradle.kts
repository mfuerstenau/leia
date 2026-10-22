import io.github.mfuerstenau.leia.buildlogic.ExcludedPackagesCheck
import io.github.mfuerstenau.leia.buildlogic.RUNTIME_MODULES
import io.github.mfuerstenau.leia.buildlogic.runtimeFolder
import io.github.mfuerstenau.leia.buildlogic.runtimeFolderJars

plugins {
    id("leia.java-conventions")
    application
    id("com.gradleup.shadow")
}

// The entry class that checks the Java version before anything else loads (ADR 0034 §8). It is
// compiled for Java 8, so that an older java runs it and says what is needed, instead of failing
// with an UnsupportedClassVersionError.
val launcher = sourceSets.create("launcher")

tasks.named<JavaCompile>(launcher.compileJavaTaskName) {
    options.release = 8
    // javac warns that release 8 is obsolete; the conventions make every warning an error.
    options.compilerArgs.add("-Xlint:-options")
}

dependencies {
    // The backend, called in-process (ADR 0034). Neither core nor Lucene is on the classpath: Lucene
    // 10 comes from runtimes/lucene10 like every other runtime (ADR 0072, ADR 0073).
    implementation(project(":leia-server"))
    implementation(project(":leia-protocol"))
    // The viewer checks JSON with it, and the document export writes it (ADR 0034 §10).
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    implementation(platform(libs.tamboui.bom))
    implementation(libs.tamboui.tui)
    implementation(libs.tamboui.widgets)
    implementation(libs.tamboui.jline3.backend)
    // Tried first, with JLine as the fallback (ADR 0034 §1).
    runtimeOnly(libs.tamboui.panama.backend)

    testImplementation(launcher.output)
    // Lucene 10 for the indexes of 9 and 10, with leia-core and Lucene, as leia-server's tests have it;
    // the old indexes of the Pilot tests need the old runtimes, which the tests load from their folders
    // (testRuntimes below).
    testRuntimeOnly(project(":leia-lucene10"))
    // The Pilot tests write the indexes they open, as leia-server's tests do.
    testImplementation(libs.lucene.core)
    testImplementation(platform(libs.tamboui.bom))
    testImplementation(testFixtures(libs.tamboui.tui))
    testImplementation(testFixtures(libs.tamboui.core))
}

application {
    mainClass = "io.github.mfuerstenau.leia.tui.Launcher"
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

// The runtimes' folders, as leiaFolder puts them into runtimes/ beside leia.jar (ADR 0073), one per
// runtime module. A new runtime is one more entry in RUNTIME_MODULES of build-logic, and in the
// runtime catalog META-INF/leia/runtimes.json of leia-server (ADR 0078).
val runtimeFolders = RUNTIME_MODULES.associateWith { runtimeFolderJars(it) }

// leia's own jars of each runtime's folder, the adapter and for Lucene 10 leia-core's, which leia.jar
// embeds under leia-runtimes/<folder>/ for --download-runtimes; Lucene's come from Maven Central
// (ADR 0073).
val runtimeAdapters = tasks.register<Sync>("runtimeAdapters") {
    description = "Puts leia's own jars of the runtimes' folders together, for leia.jar."
    into(layout.buildDirectory.dir("generated/runtime-adapters"))
    for ((module, jars) in runtimeFolders) {
        val own = jars.incoming.artifactView {
            componentFilter { it is org.gradle.api.artifacts.component.ProjectComponentIdentifier }
        }.files
        from(own) { into("leia-runtimes/" + runtimeFolder(module)) }
    }
}

sourceSets.main {
    resources.srcDir(runtimeAdapters)
}

// The old runtimes' folders for the tests, whose classpath has Lucene 10.
val testRuntimes = tasks.register<Sync>("testRuntimes") {
    description = "Puts the old runtimes' folders together for the tests."
    into(layout.buildDirectory.dir("test-runtimes"))
    for ((module, jars) in runtimeFolders - "leia-lucene10") {
        from(jars) { into(runtimeFolder(module)) }
    }
}

// Lucene 10's folder, apart, for RuntimeInstallTest: the tests have Lucene 10 on their classpath, so
// loading it from testRuntimes would put it into the jar chain twice.
val testLucene10Folder = tasks.register<Sync>("testLucene10Folder") {
    description = "Puts Lucene 10's runtime folder together for the tests."
    into(layout.buildDirectory.dir("test-lucene10"))
    from(runtimeFolders.getValue("leia-lucene10"))
}

tasks.test {
    // LaunchScriptTest runs the leia script from here.
    inputs.dir("src/scripts")
    systemProperty("leia.scripts", file("src/scripts").absolutePath)
    useJUnitPlatform { excludeTags("jars") }
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

tasks.check {
    dependsOn(jarsTest)
}

tasks.named<JavaExec>("run") {
    classpath += launcher.output
    standardInput = System.`in`
}

tasks.jar {
    from(launcher.output)
}

tasks.shadowJar {
    archiveFileName = "leia.jar"
    from(launcher.output)
    // TamboUI's backends are found through ServiceLoader, and each backend jar ships the same
    // services file. Shadow only merges duplicates it gets to see, which needs INCLUDE.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    filesMatching(listOf("META-INF/LICENSE*", "META-INF/NOTICE*", "module-info.class")) {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    manifest {
        attributes(
            "Multi-Release" to "true",
            // The Panama backend, and JLine's FFM terminal provider, call native code.
            "Enable-Native-Access" to "ALL-UNNAMED",
        )
    }
}

// leia.jar holds neither Lucene nor leia-core nor the Lucene 10 runtime as classes: they come from
// runtimes/lucene10 (ADR 0072, ADR 0073). It embeds leia's own jars of the runtimes, but none of
// Lucene's.
val noLuceneInJarCheck = tasks.register<ExcludedPackagesCheck>("noLuceneInJarCheck") {
    description = "Checks that leia.jar holds neither Lucene, leia-core nor the Lucene 10 runtime."
    group = "verification"
    jar = tasks.shadowJar.flatMap { it.archiveFile }
    excludedPackages.addAll(
        "org/apache/lucene/",
        "io/github/mfuerstenau/leia/core/",
        "io/github/mfuerstenau/leia/lucene10/",
    )
    excludedEntries.add(".*/lucene-[^/]*\\.jar")
    report = layout.buildDirectory.file("reports/no-lucene-in-jar-check.txt")
}

tasks.check {
    dependsOn(noLuceneInJarCheck)
}

// The folder leia runs from: leia.jar, the scripts that start it (ADR 0034 §8) and runtimes/, a
// folder per runtime with its adapter jar and the jars it lists (ADR 0073).
val leiaFolder by tasks.registering(Sync::class) {
    from(tasks.shadowJar)
    for ((module, jars) in runtimeFolders) {
        from(jars) { into("runtimes/" + runtimeFolder(module)) }
    }
    from("src/scripts") {
        filesMatching("leia") { permissions { unix("rwxr-xr-x") } }
    }
    into(layout.buildDirectory.dir("leia"))
}

tasks.assemble {
    dependsOn(leiaFolder)
}

// The distribution: leia.jar and the scripts in one folder leia-<version>/, packed as a zip for
// Linux and Windows alike, without runtimes/ (ADR 0073, ADR 0082). build-dist.sh copies it to dist/.
val leiaZip by tasks.registering(Zip::class) {
    description = "Packs leia.jar and the scripts as leia-<version>.zip."
    archiveFileName = "leia-${project.version}.zip"
    destinationDirectory = layout.buildDirectory.dir("dist")
    into("leia-${project.version}") {
        from(tasks.shadowJar)
        from("src/scripts") {
            filesMatching("leia") { permissions { unix("rwxr-xr-x") } }
        }
    }
}

// Under Gradle there is no leia.jar to find runtimes/ beside, so -Dleia.runtimes names leiaFolder's.
tasks.named<JavaExec>("run") {
    dependsOn(leiaFolder)
    val runtimes = layout.buildDirectory.dir("leia/runtimes")
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dleia.runtimes=" + runtimes.get().asFile.absolutePath) })
}
