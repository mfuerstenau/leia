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
 * Checks that a jar holds no class in [excludedPackages]: leia-lucene10's jar must bring Lucene 10
 * and leia-core, but neither the runtime interface, which `leia.jar` brings, nor the server (ADR
 * 0065).
 */
@CacheableTask
abstract class ExcludedPackagesCheck : DefaultTask() {

    /** The jar to check. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jar: RegularFileProperty

    /** Package prefixes in path form, such as `io/github/mfuerstenau/leia/runtime/`. */
    @get:Input
    abstract val excludedPackages: ListProperty<String>

    /** Written when the check passes, so that the task can be up to date. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val excluded = excludedPackages.get()
        val problems = ZipFile(jar.get().asFile).use { zip ->
            zip.entries().asSequence()
                .map { it.name.replace(Regex("^META-INF/versions/\\d+/"), "") }
                .filter { name -> name.endsWith(".class") && excluded.any { name.startsWith(it) } }
                .toList()
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "${jar.get().asFile.name} must hold nothing in $excluded:\n" +
                    problems.take(MAX_LISTED).joinToString("\n") { "  $it" } +
                    (if (problems.size > MAX_LISTED) "\n  ... and ${problems.size - MAX_LISTED} more" else ""))
        }
        report.get().asFile.writeText("OK\n")
    }

    private companion object {
        const val MAX_LISTED = 20
    }
}
