package io.github.mfuerstenau.leia.buildlogic

import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Checks that a runtime's shadow jar holds no class outside [allowedPackages], and no services file
 * that names or registers one: an unrelocated Lucene must never reach leia's classpath (hard rule 5,
 * ADR 0044).
 */
@CacheableTask
abstract class RelocationCheck : DefaultTask() {

    /** The jar to check. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jar: RegularFileProperty

    /** Package prefixes in path form, such as `io/github/mfuerstenau/leia/lucene3/`. */
    @get:Input
    abstract val allowedPackages: ListProperty<String>

    /** Written when the check passes, so that the task can be up to date. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val allowed = allowedPackages.get()
        val allowedNames = allowed.map { it.replace('/', '.') }
        val problems = mutableListOf<String>()
        ZipFile(jar.get().asFile).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val name = entry.name
                if (name.endsWith(".class")) {
                    val className = name.replace(Regex("^META-INF/versions/\\d+/"), "")
                    if (allowed.none { className.startsWith(it) }) problems += "$name is not relocated"
                } else if (name.startsWith(SERVICES)) {
                    val service = name.removePrefix(SERVICES)
                    if (service != LUCENE_RUNTIME && allowedNames.none { service.startsWith(it) }) {
                        problems += "$name names a service that is not relocated"
                    }
                    zip.getInputStream(entry).bufferedReader().useLines { lines ->
                        lines.map { it.substringBefore('#').trim() }
                            .filter { it.isNotEmpty() }
                            .filter { line -> allowedNames.none { line.startsWith(it) } }
                            .forEach { problems += "$name registers $it, which is not relocated" }
                    }
                }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "${jar.get().asFile.name} must hold nothing outside $allowed:\n" +
                    problems.take(MAX_LISTED).joinToString("\n") { "  $it" } +
                    (if (problems.size > MAX_LISTED) "\n  ... and ${problems.size - MAX_LISTED} more" else ""))
        }
        report.get().asFile.writeText("OK\n")
    }

    private companion object {
        const val SERVICES = "META-INF/services/"
        const val MAX_LISTED = 20
        const val LUCENE_RUNTIME = "io.github.mfuerstenau.leia.runtime.LuceneRuntime"
    }
}
