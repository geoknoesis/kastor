package com.geoknoesis.kastor.gen.annotations

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
    val generateWrappers: Boolean = true,
    val validationMode: ValidationMode = ValidationMode.EMBEDDED,
    val validationAnnotations: ValidationAnnotations = ValidationAnnotations.JAKARTA,
    val externalValidatorClass: String = ""
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
    val generateWrappers: Boolean = true,
    val validationMode: ValidationMode = ValidationMode.EMBEDDED,
    val validationAnnotations: ValidationAnnotations = ValidationAnnotations.JAKARTA,
    val externalValidatorClass: String = ""
)

/**
 * Validation mode for code generation.
 *
 * Determines how validation is handled in generated code.
 * This enum is used in annotations and must remain an enum (sealed classes cannot be used in annotations).
 *
 * @property EMBEDDED Generate validation code directly in generated classes
 * @property EXTERNAL Use external validator (requires externalValidatorClass to be specified)
 * @property NONE No validation code is generated
 *
 * @sample com.example.ValidationModeExample
 */
enum class ValidationMode {
    /**
     * Generate validation code directly in generated classes.
     * This mode embeds validation logic into the generated wrapper classes,
     * allowing validation to be performed without external dependencies.
     */
    EMBEDDED,
    
    /**
     * Use an external validator class.
     * This mode generates code that delegates validation to an external validator.
     * Requires `externalValidatorClass` to be specified in the annotation.
     */
    EXTERNAL,
    
    /**
     * No validation code is generated.
     * This mode skips all validation code generation for maximum performance.
     */
    NONE;
    
    companion object {
        /**
         * Default validation mode.
         */
        val DEFAULT: ValidationMode = EMBEDDED
        
        /**
         * Checks if validation is enabled for this mode.
         */
        fun ValidationMode.isEnabled(): Boolean = this != NONE
        
        /**
         * Checks if this mode requires an external validator class.
         */
        fun ValidationMode.requiresExternalValidator(): Boolean = this == EXTERNAL
    }
}

enum class ValidationAnnotations {
    JAKARTA,
    JAVAX,
    NONE
}

/**
 * Annotation for generating instance DSL builders from ontology and SHACL files.
 * 
 * This annotation generates type-safe DSL builders for creating RDF instances
 * that conform to the ontology classes and SHACL constraints.
 * 
 * @property ontologyPath Path to the OWL/RDFS ontology file (relative to resources)
 * @property shaclPath Path to the SHACL shapes file (relative to resources)
 * @property contextPath Optional path to the JSON-LD context file (relative to resources)
 * @property dslName Name of the DSL (e.g., "skos", "dcat")
 * @property packageName Target package name for generated DSL
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class GenerateInstanceDsl(
    val ontologyPath: String = "",
    val shaclPath: String,
    val contextPath: String = "",
    val dslName: String,
    val packageName: String = ""
)












