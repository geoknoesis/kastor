package com.example.ontomapper.annotations

/**
 * Annotation for generating domain interfaces and wrappers from SHACL and JSON-LD context files.
 * 
 * This annotation can be applied to a package or class to trigger code generation
 * from ontology files instead of manual interface definitions.
 * 
 * @property shaclPath Path to the SHACL file (relative to resources)
 * @property contextPath Path to the JSON-LD context file (relative to resources)
 * @property packageName Target package name for generated interfaces
 * @property generateInterfaces Whether to generate domain interfaces (default: true)
 * @property generateWrappers Whether to generate wrapper implementations (default: true)
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class GenerateFromOntology(
    val shaclPath: String,
    val contextPath: String,
    val packageName: String = "",
    val generateInterfaces: Boolean = true,
    val generateWrappers: Boolean = true
)

/**
 * Annotation for specifying ontology generation configuration at the package level.
 * 
 * This is a convenience annotation for package-level ontology generation.
 * 
 * @property shaclPath Path to the SHACL file (relative to resources)
 * @property contextPath Path to the JSON-LD context file (relative to resources)
 * @property generateInterfaces Whether to generate domain interfaces (default: true)
 * @property generateWrappers Whether to generate wrapper implementations (default: true)
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class OntologyPackage(
    val shaclPath: String,
    val contextPath: String,
    val generateInterfaces: Boolean = true,
    val generateWrappers: Boolean = true
)
