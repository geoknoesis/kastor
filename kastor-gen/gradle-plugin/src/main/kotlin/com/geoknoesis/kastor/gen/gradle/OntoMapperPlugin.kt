package com.geoknoesis.kastor.gen.gradle

import com.geoknoesis.kastor.gen.gradle.tasks.OntologyGenerationTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.NamedDomainObjectContainer

/**
 * Gradle plugin for Kastor Gen ontology generation.
 * 
 * This plugin provides tasks to generate domain interfaces and wrappers
 * from SHACL and JSON-LD context files without requiring annotations.
 * Supports both single and multiple ontology configurations.
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
                task.shaclPath.set(config.shaclPath)
                task.contextPath.set(config.contextPath)
                task.interfacePackage.set(config.interfacePackage)
                task.wrapperPackage.set(config.wrapperPackage)
                task.vocabularyPackage.set(config.vocabularyPackage)
                task.generateInterfaces.set(config.generateInterfaces)
                task.generateWrappers.set(config.generateWrappers)
                task.generateVocabulary.set(config.generateVocabulary)
                task.vocabularyName.set(config.vocabularyName)
                task.vocabularyNamespace.set(config.vocabularyNamespace)
                task.vocabularyPrefix.set(config.vocabularyPrefix)
                task.generateDsl.set(config.generateDsl)
                task.dslPackage.set(config.dslPackage)
                task.dslName.set(config.dslName)
                task.outputDirectory.set(project.file(config.outputDirectory).resolve(config.name))
            }
            mainTask.configure { it.dependsOn(generation) }
            project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
                project.extensions.getByType(org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension::class.java)
                    .sourceSets.getByName("main").kotlin.srcDir(generation.flatMap { it.outputDirectory })
            }
        }
    }
}
