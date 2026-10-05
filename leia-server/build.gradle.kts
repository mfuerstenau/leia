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

    testImplementation(libs.lucene.core)
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
        attributes("Multi-Release" to "true")
    }
}
