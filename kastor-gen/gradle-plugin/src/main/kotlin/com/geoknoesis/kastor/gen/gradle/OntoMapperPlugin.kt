package com.geoknoesis.kastor.gen.gradle

import com.geoknoesis.kastor.gen.gradle.tasks.OntologyGenerationTask
import org.gradle.api.GradleException
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectCollection
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.provider.Provider

/**
 * Gradle plugin for Kastor Gen ontology generation.
 *
 * This plugin provides tasks to generate domain interfaces and wrappers
 * from SHACL and JSON-LD context files without requiring annotations.
 * Supports both single and multiple ontology configurations.
 *
 * Generated sources are added to the `main` Kotlin source set of `org.jetbrains.kotlin.jvm` projects and to the
 * `main` compilation of every JVM target of `org.jetbrains.kotlin.multiplatform` projects (e.g. `jvm()` or
 * `jvm("desktop")`; the generated code is JVM-only). Other project types (including Android) are not supported: a
 * project that configures ontologies without one of those plugins fails at configuration time. The
 * `generateOntology<Name>` tasks still work there; add their `outputDirectory` to a source set manually.
 */
class OntoMapperPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        // Create the extension for configuration
        val extension = project.extensions.create("kastorGen", OntoMapperExtension::class.java)

        // Create container for ontology configurations
        val ontologyContainer = project.container(OntologyConfig::class.java) { name ->
            OntologyConfig().apply { this.name = name }
        }
        extension.ontologies = ontologyContainer

        val mainTask = project.tasks.register("generateOntology") {
            it.group = "kastor-gen"
            it.description = "Generate interfaces and wrappers for all configured ontologies"
        }
        ontologyContainer.all { config ->
            require(config.name.matches(Regex("[A-Za-z][A-Za-z0-9_-]*"))) { "Invalid ontology name: ${config.name}" }
            val taskName = "generateOntology${config.name.replaceFirstChar { it.uppercaseChar() }}"
            val generation = project.tasks.register(taskName, OntologyGenerationTask::class.java) { task ->
                task.group = "kastor-gen"
                task.description = "Generate ontology ${config.name}"
                task.ontologyName.set(config.name)
                task.shaclPath.set(config.shaclPath)
                // The JSON-LD context is optional.
                config.contextPath.takeIf { it.isNotBlank() }?.let { task.contextPath.set(it) }
                // Only user-assigned values are forwarded, so the task's documented defaults apply otherwise.
                config.interfacePackage?.let { task.interfacePackage.set(it) }
                config.wrapperPackage?.let { task.wrapperPackage.set(it) }
                config.vocabularyPackage?.let { task.vocabularyPackage.set(it) }
                config.generateInterfaces?.let { task.generateInterfaces.set(it) }
                config.generateWrappers?.let { task.generateWrappers.set(it) }
                config.generateVocabulary?.let { task.generateVocabulary.set(it) }
                config.vocabularyName?.let { task.vocabularyName.set(it) }
                config.vocabularyNamespace?.let { task.vocabularyNamespace.set(it) }
                config.vocabularyPrefix?.let { task.vocabularyPrefix.set(it) }
                config.generateDsl?.let { task.generateDsl.set(it) }
                config.dslPackage?.let { task.dslPackage.set(it) }
                config.dslName?.let { task.dslName.set(it) }
                val output = config.outputDirectory
                if (output != null) {
                    task.outputDirectory.set(project.file(output).resolve(config.name))
                } else {
                    task.outputDirectory.set(project.layout.buildDirectory.dir("generated/sources/kastor-gen/${config.name}"))
                }
            }
            mainTask.configure { it.dependsOn(generation) }
            KotlinSourceSetWiring.wire(project, generation.flatMap { it.outputDirectory })
        }

        // Without a supported Kotlin plugin the generated sources would silently not be compiled.
        if (!project.state.executed) {
            project.afterEvaluate {
                if (ontologyContainer.isNotEmpty() && !KotlinSourceSetWiring.hasSupportedKotlinPlugin(project)) {
                    throw GradleException(
                        "kastor-gen: ${project.path} configures kastorGen ontologies but applies neither " +
                            "org.jetbrains.kotlin.jvm nor org.jetbrains.kotlin.multiplatform, so the generated sources " +
                            "would not be compiled. Apply one of them (Android projects are not supported; add the " +
                            "outputDirectory of the generateOntology<Name> tasks to a source set yourself)."
                    )
                }
            }
        }
    }
}

