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
 * Checks that a jar holds no class in [excludedPackages], and no entry whose name matches one of
 * [excludedEntries]: `leia.jar` must hold no Lucene, neither as classes nor as a nested jar (ADR
 * 0072, ADR 0073).
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

    /** Regular expressions for whole entry names, such as one for nested Lucene jars. */
    @get:Input
    abstract val excludedEntries: ListProperty<String>

    /** Written when the check passes, so that the task can be up to date. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val excluded = excludedPackages.get()
        val entries = excludedEntries.get().map { Regex(it) }
        val problems = ZipFile(jar.get().asFile).use { zip ->
            zip.entries().asSequence()
                .map { it.name }
                .filter { name ->
                    val className = name.replace(Regex("^META-INF/versions/\\d+/"), "")
                    (className.endsWith(".class") && excluded.any { className.startsWith(it) }) ||
                        entries.any { it.matches(name) }
                }
                .toList()
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "${jar.get().asFile.name} must hold nothing in $excluded, nor ${excludedEntries.get()}:\n" +
                    problems.take(MAX_LISTED).joinToString("\n") { "  $it" } +
                    (if (problems.size > MAX_LISTED) "\n  ... and ${problems.size - MAX_LISTED} more" else ""))
        }
        report.get().asFile.writeText("OK\n")
    }

    private companion object {
        const val MAX_LISTED = 20
    }
}
