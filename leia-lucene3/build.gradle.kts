// Lucene 3.6.2, relocated, for indexes that Lucene 10 can't read: those written by Lucene 2.x and
// 3.x (ADR 0044). The relocation, the shadow jar and the relocation check come from the convention.
plugins {
    id("leia.lucene-runtime-conventions")
}

dependencies {
    implementation(libs.lucene3.core)
}

luceneRuntime {
    // The Lucene 2.4 test index, written at build time by Lucene24IndexWriter on lucene-core 2.4.1,
    // for this module's tests, leia-server's tests through LeiaBackend and leia-tui's Pilot tests.
    testIndex("lucene24", libs.lucene24.core, "io.github.mfuerstenau.leia.lucene3.Lucene24IndexWriter").configure {
        tasks.named<JavaCompile>(compileJavaTaskName) {
            // 2.4 is raw types throughout.
            options.compilerArgs.add("-Xlint:-rawtypes")
        }
    }
    // The Lucene 3.x test index, written by Lucene3IndexWriter on the unrelocated lucene-core 3.6.2:
    // two commits, a compound and a plain segment, a deletion, a NumericField and term vectors.
    testIndex("lucene3", libs.lucene3.core, "io.github.mfuerstenau.leia.lucene3.Lucene3IndexWriter")
}
