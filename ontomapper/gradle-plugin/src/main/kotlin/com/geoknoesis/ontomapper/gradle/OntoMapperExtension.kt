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
open class OntoMapperExtension {
    
    /**
     * Container for multiple ontology configurations.
     */
    var ontologies: NamedDomainObjectContainer<OntologyConfig>? = null
    
    // Legacy single ontology configuration (for backward compatibility)
    
    /**
     * Path to the SHACL file (relative to project root or resources).
     * @deprecated Use ontologies container instead
     */
    @Deprecated("Use ontologies container instead", ReplaceWith("ontologies"))
    var shaclPath: String = ""
    
    /**
     * Path to the JSON-LD context file (relative to project root or resources).
     * @deprecated Use ontologies container instead
     */
    @Deprecated("Use ontologies container instead", ReplaceWith("ontologies"))
    var contextPath: String = ""
    
    /**
     * Target package name for generated interfaces and wrappers.
     * @deprecated Use ontologies container instead
     */
    @Deprecated("Use ontologies container instead", ReplaceWith("ontologies"))
    var targetPackage: String = ""
    
    /**
     * Whether to generate domain interfaces (default: true).
     * @deprecated Use ontologies container instead
     */
    @Deprecated("Use ontologies container instead", ReplaceWith("ontologies"))
    var generateInterfaces: Boolean = true
    
    /**
     * Whether to generate wrapper implementations (default: true).
     * @deprecated Use ontologies container instead
     */
    @Deprecated("Use ontologies container instead", ReplaceWith("ontologies"))
    var generateWrappers: Boolean = true
    
    /**
     * Output directory for generated files (default: build/generated/sources/ontomapper).
     * @deprecated Use ontologies container instead
     */
    @Deprecated("Use ontologies container instead", ReplaceWith("ontologies"))
    var outputDirectory: String = "build/generated/sources/ontomapper"
    
    /**
     * Package name for generated interfaces.
     * @deprecated Use ontologies container instead
     */
    @Deprecated("Use ontologies container instead", ReplaceWith("ontologies"))
    var interfacePackage: String = "interfaces"
    
    /**
     * Package name for generated wrappers.
     * @deprecated Use ontologies container instead
     */
    @Deprecated("Use ontologies container instead", ReplaceWith("ontologies"))
    var wrapperPackage: String = "wrappers"
    
    /**
     * Package name for generated vocabulary.
     * @deprecated Use ontologies container instead
     */
    @Deprecated("Use ontologies container instead", ReplaceWith("ontologies"))
    var vocabularyPackage: String = "vocabulary"
    
    /**
     * Whether to generate vocabulary file (default: false).
     * @deprecated Use ontologies container instead
     */
    @Deprecated("Use ontologies container instead", ReplaceWith("ontologies"))
    var generateVocabulary: Boolean = false
    
    /**
     * Name of the vocabulary (e.g., "DCAT").
     * @deprecated Use ontologies container instead
     */
    @Deprecated("Use ontologies container instead", ReplaceWith("ontologies"))
    var vocabularyName: String = ""
    
    /**
     * Namespace URI for the vocabulary.
     * @deprecated Use ontologies container instead
     */
    @Deprecated("Use ontologies container instead", ReplaceWith("ontologies"))
    var vocabularyNamespace: String = ""
    
    /**
     * Prefix for the vocabulary (e.g., "dcat").
     * @deprecated Use ontologies container instead
     */
    @Deprecated("Use ontologies container instead", ReplaceWith("ontologies"))
    var vocabularyPrefix: String = ""
    
    
    /**
     * DSL method to configure a single ontology.
     * 
     * @param name The name of the ontology configuration
     * @param configure The configuration block
     */
    fun ontology(name: String, configure: OntologyConfig.() -> Unit) {
        val config = ontologies?.create(name)
        config?.configure()
    }
    
    /**
     * DSL method to configure multiple ontologies.
     * 
     * @param configure The configuration block for the ontologies container
     */
    fun ontologies(configure: NamedDomainObjectContainer<OntologyConfig>.() -> Unit) {
        ontologies?.configure()
    }
}
