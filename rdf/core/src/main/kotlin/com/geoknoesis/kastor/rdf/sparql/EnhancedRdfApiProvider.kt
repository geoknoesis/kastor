package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*

/**
 * Enhanced RDF API provider interface with SPARQL service description support.
 */
interface EnhancedRdfApiProvider : RdfApiProvider {
    
    /**
     * Get the provider category.
     */
    fun getProviderCategory(): ProviderCategory
    
    /**
     * Generate SPARQL service description for this provider.
     */
    fun generateServiceDescription(serviceUri: String): RdfGraph?
    
    /**
     * Get detailed capability information.
     */
    fun getDetailedCapabilities(): DetailedProviderCapabilities
}

/**
 * Detailed provider capabilities with extended information.
 */
data class DetailedProviderCapabilities(
    val basic: ProviderCapabilities,
    val providerCategory: ProviderCategory,
    val supportedSparqlFeatures: Map<String, Boolean>,
    val customExtensionFunctions: List<SparqlExtensionFunction>,
    val performanceMetrics: PerformanceMetrics? = null,
    val limitations: List<String> = emptyList()
)

/**
 * Performance metrics for the provider.
 */
data class PerformanceMetrics(
    val maxQueryComplexity: Int,
    val maxResultSize: Long,
    val averageResponseTime: Double,
    val concurrentQueryLimit: Int,
    val memoryUsageLimit: Long
)
