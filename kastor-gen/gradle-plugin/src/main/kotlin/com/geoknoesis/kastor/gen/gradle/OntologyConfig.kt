package com.geoknoesis.kastor.gen.gradle

import org.gradle.api.provider.Property

/**
 * Configuration for a single ontology generation.
 * 
 * This class represents the configuration for generating code from
 * a single SHACL file and JSON-LD context file pair.
 */
class OntologyConfig {
    
    /**
     * Name of this ontology configuration.
     */
    var name: String = ""
    
    /**
     * Path to the SHACL file (relative to project root or resources).
     */
    var shaclPath: String = ""
    
    /**
     * Path to the JSON-LD context file (relative to project root or resources).
     */
    var contextPath: String = ""
    
    
    /**
     * Package name for generated interfaces.
     */
    var interfacePackage: String = "interfaces"
    
    /**
     * Package name for generated wrappers.
     */
    var wrapperPackage: String = "wrappers"
    
    /**
     * Package name for generated vocabulary.
     */
    var vocabularyPackage: String = "vocabulary"
    
    /**
     * Whether to generate domain interfaces (default: true).
     */
    var generateInterfaces: Boolean = true
    
    /**
     * Whether to generate wrapper implementations (default: true).
     */
    var generateWrappers: Boolean = true
    
    /**
     * Output directory for generated files (default: build/generated/sources/kastor-gen).
     */
    var outputDirectory: String = "build/generated/sources/kastor-gen"
    
    /**
     * Whether to generate vocabulary file (default: false).
     */
    var generateVocabulary: Boolean = false
    
    /**
     * Name of the vocabulary (e.g., "DCAT").
     */
    var vocabularyName: String = ""
    
    /**
     * Namespace URI for the vocabulary.
     */
    var vocabularyNamespace: String = ""
    
    /**
     * Prefix for the vocabulary (e.g., "dcat").
     */
    var vocabularyPrefix: String = ""
    
}












