package com.geoknoesis.ontomapper.gradle

import com.geoknoesis.ontomapper.gradle.tasks.OntologyGenerationTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.NamedDomainObjectContainer

/**
 * Gradle plugin for OntoMapper ontology generation.
 * 
 * This plugin provides tasks to generate domain interfaces and wrappers
 * from SHACL and JSON-LD context files without requiring annotations.
 * Supports both single and multiple ontology configurations.
 */
class OntoMapperPlugin : Plugin<Project> {
    
    override fun apply(project: Project) {
        // Create the extension for configuration
        val extension = project.extensions.create("ontomapper", OntoMapperExtension::class.java)
        
        // Create container for ontology configurations
        val ontologyContainer = project.container(OntologyConfig::class.java) { name ->
            project.objects.newInstance(OntologyConfig::class.java)
        }
        extension.ontologies = ontologyContainer
        
        // Register tasks for each ontology configuration
        project.afterEvaluate {
            // Create tasks for each ontology in the container
            extension.ontologies.forEach { ontologyConfig ->
                val taskName = "generateOntology${ontologyConfig.name.capitalize()}"
                project.tasks.register(taskName, OntologyGenerationTask::class.java) { task ->
                    task.group = "ontomapper"
                    task.description = "Generate domain interfaces and wrappers for ${ontologyConfig.name} ontology"
                    
                    // Configure the task with ontology-specific values
                    task.shaclPath.set(ontologyConfig.shaclPath)
                    task.contextPath.set(ontologyConfig.contextPath)
                    task.targetPackage.set(ontologyConfig.targetPackage)
                    task.interfacePackage.set(ontologyConfig.interfacePackage)
                    task.wrapperPackage.set(ontologyConfig.wrapperPackage)
                    task.vocabularyPackage.set(ontologyConfig.vocabularyPackage)
                    task.generateInterfaces.set(ontologyConfig.generateInterfaces)
                    task.generateWrappers.set(ontologyConfig.generateWrappers)
                    task.generateVocabulary.set(ontologyConfig.generateVocabulary)
                    task.vocabularyName.set(ontologyConfig.vocabularyName)
                    task.vocabularyNamespace.set(ontologyConfig.vocabularyNamespace)
                    task.vocabularyPrefix.set(ontologyConfig.vocabularyPrefix)
                    task.outputDirectory.set(ontologyConfig.outputDirectory)
                }
            }
            
            // Create a main task that depends on all ontology generation tasks
            val mainTask = project.tasks.register("generateOntology") { task ->
                task.group = "ontomapper"
                task.description = "Generate domain interfaces and wrappers from all configured ontologies"
                
                // Add dependencies on all individual ontology tasks
                extension.ontologies.forEach { ontologyConfig ->
                    val taskName = "generateOntology${ontologyConfig.name.capitalize()}"
                    task.dependsOn(taskName)
                }
            }
            
            // Legacy support: if no ontologies are configured, use legacy properties
            if (extension.ontologies.isEmpty()) {
                if (extension.shaclPath.isPresent && extension.contextPath.isPresent) {
                    project.tasks.register("generateOntologyLegacy", OntologyGenerationTask::class.java) { task ->
                        task.group = "ontomapper"
                        task.description = "Generate domain interfaces and wrappers from legacy configuration"
                        
                        task.shaclPath.set(extension.shaclPath)
                        task.contextPath.set(extension.contextPath)
                        task.targetPackage.set(extension.targetPackage)
                        task.interfacePackage.set(extension.interfacePackage)
                        task.wrapperPackage.set(extension.wrapperPackage)
                        task.vocabularyPackage.set(extension.vocabularyPackage)
                        task.generateInterfaces.set(extension.generateInterfaces)
                        task.generateWrappers.set(extension.generateWrappers)
                        task.generateVocabulary.set(extension.generateVocabulary)
                        task.vocabularyName.set(extension.vocabularyName)
                        task.vocabularyNamespace.set(extension.vocabularyNamespace)
                        task.vocabularyPrefix.set(extension.vocabularyPrefix)
                        task.outputDirectory.set(extension.outputDirectory)
                    }
                    
                    mainTask.configure { it.dependsOn("generateOntologyLegacy") }
                }
            }
            
            // Make the main generation task run before compileKotlin
            project.tasks.named("compileKotlin") { compileTask ->
                compileTask.dependsOn(mainTask)
            }
            
            // Add generated sources to source sets
            val mainSourceSet = project.extensions.getByName("sourceSets") as org.gradle.api.tasks.SourceSetContainer
            val main = mainSourceSet.getByName("main")
            
            // Add output directories for all ontologies
            extension.ontologies.forEach { ontologyConfig ->
                main.kotlin.srcDir(ontologyConfig.outputDirectory.get())
            }
            
            // Add legacy output directory if used
            if (extension.ontologies.isEmpty() && extension.outputDirectory.isPresent) {
                main.kotlin.srcDir(extension.outputDirectory.get())
            }
        }
    }
}
