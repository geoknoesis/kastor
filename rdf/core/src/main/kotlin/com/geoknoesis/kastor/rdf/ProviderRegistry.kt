package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.provider.MemoryRepositoryProvider
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap
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

class DefaultProviderRegistry(
    private val autoDiscover: Boolean = true,
    private val registerDefaultMemoryProvider: Boolean = true,
    private val discoveryErrorHandler: (Throwable) -> Unit = {}
) : ProviderRegistry {
    private val lock = ReentrantReadWriteLock()
    private val providers = ConcurrentHashMap<String, RdfProvider>()
    private val providersByType = ConcurrentHashMap<String, RdfProvider>()
    private val discoveryErrors = CopyOnWriteArrayList<Throwable>()

    init {
        if (registerDefaultMemoryProvider) {
            register(MemoryRepositoryProvider())
        }
        if (autoDiscover) {
            discoverWithServiceLoader()
        }
    }

    override fun selectProvider(
        requirements: ProviderRequirements,
        preferredProviderId: String?,
        preferredVariantId: String?
    ): ProviderSelection? {
        val orderedProviders = lock.read {
            buildList {
                preferredProviderId?.let { providers[it] }?.let { add(it) }
                providers.values.filterNot { it.id == preferredProviderId }.forEach { add(it) }
            }
        }
        orderedProviders.forEach { provider ->
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

    override fun register(provider: RdfProvider) {
        lock.write {
            providers[provider.id] = provider
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

    override fun discoverProviders(): List<RdfProvider> = lock.read {
        providers.values.toList()
    }

    override fun getSupportedTypes(): List<String> {
        return lock.read {
            if (providersByType.isNotEmpty()) {
                providersByType.keys.toList().distinct()
            } else {
                providers.values
                    .flatMap { provider -> provider.variants().map { toTypeKey(provider.id, it.id) } }
                    .distinct()
            }
        }
    }

    override fun supports(providerId: String): Boolean = lock.read {
        providers.containsKey(providerId)
    }

    override fun supportsVariant(providerId: String, variantId: String): Boolean {
        return lock.read {
            providersByType.containsKey(toTypeKey(providerId, variantId))
        }
    }

    override fun getProvider(providerId: String): RdfProvider? = lock.read {
        providers[providerId]
    }

    override fun getProvidersByCategory(category: ProviderCategory): List<RdfProvider> {
        return lock.read {
            providers.values.filter { it.getProviderCategory() == category }
        }
    }

    override fun generateServiceDescription(
        providerId: String,
        serviceUri: String,
        variantId: String?
    ): RdfGraph? {
        val provider = lock.read { providers[providerId] } ?: return null
        val resolvedVariant = variantId ?: provider.defaultVariantId()
        return provider.generateServiceDescription(serviceUri, resolvedVariant)
    }

    override fun getAllServiceDescriptions(baseUri: String): Map<String, RdfGraph> {
        val providerSnapshot = lock.read { providers.toMap() }
        return providerSnapshot.mapValues { (_, provider) ->
            val serviceUri = "$baseUri/${provider.id}"
            provider.generateServiceDescription(serviceUri, provider.defaultVariantId()) ?: MemoryGraph(emptyList())
        }
    }

    override fun discoverAllCapabilities(): Map<String, DetailedProviderCapabilities> {
        val providerSnapshot = lock.read { providers.toMap() }
        return providerSnapshot.mapValues { (_, provider) ->
            provider.getDetailedCapabilities(provider.defaultVariantId())
        }
    }

    override fun supportsFeature(providerId: String, feature: String, variantId: String?): Boolean {
        val provider = lock.read { providers[providerId] } ?: return false
        val capabilities = provider.getDetailedCapabilities(variantId ?: provider.defaultVariantId())
        return capabilities.supportedSparqlFeatures[feature] ?: false
    }

    override fun getSupportedFeatures(): Map<String, List<String>> {
        val providerSnapshot = lock.read { providers.toMap() }
        return providerSnapshot.mapValues { (_, provider) ->
            val capabilities = provider.getDetailedCapabilities(provider.defaultVariantId())
            capabilities.supportedSparqlFeatures.filter { it.value }.keys.toList()
        }
    }

    override fun hasProviderWithFeature(feature: String): Boolean {
        val providerSnapshot = lock.read { providers.values.toList() }
        return providerSnapshot.any { provider ->
            val capabilities = provider.getDetailedCapabilities(provider.defaultVariantId())
            capabilities.supportedSparqlFeatures[feature] == true
        }
    }

    override fun getProviderStatistics(): Map<ProviderCategory, Int> {
        val categories = lock.read {
            providers.values.groupBy { it.getProviderCategory() }
        }
        return categories.mapValues { it.value.size }
    }

    fun getDiscoveryErrors(): List<Throwable> = discoveryErrors.toList()

    private fun toTypeKey(providerId: String, variantId: String): String {
        return "$providerId:$variantId"
    }

    private fun resolveSelection(config: RdfConfig): ProviderSelection? {
        if (config.providerId != null) {
            val provider = lock.read { providers[config.providerId] } ?: return null
            val resolvedVariant = config.variantId ?: provider.defaultVariantId()
            if (!provider.supportsVariant(resolvedVariant)) return null
            if (config.requirements != null &&
                !matchesRequirements(provider, resolvedVariant, config.requirements)
            ) {
                return selectProvider(config.requirements, config.providerId, config.variantId)
            }
            return ProviderSelection(provider, resolvedVariant)
        }

        if (config.requirements != null) {
            return selectProvider(config.requirements, config.providerId, config.variantId)
        }

        val defaultProviderId = DefaultRdfProvider.get()
        val provider = lock.read { providers[defaultProviderId] } ?: return null
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

