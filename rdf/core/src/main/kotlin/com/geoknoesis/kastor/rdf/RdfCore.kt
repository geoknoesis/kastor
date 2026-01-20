package com.geoknoesis.kastor.rdf

import java.io.Closeable
import java.util.ServiceLoader
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.dsl.GraphDsl
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.provider.MemoryRepositoryProvider

/**
 * Kastor RDF - The Most Elegant RDF API for Kotlin
 * 
 * A modern, type-safe, and intuitive API for working with RDF data.
 * Designed for maximum developer productivity and code elegance.
 */
object Rdf {
    
    // === FACTORY METHODS ===
    
    /**
     * Create an in-memory repository with default settings.
     * Perfect for quick prototyping and testing.
     */
    fun memory(): RdfRepository = factory {
        when {
            RdfApiRegistry.supportsVariant("jena", "memory") -> {
                providerId = "jena"
                variantId = "memory"
            }
            RdfApiRegistry.supportsVariant("rdf4j", "memory") -> {
                providerId = "rdf4j"
                variantId = "memory"
            }
            else -> {
                providerId = "memory"
                variantId = "memory"
            }
        }
    }
    
    /**
     * Create an in-memory repository with RDFS inference.
     * Automatically infers additional triples based on RDFS rules.
     */
    fun memoryWithInference(): RdfRepository = factory {
        when {
            RdfApiRegistry.supportsVariant("jena", "memory-inference") -> {
                providerId = "jena"
                variantId = "memory-inference"
            }
            RdfApiRegistry.supportsVariant("rdf4j", "memory-rdfs") -> {
                providerId = "rdf4j"
                variantId = "memory-rdfs"
            }
            else -> {
                providerId = "memory"
                variantId = "memory"
            }
        }
        inference = true
    }
    
    /**
     * Create a persistent repository with TDB2 backend.
     * Data persists between application restarts.
     */
    fun persistent(location: String = "data"): RdfRepository = factory {
        when {
            RdfApiRegistry.supportsVariant("jena", "tdb2") -> {
                providerId = "jena"
                variantId = "tdb2"
            }
            RdfApiRegistry.supportsVariant("rdf4j", "native") -> {
                providerId = "rdf4j"
                variantId = "native"
            }
            else -> {
                providerId = "memory"
                variantId = "memory"
            }
        }
        this.location = location
    }
    
    /**
     * Create a repository with custom configuration.
     * Full control over repository settings.
     */
    fun factory(configure: RepositoryBuilder.() -> Unit): RdfRepository {
        val builder = RepositoryBuilder().apply(configure)
        return builder.build()
    }
    
    /**
     * Create a standalone RDF graph using DSL.
     * Perfect for creating graphs without a repository context.
     * 
     * Example:
     * ```kotlin
     * val graph = Rdf.graph {
     *     val person = Iri("http://example.org/person")
     *     person - FOAF.name - "Alice"
     *     person - FOAF.age - 30
     * }
     * ```
     */
    fun graph(configure: GraphDsl.() -> Unit): MutableRdfGraph {
        val dsl = GraphDsl().apply(configure)
        return dsl.build()
    }
    
    
    // === DEFAULT PROVIDER MANAGEMENT ===
    
    /**
     * Set the default RDF provider for factory methods.
     * Affects which backend is used when no specific type is specified.
     */
    fun setDefaultProvider(provider: String) {
        DefaultProvider.set(provider)
    }
    
    /**
     * Get the current default RDF provider.
     */
    fun getDefaultProvider(): String = DefaultProvider.get()
    
    // === BUILDER CLASSES ===
    
    /**
     * Builder for configuring individual repositories.
     */
    class RepositoryBuilder {
        var providerId: String? = null
        var variantId: String? = null
        var requirements: ProviderRequirements = ProviderRequirements()
        var location: String? = null
        var inference: Boolean = false
        
        fun build(): RdfRepository {
            val config = RdfConfig(
                providerId = providerId,
                variantId = variantId,
                options = mapOf(
                    "location" to (location ?: "data"),
                    "inference" to inference.toString()
                ),
                requirements = requirements.takeIf { it != ProviderRequirements() }
            )
            
            return RdfApiRegistry.create(config)
        }
    }
    
}

