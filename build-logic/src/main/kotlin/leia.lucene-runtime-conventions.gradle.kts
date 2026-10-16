// Shared setup for a module leia-luceneN: an old Lucene, relocated, for indexes that Lucene 10
// can't read (ADR 0044). Every Lucene class is renamed to io.github.mfuerstenau.leia.luceneN.shaded
// .lucene, so it lives next to Lucene 10 on one classpath. leia finds it through ServiceLoader as a
// LuceneRuntime of leia-runtime, which leia.jar brings: consumers take the shadow jar
// (shadowRuntimeElements), which the module's own code is relocated in too. The module declares its
// Lucene itself, since what it needs differs per version (backward-codecs, the queryparser, ...),
// and its test indexes through the extension luceneRuntime.
import io.github.mfuerstenau.leia.buildlogic.LuceneRuntimeExtension
import io.github.mfuerstenau.leia.buildlogic.RelocationCheck

plugins {
    id("leia.java-library-conventions")
    id("com.gradleup.shadow")
}

val runtimePackage = "io.github.mfuerstenau.leia." + project.name.removePrefix("leia-")

dependencies {
    // compileOnly: the interface is leia's, so it stays out of the shadow jar.
    "compileOnly"(project(":leia-runtime"))
    "testImplementation"(project(":leia-runtime"))
}

tasks.withType<JavaCompile>().configureEach {
    // Old Lucene APIs are mostly deprecated; the conventions make every warning an error.
    options.compilerArgs.add("-Xlint:-deprecation")
}

tasks.shadowJar {
    archiveClassifier = ""
    archiveFileName = "${project.name}.jar"
    relocate("org.apache.lucene", "$runtimePackage.shaded.lucene")
    // The Snowball stemmers of analyzers-common, which Lucene 10's analysis-common has too.
    relocate("org.tartarus", "$runtimePackage.shaded.tartarus")
    // Lucene 4 and later find their codecs and formats through META-INF/services. Shadow drops a
    // path it has seen before the merge sees it, unless duplicates are included: lucene-core's codecs
    // would be lost behind backward-codecs'.
    mergeServiceFiles()
    filesMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    // Lucene 9 is a JPMS module; the relocated classes are not.
    exclude("module-info.class", "META-INF/versions/*/module-info.class")
}

// The plain jar would bring unrelocated classes to a consumer that asks for the default variant.
tasks.jar {
    archiveClassifier = "plain"
}

// Hard rule 5 as a check: the shadow jar holds nothing that is not relocated.
val relocationCheck = tasks.register<RelocationCheck>("relocationCheck") {
    description = "Checks that the shadow jar holds only relocated classes and services."
    group = "verification"
    jar = tasks.shadowJar.flatMap { it.archiveFile }
    allowedPackages.add(runtimePackage.replace('.', '/') + "/")
    report = layout.buildDirectory.file("reports/relocation-check.txt")
}

tasks.named("check") {
    dependsOn(relocationCheck)
}

extensions.create("luceneRuntime", LuceneRuntimeExtension::class, project)
