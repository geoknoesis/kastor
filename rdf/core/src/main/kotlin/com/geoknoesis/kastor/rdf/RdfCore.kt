package com.geoknoesis.kastor.rdf

import java.io.Closeable
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.dsl.TripleDsl
import com.geoknoesis.kastor.rdf.dsl.GraphDsl
import com.geoknoesis.kastor.rdf.dsl.StandaloneGraph
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
    fun memory(): RdfRepository = factory { type = "memory" }
    
    /**
     * Create an in-memory repository with RDFS inference.
     * Automatically infers additional triples based on RDFS rules.
     */
    fun memoryWithInference(): RdfRepository = factory { 
        type = "memory"
        inference = true
    }
    
    /**
     * Create a persistent repository with TDB2 backend.
     * Data persists between application restarts.
     */
    fun persistent(location: String = "data"): RdfRepository = factory { 
        type = "tdb2"
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
     * Create a repository manager for multi-repository operations.
     * Perfect for federated queries and complex data management.
     */
    fun manager(configure: ManagerBuilder.() -> Unit): RepositoryManager {
        val builder = ManagerBuilder().apply(configure)
        return builder.build()
    }
    
    /**
     * Create a standalone RDF graph using DSL.
     * Perfect for creating graphs without a repository context.
     * 
     * Example:
     * ```kotlin
     * val graph = Rdf.graph {
     *     val person = iri("http://example.org/person")
     *     person - FOAF.name - "Alice"
     *     person - FOAF.age - 30
     * }
     * ```
     */
    fun graph(configure: GraphDsl.() -> Unit): RdfGraph {
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
        var type: String = "memory"
        var location: String? = null
        var inference: Boolean = false
        var optimization: Boolean = true
        var cacheSize: Int = 1000
        var maxMemory: String = "1GB"
        
        fun build(): RdfRepository {
            val config = RdfConfig(
                type = type,
                params = mapOf(
                    "location" to (location ?: "data"),
                    "inference" to inference.toString(),
                    "optimization" to optimization.toString(),
                    "cacheSize" to cacheSize.toString(),
                    "maxMemory" to maxMemory
                )
            )
            
            return RdfApiRegistry.create(config)
        }
    }
    
    /**
     * Builder for configuring repository managers.
     */
    class ManagerBuilder {
        private val repositories = mutableMapOf<String, RdfRepository>()
        
        fun repository(name: String, configure: RepositoryBuilder.() -> Unit) {
            val builder = RepositoryBuilder().apply(configure)
            repositories[name] = builder.build()
        }
        
        fun build(): RepositoryManager {
            return DefaultRepositoryManager(repositories)
        }
    }
}

// === CORE INTERFACES ===

// RdfGraph interface moved to RdfTerms.kt to avoid duplication


/**
 * Main interface for RDF repository operations.
 * Provides a unified API for all RDF operations.
 */
interface RdfRepository : Closeable {
    
    // === GRAPH OPERATIONS ===
    
    /**
     * Get the default graph for this repository.
     */
    val defaultGraph: RdfGraph
    
    /**
     * Get a named graph by IRI.
     */
    fun getGraph(name: Iri): RdfGraph
    
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
    
    // === TRIPLE OPERATIONS ===
    
    /**
     * Add triples using the elegant DSL.
     * Supports all syntax styles: ultra-compact, natural language, infix operators.
     */
    fun add(configure: TripleDsl.() -> Unit) {
        val dsl = TripleDsl().apply(configure)
        defaultGraph.addTriples(dsl.triples)
    }
    
    /**
     * Add triples to a specific named graph.
     */
    fun addToGraph(graphName: Iri, configure: TripleDsl.() -> Unit) {
        val dsl = TripleDsl().apply(configure)
        getGraph(graphName).addTriples(dsl.triples)
    }
    
    /**
     * Add a single triple to the default graph.
     */
    fun addTriple(triple: RdfTriple) {
        defaultGraph.addTriple(triple)
    }
    
    /**
     * Add multiple triples to the default graph.
     */
    fun addTriples(triples: Collection<RdfTriple>) {
        defaultGraph.addTriples(triples)
    }
    
    /**
     * Add a single triple to a specific named graph.
     */
    fun addTriple(graphName: Iri?, triple: RdfTriple) {
        if (graphName == null) {
            defaultGraph.addTriple(triple)
        } else {
            getGraph(graphName).addTriple(triple)
        }
    }
    
    /**
     * Remove a triple from the default graph.
     */
    fun removeTriple(triple: RdfTriple): Boolean {
        return defaultGraph.removeTriple(triple)
    }
    
    /**
     * Remove multiple triples from the default graph.
     */
    fun removeTriples(triples: Collection<RdfTriple>): Boolean {
        return defaultGraph.removeTriples(triples)
    }
    
    /**
     * Check if a triple exists in the default graph.
     */
    fun hasTriple(triple: RdfTriple): Boolean {
        return defaultGraph.hasTriple(triple)
    }
    
    /**
     * Get all triples from the default graph.
     */
    fun getTriples(): List<RdfTriple> {
        return defaultGraph.getTriples()
    }
    
    // === QUERY OPERATIONS ===
    
    /**
     * Execute a SPARQL SELECT query.
     */
    fun query(sparql: String): QueryResult
    
