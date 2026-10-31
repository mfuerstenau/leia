// Lucene 3.6.2 for indexes that Lucene 10 can't read: those written by Lucene 2.x and 3.x (ADR 0044),
// in a class loader of its own (ADR 0073). The adapter jar, its list and its check come from the
// convention.
import io.github.mfuerstenau.leia.buildlogic.AdapterJarCheck

plugins {
    id("leia.lucene-runtime-conventions")
}

// RawPostings lives in Lucene's package to reach 3.6's raw postings (ADR 0062), SegmentFiles a
// segment's files (ADR 0113); lucene-core 3.6.2 isn't sealed, so it shares the package from the
// adapter jar.
tasks.named<AdapterJarCheck>("adapterJarCheck") {
    allowedPackages.add("org/apache/lucene/index/RawPostings")
    allowedPackages.add("org/apache/lucene/index/SegmentFiles")
}

dependencies {
    implementation(libs.lucene3.core)
}

luceneRuntime {
    // The Lucene 2.4 test index, written at build time by Lucene24IndexWriter on lucene-core 2.4.1,
    // for this module's tests, leia-server's tests through LeiaBackend and leia-tui's Pilot tests.
    testIndex("lucene24", "io.github.mfuerstenau.leia.lucene3.Lucene24IndexWriter", libs.lucene24.core).configure {
        tasks.named<JavaCompile>(compileJavaTaskName) {
            // 2.4 is raw types throughout.
            options.compilerArgs.add("-Xlint:-rawtypes")
        }
    }
    // The Lucene 3.x test index, written by Lucene3IndexWriter on lucene-core 3.6.2:
    // two commits, a compound and a plain segment, a deletion, a NumericField, term vectors
    // and a field with payloads, which its term vectors don't cover.
    testIndex("lucene3", "io.github.mfuerstenau.leia.lucene3.Lucene3IndexWriter", libs.lucene3.core)
}
