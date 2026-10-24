// Lucene 9.12.3 for indexes that Lucene 10 can't read (ADR 0049), in a class loader of its own (ADR
// 0073). The adapter jar, its list and its check come from the convention.
plugins {
    id("leia.lucene-runtime-conventions")
}

dependencies {
    implementation(libs.lucene9.core)
    // The codecs of 8.x.
    implementation(libs.lucene9.backward.codecs)
    implementation(libs.lucene9.queryparser)
    // The language analyzers for the query parser.
    implementation(libs.lucene9.analysis.common)
    // The UnifiedHighlighter (ADR 0063); brings lucene-memory along.
    implementation(libs.lucene9.highlighter)
    // The Completion postings formats of the completion fields of Elasticsearch 7.
    implementation(libs.lucene9.suggest)
}

luceneRuntime {
    // The test indexes, for this module's tests, leia-server's tests through LeiaBackend and leia-tui's
    // Pilot tests. lucene8 is like leia-lucene7's lucene7: two commits, a plain and a compound
    // segment, a deletion, offsets, payloads, doc values of every type and an update, points, soft
    // deletes, and an index sort. lucene8to9 is a copy that 9.12 adds a commit to, with KNN vectors.
    // lucene8completion has a completion field, whose postings format is in lucene-suggest.
    testIndex(
        "lucene8",
        "io.github.mfuerstenau.leia.lucene9.Lucene8IndexWriter",
        libs.lucene8.core,
        libs.lucene8.analyzers.common,
    )
    testIndex(
        "lucene8to9",
        "io.github.mfuerstenau.leia.lucene9.Lucene8to9IndexWriter",
        libs.lucene9.core,
        // The codecs of 8.x, to open the copy.
        libs.lucene9.backward.codecs,
    )
    testIndex(
        "lucene8completion",
        "io.github.mfuerstenau.leia.lucene9.Lucene8CompletionIndexWriter",
        libs.lucene8.core,
        libs.lucene8.suggest,
    )
}

// lucene8to9 starts as a copy of lucene8, the writer's second argument.
tasks.named<JavaExec>("lucene8to9TestIndex") {
    val lucene8 = tasks.named("lucene8TestIndex").map { it.outputs.files.singleFile }
    inputs.dir(lucene8).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("lucene8")
    argumentProviders.add(CommandLineArgumentProvider { listOf(lucene8.get().absolutePath) })
}
