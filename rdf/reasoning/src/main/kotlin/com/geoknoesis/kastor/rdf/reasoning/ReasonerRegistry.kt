package com.geoknoesis.kastor.rdf.reasoning

import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for RDF reasoner providers.
 */
object ReasonerRegistry {
    
    private val providers = ConcurrentHashMap<String, RdfReasonerProvider>()
    
    init {
        // Auto-discover providers using ServiceLoader
        discoverProviders()
    }
    
    /**
     * Register a reasoner provider.
     */
    fun register(provider: RdfReasonerProvider) {
        providers[provider.getType()] = provider
    }
    
    /**
     * Create a reasoner with the given configuration.
     */
    fun createReasoner(config: ReasonerConfig): RdfReasoner {
        val provider = findProviderForType(config.reasonerType)
            ?: throw IllegalArgumentException("No provider found for reasoner type: ${config.reasonerType}")
        
        return provider.createReasoner(config)
    }
    
    /**
     * Create a reasoner by type with default configuration.
     */
    fun createReasoner(type: ReasonerType): RdfReasoner {
        return createReasoner(ReasonerConfig(reasonerType = type))
    }
    
    /**
     * Discover available reasoner providers.
     */
    fun discoverProviders(): List<RdfReasonerProvider> {
        val serviceLoader = java.util.ServiceLoader.load(RdfReasonerProvider::class.java)
        serviceLoader.forEach { provider ->
            register(provider)
        }
        return providers.values.toList()
    }
    
    /**
     * Get all registered providers.
     */
    fun getProviders(): List<RdfReasonerProvider> {
        return providers.values.toList()
    }
    
    /**
     * Get supported reasoner types.
     */
    fun getSupportedTypes(): List<ReasonerType> {
        return providers.values.flatMap { it.getSupportedTypes() }.distinct()
    }
    
    /**
     * Check if a reasoner type is supported.
     */
    fun isSupported(type: ReasonerType): Boolean {
        return providers.values.any { it.isSupported(type) }
    }
    
    private fun findProviderForType(type: ReasonerType): RdfReasonerProvider? {
        return providers.values.find { it.isSupported(type) }
    }
}
