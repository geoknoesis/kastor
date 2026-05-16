package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Dataset
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Interface for SHACL validator providers.
 * Provides a unified way to access different SHACL validation engines.
 */
interface ShaclValidatorProvider {

    /**
     * Lower values are preferred when [EnginePreference] does not decide outright.
     */
    fun priority(): Int = 100
    
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
    fun validate(graph: RdfGraph, shapes: RdfGraph): ValidationReport

    /**
     * Validate using a SPARQL dataset’s default graph as data. Named graphs are still visible for
     * `sh:shapesGraph` resolution when [ValidationConfig.dataset.validationDataset] is aligned by the implementation.
     */
    fun validateDataset(dataset: Dataset, shapes: RdfGraph?): ValidationReport {
        val shapesGraph = shapes ?: Rdf.graph { }
        return validate(dataset.defaultGraph, shapesGraph)
    }

    /**
     * Stream violations after running full validation (batch semantics).
     */
    fun validateViolationsFlow(graph: RdfGraph, shapes: RdfGraph): Flow<ValidationViolation> = flow {
        validate(graph, shapes).violations.forEach { emit(it) }
    }

    /**
     * Validate a data graph against a list of shapes.
     *
     * **Support is implementation-defined:** many engines (including Kastor native and RDF4J `ShaclSail`)
     * only accept shapes as RDF; they throw [UnsupportedOperationException] when this list is non-empty.
     * Prefer [validate] with a shapes [RdfGraph].
     */
    fun validate(graph: RdfGraph, shapes: List<ShaclShape>): ValidationReport
    
    /**
     * Validate a specific resource against shapes.
     */
    fun validateResource(graph: RdfGraph, shapes: RdfGraph, resource: RdfResource): ValidationReport
    
    /**
     * Validate a graph against specific constraints.
     *
     * **Support is implementation-defined:** Kastor native and RDF4J throw [UnsupportedOperationException]
     * when [constraints] is non-empty; use shapes as a graph instead.
     */
    fun validateConstraints(graph: RdfGraph, constraints: List<ShaclConstraint>): ValidationReport
    
    /**
     * Check if a graph conforms to the shapes (boolean result).
     */
    fun conforms(graph: RdfGraph, shapes: RdfGraph): Boolean
    
    /**
     * Get validation statistics for a graph.
     */
    fun getValidationStatistics(graph: RdfGraph, shapes: RdfGraph): ValidationStatistics
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
    val supportsRdf12TripleTermsInData: Boolean = false,
    val supportsRdf12TripleTermsInShapeParameters: Boolean = false,
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