// === CORE INTERFACES ===

// RdfGraph interface moved to RdfTerms.kt to avoid duplication


/**
 * Main interface for RDF repository operations.
 * Provides a unified API for all RDF operations.
 */
interface RdfRepository : Repository {
    
    // === GRAPH OPERATIONS ===
    
    /**
     * Get the default graph for this repository.
     */
    override val defaultGraph: RdfGraph
    
    /**
     * Get a named graph by IRI.
     */
    fun getGraph(name: Iri): RdfGraph

    /**
     * Minimal core API graph access.
     */
    override fun graph(name: Iri): RdfGraph = getGraph(name)
    
    /**
     * Check if a named graph exists.
     */
    fun hasGraph(name: Iri): Boolean
    
    /**
     * List all named graphs in the repository.
     */
    fun listGraphs(): List<Iri>
    
    /**
     * Create a new named graph.
     */
    fun createGraph(name: Iri): RdfGraph
    
    /**
     * Remove a named graph and all its triples.
     */
    fun removeGraph(name: Iri): Boolean

    /**
     * Get an editor for the default graph.
     */
    fun editDefaultGraph(): GraphEditor

    /**
     * Get an editor for a named graph.
     */
    fun editGraph(name: Iri): GraphEditor
    
    // === QUERY OPERATIONS ===
    
    /**
     * Execute a SPARQL SELECT query.
     */
    override fun select(query: SparqlSelect): QueryResult
    
    /**
     * Execute a SPARQL ASK query.
     */
    override fun ask(query: SparqlAsk): Boolean
    
    /**
     * Execute a SPARQL CONSTRUCT query.
     */
    override fun construct(query: SparqlConstruct): Sequence<RdfTriple>
    
    /**
     * Execute a SPARQL DESCRIBE query.
     */
    override fun describe(query: SparqlDescribe): Sequence<RdfTriple>
    
    /**
     * Execute a SPARQL UPDATE operation.
     */
    override fun update(query: UpdateQuery)
    
    // === TRANSACTION OPERATIONS ===
    
    /**
     * Execute operations within a transaction.
     */
    fun transaction(operations: RdfRepository.() -> Unit)
    
    /**
     * Execute read-only operations within a transaction.
     */
    fun readTransaction(operations: RdfRepository.() -> Unit)
    
    // === UTILITY OPERATIONS ===
    
    /**
     * Clear all data from the repository.
     */
    fun clear(): Boolean
    
    /**
     * Check if the repository is closed.
     */
    fun isClosed(): Boolean
    
    /**
     * Get the capabilities of this repository.
     */
    fun getCapabilities(): ProviderCapabilities
}

// === QUERY RESULT INTERFACES ===

/**
 * Result of a SPARQL SELECT query.
 */
interface QueryResult : Iterable<BindingSet> {
    
    /**
     * Get the number of result rows.
     */
    fun count(): Int
    
    /**
     * Get the first result row, or null if empty.
     */
    fun first(): BindingSet?
    
    /**
     * Get all result rows as a list.
     */
    fun toList(): List<BindingSet>
    
    /**
     * Get result rows as a sequence for streaming.
     */
    fun asSequence(): Sequence<BindingSet>
}

/**
 * Simple in-memory query result backed by a list.
 */
class ListQueryResult(private val rows: List<BindingSet>) : QueryResult {
    override fun iterator(): Iterator<BindingSet> = rows.iterator()
    override fun count(): Int = rows.size
    override fun first(): BindingSet? = rows.firstOrNull()
    override fun toList(): List<BindingSet> = rows.toList()
    override fun asSequence(): Sequence<BindingSet> = rows.asSequence()
}

/**
 * Binding set backed by a map of variable -> term.
 */
class MapBindingSet(private val values: Map<String, RdfTerm>) : BindingSet {
    override fun get(variable: String): RdfTerm? = values[variable]
    override fun getVariableNames(): Set<String> = values.keys
    override fun hasBinding(variable: String): Boolean = values.containsKey(variable)
}

