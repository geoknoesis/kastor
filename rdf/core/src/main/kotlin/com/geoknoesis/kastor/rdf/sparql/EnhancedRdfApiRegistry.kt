package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.dsl.StandaloneGraph

/**
 * Enhanced RDF API registry with service description support.
 */
object EnhancedRdfApiRegistry {
    
    private val enhancedProviders = mutableMapOf<String, EnhancedRdfApiProvider>()
    private val standardProviders = mutableMapOf<String, RdfApiProvider>()
    
    init {
        // Register specialized providers
        register(SparqlEndpointProvider())
        register(ReasonerProvider())
        register(ShaclValidatorProvider())
    }
    
    /**
     * Register an enhanced RDF provider.
     */
    fun register(provider: EnhancedRdfApiProvider) {
        enhancedProviders[provider.getType()] = provider
        standardProviders[provider.getType()] = provider
    }
    
    /**
     * Register a standard RDF provider.
     */
    fun register(provider: RdfApiProvider) {
        standardProviders[provider.getType()] = provider
    }
    
    /**
     * Create a repository from configuration.
     */
    fun create(config: RdfConfig): RdfRepository? {
        val provider = standardProviders[config.type] 
            ?: throw IllegalArgumentException("No provider found for repository type: ${config.type}")
        return provider.createRepository(config)
    }
    
    /**
     * Get enhanced provider by type.
     */
    fun getEnhancedProvider(type: String): EnhancedRdfApiProvider? = enhancedProviders[type]
    
    /**
     * Get all enhanced providers.
     */
    fun getEnhancedProviders(): List<EnhancedRdfApiProvider> = enhancedProviders.values.toList()
    
    /**
     * Get providers by category.
     */
    fun getProvidersByCategory(category: ProviderCategory): List<EnhancedRdfApiProvider> {
        return enhancedProviders.values.filter { it.getProviderCategory() == category }
    }
    
    /**
     * Generate service description for a specific provider.
     */
    fun generateServiceDescription(providerType: String, serviceUri: String): RdfGraph? {
        val provider = enhancedProviders[providerType] ?: return null
        return provider.generateServiceDescription(serviceUri)
    }
    
    /**
     * Get all service descriptions.
     */
    fun getAllServiceDescriptions(baseUri: String): Map<String, RdfGraph> {
        return enhancedProviders.mapValues { (_, provider) ->
            val serviceUri = "$baseUri/${provider.getType()}"
            provider.generateServiceDescription(serviceUri) ?: StandaloneGraph(emptyList())
        }
    }
    
    /**
     * Discover capabilities for all providers.
     */
    fun discoverAllCapabilities(): Map<String, DetailedProviderCapabilities> {
        return enhancedProviders.mapValues { (_, provider) ->
            provider.getDetailedCapabilities()
        }
    }
    
    /**
     * Check if a provider supports a specific SPARQL feature.
     */
    fun supportsFeature(providerType: String, feature: String): Boolean {
        val provider = enhancedProviders[providerType] ?: return false
        val capabilities = provider.getDetailedCapabilities()
        return capabilities.supportedSparqlFeatures[feature] ?: false
    }
    
    /**
     * Get all supported SPARQL features across providers.
     */
    fun getSupportedFeatures(): Map<String, List<String>> {
        return enhancedProviders.mapValues { (_, provider) ->
            val capabilities = provider.getDetailedCapabilities()
            capabilities.supportedSparqlFeatures.filter { it.value }.keys.toList()
        }
    }
}
