// Lucene 10 behind the runtime interface (ADR 0065), through leia-core: the runtime that is to read
// indexes of Lucene 9 and 10 once leia.jar holds no Lucene. Not yet routed to, nor packaged.
//
// Unlike the other runtimes, Lucene is not relocated: the jars of --jar link against
// org.apache.lucene (ADR 0016, ADR 0018). So this module doesn't take
// leia.lucene-runtime-conventions, which relocates; the shadow jar is set up as there otherwise.
import io.github.mfuerstenau.leia.buildlogic.ExcludedPackagesCheck

plugins {
    id("leia.java-library-conventions")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":leia-core"))
    // compileOnly: the interface is leia's, so it stays out of the shadow jar.
    compileOnly(project(":leia-runtime"))
    testImplementation(project(":leia-runtime"))
}

tasks.shadowJar {
    archiveClassifier = ""
    archiveFileName = "${project.name}.jar"
    // Lucene finds its codecs, formats and analysis factories through META-INF/services. Shadow drops
    // a path it has seen before the merge sees it, unless duplicates are included.
    mergeServiceFiles()
    filesMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    // Lucene 10 is a JPMS module; this jar is not.
    exclude("module-info.class", "META-INF/versions/*/module-info.class")
    // Lucene's core is a multi-release jar: the class loader sees its classes of META-INF/versions
    // only with the attribute.
    manifest {
        attributes("Multi-Release" to "true")
    }
}

// The plain jar would be the shadow jar's name otherwise.
tasks.jar {
    archiveClassifier = "plain"
}

// The test indexes of leia-core: one written by Lucene 9.12 and the demo index of Lucene 10.
val testIndexes = mapOf("lucene9" to "lucene9TestIndexElements", "demo" to "demoIndexElements")
    .mapValues { (name, configuration) ->
        configurations.create("${name}TestIndex") { isCanBeConsumed = false }.also {
            dependencies.add(
                it.name,
                dependencies.project(mapOf("path" to ":leia-core", "configuration" to configuration)),
            )
        }
    }

tasks.test {
    // Relative to the test's working directory (this project), so the build cache stays relocatable.
    val projectPath = projectDir.toPath()
    val indexes = testIndexes.mapValues { (_, files) -> files as FileCollection }
    for ((name, files) in indexes) {
        inputs.files(files).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("${name}Index")
    }
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        indexes.map { (name, files) -> "-Dleia.test.index.$name=" + projectPath.relativize(files.singleFile.toPath()) }
    })
}

// The jar brings Lucene 10 and leia-core, but neither the runtime interface, which leia.jar brings,
// nor the server.
val excludedPackagesCheck = tasks.register<ExcludedPackagesCheck>("excludedPackagesCheck") {
    description = "Checks that the shadow jar holds neither the runtime interface nor the server."
    group = "verification"
    jar = tasks.shadowJar.flatMap { it.archiveFile }
    excludedPackages.addAll("io/github/mfuerstenau/leia/runtime/", "io/github/mfuerstenau/leia/server/")
    report = layout.buildDirectory.file("reports/excluded-packages-check.txt")
}

tasks.named("check") {
    dependsOn(excludedPackagesCheck)
}
