package com.geoknoesis.kastor.gen.gradle

import com.geoknoesis.kastor.gen.gradle.tasks.OntologyGenerationTask
import org.gradle.api.GradleException
import org.gradle.api.Named
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
 * Generated sources are added to the `main` Kotlin source set of `org.jetbrains.kotlin.jvm` projects and to
 * `jvmMain` of `org.jetbrains.kotlin.multiplatform` projects (the generated code is JVM-only).
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
                task.contextPath.set(config.contextPath)
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
    }
}

/**
 * Adds generated directories to Kotlin source sets without linking against the Kotlin Gradle plugin, so this
 * plugin never ships (or clashes with) its own copy of KGP classes. Source sets are reached through the
 * `kotlin` extension's `getSourceSets()` / `getKotlin()` accessors, which both the JVM and multiplatform
 * extensions expose.
 */
internal object KotlinSourceSetWiring {

    fun wire(project: Project, generated: Provider<Directory>) {
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            addWhenPresent(project, "main", generated, required = true)
        }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            addWhenPresent(project, "jvmMain", generated, required = true)
        }
    }

    private fun addWhenPresent(project: Project, sourceSetName: String, generated: Provider<Directory>, required: Boolean) {
        var wired = false
        sourceSets(project).all { sourceSet ->
            if ((sourceSet as Named).name == sourceSetName) {
                kotlinDirectories(sourceSet).srcDir(generated)
                wired = true
            }
        }
        if (required && !project.state.executed) {
            project.afterEvaluate {
                if (!wired) {
                    throw GradleException(
                        "kastor-gen: Kotlin source set '$sourceSetName' not found in ${project.path}. " +
                            "Generated code is JVM-only; Kotlin Multiplatform projects must declare a jvm() target."
                    )
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun sourceSets(project: Project): NamedDomainObjectContainer<Any> {
        val kotlin = project.extensions.getByName("kotlin")
        return kotlin.javaClass.getMethod("getSourceSets").invoke(kotlin) as NamedDomainObjectContainer<Any>
    }

    private fun kotlinDirectories(sourceSet: Any): SourceDirectorySet =
        sourceSet.javaClass.getMethod("getKotlin").invoke(sourceSet) as SourceDirectorySet
}
