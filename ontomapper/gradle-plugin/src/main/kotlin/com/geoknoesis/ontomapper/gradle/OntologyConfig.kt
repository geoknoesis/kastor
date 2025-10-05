package com.geoknoesis.ontomapper.gradle

import org.gradle.api.provider.Property

/**
 * Configuration for a single ontology generation.
 * 
 * This class represents the configuration for generating code from
 * a single SHACL file and JSON-LD context file pair.
 */
abstract class OntologyConfig {
    
    /**
     * Path to the SHACL file (relative to project root or resources).
     */
    @get:org.gradle.api.tasks.Input
    abstract val shaclPath: Property<String>
    
    /**
     * Path to the JSON-LD context file (relative to project root or resources).
     */
    @get:org.gradle.api.tasks.Input
    abstract val contextPath: Property<String>
    
    /**
     * Target package name for generated interfaces and wrappers (legacy).
     * @deprecated Use specific package properties instead
     */
    @get:org.gradle.api.tasks.Input
    @get:org.gradle.api.tasks.Optional
    @Deprecated("Use interfacePackage, wrapperPackage, and vocabularyPackage instead")
    abstract val targetPackage: Property<String>
    
    /**
     * Package name for generated interfaces.
     */
    @get:org.gradle.api.tasks.Input
    @get:org.gradle.api.tasks.Optional
    abstract val interfacePackage: Property<String>
    
    /**
     * Package name for generated wrappers.
     */
    @get:org.gradle.api.tasks.Input
    @get:org.gradle.api.tasks.Optional
    abstract val wrapperPackage: Property<String>
    
    /**
     * Package name for generated vocabulary.
     */
    @get:org.gradle.api.tasks.Input
    @get:org.gradle.api.tasks.Optional
    abstract val vocabularyPackage: Property<String>
    
    /**
     * Whether to generate domain interfaces (default: true).
     */
    @get:org.gradle.api.tasks.Input
    @get:org.gradle.api.tasks.Optional
    abstract val generateInterfaces: Property<Boolean>
    
    /**
     * Whether to generate wrapper implementations (default: true).
     */
    @get:org.gradle.api.tasks.Input
    @get:org.gradle.api.tasks.Optional
    abstract val generateWrappers: Property<Boolean>
    
    /**
     * Output directory for generated files (default: build/generated/sources/ontomapper).
     */
    @get:org.gradle.api.tasks.Input
    @get:org.gradle.api.tasks.Optional
    abstract val outputDirectory: Property<String>
    
    /**
     * Whether to generate vocabulary file (default: false).
     */
    @get:org.gradle.api.tasks.Input
    @get:org.gradle.api.tasks.Optional
    abstract val generateVocabulary: Property<Boolean>
    
    /**
     * Name of the vocabulary (e.g., "DCAT").
     */
    @get:org.gradle.api.tasks.Input
    @get:org.gradle.api.tasks.Optional
    abstract val vocabularyName: Property<String>
    
    /**
     * Namespace URI for the vocabulary.
     */
    @get:org.gradle.api.tasks.Input
    @get:org.gradle.api.tasks.Optional
    abstract val vocabularyNamespace: Property<String>
    
    /**
     * Prefix for the vocabulary (e.g., "dcat").
     */
    @get:org.gradle.api.tasks.Input
    @get:org.gradle.api.tasks.Optional
    abstract val vocabularyPrefix: Property<String>
    
    init {
        // Set default values
        generateInterfaces.convention(true)
        generateWrappers.convention(true)
        outputDirectory.convention("build/generated/sources/ontomapper")
        generateVocabulary.convention(false)
    }
}
