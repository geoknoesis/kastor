package com.geoknoesis.kastor.rdf.shacl

/**
 * Interface for SHACL validator providers.
 * Provides a unified way to access different SHACL validation engines.
 */
interface ShaclValidatorProvider {
    
    /**
     * Get the validator type identifier.
     */
    fun getType(): String
    
    /**
     * Get the validator name.
     */
    val name: String
    
    /**
     * Get the validator version.
     */
    val version: String
    
    /**
     * Create a validator with the given configuration.
     */
    fun createValidator(config: ValidationConfig): ShaclValidator
    
    /**
     * Get validator capabilities.
     */
    fun getCapabilities(): ValidatorCapabilities
    
    /**
     * Get supported validation profiles.
     */
    fun getSupportedProfiles(): List<ValidationProfile>
    
    /**
     * Check if a validation profile is supported.
     */
    fun isSupported(profile: ValidationProfile): Boolean
}

/**
 * Core SHACL validator interface.
 */
interface ShaclValidator {
    
    /**
     * Validate a data graph against a shapes graph.
     */
    fun validate(graph: com.geoknoesis.kastor.rdf.RdfGraph, shapes: com.geoknoesis.kastor.rdf.RdfGraph): ValidationReport
    
    /**
     * Validate a data graph against a list of shapes.
     */
    fun validate(graph: com.geoknoesis.kastor.rdf.RdfGraph, shapes: List<ShaclShape>): ValidationReport
    
    /**
     * Validate a specific resource against shapes.
     */
    fun validateResource(graph: com.geoknoesis.kastor.rdf.RdfGraph, shapes: com.geoknoesis.kastor.rdf.RdfGraph, resource: com.geoknoesis.kastor.rdf.RdfResource): ValidationReport
    
    /**
     * Validate a graph against specific constraints.
     */
    fun validateConstraints(graph: com.geoknoesis.kastor.rdf.RdfGraph, constraints: List<ShaclConstraint>): ValidationReport
    
    /**
     * Check if a graph conforms to the shapes (boolean result).
     */
    fun conforms(graph: com.geoknoesis.kastor.rdf.RdfGraph, shapes: com.geoknoesis.kastor.rdf.RdfGraph): Boolean
    
    /**
     * Get validation statistics for a graph.
     */
    fun getValidationStatistics(graph: com.geoknoesis.kastor.rdf.RdfGraph, shapes: com.geoknoesis.kastor.rdf.RdfGraph): ValidationStatistics
}

/**
 * Validator capabilities.
 */
data class ValidatorCapabilities(
    val supportsShaclCore: Boolean,
    val supportsShaclSparql: Boolean,
    val supportsShaclJs: Boolean,
    val supportsShaclPy: Boolean,
    val supportsShaclDash: Boolean,
    val supportsCustomConstraints: Boolean,
    val supportsParallelValidation: Boolean,
    val supportsStreamingValidation: Boolean,
    val supportsIncrementalValidation: Boolean,
    val maxGraphSize: Long = Long.MAX_VALUE,
    val performanceProfile: PerformanceProfile = PerformanceProfile.MEDIUM
)

/**
 * Performance profiles for validators.
 */
enum class PerformanceProfile {
    FAST,      // Optimized for speed, basic validation
    MEDIUM,    // Balanced performance and features
    THOROUGH   // Comprehensive validation, slower but more thorough
}
