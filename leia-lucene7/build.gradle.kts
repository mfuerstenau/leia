// Lucene 7.7.3, relocated, for indexes that Lucene 10 can't read: those written by Lucene 6.x and
// 7.x (ADR 0048). The relocation, the shadow jar and the relocation check come from the convention.
plugins {
    id("leia.lucene-runtime-conventions")
}

dependencies {
    implementation(libs.lucene7.core)
    // The codecs of 6.x.
    implementation(libs.lucene7.backward.codecs)
    implementation(libs.lucene7.queryparser)
    // The language analyzers for the query parser.
    implementation(libs.lucene7.analyzers.common)
}

luceneRuntime {
    // The test indexes, for this module's tests, leia-server's tests through LeiaBackend and leia-tui's
    // Pilot tests: two commits, a plain and a compound segment, a deletion, offsets, payloads, doc
    // values of every type and points in one and two dimensions; 6.x adds trie-encoded numeric terms
    // and an index sort, 7.x soft deletes.
    testIndex(
        "lucene6",
        "io.github.mfuerstenau.leia.lucene7.Lucene6IndexWriter",
        libs.lucene6.core,
        libs.lucene6.analyzers.common,
    )
    testIndex(
        "lucene7",
        "io.github.mfuerstenau.leia.lucene7.Lucene7IndexWriter",
        libs.lucene7.core,
        libs.lucene7.analyzers.common,
    )
}
