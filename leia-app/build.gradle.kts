import io.github.mfuerstenau.leia.buildlogic.ExcludedPackagesCheck
import io.github.mfuerstenau.leia.buildlogic.registerRuntimeAdapters
import io.github.mfuerstenau.leia.buildlogic.runtimeFolder
import io.github.mfuerstenau.leia.buildlogic.runtimeFolders

// The composition root (ADR 0126): the command line, which starts the TUI or, with --mcp, MCP mode
// (ADR 0100), and the builds of leia.jar, the folder leia runs from and the distribution.
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
    // The frontends it starts, the client they share, and the backend, called in-process (ADR 0034).
    // Neither core nor Lucene is on the classpath: Lucene 10 comes from runtimes/lucene10 like every
    // other runtime (ADR 0072, ADR 0073).
    implementation(project(":leia-tui"))
    implementation(project(":leia-mcp"))
    implementation(project(":leia-client"))
    implementation(project(":leia-server"))
    implementation(project(":leia-protocol"))
    // Tried first, with JLine as the fallback (ADR 0034 §1).
    runtimeOnly(platform(libs.tamboui.bom))
    runtimeOnly(libs.tamboui.panama.backend)

    testImplementation(launcher.output)
    // Lucene 10 for the indexes of 9 and 10, which MCP mode's test opens.
    testRuntimeOnly(project(":leia-lucene10"))
}

application {
    mainClass = "io.github.mfuerstenau.leia.app.Launcher"
}

// The runtimes' folders, as leiaFolder puts them into runtimes/ beside leia.jar (ADR 0073), one per
// runtime module. A new runtime is one more entry in RUNTIME_MODULES of build-logic, and in the
// runtime catalog META-INF/leia/runtimes.json of leia-server (ADR 0078).
val runtimeFolders = runtimeFolders()

// leia's own jars of each runtime's folder, which leia.jar embeds under leia-runtimes/<folder>/ for
// --download-runtimes and the window of D (ADR 0073).
val runtimeAdapters = registerRuntimeAdapters(runtimeFolders)

sourceSets.main {
    resources.srcDir(runtimeAdapters)
}

tasks.test {
    // LaunchScriptTest runs the leia script from here.
    inputs.dir("src/scripts")
    systemProperty("leia.scripts", file("src/scripts").absolutePath)
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

// The distribution: leia.jar, the scripts, the user README (src/dist) and the repository's LICENSE
// and NOTICE in one folder leia-<version>/, packed as a zip for Linux and Windows alike, without
// runtimes/ (ADR 0073, ADR 0082, ADR 0083). build-dist.sh copies it to dist/.
val leiaZip by tasks.registering(Zip::class) {
    description = "Packs leia.jar, the scripts, README.md, LICENSE and NOTICE as leia-<version>.zip."
    archiveFileName = "leia-${project.version}.zip"
    destinationDirectory = layout.buildDirectory.dir("dist")
    into("leia-${project.version}") {
        from(tasks.shadowJar)
        from("src/scripts") {
            filesMatching("leia") { permissions { unix("rwxr-xr-x") } }
        }
        from("src/dist")
        from(rootProject.file("LICENSE"), rootProject.file("NOTICE"))
    }
}

// Under Gradle there is no leia.jar to find runtimes/ beside, so -Dleia.runtimes names leiaFolder's.
tasks.named<JavaExec>("run") {
    dependsOn(leiaFolder)
    val runtimes = layout.buildDirectory.dir("leia/runtimes")
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dleia.runtimes=" + runtimes.get().asFile.absolutePath) })
}
