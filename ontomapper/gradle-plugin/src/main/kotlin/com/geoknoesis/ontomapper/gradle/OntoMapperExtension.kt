package com.geoknoesis.ontomapper.gradle

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional

/**
 * Extension for configuring OntoMapper plugin.
 * 
 * This extension allows configuring ontology generation through Gradle build scripts
 * without requiring annotations in the source code. Supports both single and multiple
 * ontology configurations.
 */
abstract class OntoMapperExtension {
    
    /**
     * Container for multiple ontology configurations.
     */
    abstract val ontologies: NamedDomainObjectContainer<OntologyConfig>
    
    // Legacy single ontology configuration (for backward compatibility)
    
    /**
     * Path to the SHACL file (relative to project root or resources).
     * @deprecated Use ontologies container instead
     */
    @get:Input
    @get:Optional
    @Deprecated("Use ontologies container instead", ReplaceWith("ontologies"))
    abstract val shaclPath: Property<String>
    
    /**
     * Path to the JSON-LD context file (relative to project root or resources).
     * @deprecated Use ontologies container instead
     */
    @get:Input
    @get:Optional
    @Deprecated("Use ontologies container instead", ReplaceWith("ontologies"))
    abstract val contextPath: Property<String>
    
    /**
     * Target package name for generated interfaces and wrappers.
     * @deprecated Use ontologies container instead
     */
    @get:Input
    @get:Optional
    @Deprecated("Use ontologies container instead", ReplaceWith("ontologies"))
    abstract val targetPackage: Property<String>
    
    /**
     * Whether to generate domain interfaces (default: true).
     * @deprecated Use ontologies container instead
     */
    @get:Input
    @get:Optional
    @Deprecated("Use ontologies container instead", ReplaceWith("ontologies"))
    abstract val generateInterfaces: Property<Boolean>
    
    /**
     * Whether to generate wrapper implementations (default: true).
     * @deprecated Use ontologies container instead
     */
    @get:Input
    @get:Optional
    @Deprecated("Use ontologies container instead", ReplaceWith("ontologies"))
    abstract val generateWrappers: Property<Boolean>
    
    /**
     * Output directory for generated files (default: build/generated/sources/ontomapper).
     * @deprecated Use ontologies container instead
     */
    @get:Input
    @get:Optional
    @Deprecated("Use ontologies container instead", ReplaceWith("ontologies"))
    abstract val outputDirectory: Property<String>
    
    init {
        // Set default values for legacy properties
        generateInterfaces.convention(true)
        generateWrappers.convention(true)
        outputDirectory.convention("build/generated/sources/ontomapper")
    }
    
    /**
     * DSL method to configure a single ontology.
     * 
     * @param name The name of the ontology configuration
     * @param configure The configuration block
     */
    fun ontology(name: String, configure: OntologyConfig.() -> Unit) {
        val config = ontologies.create(name)
        config.configure()
    }
    
    /**
     * DSL method to configure multiple ontologies.
     * 
     * @param configure The configuration block for the ontologies container
     */
    fun ontologies(configure: NamedDomainObjectContainer<OntologyConfig>.() -> Unit) {
        ontologies.configure()
    }
}
