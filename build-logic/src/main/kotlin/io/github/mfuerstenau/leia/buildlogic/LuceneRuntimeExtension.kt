package io.github.mfuerstenau.leia.buildlogic

import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider

/**
 * The `luceneRuntime` extension of `leia.lucene-runtime-conventions`: the test indexes a runtime
 * module writes at build time with an old Lucene, as leia-core writes its Lucene 8 and 9 indexes;
 * no binary index is checked in.
 */
abstract class LuceneRuntimeExtension(private val project: Project) {

    /**
     * Registers the test index [name], written by [mainClass] on the Lucene jars [lucene] into
     * `build/test-indexes/<name>`: the source set `<name>Index`, the task `<name>TestIndex`, the
     * system property `leia.test.index.<name>` for the module's tests, and the consumable
     * `<name>TestIndexElements` for leia-server's and leia-tui's tests.
     *
     * One source set per index keeps each writer on its own Lucene. The source set is returned, so
     * that a module can adjust its compile task.
     */
    fun testIndex(
        name: String,
        mainClass: String,
        vararg lucene: Provider<MinimalExternalModuleDependency>,
    ): NamedDomainObjectProvider<SourceSet> {
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        val sourceSet = sourceSets.register("${name}Index")
        for (dependency in lucene) {
            project.dependencies.addProvider(sourceSet.get().implementationConfigurationName, dependency)
        }

        val indexDir = project.layout.buildDirectory.dir("test-indexes/$name")
        val writer = project.tasks.register("${name}TestIndex", JavaExec::class.java) {
            description = "Writes the test index $name."
            classpath = sourceSet.get().runtimeClasspath
            this.mainClass.set(mainClass)
            outputs.dir(indexDir).withPropertyName("index")
            argumentProviders.add(CommandLineArgumentProvider { listOf(indexDir.get().asFile.absolutePath) })
        }

        val projectPath = project.projectDir.toPath()
        project.tasks.named("test", Test::class.java) {
            val index: FileCollection = project.files(writer)
            inputs.files(index).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("${name}Index")
            jvmArgumentProviders.add(CommandLineArgumentProvider {
                listOf("-Dleia.test.index.$name=" + projectPath.relativize(indexDir.get().asFile.toPath()))
            })
        }

        val elements = project.configurations.consumable("${name}TestIndexElements")
        project.artifacts.add(elements.name, indexDir) { builtBy(writer) }
        return sourceSet
    }
}
