// MCP mode (ADR 0100): leia's tools for an MCP client over stdio, calling the backend in-process
// through LeiaBackend, as leia-tui does (ADR 0034). leia-tui's Main hands --mcp over to it.
plugins {
    id("leia.java-library-conventions")
}

dependencies {
    // LeiaBackend, and through its api the protocol records. Neither leia-core nor Lucene: every
    // index is read through a runtime (AGENTS.md, hard rule 2).
    implementation(project(":leia-server"))
    implementation(project(":leia-protocol"))
    // What every frontend shares: the values' decoding and more (ADR 0126).
    implementation(project(":leia-client"))
    // The JSON of the messages and the tools' records.
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    // Lucene 10 for the demo index, as leia-server's tests have it.
    testRuntimeOnly(project(":leia-lucene10"))
}

// The demo index that leia-core's build writes (docs/demo-index.md).
val demoIndex = configurations.create("demoIndex") { isCanBeConsumed = false }

dependencies {
    demoIndex(project(path = ":leia-core", configuration = "demoIndexElements"))
}

tasks.test {
    val index: FileCollection = demoIndex
    inputs.files(index).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("demoIndex")
    // Relative to the test's working directory (this project), so the build cache stays relocatable.
    val projectPath = projectDir.toPath()
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dleia.test.index.demo=" + projectPath.relativize(index.singleFile.toPath()))
    })
}
