plugins {
    id("leia.java-conventions")
    application
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":leia-core"))
    implementation(project(":leia-protocol"))
    implementation(libs.dagger)
    annotationProcessor(libs.dagger.compiler)
}

// The server tests replay the shared request fixtures (see leia-protocol/build.gradle.kts).
val protocolDir = layout.settingsDirectory.dir("protocol")

tasks.test {
    inputs.dir(protocolDir).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("protocol")
    systemProperty("leia.protocol.dir", projectDir.toPath().relativize(protocolDir.asFile.toPath()).toString())
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
