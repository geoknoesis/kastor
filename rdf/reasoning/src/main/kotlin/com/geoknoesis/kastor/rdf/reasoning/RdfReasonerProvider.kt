package com.geoknoesis.kastor.rdf.reasoning

/**
 * Interface for RDF reasoner providers.
 * Provides a unified way to access different reasoning engines.
 */
interface RdfReasonerProvider {
    
    /**
     * Get the reasoner type identifier.
     */
    fun getType(): String
    
    /**
     * Get the reasoner name.
     */
    val name: String
    
    /**
     * Get the reasoner version.
     */
    val version: String
    
    /**
     * Create a reasoner with the given configuration.
     */
    fun createReasoner(config: ReasonerConfig): RdfReasoner
    
    /**
     * Get reasoner capabilities.
     */
    fun getCapabilities(): ReasonerCapabilities
    
    /**
     * Get supported reasoner types.
     */
    fun getSupportedTypes(): List<ReasonerType>
    
    /**
     * Check if a reasoner type is supported.
     */
    fun isSupported(type: ReasonerType): Boolean
}

/**
 * Core reasoner interface.
 */
interface RdfReasoner {
    
    /**
     * Perform reasoning on a graph.
     */
    fun reason(graph: com.geoknoesis.kastor.rdf.RdfGraph): ReasoningResult
    
    /**
     * Check consistency of a graph.
     */
    fun isConsistent(graph: com.geoknoesis.kastor.rdf.RdfGraph): Boolean
    
    /**
     * Get inferred triples from a graph.
     */
    fun getInferredTriples(graph: com.geoknoesis.kastor.rdf.RdfGraph): List<com.geoknoesis.kastor.rdf.RdfTriple>
    
    /**
     * Perform classification on a graph.
     */
    fun classify(graph: com.geoknoesis.kastor.rdf.RdfGraph): ClassificationResult
    
    /**
     * Validate ontology constraints.
     */
    fun validateOntology(graph: com.geoknoesis.kastor.rdf.RdfGraph): ValidationReport
}

/**
 * Reasoner capabilities.
 */
data class ReasonerCapabilities(
    val supportedTypes: Set<ReasonerType>,
    val supportsIncrementalReasoning: Boolean = false,
    val supportsCustomRules: Boolean = false,
    val supportsExplanation: Boolean = false,
    val supportsConsistencyChecking: Boolean = true,
    val supportsClassification: Boolean = true,
    val maxGraphSize: Long = Long.MAX_VALUE,
    val typicalPerformance: PerformanceProfile = PerformanceProfile.MEDIUM
)

enum class PerformanceProfile {
    FAST,      // RDFS, basic OWL
    MEDIUM,    // OWL-EL, OWL-QL
    SLOW       // OWL-DL, complex custom rules
}









