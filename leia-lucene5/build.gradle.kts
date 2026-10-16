// Lucene 5.5.5, relocated, for indexes that Lucene 10 can't read: those written by Lucene 4.x and
// 5.x (ADR 0046). The relocation, the shadow jar and the relocation check come from the convention.
plugins {
    id("leia.lucene-runtime-conventions")
}

dependencies {
    implementation(libs.lucene5.core)
    // The codecs of 4.0 to 5.3.
    implementation(libs.lucene5.backward.codecs)
    implementation(libs.lucene5.queryparser)
    // The language analyzers for the query parser.
    implementation(libs.lucene5.analyzers.common)
}

luceneRuntime {
    // The test indexes, for this module's tests, leia-server's tests through LeiaBackend and leia-tui's
    // Pilot tests: two commits, a plain and a compound segment, a deletion, trie-encoded numeric terms,
    // offsets, payloads and doc values of every type; 5.x adds a doc values update.
    testIndex(
        "lucene4",
        "io.github.mfuerstenau.leia.lucene5.Lucene4IndexWriter",
        libs.lucene4.core,
        libs.lucene4.analyzers.common,
    )
    testIndex(
        "lucene5",
        "io.github.mfuerstenau.leia.lucene5.Lucene5IndexWriter",
        libs.lucene5.core,
        libs.lucene5.analyzers.common,
    )
}
