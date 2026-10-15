// The interface between leia and the runtimes that read indexes Lucene 10 can't (ADR 0044): JDK
// types and records of its own only, so a runtime is built against it and nothing else of leia. It is
// part of leia.jar; a runtime takes it compileOnly.
plugins {
    id("leia.java-library-conventions")
}
