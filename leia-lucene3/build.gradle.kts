// Lucene 3.6.2, relocated, for indexes that Lucene 10 can't read: those written by Lucene 2.x and
// 3.x (ADR 0044). Every Lucene class is renamed to io.github.mfuerstenau.leia.lucene3.shaded.lucene,
// so it lives next to Lucene 10 on one classpath. leia finds it through ServiceLoader as a
// LuceneRuntime of leia-runtime, which leia.jar brings: consumers take the shadow jar
// (shadowRuntimeElements) at runtime, which the module's own code is relocated in too.
plugins {
    id("leia.java-library-conventions")
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(libs.lucene3.core)
    // compileOnly: the interface is leia's, so it stays out of the shadow jar.
    compileOnly(project(":leia-runtime"))
    testImplementation(project(":leia-runtime"))
}

tasks.withType<JavaCompile>().configureEach {
    // The 3.6 API is mostly deprecated; the conventions make every warning an error.
    options.compilerArgs.add("-Xlint:-deprecation")
}

tasks.shadowJar {
    archiveClassifier = ""
    archiveFileName = "leia-lucene3.jar"
    // Lucene 3.6 has no META-INF/services, so renaming its classes is all there is to it.
    relocate("org.apache.lucene", "io.github.mfuerstenau.leia.lucene3.shaded.lucene")
}

// The plain jar would bring unrelocated classes to a consumer that asks for the default variant.
tasks.jar {
    archiveClassifier = "plain"
}

// The Lucene 2.4 test index, written at build time by Lucene24IndexWriter on lucene-core 2.4.1, as
// leia-core writes its Lucene 8 and 9 indexes; no binary index is checked in.
val lucene24Index by sourceSets.creating

dependencies {
    "lucene24IndexImplementation"(libs.lucene24.core)
}

tasks.named<JavaCompile>(lucene24Index.compileJavaTaskName) {
    // 2.4 is raw types throughout.
    options.compilerArgs.add("-Xlint:-rawtypes")
}

val testIndexesDir = layout.buildDirectory.dir("test-indexes")

val lucene24TestIndex = tasks.register<JavaExec>("lucene24TestIndex") {
    description = "Writes a test index with Lucene 2.4."
    classpath = lucene24Index.runtimeClasspath
    mainClass = "io.github.mfuerstenau.leia.lucene3.Lucene24IndexWriter"
    val indexDir = testIndexesDir.map { it.dir("lucene24") }
    outputs.dir(indexDir).withPropertyName("index")
    argumentProviders.add(CommandLineArgumentProvider { listOf(indexDir.get().asFile.absolutePath) })
}

tasks.test {
    val projectPath = projectDir.toPath()
    inputs.files(lucene24TestIndex).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("lucene24Index")
    val indexPath = projectPath.relativize(testIndexesDir.get().dir("lucene24").asFile.toPath()).toString()
    systemProperty("leia.test.index.lucene24", indexPath)
}

// The Lucene 2.4 index, for leia-server's tests through LeiaBackend and leia-tui's Pilot tests.
val lucene24TestIndexElements = configurations.consumable("lucene24TestIndexElements")
artifacts {
    add(lucene24TestIndexElements.name, testIndexesDir.map { it.dir("lucene24") }) {
        builtBy(lucene24TestIndex)
    }
}
