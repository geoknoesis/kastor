package com.geoknoesis.kastor.rdf.shacl

import java.time.Duration

/**
 * Configuration for SHACL validation operations.
 */
data class ValidationConfig(
    val profile: ValidationProfile = ValidationProfile.SHACL_CORE,
    val strictMode: Boolean = false,
    val includeWarnings: Boolean = true,
    val maxViolations: Int = 1000,
    val timeout: Duration = Duration.ofMinutes(5),
    val parallelValidation: Boolean = false,
    val streamingMode: Boolean = false,
    val batchSize: Int = 1000,
    val enableExplanations: Boolean = true,
    val enableSuggestions: Boolean = true,
    val validateClosedShapes: Boolean = true,
    val validateInactiveShapes: Boolean = false,
    val customParameters: Map<String, Any> = emptyMap()
) {
    
    companion object {
        /**
         * Create a default configuration.
         */
        fun default(): ValidationConfig = ValidationConfig()
        
        /**
         * Create a configuration for SHACL Core validation.
         */
        fun shaclCore(): ValidationConfig = ValidationConfig(
            profile = ValidationProfile.SHACL_CORE
        )
        
        /**
         * Create a configuration for SHACL SPARQL validation.
         */
        fun shaclSparql(): ValidationConfig = ValidationConfig(
            profile = ValidationProfile.SHACL_SPARQL
        )
        
        /**
         * Create a configuration for strict validation.
         */
        fun strict(): ValidationConfig = ValidationConfig(
            strictMode = true,
            validateClosedShapes = true,
            validateInactiveShapes = true
        )
        
        /**
         * Create a configuration for large graphs.
         */
        fun forLargeGraphs(): ValidationConfig = ValidationConfig(
            streamingMode = true,
            parallelValidation = true,
            batchSize = 5000,
            maxViolations = 10000
        )
        
        /**
         * Create a configuration for fast validation.
         */
        fun forFastValidation(): ValidationConfig = ValidationConfig(
            includeWarnings = false,
            enableExplanations = false,
            enableSuggestions = false,
            timeout = Duration.ofMinutes(1)
        )
        
        /**
         * Create a configuration for memory-constrained environments.
         */
        fun forMemoryConstrained(): ValidationConfig = ValidationConfig(
            streamingMode = true,
            batchSize = 100,
            maxViolations = 100
        )
    }
}

/**
 * Validation profiles for different SHACL constraint types.
 */
enum class ValidationProfile {
    SHACL_CORE,           // Core SHACL constraints (PropertyShape, NodeShape)
    SHACL_SPARQL,         // SPARQL-based constraints
    SHACL_JS,             // JavaScript-based constraints
    SHACL_PY,             // Python-based constraints
    SHACL_DASH,           // DASH extensions
    CUSTOM,               // Custom constraint types
    STRICT,               // Strict validation mode
    PERMISSIVE,           // Permissive validation mode
    COMPREHENSIVE         // All available constraint types
}

/**
 * SHACL Shape representation.
 */
data class ShaclShape(
    val shapeUri: String,
    val targetClass: String? = null,
    val targetNode: String? = null,
    val targetSubjectsOf: String? = null,
    val targetObjectsOf: String? = null,
    val deactivated: Boolean = false,
    val closed: Boolean = false,
    val ignoredProperties: List<String> = emptyList(),
    val constraints: List<ShaclConstraint> = emptyList()
)

/**
 * SHACL Constraint representation.
 */
data class ShaclConstraint(
    val constraintType: ConstraintType,
    val path: String? = null,
    val severity: ViolationSeverity = ViolationSeverity.VIOLATION,
    val message: String? = null,
    val parameters: Map<String, Any> = emptyMap()
)

/**
 * SHACL constraint types.
 */
enum class ConstraintType {
    // Property constraints
    PROPERTY_SHAPE,
    MIN_COUNT,
    MAX_COUNT,
    UNIQUE_LANG,
    LANGUAGE_IN,
    EQUALS,
    DISJOINT,
    LESS_THAN,
    LESS_THAN_OR_EQUALS,
    NOT,
    AND,
    OR,
    XONE,
    NODE,
    
    // Value constraints
    DATATYPE,
    CLASS,
    NODE_KIND,
    MIN_LENGTH,
    MAX_LENGTH,
    PATTERN,
    FLAGS,
    MIN_INCLUSIVE,
    MAX_INCLUSIVE,
    MIN_EXCLUSIVE,
    MAX_EXCLUSIVE,
    IN,
    HAS_VALUE,
    
    // SPARQL constraints
    SPARQL_CONSTRAINT,
    SPARQL_CONSTRAINT_COMPONENT,
    
    // JavaScript constraints
    JS_CONSTRAINT,
    
    // Python constraints
    PY_CONSTRAINT,
    
    // Custom constraints
    CUSTOM_CONSTRAINT
}

/**
 * Violation severity levels.
 */
enum class ViolationSeverity {
    INFO,        // Informational message
    WARNING,     // Warning that should be addressed
    VIOLATION,   // Constraint violation
    ERROR        // Critical error
}









