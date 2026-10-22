package io.github.mfuerstenau.leia.buildlogic

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Checks that the runtime catalog `META-INF/leia/runtimes.json` of leia-server (ADR 0078) names
 * exactly the folders of the runtime modules that the build puts into `runtimes/`, and that its
 * first entry, the jar chain's runtime (ADR 0071), is [latest]. The server checks the rest of its
 * schema when it reads it, which RuntimeCatalogTest covers.
 */
@CacheableTask
abstract class RuntimeCatalogCheck : DefaultTask() {

    /** The catalog. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val catalog: RegularFileProperty

    /** The folders of the runtime modules, such as `lucene9`. */
    @get:Input
    abstract val folders: ListProperty<String>

    /** The folder that must come first. */
    @get:Input
    abstract val latest: Property<String>

    /** Written when the check passes, so that the task can be up to date. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val file = catalog.get().asFile
        val root = try {
            JsonSlurper().parse(file)
        } catch (e: Exception) {
            throw GradleException("${file.name} is not JSON: ${e.message}", e)
        }
        val entries = ((root as? Map<*, *>)?.get("runtimes") as? List<*>)
            ?: throw GradleException("${file.name} has no array \"runtimes\"")
        val inCatalog = entries.map { (it as? Map<*, *>)?.get("folder") as? String }
        val expected = folders.get()
        if (inCatalog.toSet() != expected.toSet() || inCatalog.size != expected.size) {
            throw GradleException(
                "${file.name} names the folders $inCatalog, but the runtime modules (RUNTIME_MODULES of " +
                    "build-logic) have $expected: a runtime is a module and a catalog entry")
        }
        if (inCatalog.first() != latest.get()) {
            throw GradleException("${file.name} must name ${latest.get()} first, not ${inCatalog.first()}")
        }
        report.get().asFile.writeText("OK\n")
    }
}