/**
 * A single row from a SPARQL SELECT query result.
 */
interface BindingSet {
    
    /**
     * Get the value for a variable.
     */
    fun get(variable: String): RdfTerm?
    
    /**
     * Get all variable names in this binding set.
     */
    fun getVariableNames(): Set<String>
    
    /**
     * Check if a variable is bound.
     */
    fun hasBinding(variable: String): Boolean
    
    /**
     * Get a string value for a variable.
     */
    fun getString(variable: String): String? = (get(variable) as? Literal)?.lexical
    
    /**
     * Get an integer value for a variable.
     */
    fun getInt(variable: String): Int? = getString(variable)?.toIntOrNull()
    
    /**
     * Get a double value for a variable.
     */
    fun getDouble(variable: String): Double? = getString(variable)?.toDoubleOrNull()
    
    /**
     * Get a boolean value for a variable.
     */
    fun getBoolean(variable: String): Boolean? = getString(variable)?.toBooleanStrictOrNull()
}

// === CONFIGURATION ===

/**
 * Configuration for RDF repositories.
 */
data class RdfConfig(
    val providerId: String? = null,
    val variantId: String? = null,
    val options: Map<String, String> = emptyMap(),
    val requirements: ProviderRequirements? = null
)

/**
 * Provider variant metadata.
 */
data class RdfVariant(
    val id: String,
    val description: String = "",
    val defaultOptions: Map<String, String> = emptyMap()
)

/**
 * Selection requirements for provider discovery.
 * null means "don't care", true means "must support", false means "must not support".
 */
data class ProviderRequirements(
    val providerCategory: ProviderCategory? = null,
    val supportsInference: Boolean? = null,
    val supportsTransactions: Boolean? = null,
    val supportsNamedGraphs: Boolean? = null,
    val supportsUpdates: Boolean? = null,
    val supportsRdfStar: Boolean? = null,
    val supportsFederation: Boolean? = null,
    val supportsServiceDescription: Boolean? = null
)

/**
 * Unified registry for RDF API providers with enhanced capabilities.
 * Consolidates functionality from both RdfApiRegistry and EnhancedRdfApiRegistry.
 */
object RdfApiRegistry {
    
    private val providers = mutableMapOf<String, RdfApiProvider>()
    private val providersByType = mutableMapOf<String, RdfApiProvider>()
    
    init {
        // Register default memory provider
        register(MemoryRepositoryProvider())

        // Discover providers via ServiceLoader to keep registration portable.
        discoverWithServiceLoader()
    }

    data class ProviderSelection(val provider: RdfApiProvider, val variantId: String)

    private fun toTypeKey(providerId: String, variantId: String): String {
        return "$providerId:$variantId"
    }