    /**
     * Execute a SPARQL ASK query.
     */
    fun ask(sparql: String): Boolean
    
    /**
     * Execute a SPARQL CONSTRUCT query.
     */
    fun construct(sparql: String): List<RdfTriple>
    
    /**
     * Execute a SPARQL DESCRIBE query.
     */
    fun describe(sparql: String): List<RdfTriple>
    
    /**
     * Execute a SPARQL UPDATE operation.
     */
    fun update(sparql: String)
    
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
     * Get repository statistics.
     */
    fun getStatistics(): RepositoryStatistics
    
    /**
     * Get performance monitoring data.
     */
    fun getPerformanceMonitor(): PerformanceMonitor
    
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

// === REPOSITORY MANAGEMENT ===

/**
 * Interface for managing multiple repositories.
 */
interface RepositoryManager : Closeable {
    
    /**
     * Get a repository by name.
     */
    fun getRepository(name: String): RdfRepository?
    
    /**
     * Check if a repository exists.
     */
    fun hasRepository(name: String): Boolean
    
    /**
     * List all repository names.
     */
    fun listRepositories(): List<String>
    
    /**
     * Execute a federated query across multiple repositories.
     */
    fun federatedQuery(sparql: String): QueryResult
}

/**
 * Default implementation of RepositoryManager.
 */
class DefaultRepositoryManager(private val repositories: Map<String, RdfRepository>) : RepositoryManager {
    
    override fun getRepository(name: String): RdfRepository? = repositories[name]
    
    override fun hasRepository(name: String): Boolean = repositories.containsKey(name)
    
    override fun listRepositories(): List<String> = repositories.keys.toList()
    
    override fun federatedQuery(sparql: String): QueryResult {
        // Simple implementation - could be enhanced for true federation
        val firstRepo = repositories.values.firstOrNull() 
            ?: throw IllegalStateException("No repositories available for federated query")
        return firstRepo.query(sparql)
    }
    
    override fun close() {
        repositories.values.forEach { it.close() }
    }
}

// === STATISTICS AND MONITORING ===

/**
 * Repository statistics.
 */
data class RepositoryStatistics(
    val tripleCount: Long,
    val graphCount: Int,
    val memoryUsage: Long,
    val diskUsage: Long,
    val lastModified: Long
) {
    /**
     * Get total number of triples across all graphs.
     */
    val totalTriples: Long = tripleCount
    
    /**
     * Get size in bytes.
     */
    val sizeBytes: Long = memoryUsage + diskUsage
}

/**
 * Performance monitoring data.
 */
data class PerformanceMonitor(
    val queryCount: Long,
    val averageQueryTime: Double,
    val totalQueryTime: Long,
    val cacheHitRate: Double,
    val memoryUsage: Long
)

// === CONFIGURATION ===

/**
 * Configuration for RDF repositories.
 */
data class RdfConfig(
    val type: String,
    val params: Map<String, String> = emptyMap()
)

/**
 * Registry for RDF API providers.
 */
object RdfApiRegistry {
    
    private val providers = mutableMapOf<String, RdfApiProvider>()
    
    init {
        // Register default memory provider
        register(MemoryRepositoryProvider())
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
     * Discover available providers.
     */
    fun discoverProviders(): List<RdfApiProvider> {
        return providers.values.toList()
    }
    
    /**
     * Get supported repository types.
     */
    fun getSupportedTypes(): List<String> {
        return providers.keys.toList()
    }
    
    /**
     * Check if a provider supports a specific type.
     */
    fun supports(type: String): Boolean {
        return providers.containsKey(type)
    }
    
    /**
     * Check if a provider supports a specific type.
     */
    fun isSupported(type: String): Boolean {
        return supports(type)
    }
}

/**
 * Interface for RDF API providers.
 */
interface RdfApiProvider {
    
    /**
     * Get the provider type identifier.
     */
    fun getType(): String
    
    /**
     * Get the provider name.
     */
    val name: String
    
    /**
     * Get the provider version.
     */
    val version: String
    
    /**
     * Create a repository with the given configuration.
     */
    fun createRepository(config: RdfConfig): RdfRepository
    
    /**
     * Get provider capabilities.
     */
    fun getCapabilities(): ProviderCapabilities
    
    /**
     * Get supported repository types.
     */
    fun getSupportedTypes(): List<String>
    
    /**
     * Check if a repository type is supported.
     */
    fun isSupported(type: String): Boolean
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
    val sparqlVersion: String = "1.2",
    val supportsPropertyPaths: Boolean = true,
    val supportsAggregation: Boolean = true,
    val supportsSubSelect: Boolean = true,
    val supportsFederation: Boolean = false,
    val supportsVersionDeclaration: Boolean = true,
    val supportsServiceDescription: Boolean = true,
    
    // Service description capabilities
    val supportedLanguages: List<String> = listOf("sparql", "sparql12"),
    val supportedResultFormats: List<String> = listOf(
        "application/sparql-results+json",
        "application/sparql-results+xml",
        "text/csv",
        "text/tab-separated-values"
    ),
    val supportedInputFormats: List<String> = listOf(
        "application/sparql-query",
        "application/sparql-update"
    ),
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