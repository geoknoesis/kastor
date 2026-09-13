package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.provider.MemoryRepositoryProvider
import java.util.ServiceLoader
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

data class ProviderSelection(val provider: RdfProvider, val variantId: String)

interface ProviderRegistry {
    fun selectProvider(
        requirements: ProviderRequirements,
        preferredProviderId: String? = null,
        preferredVariantId: String? = null
    ): ProviderSelection?

    fun register(provider: RdfProvider)

    fun create(config: RdfConfig): RdfRepository

    fun discoverProviders(): List<RdfProvider>

    fun getAllProviders(): List<RdfProvider> = discoverProviders()

    fun getSupportedTypes(): List<String>

    fun supports(providerId: String): Boolean

    fun supports(providerId: ProviderId): Boolean = supports(providerId.value)

    fun supportsVariant(providerId: String, variantId: String): Boolean

    fun supportsVariant(providerId: ProviderId, variantId: VariantId): Boolean =
        supportsVariant(providerId.value, variantId.value)

    fun isSupported(type: String): Boolean = supports(type)

    fun getProvider(providerId: String): RdfProvider?

    fun getProvider(providerId: ProviderId): RdfProvider? = getProvider(providerId.value)

    fun getProvidersByCategory(category: ProviderCategory): List<RdfProvider>

    fun generateServiceDescription(
        providerId: String,
        serviceUri: String,
        variantId: String? = null
    ): RdfGraph?

    fun getAllServiceDescriptions(baseUri: String): Map<String, RdfGraph>

    fun discoverAllCapabilities(): Map<String, DetailedProviderCapabilities>

    fun supportsFeature(providerId: String, feature: String, variantId: String? = null): Boolean

    fun getSupportedFeatures(): Map<String, List<String>>

    fun hasProviderWithFeature(feature: String): Boolean

    fun getProviderStatistics(): Map<ProviderCategory, Int>
}

/**
 * Default registry. Providers are kept in a deterministic order - descending
 * [RdfProvider.priority], then registration order (ServiceLoader discovery order for
 * auto-discovered providers) - which [discoverProviders] and every format-based lookup use.
 */
