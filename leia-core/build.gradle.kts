plugins {
    id("leia.java-library-conventions")
}

dependencies {
    implementation(libs.lucene.core)
    // Lucene 9 indexes must open with Lucene 10 (AGENTS.md, hard rule 5).
    runtimeOnly(libs.lucene.backward.codecs)
}