/**
 * Adds generated directories to Kotlin source sets without linking against the Kotlin Gradle plugin, so this
 * plugin never ships (or clashes with) its own copy of KGP classes. The JVM extension is reached through
 * `getSourceSets()` / `getKotlin()`, the multiplatform extension through `getTargets()`, each target's
 * `getPlatformType()` and `getCompilations()`, and each compilation's `getDefaultSourceSet()`.
 */
internal object KotlinSourceSetWiring {

    private const val KOTLIN_JVM = "org.jetbrains.kotlin.jvm"
    private const val KOTLIN_MULTIPLATFORM = "org.jetbrains.kotlin.multiplatform"

    fun hasSupportedKotlinPlugin(project: Project): Boolean =
        project.pluginManager.hasPlugin(KOTLIN_JVM) || project.pluginManager.hasPlugin(KOTLIN_MULTIPLATFORM)

    fun wire(project: Project, generated: Provider<Directory>) {
        project.pluginManager.withPlugin(KOTLIN_JVM) {
            addToJvmMain(project, generated)
        }
        project.pluginManager.withPlugin(KOTLIN_MULTIPLATFORM) {
            wireMultiplatform(project, project.extensions.getByName("kotlin"), generated)
        }
    }

    private fun addToJvmMain(project: Project, generated: Provider<Directory>) {
        var wired = false
        sourceSets(project).all { sourceSet ->
            if ((sourceSet as Named).name == "main") {
                kotlinDirectories(sourceSet).srcDir(generated)
                wired = true
            }
        }
        failIfNotWired(project, { wired }, "Kotlin source set 'main' not found in ${project.path}.")
    }

    /**
     * Adds [generated] to the default source set of the `main` compilation of every JVM target (platform type
     * `jvm`) of the multiplatform extension [kotlin], including targets added later. Fails at the end of
     * configuration when the project declares no JVM target.
     */
    fun wireMultiplatform(project: Project, kotlin: Any, generated: Provider<Directory>) {
        var wired = false
        targets(kotlin).all { target ->
            if (platformTypeName(target) == "jvm") {
                compilations(target).all { compilation ->
                    if ((compilation as Named).name == "main") {
                        kotlinDirectories(invoke(compilation, "getDefaultSourceSet")).srcDir(generated)
                        wired = true
                    }
                }
            }
        }
        failIfNotWired(
            project, { wired },
            "no JVM target found in ${project.path}. Generated code is JVM-only; Kotlin Multiplatform projects must " +
                "declare a JVM target (jvm() or jvm(\"name\")).",
        )
    }

    private fun failIfNotWired(project: Project, wired: () -> Boolean, message: String) {
        if (project.state.executed) return
        project.afterEvaluate {
            if (!wired()) throw GradleException("kastor-gen: $message")
        }
    }

    private fun invoke(target: Any, getter: String): Any = target.javaClass.getMethod(getter).invoke(target)

    @Suppress("UNCHECKED_CAST")
    private fun sourceSets(project: Project): NamedDomainObjectContainer<Any> =
        invoke(project.extensions.getByName("kotlin"), "getSourceSets") as NamedDomainObjectContainer<Any>

    @Suppress("UNCHECKED_CAST")
    private fun targets(kotlin: Any): NamedDomainObjectCollection<Any> =
        invoke(kotlin, "getTargets") as NamedDomainObjectCollection<Any>

    @Suppress("UNCHECKED_CAST")
    private fun compilations(target: Any): NamedDomainObjectCollection<Any> =
        invoke(target, "getCompilations") as NamedDomainObjectCollection<Any>

    private fun platformTypeName(target: Any): String = (invoke(target, "getPlatformType") as Enum<*>).name

    private fun kotlinDirectories(sourceSet: Any): SourceDirectorySet = invoke(sourceSet, "getKotlin") as SourceDirectorySet
}
