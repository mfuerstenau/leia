plugins {
    id("leia.java-library-conventions")
}

dependencies {
    // @Nullable on the records' convenience constructors (ADR 0127).
    compileOnlyApi(libs.jspecify)
}
