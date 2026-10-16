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
    // The backend, called in-process (ADR 0034); core and Lucene are only on the runtime classpath.
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
    // The old indexes of the Pilot tests need the runtimes, which leia.jar leaves to runtimes/.
    testRuntimeOnly(project(path = ":leia-lucene3", configuration = "shadowRuntimeElements"))
    testRuntimeOnly(project(path = ":leia-lucene5", configuration = "shadowRuntimeElements"))
    testRuntimeOnly(project(path = ":leia-lucene7", configuration = "shadowRuntimeElements"))
    testRuntimeOnly(project(path = ":leia-lucene9", configuration = "shadowRuntimeElements"))
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

tasks.test {
    // LaunchScriptTest runs the leia script from here.
    inputs.dir("src/scripts")
    systemProperty("leia.scripts", file("src/scripts").absolutePath)
    useJUnitPlatform { excludeTags("jars") }
    val indexes: Map<String, FileCollection> = oldTestIndexes
    for ((name, index) in indexes) {
        inputs.files(index).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("${name}Index")
    }
    val projectPath = projectDir.toPath()
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        indexes.map { (name, index) -> "-Dleia.test.index.$name=" + projectPath.relativize(index.singleFile.toPath()) }
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
    inputs.files(codecJar).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("codecJar")
    inputs.files(codecIndex).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("codecIndex")
    // Relative to the test's working directory (this project), so the build cache stays relocatable.
    val projectPath = projectDir.toPath()
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-Dleia.test.codec.jar=" + projectPath.relativize(codecJar.singleFile.toPath()),
            "-Dleia.test.index.custom-codec=" + projectPath.relativize(codecIndex.singleFile.toPath()),
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
    // The backends are found through ServiceLoader, and each backend jar ships the same services
    // file. Shadow only merges duplicates it gets to see, which needs INCLUDE (as in leia-server).
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

// The runtimes for indexes that Lucene 10 can't read, each a jar of its own in runtimes/ beside
// leia.jar, which leia loads at startup (ADR 0044). A new runtime is one more line here.
val runtimeJars = configurations.create("runtimeJars") {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    runtimeJars(project(path = ":leia-lucene3", configuration = "shadowRuntimeElements"))
    runtimeJars(project(path = ":leia-lucene5", configuration = "shadowRuntimeElements"))
    runtimeJars(project(path = ":leia-lucene7", configuration = "shadowRuntimeElements"))
    runtimeJars(project(path = ":leia-lucene9", configuration = "shadowRuntimeElements"))
}

// The folder leia runs from: leia.jar, the scripts that start it (ADR 0034 §8) and runtimes/.
val leiaFolder by tasks.registering(Sync::class) {
    from(tasks.shadowJar)
    from(runtimeJars) { into("runtimes") }
    from("src/scripts") {
        filesMatching("leia") { permissions { unix("rwxr-xr-x") } }
    }
    into(layout.buildDirectory.dir("leia"))
}

tasks.assemble {
    dependsOn(leiaFolder)
}
