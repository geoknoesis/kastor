package com.geoknoesis.kastor.rdf

/**
 * Unified registry for RDF providers with enhanced capabilities.
 */
object RdfProviderRegistry : ProviderRegistry {
    @Volatile
    private var delegate: ProviderRegistry = DefaultProviderRegistry()

    fun setDelegate(registry: ProviderRegistry) {
        delegate = registry
    }

    fun resetDelegate() {
        delegate = DefaultProviderRegistry()
    }

    override fun selectProvider(
        requirements: ProviderRequirements,
        preferredProviderId: String?,
        preferredVariantId: String?
    ): ProviderSelection? = delegate.selectProvider(requirements, preferredProviderId, preferredVariantId)

    override fun register(provider: RdfProvider) = delegate.register(provider)
    
    /**
     * Register multiple providers at once.
     * Useful for Android/KMP initialization where ServiceLoader may not work.
     * 
     * @param providers The providers to register
     * 
     */
    fun registerAll(vararg providers: RdfProvider) {
        providers.forEach { register(it) }
    }

    override fun create(config: RdfConfig): RdfRepository = delegate.create(config)

    override fun discoverProviders(): List<RdfProvider> = delegate.discoverProviders()

    override fun getAllProviders(): List<RdfProvider> = delegate.getAllProviders()

    override fun getSupportedTypes(): List<String> = delegate.getSupportedTypes()

    override fun supports(providerId: String): Boolean = delegate.supports(providerId)

    override fun supports(providerId: ProviderId): Boolean = delegate.supports(providerId)

    override fun supportsVariant(providerId: String, variantId: String): Boolean =
        delegate.supportsVariant(providerId, variantId)

    override fun supportsVariant(providerId: ProviderId, variantId: VariantId): Boolean =
        delegate.supportsVariant(providerId, variantId)

    override fun isSupported(type: String): Boolean = delegate.isSupported(type)

    override fun getProvider(providerId: String): RdfProvider? = delegate.getProvider(providerId)

    override fun getProvider(providerId: ProviderId): RdfProvider? = delegate.getProvider(providerId)

    override fun getProvidersByCategory(category: ProviderCategory): List<RdfProvider> =
        delegate.getProvidersByCategory(category)

    override fun generateServiceDescription(
        providerId: String,
        serviceUri: String,
        variantId: String?
    ): RdfGraph? = delegate.generateServiceDescription(providerId, serviceUri, variantId)

    override fun getAllServiceDescriptions(baseUri: String): Map<String, RdfGraph> =
        delegate.getAllServiceDescriptions(baseUri)

    override fun discoverAllCapabilities(): Map<String, DetailedProviderCapabilities> =
        delegate.discoverAllCapabilities()

    override fun supportsFeature(
        providerId: String,
        feature: String,
        variantId: String?
    ): Boolean = delegate.supportsFeature(providerId, feature, variantId)

    override fun getSupportedFeatures(): Map<String, List<String>> =
        delegate.getSupportedFeatures()

    override fun hasProviderWithFeature(feature: String): Boolean =
        delegate.hasProviderWithFeature(feature)

    override fun getProviderStatistics(): Map<ProviderCategory, Int> =
        delegate.getProviderStatistics()
}

/**
 * Manages the default RDF provider.
 */
object DefaultRdfProvider {
    /**
     * Default provider ID used when no provider is specified.
     */
    const val DEFAULT_PROVIDER_ID = "memory"

    // Shared mutable selection on a singleton: @Volatile gives set()/get() across
    // threads a happens-before relationship (mirrors RdfProviderRegistry.delegate).
    @Volatile
    private var current: String = DEFAULT_PROVIDER_ID
    
    fun set(provider: String) {
        current = provider
    }

    fun set(provider: ProviderId) {
        current = provider.value
    }
    
    fun get(): String = current

    fun getId(): ProviderId = ProviderId(current)
}
