package com.geoknoesis.kastor.rdf

import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.dsl.GraphDsl
import com.geoknoesis.kastor.rdf.provider.EmptySparqlQueryResult
import com.geoknoesis.kastor.rdf.RdfFormat
import java.util.regex.Pattern

/**
 * Helper function to extract parsing error context from provider exceptions.
 * Attempts to extract line/column information from exception messages.
 */
private fun extractParseErrorContext(
    exception: Throwable,
    format: String,
    data: ByteArray? = null
): ParseErrorDetails {
    val message = exception.message ?: "Unknown parsing error"
    
    // Try to extract line/column from common error message patterns
    var line: Int? = null
    var column: Int? = null
    var snippet: String? = null
    
    // Pattern for "line X" or "line X, column Y"
    val linePattern = Pattern.compile("line\\s+(\\d+)", Pattern.CASE_INSENSITIVE)
    val columnPattern = Pattern.compile("column\\s+(\\d+)", Pattern.CASE_INSENSITIVE)
    
    val messageMatcher = linePattern.matcher(message)
    if (messageMatcher.find()) {
        line = messageMatcher.group(1)?.toIntOrNull()
    }
    
    val columnMatcher = columnPattern.matcher(message)
    if (columnMatcher.find()) {
        column = columnMatcher.group(1)?.toIntOrNull()
    }
    
    // Extract snippet around error location if we have line number and data
    if (line != null && data != null) {
        try {
            val text = data.toString(Charsets.UTF_8)
            val lines = text.lines()
            if (line > 0 && line <= lines.size) {
                val errorLine = lines[line - 1]
                // Include error line and context (previous and next line if available)
                val start = maxOf(0, line - 2)
                val end = minOf(lines.size, line + 1)
                snippet = lines.subList(start, end).joinToString("\n")
            }
        } catch (e: Exception) {
            // Ignore snippet extraction errors
        }
    }
    
    return ParseErrorDetails(
        message = message,
        line = line,
        column = column,
        snippet = snippet,
        format = format,
        cause = exception
    )
}

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
    private val urlIoExecutor: Executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "kastor-url-io").apply { isDaemon = true }
    }
    
    /**
     * Default location for persistent repositories.
     * Used when no location is specified in repository configuration.
     */
    internal const val DEFAULT_PERSISTENT_LOCATION = "data"
    
    // === FACTORY METHODS ===
    
    /**
     * Create an in-memory repository backed by a real SPARQL-capable provider.
     *
     * **Required runtime dependency:** Either `:rdf:jena` or `:rdf:rdf4j` (or any other
     * provider that exposes a `memory` variant) must be on the classpath. The bundled
     * `MemoryRepositoryProvider` is intentionally not used here because it does not
     * support SPARQL queries or RDF parsing/serialization. To use that provider for
     * graph-only testing, opt in explicitly via [repository] with `providerId = "memory"`.
     *
     * @throws RdfProviderException if no SPARQL-capable provider with a `memory`
     * variant is registered.
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
            else -> throw RdfProviderException(
                "Rdf.memory() requires a SPARQL-capable provider on the classpath. " +
                    "Add either 'com.geoknoesis.kastor:rdf-jena' or 'com.geoknoesis.kastor:rdf-rdf4j' " +
                    "to your dependencies, or call Rdf.repository { providerId = \"memory\" } " +
                    "to use the limited graph-only memory provider explicitly.",
                RdfErrorCode.PROVIDER_NOT_FOUND
            )
        }
    }
    
    /**
     * Create an in-memory repository with RDFS inference.
     * Automatically infers additional triples based on RDFS rules.
     *
     * **Required runtime dependency:** Either `:rdf:jena` (provides `memory-inference`)
     * or `:rdf:rdf4j` (provides `memory-rdfs`) must be on the classpath.
     *
     * @throws RdfProviderException if no inference-capable in-memory provider is registered.
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
            else -> throw RdfProviderException(
                "Rdf.memoryWithInference() requires a provider that supports RDFS inference. " +
                    "Add either 'com.geoknoesis.kastor:rdf-jena' or 'com.geoknoesis.kastor:rdf-rdf4j' " +
                    "to your dependencies.",
                RdfErrorCode.PROVIDER_NOT_FOUND
            )
        }
        inference = true
    }
    
    /**
     * Create a persistent repository with TDB2 (Jena) or NativeStore (RDF4J) backend.
     * Data persists between application restarts.
     *
     * **Required runtime dependency:** Either `:rdf:jena` (provides `tdb2`) or
     * `:rdf:rdf4j` (provides `native`) must be on the classpath.
     *
     * @throws RdfProviderException if no persistent-capable provider is registered.
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
            else -> throw RdfProviderException(
                "Rdf.persistent() requires a provider with a persistent backend. " +
                    "Add either 'com.geoknoesis.kastor:rdf-jena' or 'com.geoknoesis.kastor:rdf-rdf4j' " +
                    "to your dependencies.",
                RdfErrorCode.PROVIDER_NOT_FOUND
            )
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
        executor: Executor = urlIoExecutor
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
        executor: Executor = urlIoExecutor
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
                    // Extract parsing error context with line/column information
                    val parseError = extractParseErrorContext(e, formatEnum.formatName, null)
                    throw RdfFormatException.ParseError(parseError)
                }
            }
        }
        
        throw RdfFormatException.UnsupportedFormat(
            formatEnum.formatName,
            providers.flatMap { it.getCapabilities().supportedInputFormats }.distinct()
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
    
    /**
     * Parse RDF data from an input stream as a sequence of triples (streaming).
     * 
     * This method enables memory-efficient parsing of large RDF files by returning
     * triples as a lazy sequence rather than loading everything into memory at once.
     * 
     * **Memory Efficiency:**
     * - The input stream is processed incrementally
     * - Triples are yielded as they are parsed (lazy evaluation)
     * - Suitable for processing large files without running out of memory
     * 
     * **Example:**
     * ```kotlin
     * Rdf.parseStreaming(File("large.ttl").inputStream(), RdfFormat.TURTLE)
     *     .chunked(1000) // Process in batches
     *     .forEach { batch ->
     *         repo.addTriples(batch)
     *     }
     * ```
     * 
     * @param inputStream The input stream containing RDF data
     * @param format The RDF format
     * @return A sequence of RDF triples (lazy evaluation)
     * @throws RdfFormatException if parsing fails or format is not supported
     */
    fun parseStreaming(inputStream: InputStream, format: String): Sequence<RdfTriple> {
        val formatEnum = RdfFormat.fromStringOrThrow(format)
        val providers = RdfProviderRegistry.discoverProviders()
        
        // Try to find a provider that supports this format
        for (provider in providers) {
            if (provider.supportsFormat(formatEnum.formatName)) {
                try {
                    return provider.parseStreaming(inputStream, formatEnum.formatName)
                } catch (e: UnsupportedOperationException) {
                    // Provider doesn't actually support it, try next
                    continue
                } catch (e: RdfFormatException) {
                    // Format error, rethrow
                    throw e
                } catch (e: Exception) {
                    // Extract parsing error context
                    val parseError = extractParseErrorContext(e, formatEnum.formatName, null)
                    throw RdfFormatException.ParseError(parseError)
                }
            }
        }
        
        throw RdfFormatException.UnsupportedFormat(
            formatEnum.formatName,
            providers.flatMap { it.getCapabilities().supportedInputFormats }.distinct()
        )
    }
    
    /**
     * Parse RDF data from an input stream as a sequence of triples (streaming, type-safe version).
     * 
     * @param inputStream The input stream containing RDF data
     * @param format The RDF format enum value
     * @return A sequence of RDF triples (lazy evaluation)
     * @throws RdfFormatException if parsing fails or format is not supported
     */
    fun parseStreaming(inputStream: InputStream, format: RdfFormat): Sequence<RdfTriple> {
        return parseStreaming(inputStream, format.formatName)
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
                    // Extract parsing error context with line/column information
                    val parseError = extractParseErrorContext(e, formatEnum.formatName, null)
                    throw RdfFormatException.ParseError(parseError)
                }
            }
        }
        
        throw RdfFormatException.UnsupportedFormat(
            formatEnum.formatName,
            providers.flatMap { it.getCapabilities().supportedInputFormats }.distinct()
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
            val options = buildMap<String, String> {
                // Only thread `location` through when the caller explicitly set it.
                // This avoids handing a phantom `data` directory to memory variants
                // that do not need any storage path.
                location?.let { put("location", it) }
                put("inference", inference.toString())
            }
            val config = RdfConfig(
                providerId = providerId,
                variantId = variantId,
                options = options,
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
     * Get a mutable graph for the default graph.
     */
    fun editDefaultGraph(): MutableRdfGraph

    /**
     * Get a mutable graph for a named graph.
     */
    fun editGraph(name: Iri): MutableRdfGraph
    
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
     *
     * Accepts the same lexical forms as the [Literal] factory for `xsd:boolean`:
     * `"true"` / `"false"` (case-insensitive) and `"1"` / `"0"`. Any other
     * lexical form returns null.
     */
    fun getBoolean(variable: String): Boolean? = when (getString(variable)?.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        null -> null
        else -> null
    }
    
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
    
    /**
     * Register multiple providers at once.
     * Useful for Android/KMP initialization where ServiceLoader may not work.
     * 
     * @param providers The providers to register
     * 
     * @sample com.example.RegisterMultipleProviders
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
     * Default implementation checks against [ProviderCapabilities.supportedOutputFormats]
     * (for serialization) or [ProviderCapabilities.supportedInputFormats] (for parsing),
     * and common format names.
     * 
     * @param format The RDF format (can be a string or RdfFormat enum value)
     * @return true if the format is supported, false otherwise
     */
    fun supportsFormat(format: String): Boolean {
        val normalized = format.uppercase().trim()
        val capabilities = getCapabilities()
        
        // Check output formats first (for serialization), then input formats (for parsing)
        val supportedFormats = if (capabilities.supportedOutputFormats.isNotEmpty()) {
            capabilities.supportedOutputFormats
        } else {
            capabilities.supportedInputFormats
        }
        
        // Check explicit format support
        if (normalized in supportedFormats.map { it.uppercase() }) {
            return true
        }
        
        // Check common format aliases
        val formatEnum = RdfFormat.fromString(normalized)
        return formatEnum != null && formatEnum.formatName in supportedFormats.map { it.uppercase() }
    }
    
    /**
     * Serialize a graph to the specified format.
     * 
     * Default implementation throws [UnsupportedOperationException].
     * Providers that support serialization should override this method.
     * 
     * @param graph The graph to serialize
     * @param format The target format
     * @param options Serialization options (optional, defaults to [SerializationOptions.DEFAULT])
     * @return The serialized RDF data as a string
     * @throws RdfFormatException if the format is not supported or serialization fails
     * @throws UnsupportedOperationException if the provider doesn't support serialization
     */
    fun serializeGraph(graph: RdfGraph, format: String, options: SerializationOptions = SerializationOptions.DEFAULT): String {
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
     * @param options Serialization options (optional, defaults to [SerializationOptions.DEFAULT])
     * @return The serialized RDF dataset as a string
     * @throws RdfFormatException if the format is not supported or serialization fails
     * @throws UnsupportedOperationException if the provider doesn't support dataset serialization
     */
    fun serializeDataset(repository: RdfRepository, format: String, options: SerializationOptions = SerializationOptions.DEFAULT): String {
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
     * Parse RDF data from an input stream into a graph using an explicit base
     * IRI for resolving relative IRIs in the input.
     *
     * The default implementation ignores [baseIri] and calls [parseGraph];
     * providers that can honour an external base should override this method.
     *
     * @param inputStream The input stream containing RDF data
     * @param format The RDF format
     * @param baseIri Absolute IRI used to resolve relative references in the
     *   input. Pass null to fall back to the format's default behaviour.
     */
    fun parseGraph(
        inputStream: java.io.InputStream,
        format: String,
        baseIri: String?,
    ): MutableRdfGraph = parseGraph(inputStream, format)
    
    /**
     * Parse RDF data from an input stream as a sequence of triples (streaming).
     * 
     * This method enables memory-efficient parsing of large RDF files by returning
     * triples as a lazy sequence rather than loading everything into memory.
     * 
     * Default implementation reads the stream and delegates to [parseGraph], then
     * returns the triples as a sequence. Providers that support true streaming
     * should override this method for better performance.
     * 
     * @param inputStream The input stream containing RDF data
     * @param format The RDF format
     * @return A sequence of RDF triples (lazy evaluation)
     * @throws RdfFormatException if the format is not supported or parsing fails
     * @throws UnsupportedOperationException if the provider doesn't support parsing
     */
    fun parseStreaming(inputStream: java.io.InputStream, format: String): Sequence<RdfTriple> {
        // Default implementation: parse to graph, then return triples as sequence
        val graph = parseGraph(inputStream, format)
        return graph.getTriplesSequence()
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

    /**
     * Parse RDF dataset data from an input stream into the given repository,
     * using an explicit base IRI for relative-IRI resolution.
     *
     * The default implementation ignores [baseIri] and delegates to the
     * single-arg [parseDataset]. Providers that can honour an external base
     * should override.
     */
    fun parseDataset(
        repository: RdfRepository,
        inputStream: java.io.InputStream,
        format: String,
        baseIri: String?,
    ) {
        parseDataset(repository, inputStream, format)
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
    /**
     * The RDF specification version this provider conforms to. `"1.1"` for
     * the bundled in-memory provider; `"1.2"` for Jena/RDF4J in their current
     * pinned versions. Used by callers that need to make spec-version aware
     * decisions (for example: should we emit `<<( s p o )>>` or use the legacy
     * RDF-star reification model when constructing data).
     */
    val rdfVersion: String = "1.1",
    /**
     * True if the provider supports RDF 1.2 triple terms (`<<( s p o )>>`)
     * including `rdf:reifies`. False on legacy RDF 1.1-only providers.
     */
    val supportsTripleTerms: Boolean = false,
    // Existing capabilities
    val supportsInference: Boolean = false,
    val supportsTransactions: Boolean = false,
    val supportsNamedGraphs: Boolean = false,
    val supportsUpdates: Boolean = false,
    /**
     * True if the provider preserves RDF-star quoted triples (legacy semantics)
     * during round-trip. In RDF 1.2 [supportsTripleTerms] is the relevant flag;
     * this field stays for backwards compatibility.
     */
    val supportsRdfStar: Boolean = false,
    /** True if the provider validates writes against SHACL shapes (e.g. RDF4J `ShaclSail`). */
    val supportsShacl: Boolean = false,
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
    val supportedOutputFormats: List<String> = emptyList(), // Formats supported for serialization
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









