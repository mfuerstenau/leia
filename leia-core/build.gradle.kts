plugins {
    id("leia.java-library-conventions")
}

dependencies {
    // api: the public API (IndexSummary, ReadOnlyIndex.reader()) exposes Lucene types.
    api(libs.lucene.core)
    // The classic query parser for search (ADR 0010); the API takes and returns lucene-core types only.
    implementation(libs.lucene.queryparser)
    // MoreLikeThis (ADR 0013).
    implementation(libs.lucene.queries)
    // CustomAnalyzer and the analysis factories for the analysis screen (ADR 0014).
    implementation(libs.lucene.analysis.common)
    // Lucene 9 indexes must open with Lucene 10 (AGENTS.md, hard rule 5).
    runtimeOnly(libs.lucene.backward.codecs)
    // Lucene's other codecs and formats, such as SimpleText and the suggesters' Completion
    // postings formats, so that indexes using them open (ADR 0018).
    runtimeOnly(libs.lucene.codecs)
    runtimeOnly(libs.lucene.suggest)
}

// Test indexes written by older Lucene versions: a Lucene 9 index must open, a Lucene 8 index must
// be rejected as unsupported. They are generated at build time by LegacyIndexWriter, which runs on
// the old Lucene jars, so no binary indexes are checked in.
val legacyIndex by sourceSets.creating

dependencies {
    "legacyIndexCompileOnly"(libs.lucene9.core)
}

val legacyLucene = mapOf("lucene9" to libs.lucene9.core, "lucene8" to libs.lucene8.core)
val testIndexesDir = layout.buildDirectory.dir("test-indexes")

val legacyIndexTasks = legacyLucene.map { (name, library) ->
    val runtime = configurations.create("${name}Runtime") {
        isCanBeConsumed = false
        isTransitive = false
    }
    dependencies.add(runtime.name, library)

    name to tasks.register<JavaExec>("${name}TestIndex") {
        description = "Writes a test index with ${library.get().version}."
        classpath = legacyIndex.output + runtime
        mainClass = "io.github.mfuerstenau.leia.core.LegacyIndexWriter"
        val indexDir = testIndexesDir.map { it.dir(name) }
        outputs.dir(indexDir).withPropertyName("index")
        argumentProviders.add(CommandLineArgumentProvider { listOf(indexDir.get().asFile.absolutePath) })
    }
}

tasks.test {
    // Relative to the test's working directory (this project), so the build cache stays relocatable.
    val projectPath = projectDir.toPath()
    for ((name, task) in legacyIndexTasks) {
        inputs.files(task).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName(task.name)
        val indexPath = projectPath.relativize(testIndexesDir.get().dir(name).asFile.toPath()).toString()
        systemProperty("leia.test.index.$name", indexPath)
    }
}

// A codec and formats that leia doesn't bundle (ADR 0018): build/test-codec-jar/leia-test-codec.jar,
// and two indexes written with them, custom-codec with the codec and custom-formats with Lucene's
// codec and the jar's postings, doc values and KNN vectors formats. The plain tests check that
// these indexes don't open without the jar, jarsTest that they do with it. The jarsTests of
// leia-server and leia-tui use them too.
val testCodec by sourceSets.creating

dependencies {
    "testCodecImplementation"(libs.lucene.core)
}

val testCodecJar = tasks.register<Jar>("testCodecJar") {
    description = "Builds the jar with the test codec and formats."
    from(testCodec.output)
    exclude("**/TestCodecIndexWriter*")
    archiveFileName = "leia-test-codec.jar"
    destinationDirectory = layout.buildDirectory.dir("test-codec-jar")
}

val testCodecIndexNames = listOf("custom-codec", "custom-formats")

val testCodecIndexes = tasks.register<JavaExec>("testCodecIndexes") {
    description = "Writes the test indexes that need the test codec jar."
    classpath = testCodec.runtimeClasspath
    mainClass = "io.github.mfuerstenau.leia.testcodec.TestCodecIndexWriter"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    val indexDirs = testCodecIndexNames.map { name -> testIndexesDir.map { it.dir(name) } }
    for ((name, dir) in testCodecIndexNames.zip(indexDirs)) {
        outputs.dir(dir).withPropertyName(name)
    }
    argumentProviders.add(CommandLineArgumentProvider { indexDirs.map { it.get().asFile.absolutePath } })
}

tasks.withType<Test>().configureEach {
    val projectPath = projectDir.toPath()
    inputs.files(testCodecIndexes).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("codecIndexes")
    for (name in testCodecIndexNames) {
        val indexPath = projectPath.relativize(testIndexesDir.get().dir(name).asFile.toPath()).toString()
        systemProperty("leia.test.index.$name", indexPath)
    }
}

