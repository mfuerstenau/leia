package io.github.mfuerstenau.leia.buildlogic

import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Writes a runtime's list, `META-INF/leia/runtime.txt` under [outputDir], which goes into its
 * adapter jar (ADR 0073): one line per jar that sits beside the adapter in the runtime's folder,
 * `<file name> <SHA-256> <size>`, and for a jar from Maven Central its path there as a fourth field,
 * such as `org/apache/lucene/lucene-core/9.12.3/lucene-core-9.12.3.jar`. leia's own jars, such as
 * leia-core's, have no fourth field: `leia.jar` brings them.
 *
 * leia reads the list to know whether a folder is complete and which jars to load, and `leia
 * --download-runtimes` to know what to fetch.
 */
@CacheableTask
abstract class RuntimeListTask : DefaultTask() {

    /** One entry per jar, `<file name>` or `<file name> <Maven path>`, from [describe]. */
    @get:Input
    abstract val entries: ListProperty<String>

    /** The jars themselves, to hash. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val jars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val byName = jars.files.associateBy { it.name }
        val lines = entries.get().map { entry ->
            val name = entry.substringBefore(' ')
            val file = byName[name] ?: throw GradleException("$name is not among the resolved jars")
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            val mavenPath = entry.substringAfter(' ', "")
            listOf(name, sha256, file.length().toString(), mavenPath).filter { it.isNotEmpty() }.joinToString(" ")
        }
        val list = outputDir.get().file(PATH).asFile
        list.parentFile.mkdirs()
        list.writeText(
            "# The jars beside this runtime's adapter jar: file, SHA-256, size, and the path on Maven\n" +
                "# Central for those that leia.jar doesn't bring (ADR 0073).\n" +
                lines.joinToString("") { "$it\n" })
    }

    companion object {
        const val PATH = "META-INF/leia/runtime.txt"
        private const val BUFFER = 64 * 1024

        /** The entry for a resolved jar: its file name, and its Maven path if it is a module's. */
        fun describe(artifact: ResolvedArtifactResult): String {
            val name = artifact.file.name
            val id = artifact.id.componentIdentifier
            return if (id is ModuleComponentIdentifier) {
                "$name ${id.group.replace('.', '/')}/${id.module}/${id.version}/$name"
            } else {
                name
            }
        }
    }
}
