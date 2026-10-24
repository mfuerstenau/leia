// Fake Elasticsearch or OpenSearch installations, for the tests of --server (ADR 0090); in neither
// leia.jar nor the zip. For each Lucene major the server runtime reads, build/fake-server-<major>/
// has a lib/ like a server's, with Lucene's jars of an older minor than leia's runtime is built for
// and the server's jar, whose postings format LeiaServerPostings no Lucene knows, and two indexes
// written with that Lucene: index, with the postings format, and plain, without. The one for 9 is
// an OpenSearch; the one for 10 an Elasticsearch, whose postings format needs the setup that
// Elasticsearch's bootstrap does, and its lib/platform/ of native libraries.
plugins {
    id("leia.java-conventions")
}

// The Lucene modules in a server's lib/, as Elasticsearch 8 and 9 ship them.
val serverModules = listOf(
    "core",
    "backward-codecs",
    "analysis-common",
    "grouping",
    "highlighter",
    "join",
    "memory",
    "misc",
    "queries",
    "queryparser",
    "sandbox",
    "suggest",
)

fun fakeServer(major: Int, luceneVersion: String, product: String) {
    val sourceSet = sourceSets.create("server$major")
    dependencies.add(sourceSet.implementationConfigurationName, "org.apache.lucene:lucene-core:$luceneVersion")

    val luceneJars = configurations.create("server${major}Lucene") {
        isCanBeConsumed = false
        isTransitive = false
    }
    for (module in serverModules) {
        dependencies.add(luceneJars.name, "org.apache.lucene:lucene-$module:$luceneVersion")
    }

    val serverJar = tasks.register<Jar>("server${major}Jar") {
        description = "Builds the fake server's jar with its postings format, for Lucene $major."
        group = "verification"
        from(sourceSet.output)
        exclude("**/ServerIndexWriter*")
        archiveFileName = "$product-0.0.0-leiatest.jar"
        destinationDirectory = layout.buildDirectory.dir("server-$major-jar")
    }

    val home = layout.buildDirectory.dir("fake-server-$major")
    val lib = tasks.register<Sync>("fakeServer${major}Lib") {
        description = "Puts together the lib/ of the fake server for Lucene $major."
        group = "verification"
        into(home.map { it.dir("lib") })
        from(serverJar)
        from(luceneJars)
        if (product == "elasticsearch") {
            // Where Elasticsearch's native libraries are, in a folder per platform; the fake has none.
            from(layout.projectDirectory.file("src/platform/README.md")) { into("platform") }
        }
    }

    val indexes = layout.buildDirectory.dir("fake-server-$major-indexes")
    val writer = tasks.register<JavaExec>("fakeServer${major}Indexes") {
        description = "Writes the indexes of the fake server for Lucene $major."
        group = "verification"
        classpath = sourceSet.runtimeClasspath
        mainClass = "io.github.mfuerstenau.leia.testserver.ServerIndexWriter"
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        // For the fake Elasticsearch's LoaderHelper.
        systemProperty("es.nativelibs.path", home.get().dir("lib/platform/any").asFile.absolutePath)
        dependsOn(lib)
        val dirs = listOf("index", "plain").map { name -> indexes.map { it.dir(name) } }
        for ((name, dir) in listOf("index", "plain").zip(dirs)) {
            outputs.dir(dir).withPropertyName(name)
        }
        argumentProviders.add(CommandLineArgumentProvider { dirs.map { it.get().asFile.absolutePath } })
    }

    // For leia-server's and leia-tui's tests.
    val homeElements = configurations.consumable("fakeServer${major}Elements")
    val indexElements = configurations.consumable("fakeServer${major}IndexElements")
    val plainElements = configurations.consumable("fakeServer${major}PlainIndexElements")
    artifacts {
        add(homeElements.name, home) { builtBy(lib) }
        add(indexElements.name, indexes.map { it.dir("index") }) { builtBy(writer) }
        add(plainElements.name, indexes.map { it.dir("plain") }) { builtBy(writer) }
    }
}

fakeServer(9, libs.versions.serverLucene9.get(), "opensearch")
fakeServer(10, libs.versions.serverLucene10.get(), "elasticsearch")
