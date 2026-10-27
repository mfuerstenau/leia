// Lucene 10 behind the runtime interface (ADR 0065), through leia-core: the runtime that reads
// indexes of Lucene 9 and 10 and those of an unknown version (ADR 0071). Its folder runtimes/lucene10
// (not in leia.jar, which holds no Lucene, ADR 0072) has the adapter jar, leia-core's jar and the
// official Lucene jars (ADR 0073); leia loads them as the first link of the jar chain, since the jars
// of --jar link against org.apache.lucene (ADR 0016, ADR 0018, ADR 0065).
plugins {
    id("leia.runtime-folder-conventions")
}

dependencies {
    implementation(project(":leia-core"))
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
