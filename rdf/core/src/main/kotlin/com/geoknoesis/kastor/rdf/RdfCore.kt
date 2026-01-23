package com.geoknoesis.kastor.rdf

import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.dsl.GraphDsl
import com.geoknoesis.kastor.rdf.provider.EmptySparqlQueryResult
import com.geoknoesis.kastor.rdf.RdfFormat

/**
 * Kastor RDF - The Most Elegant RDF API for Kotlin
 * 
 * A modern, type-safe, and intuitive API for working with RDF data.
 * Designed for maximum developer productivity and code elegance.
 * 
 * **API Stability:** The factory methods (`memory()`, `persistent()`, `repository()`) 
 * and `graph()` DSL are stable and part of the public API.
 */
object Rdf {
    
    /**
     * Default location for persistent repositories.
     * Used when no location is specified in repository configuration.
     */
    internal const val DEFAULT_PERSISTENT_LOCATION = "data"
    
    // === FACTORY METHODS ===
    
    /**
     * Create an in-memory repository with default settings.
     * Perfect for quick prototyping and testing.
     */
    fun memory(): RdfRepository = repository {
        when {
            RdfProviderRegistry.supportsVariant("jena", "memory") -> {
                providerId = "jena"
                variantId = "memory"
            }
            RdfProviderRegistry.supportsVariant("rdf4j", "memory") -> {
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
    fun memoryWithInference(): RdfRepository = repository {
        when {
            RdfProviderRegistry.supportsVariant("jena", "memory-inference") -> {
                providerId = "jena"
                variantId = "memory-inference"
            }
            RdfProviderRegistry.supportsVariant("rdf4j", "memory-rdfs") -> {
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
    fun persistent(location: String = DEFAULT_PERSISTENT_LOCATION): RdfRepository = repository {
        when {
            RdfProviderRegistry.supportsVariant("jena", "tdb2") -> {
                providerId = "jena"
                variantId = "tdb2"
            }
            RdfProviderRegistry.supportsVariant("rdf4j", "native") -> {
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
     * Create a repository with full configuration control.
     * 
     * Use this method when you need fine-grained control over repository configuration
     * that isn't available through the convenience methods (`memory()`, `persistent()`, etc.).
     * 
     * **Example:**
     * ```kotlin
     * val repo = Rdf.repository {
     *     providerId = "jena"
     *     variantId = "tdb2"
     *     location = "/path/to/storage"
     *     inference = true
     *     requirements = ProviderRequirements(
     *         supportsTransactions = true,
     *         supportsNamedGraphs = true
     *     )
     * }
     * ```
     * 
     * **When to use:**
     * - Need specific provider/variant combination
     * - Require custom provider requirements
     * - Want to configure advanced options
     * 
     * **When to use convenience methods instead:**
     * - `memory()` - Simple in-memory repository
     * - `persistent()` - Persistent storage with defaults
     * - `memoryWithInference()` - In-memory with RDFS inference
     * 
     * @param configure Lambda to configure the repository builder
     * @return A configured RdfRepository instance
     */
    fun repository(
        configure: RdfRepositoryBuilder.() -> Unit
    ): RdfRepository {
        return repository(RdfProviderRegistry, configure)
    }

    /**
     * Create a repository using a specific provider registry.
     * Useful for tests or custom registry instances.
     */
    fun repository(
        registry: ProviderRegistry,
        configure: RdfRepositoryBuilder.() -> Unit
    ): RdfRepository {
        val builder = RdfRepositoryBuilder(registry).apply(configure)
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
    
    // === PARSING FACTORY METHODS ===
    
    /**
     * Parse RDF data from a string into a graph.
     * 
     * This method is provider-agnostic and automatically uses an available provider
     * that supports the requested format. The format can be specified as either
     * a string (e.g., "TURTLE", "JSON-LD") or an [RdfFormat] enum value.
     * 
     * **Example:**
     * ```kotlin
     * val turtleData = """
     *     @prefix foaf: <http://xmlns.com/foaf/0.1/> .
     *     <http://example.org/alice> foaf:name "Alice" .
     * """
     * val graph = Rdf.parse(turtleData, format = "TURTLE")
     * 
     * // Or using enum (type-safe)
     * val graph2 = Rdf.parse(turtleData, format = RdfFormat.TURTLE)
     * ```
     * 
     * @param data The RDF data as a string
     * @param format The RDF format (default: "TURTLE")
     * @return A new MutableRdfGraph containing the parsed triples
     * @throws RdfFormatException if parsing fails or format is not supported
     */
    fun parse(data: String, format: String = "TURTLE"): MutableRdfGraph {
        return parseFromInputStream(data.byteInputStream(), format)
    }
    
    /**
     * Parse RDF data from a string into a graph (type-safe version).
     * 
     * @param data The RDF data as a string
     * @param format The RDF format enum value
     * @return A new MutableRdfGraph containing the parsed triples
     * @throws RdfFormatException if parsing fails or format is not supported
     */
    fun parse(data: String, format: RdfFormat): MutableRdfGraph {
        return parseFromInputStream(data.byteInputStream(), format.formatName)
    }
    
    /**
     * Parse RDF data from a file into a graph.
     * 
     * **Example:**
     * ```kotlin
     * val graph = Rdf.parseFromFile("data.ttl", format = "TURTLE")
     * val graph2 = Rdf.parseFromFile("data.jsonld", format = RdfFormat.JSON_LD)
     * ```
     * 
     * @param filePath The path to the RDF file
     * @param format The RDF format (default: "TURTLE")
     * @return A new MutableRdfGraph containing the parsed triples
     * @throws RdfFormatException if parsing fails or format is not supported
     * @throws java.io.FileNotFoundException if the file does not exist
     */
    fun parseFromFile(filePath: String, format: String = "TURTLE"): MutableRdfGraph {
        val file = java.io.File(filePath)
        if (!file.exists()) {
            throw java.io.FileNotFoundException("RDF file not found: $filePath")
        }
        return file.inputStream().use { stream -> parseFromInputStream(stream, format) }
    }
    
    /**
     * Parse RDF data from a file into a graph (type-safe version).
     * 
     * @param filePath The path to the RDF file
     * @param format The RDF format enum value
     * @return A new MutableRdfGraph containing the parsed triples
     * @throws RdfFormatException if parsing fails or format is not supported
     * @throws java.io.FileNotFoundException if the file does not exist
     */
    fun parseFromFile(filePath: String, format: RdfFormat): MutableRdfGraph {
        return parseFromFile(filePath, format.formatName)
    }
    
    /**
     * Parse RDF data from a URL into a graph.
     * 
     * **Example:**
     * ```kotlin
     * val graph = Rdf.parseFromUrl(
     *     "https://example.org/data.ttl",
     *     format = "TURTLE"
     * )
     * ```
     * 
     * @param url The URL to load RDF data from
     * @param format The RDF format (default: "TURTLE")
     * @return A new MutableRdfGraph containing the parsed triples
     * @throws RdfFormatException if parsing fails or format is not supported
     * @throws java.io.IOException if network access fails
     */
    fun parseFromUrl(url: String, format: String = "TURTLE"): MutableRdfGraph {
        val connection = java.net.URL(url).openConnection()
        connection.connectTimeout = 30000 // 30 seconds
        connection.readTimeout = 30000
        return connection.getInputStream().use { stream -> parseFromInputStream(stream, format) }
    }
    
    /**
     * Parse RDF data from a URL into a graph (type-safe version).
     * 
     * @param url The URL to load RDF data from
     * @param format The RDF format enum value
     * @return A new MutableRdfGraph containing the parsed triples
     * @throws RdfFormatException if parsing fails or format is not supported
     * @throws java.io.IOException if network access fails
     */
    fun parseFromUrl(url: String, format: RdfFormat): MutableRdfGraph {
        return parseFromUrl(url, format.formatName)
    }

    /**
     * Parse RDF data from a URL asynchronously into a graph.
     *
     * This runs the blocking network call on the provided executor.
     *
     * @param url The URL to load RDF data from
     * @param format The RDF format (default: "TURTLE")
     * @param executor Executor used for the blocking operation
     * @return A future with the parsed graph
     */
    fun parseFromUrlAsync(
        url: String,
        format: String = "TURTLE",
        executor: Executor = ForkJoinPool.commonPool()
    ): CompletableFuture<MutableRdfGraph> {
        return CompletableFuture.supplyAsync({ parseFromUrl(url, format) }, executor)
    }

    /**
     * Parse RDF data from a URL asynchronously into a graph (type-safe version).
     *
     * @param url The URL to load RDF data from
     * @param format The RDF format enum value
     * @param executor Executor used for the blocking operation
     * @return A future with the parsed graph
     */
    fun parseFromUrlAsync(
        url: String,
        format: RdfFormat,
        executor: Executor = ForkJoinPool.commonPool()
    ): CompletableFuture<MutableRdfGraph> {
        return parseFromUrlAsync(url, format.formatName, executor)
    }
    
    /**
     * Parse RDF data from an input stream into a graph.
     * 
     * **Note:** The input stream is automatically closed after parsing.
     * 
     * @param inputStream The input stream containing RDF data
     * @param format The RDF format
     * @return A new MutableRdfGraph containing the parsed triples
     * @throws RdfFormatException if parsing fails or format is not supported
     */
    fun parseFromInputStream(inputStream: InputStream, format: String): MutableRdfGraph {
        val formatEnum = RdfFormat.fromStringOrThrow(format)
        val providers = RdfProviderRegistry.discoverProviders()
        
        // Try to find a provider that supports this format
        for (provider in providers) {
            if (provider.supportsFormat(formatEnum.formatName)) {
                try {
                    // Read stream into byte array to allow multiple attempts if needed
                    val data = inputStream.readBytes()
                    return provider.parseGraph(data.inputStream(), formatEnum.formatName)
                } catch (e: UnsupportedOperationException) {
                    // Provider doesn't actually support it, try next
                    continue
                } catch (e: RdfFormatException) {
                    // Format error, rethrow
                    throw e
                } catch (e: Exception) {
                    // Other error, wrap and throw
                    throw RdfFormatException(
                        "Failed to parse RDF from input stream (format: ${formatEnum.formatName}) using provider ${provider.id}: ${e.message}",
                        e
                    )
                }
            }
        }
        
        throw RdfFormatException(
            "No provider found that supports format: ${formatEnum.formatName}. " +
            "Available providers: ${providers.map { it.id }.joinToString()}"
        )
    }
    
    /**
     * Parse RDF data from an input stream into a graph (type-safe version).
     * 
     * @param inputStream The input stream containing RDF data
     * @param format The RDF format enum value
     * @return A new MutableRdfGraph containing the parsed triples
     * @throws RdfFormatException if parsing fails or format is not supported
     */
    fun parseFromInputStream(inputStream: InputStream, format: RdfFormat): MutableRdfGraph {
        return parseFromInputStream(inputStream, format.formatName)
    }
    
    // === DATASET PARSING (QUAD FORMATS) ===
    
    /**
     * Parse RDF dataset (with named graphs) from a string.
     * 
     * Quad formats (TriG, N-Quads) support parsing of multiple named graphs.
     * The parsed data will be added to a new in-memory repository, which is returned as a Dataset.
     * 
     * **Example:**
     * ```kotlin
     * val trigData = """
     *     <http://example.org/alice> <http://xmlns.com/foaf/0.1/name> "Alice" .
     *     GRAPH <http://example.org/graph1> {
     *         <http://example.org/bob> <http://xmlns.com/foaf/0.1/name> "Bob" .
     *     }
     * """
     * val dataset = Rdf.parseDataset(trigData, format = "TRIG")
     * ```
     * 
     * @param data The RDF dataset data as a string
     * @param format The RDF quad format (default: "TRIG")
     * @return A new Dataset containing the parsed data
     * @throws RdfFormatException if parsing fails or format is not supported
     * @throws IllegalArgumentException if the format is not a quad format
     */
    fun parseDataset(data: String, format: String = "TRIG"): Dataset {
        val formatEnum = RdfFormat.fromStringOrThrow(format)
        if (!RdfFormat.isQuadFormat(formatEnum)) {
            throw IllegalArgumentException("Format '${formatEnum.formatName}' is not a quad format. Use parse() for graph formats, or use TRIG or N-QUADS for datasets.")
        }
        val repo = memory()
        parseDataset(repo, data.byteInputStream(), format)
        return repo
    }
    
    /**
     * Parse RDF dataset from a string (type-safe version).
     * 
     * @param data The RDF dataset data as a string
     * @param format The RDF quad format enum value
     * @return A new Dataset containing the parsed data
     * @throws RdfFormatException if parsing fails or format is not supported
     */
    fun parseDataset(data: String, format: RdfFormat): Dataset {
        return parseDataset(data, format.formatName)
    }
    
    /**
     * Parse RDF dataset (with named graphs) from a file.
     * 
     * @param filePath The path to the RDF dataset file
     * @param format The RDF quad format (default: "TRIG")
     * @return A new Dataset containing the parsed data
     * @throws RdfFormatException if parsing fails or format is not supported
     * @throws java.io.FileNotFoundException if the file does not exist
     */
    fun parseDatasetFromFile(filePath: String, format: String = "TRIG"): Dataset {
        val formatEnum = RdfFormat.fromStringOrThrow(format)
        if (!RdfFormat.isQuadFormat(formatEnum)) {
            throw IllegalArgumentException("Format '${formatEnum.formatName}' is not a quad format. Use parseFromFile() for graph formats, or use TRIG or N-QUADS for datasets.")
        }
        val file = java.io.File(filePath)
        if (!file.exists()) {
            throw java.io.FileNotFoundException("RDF file not found: $filePath")
        }
        val repo = memory()
        file.inputStream().use { stream -> parseDataset(repo, stream, format) }
        return repo
    }
    
    /**
     * Parse RDF dataset from a file (type-safe version).
     * 
     * @param filePath The path to the RDF dataset file
     * @param format The RDF quad format enum value
     * @return A new Dataset containing the parsed data
     * @throws RdfFormatException if parsing fails or format is not supported
     */
    fun parseDatasetFromFile(filePath: String, format: RdfFormat): Dataset {
        return parseDatasetFromFile(filePath, format.formatName)
    }
    
    /**
     * Parse RDF dataset (with named graphs) from a URL.
     * 
     * @param url The URL to load RDF dataset data from
     * @param format The RDF quad format (default: "TRIG")
     * @return A new Dataset containing the parsed data
     * @throws RdfFormatException if parsing fails or format is not supported
     * @throws java.io.IOException if network access fails
     */
    fun parseDatasetFromUrl(url: String, format: String = "TRIG"): Dataset {
        val formatEnum = RdfFormat.fromStringOrThrow(format)
        if (!RdfFormat.isQuadFormat(formatEnum)) {
            throw IllegalArgumentException("Format '${formatEnum.formatName}' is not a quad format. Use parseFromUrl() for graph formats, or use TRIG or N-QUADS for datasets.")
        }
        val connection = java.net.URL(url).openConnection()
        connection.connectTimeout = 30000 // 30 seconds
        connection.readTimeout = 30000
        val repo = memory()
        connection.getInputStream().use { stream -> parseDataset(repo, stream, format) }
        return repo
    }
    
    /**
     * Parse RDF dataset from a URL (type-safe version).
     * 
     * @param url The URL to load RDF dataset data from
     * @param format The RDF quad format enum value
     * @return A new Dataset containing the parsed data
     * @throws RdfFormatException if parsing fails or format is not supported
     */
    fun parseDatasetFromUrl(url: String, format: RdfFormat): Dataset {
        return parseDatasetFromUrl(url, format.formatName)
    }
    
    /**
     * Parse RDF dataset (with named graphs) from an input stream into a repository.
     * 
     * The parsed data will be added to the provided repository, preserving
     * named graph structure.
     * 
     * @param repository The repository to populate with parsed data
     * @param inputStream The input stream containing RDF dataset data
     * @param format The RDF quad format
     * @throws RdfFormatException if parsing fails or format is not supported
     */
    fun parseDataset(repository: RdfRepository, inputStream: InputStream, format: String) {
        val formatEnum = RdfFormat.fromStringOrThrow(format)
        if (!RdfFormat.isQuadFormat(formatEnum)) {
            throw IllegalArgumentException("Format '${formatEnum.formatName}' is not a quad format. Use parseFromInputStream() for graph formats, or use TRIG or N-QUADS for datasets.")
        }
        val providers = RdfProviderRegistry.discoverProviders()
        
        // Try to find a provider that supports this format
        for (provider in providers) {
            if (provider.supportsFormat(formatEnum.formatName)) {
                try {
                    provider.parseDataset(repository, inputStream, formatEnum.formatName)
                    return
                } catch (e: UnsupportedOperationException) {
                    // Provider doesn't actually support it, try next
                    continue
                } catch (e: RdfFormatException) {
                    // Format error, rethrow
                    throw e
                } catch (e: Exception) {
                    // Other error, wrap and throw
                    throw RdfFormatException(
                        "Failed to parse RDF dataset from input stream (format: ${formatEnum.formatName}) using provider ${provider.id}: ${e.message}",
                        e
                    )
                }
            }
        }
        
        throw RdfFormatException(
            "No provider found that supports format: ${formatEnum.formatName}. " +
            "Available providers: ${providers.map { it.id }.joinToString()}"
        )
    }
    
    /**
     * Parse RDF dataset from an input stream into a repository (type-safe version).
     * 
     * @param repository The repository to populate with parsed data
     * @param inputStream The input stream containing RDF dataset data
     * @param format The RDF quad format enum value
     * @throws RdfFormatException if parsing fails or format is not supported
     */
    fun parseDataset(repository: RdfRepository, inputStream: InputStream, format: RdfFormat) {
        parseDataset(repository, inputStream, format.formatName)
    }
    
    
    // === DEFAULT PROVIDER MANAGEMENT ===
    
    /**
     * Set the default RDF provider for factory methods.
     * Affects which backend is used when no specific type is specified.
     */
    fun setDefaultProvider(provider: String) {
        DefaultRdfProvider.set(provider)
    }
    
    /**
     * Get the current default RDF provider.
     */
    fun getDefaultProvider(): String = DefaultRdfProvider.get()
    
    // === BUILDER CLASSES ===
    
    /**
     * Builder for configuring individual RDF repositories.
     */
    class RdfRepositoryBuilder(
        defaultRegistry: ProviderRegistry = RdfProviderRegistry
    ) {
        var providerId: String? = null
        var variantId: String? = null
        var requirements: ProviderRequirements = ProviderRequirements()
        var location: String? = null
        var inference: Boolean = false
        var registry: ProviderRegistry = defaultRegistry

        fun provider(id: ProviderId) {
            providerId = id.value
        }

        fun variant(id: VariantId) {
            variantId = id.value
        }
        
        fun build(): RdfRepository {
            val config = RdfConfig(
                providerId = providerId,
                variantId = variantId,
                options = mapOf(
                    "location" to (location ?: DEFAULT_PERSISTENT_LOCATION),
                    "inference" to inference.toString()
                ),
                requirements = requirements.takeIf { it != ProviderRequirements() }
            )
            
            return registry.create(config)
        }
    }
    
}

// === CORE INTERFACES ===

// RdfGraph interface moved to RdfTerms.kt to avoid duplication


/**
 * Main interface for RDF repository operations.
 * Provides a unified API for all RDF operations including graph management and SPARQL queries.
 * 
 * **Relationship with [Dataset], [SparqlQueryable], and [SparqlMutable]:**
 * - [SparqlQueryable] is the minimal interface providing read-only SPARQL query operations
 * - [SparqlMutable] extends [SparqlQueryable] and adds [update] for SPARQL UPDATE operations
 * - [Dataset] extends [SparqlQueryable] and represents a SPARQL dataset (read-only, multiple default/named graphs)
 * - [RdfRepository] extends [Dataset] and [SparqlMutable], adding graph management operations (create/remove graphs, editing, etc.)
 * 
 * A repository is essentially a mutable dataset. All [RdfRepository] implementations also implement [Dataset],
 * [SparqlMutable], and [SparqlQueryable], so you can use any interface depending on your needs.
 */
interface RdfRepository : Dataset, SparqlMutable {
    
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
    
    // === DATASET INTERFACE IMPLEMENTATION ===
    
    /**
     * List of graphs whose union forms the default graph.
     * For a repository, this is just the single default graph.
     */
    override val defaultGraphs: List<RdfGraph>
        get() = listOf(defaultGraph)
    
    /**
     * Map of graph names to graphs for named graph access.
     * For a repository, this includes all named graphs.
     * 
     * Note: Implementations should cache this value to avoid expensive recomputation.
     */
    override val namedGraphs: Map<Iri, RdfGraph>
        get() = listGraphs().associateWith { getGraph(it) }
    
    /**
     * Get a named graph by IRI (Dataset interface method).
     */
    override fun getNamedGraph(name: Iri): RdfGraph? {
        return if (hasGraph(name)) getGraph(name) else null
    }
    
    /**
     * Check if a named graph exists (Dataset interface method).
     */
    override fun hasNamedGraph(name: Iri): Boolean = hasGraph(name)
    
    /**
     * List all named graph IRIs (Dataset interface method).
     */
    override fun listNamedGraphs(): List<Iri> = listGraphs()
    
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
     * 
     * **Error Handling:**
     * - Throws [RdfQueryException] if the query fails to parse or execute
     * - The exception includes the query string for debugging
     * - Use [selectOrNull] or [selectResult] for functional error handling
     * 
     * **Error Handling Pattern:**
     * - **Technical failures** (parsing, execution) → [RdfQueryException]
     * - **Semantic failures** (validation) → [ValidationResult] sealed class
     * - **Operations that should never fail** → Direct return types
     * 
     * @param query The SPARQL SELECT query to execute
     * @return SparqlQueryResult containing the query results
     * @throws RdfQueryException if the query fails to parse or execute
     */
    override fun select(query: SparqlSelect): SparqlQueryResult
    
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
     * 
     * **Resource Management:**
     * - Transaction is automatically rolled back on exception
     * - Transaction is committed on successful completion
     * - Resources are cleaned up even if exception occurs
     * 
     * **Example:**
     * ```kotlin
     * repo.transaction {
     *     repo.addTriple(triple1)
     *     repo.addTriple(triple2)
     *     // If any operation throws, all changes are rolled back
     * }
     * ```
     * 
     * @param operations The operations to execute in the transaction
     * @throws RdfTransactionException if transaction fails
     */
    fun transaction(operations: RdfRepository.() -> Unit)
    
    /**
     * Execute read-only operations within a transaction.
     * 
     * **Resource Management:**
     * - Read transaction provides consistent view of data
     * - No changes are committed (read-only)
     * - Resources are cleaned up on completion
     * 
     * @param operations The read-only operations to execute
     * @throws RdfTransactionException if transaction fails
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
interface SparqlQueryResult : Iterable<BindingSet> {
    
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
 * Simple in-memory SPARQL query result backed by a list.
 */
class ListSparqlQueryResult(private val rows: List<BindingSet>) : SparqlQueryResult {
    override fun iterator(): Iterator<BindingSet> = rows.iterator()
    override fun count(): Int = rows.size
    override fun first(): BindingSet? = rows.firstOrNull()
    override fun toList(): List<BindingSet> = rows.toList()
    override fun asSequence(): Sequence<BindingSet> = rows.asSequence()
}

/**
 * Factory function to create a SPARQL query result from a list of binding sets.
 * 
 * **Example:**
 * ```kotlin
 * val bindings = listOf(
 *     MapBindingSet(mapOf("name" to Literal("Alice"))),
 *     MapBindingSet(mapOf("name" to Literal("Bob")))
 * )
 * val result = sparqlQueryResult(bindings)
 * ```
 * 
 * @param rows List of binding sets representing query result rows
 * @return A SparqlQueryResult containing the provided rows
 */
fun sparqlQueryResult(rows: List<BindingSet>): SparqlQueryResult = ListSparqlQueryResult(rows)

/**
 * Factory function to create an empty SPARQL query result.
 * 
 * **Example:**
 * ```kotlin
 * val emptyResult = emptySparqlQueryResult()
 * assertTrue(emptyResult.count() == 0)
 * ```
 * 
 * @return An empty SparqlQueryResult (singleton instance)
 */
fun emptySparqlQueryResult(): SparqlQueryResult = EmptySparqlQueryResult

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
    
    /**
     * Get a string value for a variable, or return the default if not bound.
     * 
     * @param variable The variable name
     * @param default The default value to return if the variable is not bound
     * @return The string value or the default
     */
    fun getStringOr(variable: String, default: String): String = getString(variable) ?: default
    
    /**
     * Get an integer value for a variable, or return the default if not bound.
     * 
     * @param variable The variable name
     * @param default The default value to return if the variable is not bound
     * @return The integer value or the default
     */
    fun getIntOr(variable: String, default: Int): Int = getInt(variable) ?: default
    
    /**
     * Get a double value for a variable, or return the default if not bound.
     * 
     * @param variable The variable name
     * @param default The default value to return if the variable is not bound
     * @return The double value or the default
     */
    fun getDoubleOr(variable: String, default: Double): Double = getDouble(variable) ?: default
    
    /**
     * Get a boolean value for a variable, or return the default if not bound.
     * 
     * @param variable The variable name
     * @param default The default value to return if the variable is not bound
     * @return The boolean value or the default
     */
    fun getBooleanOr(variable: String, default: Boolean): Boolean = getBoolean(variable) ?: default
    
    /**
     * Get a string value for a variable, or throw an exception if not bound.
     * 
     * @param variable The variable name
     * @return The string value
     * @throws IllegalArgumentException if the variable is not bound
     */
    fun getStringOrThrow(variable: String): String = 
        getString(variable) ?: throw IllegalArgumentException("Variable '$variable' is not bound")
    
    /**
     * Get an integer value for a variable, or throw an exception if not bound.
     * 
     * @param variable The variable name
     * @return The integer value
     * @throws IllegalArgumentException if the variable is not bound or cannot be converted to Int
     */
    fun getIntOrThrow(variable: String): Int = 
        getInt(variable) ?: throw IllegalArgumentException("Variable '$variable' is not bound or cannot be converted to Int")
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
) {
    fun providerIdTyped(): ProviderId? = providerId?.let(::ProviderId)

    fun variantIdTyped(): VariantId? = variantId?.let(::VariantId)

    companion object {
        fun of(
            providerId: ProviderId?,
            variantId: VariantId? = null,
            options: Map<String, String> = emptyMap(),
            requirements: ProviderRequirements? = null
        ): RdfConfig = RdfConfig(providerId?.value, variantId?.value, options, requirements)
    }
}

/**
 * Typed provider identifiers to avoid stringly-typed APIs.
 */
@JvmInline
value class ProviderId(val value: String)

@JvmInline
value class VariantId(val value: String)

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
 * Interface for RDF providers with optional enhanced capabilities.
 */
interface RdfProvider {
    
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
    
    // === FORMAT SUPPORT (Optional) ===
    
    /**
     * Check if this provider supports a specific RDF format for serialization/parsing.
     * 
     * Default implementation checks against [ProviderCapabilities.supportedInputFormats]
     * and common format names.
     * 
     * @param format The RDF format (can be a string or RdfFormat enum value)
     * @return true if the format is supported, false otherwise
     */
    fun supportsFormat(format: String): Boolean {
        val normalized = format.uppercase().trim()
        val capabilities = getCapabilities()
        
        // Check explicit format support
        if (normalized in capabilities.supportedInputFormats.map { it.uppercase() }) {
            return true
        }
        
        // Check common format aliases
        val formatEnum = RdfFormat.fromString(normalized)
        return formatEnum != null && formatEnum.formatName in capabilities.supportedInputFormats.map { it.uppercase() }
    }
    
    /**
     * Serialize a graph to the specified format.
     * 
     * Default implementation throws [UnsupportedOperationException].
     * Providers that support serialization should override this method.
     * 
     * @param graph The graph to serialize
     * @param format The target format
     * @return The serialized RDF data as a string
     * @throws RdfFormatException if the format is not supported or serialization fails
     * @throws UnsupportedOperationException if the provider doesn't support serialization
     */
    fun serializeGraph(graph: RdfGraph, format: String): String {
        throw UnsupportedOperationException("Provider '${id}' does not support graph serialization")
    }
    
    /**
     * Serialize a repository (dataset with named graphs) to the specified quad format.
     * 
     * Quad formats (TriG, N-Quads) support serialization of multiple named graphs.
     * For graph-only formats, use [serializeGraph] instead.
     * 
     * Default implementation throws [UnsupportedOperationException].
     * Providers that support dataset serialization should override this method.
     * 
     * @param repository The repository to serialize
     * @param format The target quad format (TRIG, N-QUADS)
     * @return The serialized RDF dataset as a string
     * @throws RdfFormatException if the format is not supported or serialization fails
     * @throws UnsupportedOperationException if the provider doesn't support dataset serialization
     */
    fun serializeDataset(repository: RdfRepository, format: String): String {
        throw UnsupportedOperationException("Provider '${id}' does not support dataset serialization")
    }
    
    /**
     * Parse RDF data from an input stream into a graph.
     * 
     * Default implementation throws [UnsupportedOperationException].
     * Providers that support parsing should override this method.
     * 
     * @param inputStream The input stream containing RDF data
     * @param format The RDF format
     * @return A new MutableRdfGraph containing the parsed triples
     * @throws RdfFormatException if the format is not supported or parsing fails
     * @throws UnsupportedOperationException if the provider doesn't support parsing
     */
    fun parseGraph(inputStream: java.io.InputStream, format: String): MutableRdfGraph {
        throw UnsupportedOperationException("Provider '${id}' does not support graph parsing")
    }
    
    /**
     * Parse RDF dataset (with named graphs) from an input stream into a repository.
     * 
     * Quad formats (TriG, N-Quads) support parsing of multiple named graphs.
     * The parsed data will be added to the provided repository.
     * 
     * Default implementation throws [UnsupportedOperationException].
     * Providers that support dataset parsing should override this method.
     * 
     * @param repository The repository to populate with parsed data
     * @param inputStream The input stream containing RDF dataset data
     * @param format The RDF quad format (TRIG, N-QUADS)
     * @throws RdfFormatException if the format is not supported or parsing fails
     * @throws UnsupportedOperationException if the provider doesn't support dataset parsing
     */
    fun parseDataset(repository: RdfRepository, inputStream: java.io.InputStream, format: String) {
        throw UnsupportedOperationException("Provider '${id}' does not support dataset parsing")
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
    val sparqlFeatures: Set<SparqlFeature> = emptySet(),
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
    val defaultGraphs: List<String> = emptyList(),
    val sparqlFeatures: Set<SparqlFeature> = emptySet()
)

/**
 * Canonical SPARQL feature identifiers for typed capability checks.
 */
enum class SparqlFeature {
    RDF_STAR,
    PROPERTY_PATHS,
    AGGREGATION,
    SUBSELECT,
    INFERENCE,
    ENTAILMENT,
    FEDERATION,
    SERVICE_DESCRIPTION,
    VERSION_DECLARATION
}

/**
 * Map legacy booleans to a typed feature set.
 */
fun ProviderCapabilities.featureSet(): Set<SparqlFeature> = buildSet {
    if (supportsRdfStar) add(SparqlFeature.RDF_STAR)
    if (supportsPropertyPaths) add(SparqlFeature.PROPERTY_PATHS)
    if (supportsAggregation) add(SparqlFeature.AGGREGATION)
    if (supportsSubSelect) add(SparqlFeature.SUBSELECT)
    if (supportsInference) add(SparqlFeature.INFERENCE)
    if (supportsFederation) add(SparqlFeature.FEDERATION)
    if (supportsServiceDescription) add(SparqlFeature.SERVICE_DESCRIPTION)
    if (supportsVersionDeclaration) add(SparqlFeature.VERSION_DECLARATION)
}

// === DEFAULT PROVIDER MANAGEMENT ===

/**
 * Manages the default RDF provider.
 */
object DefaultRdfProvider {
    /**
     * Default provider ID used when no provider is specified.
     */
    const val DEFAULT_PROVIDER_ID = "memory"
    
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









