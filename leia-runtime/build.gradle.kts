// The interface between leia and the runtimes that read indexes Lucene 10 can't (ADR 0044): JDK
// types and records of its own only, so a runtime is built against it and nothing else of leia. It is
// part of leia.jar; a runtime takes it compileOnly.
plugins {
    id("leia.java-library-conventions")
}

dependencies {
    // JSpecify's @Nullable on the records and parameters (ADR 0127): compile-only, and through
    // compileOnlyApi for the modules that build against the interface.
    compileOnlyApi(libs.jspecify)
}
