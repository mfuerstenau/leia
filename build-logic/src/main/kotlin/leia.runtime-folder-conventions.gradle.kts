// Shared setup for a runtime module leia-luceneN (ADR 0073): its plain jar, leia-luceneN.jar, is the
// adapter between the runtime interface and the official Lucene jars. It sits with them, and with
// the other jars of its runtime classpath, in a folder runtimes/luceneN beside leia.jar, which leia
// loads through a class loader of its own. The adapter carries the folder's list,
// META-INF/leia/runtime.txt (RuntimeListTask), and holds nothing but leia's code (AdapterJarCheck).
// A consumer takes the folder's content by resolving the module's runtimeElements, transitively.
import io.github.mfuerstenau.leia.buildlogic.AdapterJarCheck
import io.github.mfuerstenau.leia.buildlogic.RuntimeListTask

plugins {
    id("leia.java-library-conventions")
}

val runtimePackage = "io.github.mfuerstenau.leia." + project.name.removePrefix("leia-")

dependencies {
    // compileOnly: the interface is leia's, and leia.jar brings it.
    "compileOnly"(project(":leia-runtime"))
    "testImplementation"(project(":leia-runtime"))
}

tasks.jar {
    archiveFileName = "${project.name}.jar"
}

val runtimeList = tasks.register<RuntimeListTask>("runtimeList") {
    description = "Writes the list of the jars of the runtime's folder."
    val classpath = configurations.runtimeClasspath
    entries = classpath.flatMap { it.incoming.artifacts.resolvedArtifacts }
        .map { artifacts -> artifacts.map { RuntimeListTask.describe(it) } }
    jars.from(classpath)
    outputDir = layout.buildDirectory.dir("generated/runtime-list")
}

sourceSets.main {
    resources.srcDir(runtimeList.flatMap { it.outputDir })
}

val adapterJarCheck = tasks.register<AdapterJarCheck>("adapterJarCheck") {
    description = "Checks that the adapter jar holds only leia's code."
    group = "verification"
    jar = tasks.jar.flatMap { it.archiveFile }
    allowedPackages.add(runtimePackage.replace('.', '/') + "/")
    report = layout.buildDirectory.file("reports/adapter-jar-check.txt")
}

tasks.named("check") {
    dependsOn(adapterJarCheck)
}
