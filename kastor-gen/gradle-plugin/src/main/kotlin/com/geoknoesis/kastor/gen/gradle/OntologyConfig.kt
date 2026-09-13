package com.geoknoesis.kastor.gen.gradle

/**
 * Configuration for a single ontology generation.
 *
 * This class represents the configuration for generating code from
 * a single SHACL file and JSON-LD context file pair.
 *
 * Optional settings are `null` until assigned; the generation task then applies its documented defaults
 * (see [com.geoknoesis.kastor.gen.gradle.tasks.OntologyGenerationTask]).
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
     * Package name for generated interfaces (required).
     */
    var interfacePackage: String? = null

    /**
     * Package name for generated wrappers (default: [interfacePackage]).
     */
    var wrapperPackage: String? = null

    /**
     * Package name for generated vocabulary (default: [interfacePackage]).
     */
    var vocabularyPackage: String? = null

    /**
     * Whether to generate domain interfaces (default: true).
     */
    var generateInterfaces: Boolean? = null

    /**
     * Whether to generate wrapper implementations (default: true).
     */
    var generateWrappers: Boolean? = null

    /**
     * Output directory for generated files, relative to the project directory; the ontology name is appended
     * (default: `<buildDir>/generated/sources/kastor-gen`).
     */
    var outputDirectory: String? = null

    /**
     * Whether to generate a vocabulary file (default: true when [vocabularyName], [vocabularyNamespace] and
     * [vocabularyPrefix] are all set, otherwise false).
     */
    var generateVocabulary: Boolean? = null

    /**
     * Name of the vocabulary (e.g., "DCAT").
     */
    var vocabularyName: String? = null

    /**
     * Namespace URI for the vocabulary.
     */
    var vocabularyNamespace: String? = null

    /**
     * Prefix for the vocabulary (e.g., "dcat").
     */
    var vocabularyPrefix: String? = null

    /**
     * Whether to generate domain-specific DSL builders (default: false).
     */
    var generateDsl: Boolean? = null

    /**
     * Package name for generated DSL code (default: `<interfacePackage>.dsl`).
     */
    var dslPackage: String? = null

    /**
     * Name of the DSL (e.g., "dcat", "skos"). Used as the top-level DSL function name.
     * If not specified, derived from the JSON-LD context file name.
     */
    var dslName: String? = null
}