// The test codec jar and the indexes that need it, for the jarsTests of leia-server and leia-tui.
val testCodecJarElements = configurations.consumable("testCodecJarElements")
val testCodecIndexElements = configurations.consumable("testCodecIndexElements")
val customFormatsIndexElements = configurations.consumable("customFormatsIndexElements")
artifacts {
    add(testCodecJarElements.name, testCodecJar)
    add(testCodecIndexElements.name, testIndexesDir.map { it.dir("custom-codec") }) {
        builtBy(testCodecIndexes)
    }
    add(customFormatsIndexElements.name, testIndexesDir.map { it.dir("custom-formats") }) {
        builtBy(testCodecIndexes)
    }
}

// The Lucene 9 index, for leia-server's tests through LeiaBackend.
val lucene9TestIndexElements = configurations.consumable("lucene9TestIndexElements")
artifacts {
    add(lucene9TestIndexElements.name, testIndexesDir.map { it.dir("lucene9") }) {
        builtBy(legacyIndexTasks.toMap().getValue("lucene9"))
    }
}

// Analysis jars (ADR 0016): Lucene modules that leia doesn't bundle, each with its dependencies in a
// directory of its own under build/test-analysis-jars: two small ones, and Kuromoji and Nori for the
// options of their analyzers (ADR 0023). Loading them, or the test codec jar, changes Lucene's
// global lists of factories, codecs and formats, so the tests that do run in a JVM of their own
// (jarsTest). leia-server's jarsTest uses these directories too.
val testAnalysisModules = mapOf(
    "phonetic" to libs.lucene.analysis.phonetic,
    "stempel" to libs.lucene.analysis.stempel,
    "kuromoji" to libs.lucene.analysis.kuromoji,
    "nori" to libs.lucene.analysis.nori,
)
val testAnalysisJarsDir = layout.buildDirectory.dir("test-analysis-jars")

val testAnalysisModuleJars = testAnalysisModules.mapValues { (name, library) ->
    configurations.create("${name}AnalysisJars") {
        isCanBeConsumed = false
        dependencies.addLater(library.map { project.dependencies.create(it) })
        // In the server jar already.
        exclude(group = "org.apache.lucene", module = "lucene-core")
        exclude(group = "org.apache.lucene", module = "lucene-analysis-common")
    }
}

val testAnalysisJars = tasks.register<Sync>("testAnalysisJars") {
    description = "Copies the analysis modules that the jar tests load."
    into(testAnalysisJarsDir)
    for ((name, jars) in testAnalysisModuleJars) {
        from(jars) { into(name) }
    }
}

tasks.test {
    useJUnitPlatform { excludeTags("jars") }
}

val testAnalysisJarsElements = configurations.consumable("testAnalysisJarsElements")
artifacts {
    add(testAnalysisJarsElements.name, testAnalysisJarsDir) {
        builtBy(testAnalysisJars)
    }
}

val jarsTest = tasks.register<Test>("jarsTest") {
    description = "Runs the tests that load jars, in a JVM of their own."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("jars") }
    val projectPath = projectDir.toPath()
    inputs.files(testAnalysisJars).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("analysisJars")
    systemProperty(
        "leia.test.analysis.jars",
        projectPath.relativize(testAnalysisJarsDir.get().asFile.toPath()).toString(),
    )
    inputs.files(testCodecJar).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("codecJar")
    systemProperty(
        "leia.test.codec.jar",
        projectPath.relativize(testCodecJar.get().archiveFile.get().asFile.toPath()).toString(),
    )
}

tasks.check {
    dependsOn(jarsTest)
}

// A demo index for trying leia by hand (docs/demo-index.md): ./gradlew :leia-core:demoIndex writes
// it to build/test-indexes/demo. leia-server's DemoIndexTest checks the numbers that the document
// quotes.
val demoIndex by sourceSets.creating

dependencies {
    "demoIndexImplementation"(libs.lucene.core)
}

val demoIndexTask = tasks.register<JavaExec>("demoIndex") {
    description = "Writes a demo index for trying leia by hand."
    group = "leia"
    classpath = demoIndex.runtimeClasspath
    mainClass = "io.github.mfuerstenau.leia.core.DemoIndexWriter"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    val indexDir = testIndexesDir.map { it.dir("demo") }
    outputs.dir(indexDir).withPropertyName("index")
    argumentProviders.add(CommandLineArgumentProvider { listOf(indexDir.get().asFile.absolutePath) })
}

val demoIndexElements = configurations.consumable("demoIndexElements")
artifacts {
    add(demoIndexElements.name, testIndexesDir.map { it.dir("demo") }) {
        builtBy(demoIndexTask)
    }
}
