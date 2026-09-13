package com.geoknoesis.kastor.rdf.reasoning

import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for RDF reasoner providers.
 *
 * Provider selection is deterministic: among the providers supporting a [ReasonerType], the one with
 * the highest [RdfReasonerProvider.priority] wins (ties broken by [RdfReasonerProvider.getType]).
 */
object ReasonerRegistry {

    private val providers = ConcurrentHashMap<String, RdfReasonerProvider>()

    private val selectionOrder =
        compareByDescending<RdfReasonerProvider> { it.priority() }.thenBy { it.getType() }

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

    /** Removes a provider by type (test support). */
    internal fun unregister(type: String) {
        providers.remove(type)
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
     * Create a reasoner by type with that type's recommended configuration ([ReasonerConfig.forType]).
     */
    fun createReasoner(type: ReasonerType): RdfReasoner {
        return createReasoner(ReasonerConfig.forType(type))
    }

    /**
     * Discover available reasoner providers.
     */
    fun discoverProviders(): List<RdfReasonerProvider> {
        val serviceLoader = java.util.ServiceLoader.load(RdfReasonerProvider::class.java)
        serviceLoader.forEach { provider ->
            register(provider)
        }
        return getProviders()
    }

    /**
     * Get all registered providers, in selection order (highest priority first).
     */
    fun getProviders(): List<RdfReasonerProvider> {
        return providers.values.sortedWith(selectionOrder)
    }

    /**
     * Get supported reasoner types.
     */
    fun getSupportedTypes(): List<ReasonerType> {
        return getProviders().flatMap { it.getSupportedTypes() }.distinct()
    }

    /**
     * Check if a reasoner type is supported.
     */
    fun isSupported(type: ReasonerType): Boolean {
        return providers.values.any { it.isSupported(type) }
    }

    /** The provider that [createReasoner] uses for [type], or null. */
    fun findProviderForType(type: ReasonerType): RdfReasonerProvider? {
        return getProviders().firstOrNull { it.isSupported(type) }
    }
}
