package io.github.mfuerstenau.leia.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register

/**
 * A resolvable configuration with the jars of the runtime module [runtime]'s folder, such as
 * `leia-lucene9` (ADR 0073): its adapter jar and its runtime classpath, as the module's
 * runtimeElements resolve transitively. Named `<folder>RuntimeFolder`, such as
 * `lucene9RuntimeFolder`.
 */
fun Project.runtimeFolderJars(runtime: String): Configuration =
    configurations.create("${runtime.removePrefix("leia-")}RuntimeFolder") {
        isCanBeConsumed = false
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        }
        dependencies.add(project.dependencies.project(mapOf("path" to ":$runtime")))
    }

/** The runtime modules, oldest first, each with its folder in `runtimes/`. */
val RUNTIME_MODULES = listOf("leia-lucene3", "leia-lucene5", "leia-lucene7", "leia-lucene9", "leia-lucene10")

/** The folder of a runtime module in `runtimes/`, such as `lucene9` for `leia-lucene9`. */
fun runtimeFolder(runtime: String): String = runtime.removePrefix("leia-")

/** The jars of each runtime module's folder, by module, as [runtimeFolderJars] resolves them. */
fun Project.runtimeFolders(): Map<String, Configuration> = RUNTIME_MODULES.associateWith { runtimeFolderJars(it) }

/**
 * Registers `runtimeAdapters`: leia's own jars of each runtime's folder in [folders], the adapter
 * and for Lucene 10 leia-core's, under `leia-runtimes/<folder>/`, as leia.jar embeds them for the
 * install; Lucene's come from Maven Central (ADR 0073).
 */
fun Project.registerRuntimeAdapters(folders: Map<String, Configuration>): TaskProvider<Sync> =
    tasks.register<Sync>("runtimeAdapters") {
        description = "Puts leia's own jars of the runtimes' folders together, as leia.jar embeds them."
        group = "build"
        into(layout.buildDirectory.dir("generated/runtime-adapters"))
        for ((module, jars) in folders) {
            val own = jars.incoming.artifactView {
                componentFilter { it is ProjectComponentIdentifier }
            }.files
            from(own) { into("leia-runtimes/" + runtimeFolder(module)) }
        }
    }

/**
 * Registers `testRuntimes`: the old runtimes' folders in [folders] for the tests, whose classpath
 * has Lucene 10, so Lucene 10's folder isn't among them.
 */
fun Project.registerTestRuntimes(folders: Map<String, Configuration>): TaskProvider<Sync> =
    tasks.register<Sync>("testRuntimes") {
        description = "Puts the old runtimes' folders together for the tests."
        group = "verification"
        into(layout.buildDirectory.dir("test-runtimes"))
        for ((module, jars) in folders - "leia-lucene10") {
            from(jars) { into(runtimeFolder(module)) }
        }
    }

/**
 * Registers `testLucene10Folder`: Lucene 10's folder in [folders], apart, for the tests of the
 * install, which serve its jars; loading it from `testRuntimes` would put it into the jar chain a
 * second time.
 */
fun Project.registerTestLucene10Folder(folders: Map<String, Configuration>): TaskProvider<Sync> =
    tasks.register<Sync>("testLucene10Folder") {
        description = "Puts Lucene 10's runtime folder together for the tests."
        group = "verification"
        into(layout.buildDirectory.dir("test-lucene10"))
        from(folders.getValue("leia-lucene10"))
    }