class DefaultProviderRegistry(
    private val autoDiscover: Boolean = true,
    private val registerDefaultMemoryProvider: Boolean = true,
    private val discoveryErrorHandler: (Throwable) -> Unit = {}
) : ProviderRegistry {
    private class Registration(val provider: RdfProvider, val sequence: Long)

    private val lock = ReentrantReadWriteLock()
    private val providers = LinkedHashMap<String, Registration>()
    private val providersByType = LinkedHashMap<String, RdfProvider>()
    private var nextSequence = 0L
    private val discoveryErrors = CopyOnWriteArrayList<Throwable>()

    init {
        if (registerDefaultMemoryProvider) {
            register(MemoryRepositoryProvider())
        }
        if (autoDiscover) {
            discoverWithServiceLoader()
        }
    }

    private fun orderedProviders(): List<RdfProvider> = lock.read {
        providers.values
            .sortedWith(compareByDescending<Registration> { it.provider.priority }.thenBy { it.sequence })
            .map { it.provider }
    }

    override fun selectProvider(
        requirements: ProviderRequirements,
        preferredProviderId: String?,
        preferredVariantId: String?
    ): ProviderSelection? {
        val ordered = orderedProviders()
        val candidates = ordered.filter { it.id == preferredProviderId } + ordered.filterNot { it.id == preferredProviderId }
        candidates.forEach { provider ->
            val variants = if (preferredVariantId != null) {
                provider.variants().filter { it.id == preferredVariantId }
            } else {
                provider.variants()
            }
            variants.forEach { variant ->
                if (matchesRequirements(provider, variant.id, requirements)) {
                    return ProviderSelection(provider, variant.id)
                }
            }
        }
        return null
    }

    /** Registers [provider], replacing (in place, keeping its position) any provider with the same id. */
    override fun register(provider: RdfProvider) {
        lock.write {
            val previous = providers[provider.id]
            if (previous != null) {
                providersByType.entries.removeIf { it.value === previous.provider }
            }
            providers[provider.id] = Registration(provider, previous?.sequence ?: nextSequence++)
            provider.variants().forEach { variant ->
                providersByType[toTypeKey(provider.id, variant.id)] = provider
            }
        }
    }

    override fun create(config: RdfConfig): RdfRepository {
        val selection = resolveSelection(config)
            ?: throw IllegalArgumentException("No provider found for repository config: $config")
        val variant = selection.provider.variants().firstOrNull { it.id == selection.variantId }
        val mergedOptions = (variant?.defaultOptions ?: emptyMap()) + config.options
        val mergedConfig = config.copy(
            providerId = selection.provider.id,
            variantId = selection.variantId,
            options = mergedOptions
        )
        return selection.provider.createRepository(selection.variantId, mergedConfig)
    }

    override fun discoverProviders(): List<RdfProvider> = orderedProviders()

    override fun getSupportedTypes(): List<String> = lock.read { providersByType.keys.toList() }

    override fun supports(providerId: String): Boolean = lock.read {
        providers.containsKey(providerId)
    }

    override fun supportsVariant(providerId: String, variantId: String): Boolean {
        return lock.read {
            providersByType.containsKey(toTypeKey(providerId, variantId))
        }
    }

    override fun getProvider(providerId: String): RdfProvider? = lock.read {
        providers[providerId]?.provider
    }

    override fun getProvidersByCategory(category: ProviderCategory): List<RdfProvider> =
        orderedProviders().filter { it.getProviderCategory() == category }

    override fun generateServiceDescription(
        providerId: String,
        serviceUri: String,
        variantId: String?
    ): RdfGraph? {
        val provider = getProvider(providerId) ?: return null
        val resolvedVariant = variantId ?: provider.defaultVariantId()
        return provider.generateServiceDescription(serviceUri, resolvedVariant)
    }

    override fun getAllServiceDescriptions(baseUri: String): Map<String, RdfGraph> {
        return orderedProviders().associate { provider ->
            val serviceUri = "$baseUri/${provider.id}"
            provider.id to (provider.generateServiceDescription(serviceUri, provider.defaultVariantId()) ?: MemoryGraph(emptyList()))
        }
    }

    override fun discoverAllCapabilities(): Map<String, DetailedProviderCapabilities> {
        return orderedProviders().associate { provider ->
            provider.id to provider.getDetailedCapabilities(provider.defaultVariantId())
        }
    }

    override fun supportsFeature(providerId: String, feature: String, variantId: String?): Boolean {
        val provider = getProvider(providerId) ?: return false
        val capabilities = provider.getDetailedCapabilities(variantId ?: provider.defaultVariantId())
        return capabilities.supportedSparqlFeatures[feature] ?: false
    }

    override fun getSupportedFeatures(): Map<String, List<String>> {
        return orderedProviders().associate { provider ->
            val capabilities = provider.getDetailedCapabilities(provider.defaultVariantId())
            provider.id to capabilities.supportedSparqlFeatures.filter { it.value }.keys.toList()
        }
    }

    override fun hasProviderWithFeature(feature: String): Boolean {
        return orderedProviders().any { provider ->
            val capabilities = provider.getDetailedCapabilities(provider.defaultVariantId())
            capabilities.supportedSparqlFeatures[feature] == true
        }
    }

    override fun getProviderStatistics(): Map<ProviderCategory, Int> {
        return orderedProviders().groupBy { it.getProviderCategory() }.mapValues { it.value.size }
    }

    fun getDiscoveryErrors(): List<Throwable> = discoveryErrors.toList()

    private fun toTypeKey(providerId: String, variantId: String): String {
        return "$providerId:$variantId"
    }

    /**
     * An explicit [RdfConfig.providerId] is honoured exactly: an unknown provider, an unsupported
     * variant or unmet requirements fail with [IllegalArgumentException] instead of silently
     * selecting a different provider.
     */
    private fun resolveSelection(config: RdfConfig): ProviderSelection? {
        if (config.providerId != null) {
            val provider = getProvider(config.providerId)
                ?: throw IllegalArgumentException(
                    "Provider '${config.providerId}' is not registered (available: ${orderedProviders().map { it.id }})"
                )
            val resolvedVariant = config.variantId ?: provider.defaultVariantId()
            require(provider.supportsVariant(resolvedVariant)) {
                "Provider '${provider.id}' has no variant '$resolvedVariant' (available: ${provider.variants().map { it.id }})"
            }
            if (config.requirements != null) {
                require(matchesRequirements(provider, resolvedVariant, config.requirements)) {
                    "Provider '${provider.id}' variant '$resolvedVariant' does not satisfy ${config.requirements}"
                }
            }
            return ProviderSelection(provider, resolvedVariant)
        }

        if (config.requirements != null) {
            return selectProvider(config.requirements, null, config.variantId)
        }

        val defaultProviderId = DefaultRdfProvider.get()
        val provider = getProvider(defaultProviderId) ?: return null
        val resolvedVariant = config.variantId ?: provider.defaultVariantId()
        return ProviderSelection(provider, resolvedVariant)
    }

    private fun matchesRequirements(
        provider: RdfProvider,
        variantId: String,
        requirements: ProviderRequirements
    ): Boolean {
        requirements.providerCategory?.let {
            if (provider.getProviderCategory() != it) return false
        }
        val capabilities = provider.getCapabilities(variantId)
        fun matches(required: Boolean?, actual: Boolean): Boolean {
            return when (required) {
                null -> true
                true -> actual
                false -> !actual
            }
        }
        if (!matches(requirements.supportsInference, capabilities.supportsInference)) return false
        if (!matches(requirements.supportsTransactions, capabilities.supportsTransactions)) return false
        if (!matches(requirements.supportsNamedGraphs, capabilities.supportsNamedGraphs)) return false
        if (!matches(requirements.supportsUpdates, capabilities.supportsUpdates)) return false
        if (!matches(requirements.supportsRdfStar, capabilities.supportsRdfStar)) return false
        if (!matches(requirements.supportsFederation, capabilities.supportsFederation)) return false
        if (!matches(requirements.supportsServiceDescription, capabilities.supportsServiceDescription)) return false
        return true
    }

    private fun discoverWithServiceLoader() {
        try {
            ServiceLoader.load(RdfProvider::class.java).forEach { provider ->
                register(provider)
            }
        } catch (e: Exception) {
            discoveryErrors.add(e)
            discoveryErrorHandler(e)
        }
    }
}
