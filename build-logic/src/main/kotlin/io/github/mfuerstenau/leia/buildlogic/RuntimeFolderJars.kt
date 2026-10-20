package io.github.mfuerstenau.leia.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.kotlin.dsl.named

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
