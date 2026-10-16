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
    // The Pilot tests write the indexes they open, as leia-server's tests do.
    testImplementation(libs.lucene.core)
    testImplementation(platform(libs.tamboui.bom))
    testImplementation(testFixtures(libs.tamboui.tui))
    testImplementation(testFixtures(libs.tamboui.core))
}

application {
    mainClass = "io.github.mfuerstenau.leia.tui.Launcher"
}

// The Lucene 2.4 index that leia-lucene3's build writes, which a Pilot test opens (ADR 0044).
val lucene24TestIndex = configurations.create("lucene24TestIndex") { isCanBeConsumed = false }

dependencies {
    lucene24TestIndex(project(path = ":leia-lucene3", configuration = "lucene24TestIndexElements"))
}

tasks.test {
    // LaunchScriptTest runs the leia script from here.
    inputs.dir("src/scripts")
    systemProperty("leia.scripts", file("src/scripts").absolutePath)
    useJUnitPlatform { excludeTags("jars") }
    val lucene24Index: FileCollection = lucene24TestIndex
    inputs.files(lucene24Index).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("lucene24Index")
    val projectPath = projectDir.toPath()
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dleia.test.index.lucene24=" + projectPath.relativize(lucene24Index.singleFile.toPath()))
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

// The folder leia runs from: leia.jar and the scripts that start it (ADR 0034 §8).
val leiaFolder by tasks.registering(Sync::class) {
    from(tasks.shadowJar)
    from("src/scripts") {
        filesMatching("leia") { permissions { unix("rwxr-xr-x") } }
    }
    into(layout.buildDirectory.dir("leia"))
}

tasks.assemble {
    dependsOn(leiaFolder)
}
