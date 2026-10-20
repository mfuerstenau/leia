// Shared setup for a module leia-luceneN with an old Lucene, for indexes that Lucene 10 can't read
// (ADR 0044): the runtime folder of leia.runtime-folder-conventions, whose class loader of its own
// keeps the official, unrelocated Lucene jars apart from Lucene 10 (ADR 0073). The module declares
// its Lucene itself, since what it needs differs per version (backward-codecs, the queryparser,
// ...), and its test indexes through the extension luceneRuntime.
import io.github.mfuerstenau.leia.buildlogic.LuceneRuntimeExtension

plugins {
    id("leia.runtime-folder-conventions")
}

tasks.withType<JavaCompile>().configureEach {
    // Old Lucene APIs are mostly deprecated; the conventions make every warning an error.
    options.compilerArgs.add("-Xlint:-deprecation")
}

extensions.create("luceneRuntime", LuceneRuntimeExtension::class, project)
