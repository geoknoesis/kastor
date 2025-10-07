package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.dsl.StandaloneGraph

/**
 * Enhanced RDF API registry with service description support.
 * Now uses the consolidated RdfApiProvider interface.
 */
object EnhancedRdfApiRegistry {
    
    private val providers = mutableMapOf<String, RdfApiProvider>()
    
    init {
        // Register specialized providers
        register(SparqlEndpointProvider())
        register(ReasonerProvider())
        register(ShaclValidatorProvider())
    }
    
    /**
     * Register an RDF provider.
     */
    fun register(provider: RdfApiProvider) {
        providers[provider.getType()] = provider
    }
    
    /**
     * Create a repository from configuration.
     */
    fun create(config: RdfConfig): RdfRepository {
        val provider = providers[config.type] 
            ?: throw IllegalArgumentException("No provider found for repository type: ${config.type}")
        return provider.createRepository(config)
    }
    
    /**
     * Get provider by type.
     */
    fun getProvider(type: String): RdfApiProvider? = providers[type]
    
    /**
     * Get all providers.
     */
    fun getAllProviders(): List<RdfApiProvider> = providers.values.toList()
    
    /**
     * Get providers by category.
     */
    fun getProvidersByCategory(category: ProviderCategory): List<RdfApiProvider> {
        return providers.values.filter { it.getProviderCategory() == category }
    }
    
    /**
     * Generate service description for a specific provider.
     */
    fun generateServiceDescription(providerType: String, serviceUri: String): RdfGraph? {
        val provider = providers[providerType] ?: return null
        return provider.generateServiceDescription(serviceUri)
    }
    
    /**
     * Get all service descriptions.
     */
    fun getAllServiceDescriptions(baseUri: String): Map<String, RdfGraph> {
        return providers.mapValues { (_, provider) ->
            val serviceUri = "$baseUri/${provider.getType()}"
            provider.generateServiceDescription(serviceUri) ?: StandaloneGraph(emptyList())
        }
    }
    
    /**
     * Discover capabilities for all providers.
     */
    fun discoverAllCapabilities(): Map<String, DetailedProviderCapabilities> {
        return providers.mapValues { (_, provider) ->
            provider.getDetailedCapabilities()
        }
    }
    
    /**
     * Check if a provider supports a specific SPARQL feature.
     */
    fun supportsFeature(providerType: String, feature: String): Boolean {
        val provider = providers[providerType] ?: return false
        val capabilities = provider.getDetailedCapabilities()
        return capabilities.supportedSparqlFeatures[feature] ?: false
    }
    
    /**
     * Get all supported SPARQL features across providers.
     */
    fun getSupportedFeatures(): Map<String, List<String>> {
        return providers.mapValues { (_, provider) ->
            val capabilities = provider.getDetailedCapabilities()
            capabilities.supportedSparqlFeatures.filter { it.value }.keys.toList()
        }
    }
    
    /**
     * Check if any provider supports a specific feature.
     */
    fun hasProviderWithFeature(feature: String): Boolean {
        return providers.values.any { provider ->
            val capabilities = provider.getDetailedCapabilities()
            capabilities.supportedSparqlFeatures[feature] == true
        }
    }
    
    /**
     * Get provider statistics.
     */
    fun getProviderStatistics(): Map<ProviderCategory, Int> {
        val categories = providers.values.groupBy { it.getProviderCategory() }
        return categories.mapValues { it.value.size }
    }
}