    private fun resolveSelection(config: RdfConfig): ProviderSelection? {
        if (config.providerId != null) {
            val provider = providers[config.providerId] ?: return null
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

        val defaultProviderId = DefaultProvider.get()
        val provider = providers[defaultProviderId] ?: return null
        val resolvedVariant = config.variantId ?: provider.defaultVariantId()
        return ProviderSelection(provider, resolvedVariant)
    }

    fun selectProvider(
        requirements: ProviderRequirements,
        preferredProviderId: String? = null,
        preferredVariantId: String? = null
    ): ProviderSelection? {
        val orderedProviders = buildList {
            preferredProviderId?.let { providers[it] }?.let { add(it) }
            providers.values.filterNot { it.id == preferredProviderId }.forEach { add(it) }
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

    private fun matchesRequirements(
        provider: RdfApiProvider,
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
            ServiceLoader.load(RdfApiProvider::class.java).forEach { provider ->
                register(provider)
            }
        } catch (e: Exception) {
            // If discovery fails, keep going with explicitly registered providers.
        }
    }
    
    // === BASIC PROVIDER OPERATIONS ===
    
    /**
     * Register an RDF provider.
     */
    fun register(provider: RdfApiProvider) {
        providers[provider.id] = provider
        provider.variants().forEach { variant ->
            providersByType[toTypeKey(provider.id, variant.id)] = provider
        }
    }
    
    /**
     * Create a repository from configuration.
     */
    fun create(config: RdfConfig): RdfRepository {
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
    
    /**
     * Discover available providers.
     */
    fun discoverProviders(): List<RdfApiProvider> {
        return providers.values.toList()
    }
    
    /**
     * Get all providers (alias for discoverProviders for consistency).
     */
    fun getAllProviders(): List<RdfApiProvider> = discoverProviders()
    
    /**
     * Get supported repository types.
     */
    fun getSupportedTypes(): List<String> {
        if (providersByType.isNotEmpty()) {
            return providersByType.keys.toList().distinct()
        }
        return providers.values
            .flatMap { provider -> provider.variants().map { toTypeKey(provider.id, it.id) } }
            .distinct()
    }
    
    /**
     * Check if a provider supports a specific type.
     */
    fun supports(providerId: String): Boolean = providers.containsKey(providerId)

    fun supportsVariant(providerId: String, variantId: String): Boolean {
        return providersByType.containsKey(toTypeKey(providerId, variantId))
    }
    
    /**
     * Check if a provider supports a specific type (alias for supports).
     */
    fun isSupported(type: String): Boolean {
        return supports(type)
    }
    
    /**
     * Get provider by type.
     */
    fun getProvider(providerId: String): RdfApiProvider? = providers[providerId]
    
    // === ENHANCED PROVIDER OPERATIONS ===
    
    /**
     * Get providers by category.
     */
    fun getProvidersByCategory(category: ProviderCategory): List<RdfApiProvider> {
        return providers.values.filter { it.getProviderCategory() == category }
    }
    
    /**
     * Generate service description for a specific provider.
     */
    fun generateServiceDescription(providerId: String, serviceUri: String, variantId: String? = null): RdfGraph? {
        val provider = providers[providerId] ?: return null
        val resolvedVariant = variantId ?: provider.defaultVariantId()
        return provider.generateServiceDescription(serviceUri, resolvedVariant)
    }
    
    /**
     * Get all service descriptions.
     */
    fun getAllServiceDescriptions(baseUri: String): Map<String, RdfGraph> {
        return providers.mapValues { (_, provider) ->
            val serviceUri = "$baseUri/${provider.id}"
            provider.generateServiceDescription(serviceUri, provider.defaultVariantId()) ?: MemoryGraph(emptyList())
        }
    }
    
    /**
     * Discover capabilities for all providers.
     */
    fun discoverAllCapabilities(): Map<String, DetailedProviderCapabilities> {
        return providers.mapValues { (_, provider) ->
            provider.getDetailedCapabilities(provider.defaultVariantId())
        }
    }
    
    /**
     * Check if a provider supports a specific SPARQL feature.
     */
    fun supportsFeature(providerId: String, feature: String, variantId: String? = null): Boolean {
        val provider = providers[providerId] ?: return false
        val capabilities = provider.getDetailedCapabilities(variantId ?: provider.defaultVariantId())
        return capabilities.supportedSparqlFeatures[feature] ?: false
    }
    
    /**
     * Get all supported SPARQL features across providers.
     */
    fun getSupportedFeatures(): Map<String, List<String>> {
        return providers.mapValues { (_, provider) ->
            val capabilities = provider.getDetailedCapabilities(provider.defaultVariantId())
            capabilities.supportedSparqlFeatures.filter { it.value }.keys.toList()
        }
    }
    
    /**
     * Check if any provider supports a specific feature.
     */
    fun hasProviderWithFeature(feature: String): Boolean {
        return providers.values.any { provider ->
            val capabilities = provider.getDetailedCapabilities(provider.defaultVariantId())
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

/**
 * Interface for RDF API providers with optional enhanced capabilities.
 */
interface RdfApiProvider {
    
    /**
     * Provider id (stable identifier).
     */
    val id: String
    
    /**
     * Get the provider name.
     */
    val name: String get() = id
    
    /**
     * Get the provider version.
     */
    val version: String get() = "unspecified"
    
    /**
     * Create a repository with the given configuration.
     */
    fun createRepository(variantId: String, config: RdfConfig): RdfRepository
    
    /**
     * Get provider capabilities.
     */
    fun getCapabilities(variantId: String? = null): ProviderCapabilities = ProviderCapabilities()
    
    /**
     * Get supported variants for this provider.
     */
    fun variants(): List<RdfVariant> = listOf(RdfVariant("default"))
    
    /**
     * Get the default variant id.
     */
    fun defaultVariantId(): String = variants().firstOrNull()?.id ?: "default"
    
    /**
     * Check if a variant is supported.
     */
    fun supportsVariant(variantId: String): Boolean = variants().any { it.id == variantId }
    
    // === ENHANCED CAPABILITIES (Optional) ===
    
    /**
     * Get the provider category.
     * Default implementation returns RDF_STORE for backward compatibility.
     */
    fun getProviderCategory(): ProviderCategory = ProviderCategory.RDF_STORE
    
    /**
     * Generate SPARQL service description for this provider.
     * Default implementation returns null for providers that don't support service descriptions.
     */
    fun generateServiceDescription(serviceUri: String, variantId: String? = null): RdfGraph? = null
    
    /**
     * Get detailed capability information.
     * Default implementation creates DetailedProviderCapabilities from basic capabilities.
     */
    fun getDetailedCapabilities(variantId: String? = null): DetailedProviderCapabilities {
        return DetailedProviderCapabilities(
            basic = getCapabilities(variantId),
            providerCategory = getProviderCategory(),
            supportedSparqlFeatures = emptyMap(),
            customExtensionFunctions = emptyList()
        )
    }
}

/**
 * Represents a SPARQL extension function.
 */
data class SparqlExtensionFunction(
    val iri: String,
    val name: String,
    val description: String,
    val argumentTypes: List<String> = emptyList(),
    val returnType: String? = null,
    val isAggregate: Boolean = false,
    val isBuiltIn: Boolean = true
)

/**
 * Categories of RDF providers.
 */
enum class ProviderCategory {
    RDF_STORE,              // Jena, RDF4J, etc.
    SPARQL_ENDPOINT,        // Remote SPARQL endpoints
    REASONER,              // Inference engines
    SHACL_VALIDATOR,       // SHACL validation
    SERVICE_DESCRIPTION,   // SPARQL service description
    FEDERATION            // Federated query support
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

/**
 * Enhanced provider capabilities with SPARQL 1.2 support.
 */
data class ProviderCapabilities(
    // Existing capabilities
    val supportsInference: Boolean = false,
    val supportsTransactions: Boolean = false,
    val supportsNamedGraphs: Boolean = false,
    val supportsUpdates: Boolean = false,
    val supportsRdfStar: Boolean = false,
    val maxMemoryUsage: Long = Long.MAX_VALUE,
    
    // SPARQL 1.2 specific capabilities
    val sparqlVersion: String = "1.1",
    val supportsPropertyPaths: Boolean = false,
    val supportsAggregation: Boolean = false,
    val supportsSubSelect: Boolean = false,
    val supportsFederation: Boolean = false,
    val supportsVersionDeclaration: Boolean = false,
    val supportsServiceDescription: Boolean = false,
    
    // Service description capabilities
    val supportedLanguages: List<String> = emptyList(),
    val supportedResultFormats: List<String> = emptyList(),
    val supportedInputFormats: List<String> = emptyList(),
    val extensionFunctions: List<SparqlExtensionFunction> = emptyList(),
    val entailmentRegimes: List<String> = emptyList(),
    val namedGraphs: List<String> = emptyList(),
    val defaultGraphs: List<String> = emptyList()
)

// === DEFAULT PROVIDER MANAGEMENT ===

/**
 * Manages the default RDF provider.
 */
object DefaultProvider {
    private var current: String = "memory"
    
    fun set(provider: String) {
        current = provider
    }
    
    fun get(): String = current
}









