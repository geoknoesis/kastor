package com.geoknoesis.kastor.rdf

import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import com.geoknoesis.kastor.rdf.dsl.GraphDsl
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

private fun providerConsumedInput(provider: RdfProvider, format: RdfFormat, cause: Throwable) =
    RdfFormatException.Generic(
        "Provider '${provider.id}' consumed the input before declining ${format.formatName}; no other provider can be tried",
        RdfErrorCode.FORMAT_UNSUPPORTED,
        cause,
    )

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
    /** Threads of the default [parseFromUrlAsync] executor. */
    internal const val URL_IO_THREADS = 4

    /** Pending loads the default [parseFromUrlAsync] executor queues before rejecting new ones. */
    internal const val URL_IO_QUEUE_CAPACITY = 256

    /**
     * Default executor for [parseFromUrlAsync]: 4 daemon threads and a queue of 256 pending loads. When both are
     * full, a new load is rejected: its future completes exceptionally with
     * [java.util.concurrent.RejectedExecutionException]. A load never runs on the calling thread.
     */
    private val urlIoExecutor: Executor = newUrlIoExecutor()

    /**
     * An executor of the kind [parseFromUrlAsync] uses by default: [threads] daemon threads that end when idle, a
     * queue of [queueCapacity] pending loads, and rejection (never the calling thread) beyond that. Tests create
     * small ones, so that saturating one takes a handful of loads.
     */
    internal fun newUrlIoExecutor(
        threads: Int = URL_IO_THREADS,
        queueCapacity: Int = URL_IO_QUEUE_CAPACITY,
    ): java.util.concurrent.ThreadPoolExecutor = java.util.concurrent.ThreadPoolExecutor(
        threads, threads, 30L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue(queueCapacity),
        java.util.concurrent.ThreadFactory { runnable -> Thread(runnable, "kastor-url-io").apply { isDaemon = true } },
        java.util.concurrent.RejectedExecutionHandler { _, _ ->
            throw java.util.concurrent.RejectedExecutionException(
                "The default Rdf.parseFromUrlAsync executor is saturated ($threads loads running, " +
                    "$queueCapacity queued); retry later or pass an executor sized for this workload"
            )
        },
    ).apply { allowCoreThreadTimeOut(true) }
    
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
     * The provider is the first registered provider, in registry order ([RdfProvider.priority], then
     * registration order), that offers a `memory` variant and declares SPARQL support for it - for example
     * Jena (priority 50) before RDF4J (priority 40) when both are present. A provider declares SPARQL support when
     * the capabilities of its `memory` variant list a [SparqlFeature], property paths, aggregation or sub-selects,
     * or a supported language containing `SPARQL` ([ProviderCapabilities.sparqlVersion] has a default value and
     * is therefore not a signal).
     *
     * @throws RdfProviderException if no SPARQL-capable provider with a `memory`
     * variant is registered.
     */
    fun memory(): RdfRepository {
        val provider = RdfProviderRegistry.discoverProviders().firstOrNull {
            it !is com.geoknoesis.kastor.rdf.provider.MemoryRepositoryProvider && it.supportsVariant("memory") &&
                runCatching { it.getCapabilities("memory").declaresSparqlSupport() }.getOrDefault(false)
        } ?: throw RdfProviderException(
            "Rdf.memory() requires a SPARQL-capable provider on the classpath. " +
                "Add either 'com.geoknoesis.kastor:rdf-jena' or 'com.geoknoesis.kastor:rdf-rdf4j' " +
                "to your dependencies, or call Rdf.repository { providerId = \"memory\" } " +
                "to use the limited graph-only memory provider explicitly.",
            RdfErrorCode.PROVIDER_NOT_FOUND
        )
        return repository {
            providerId = provider.id
            variantId = "memory"
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
     *     variantId = "tdb2-inference"
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
     * Parse RDF data from a string, resolving relative IRIs against [baseIri].
     *
     * @param baseIri Absolute IRI for relative references; null keeps the provider's default (relative IRIs
     *   are then errors with the bundled providers)
     * @throws RdfFormatException if parsing fails or format is not supported
     */
    fun parse(data: String, format: RdfFormat, baseIri: String?): MutableRdfGraph =
        parseFromInputStream(data.byteInputStream(), format.formatName, baseIri)

    /**
     * Parse RDF data from a string, resolving relative IRIs against [baseIri].
     *
     * @param format The RDF format name or alias (for example `"TURTLE"` or `"ttl"`)
     * @param baseIri Absolute IRI for relative references; null keeps the provider's default
     * @throws RdfFormatException if parsing fails or format is not supported
     */
    fun parse(data: String, format: String, baseIri: String?): MutableRdfGraph =
        parseFromInputStream(data.byteInputStream(), format, baseIri)
    
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
     *
     * Relative IRIs (`<>`, `<#Foo>`, RDF/XML `rdf:about="#Foo"`) resolve against the file's absolute
     * `file:` URI.
     */
    fun parseFromFile(filePath: String, format: String = "TURTLE"): MutableRdfGraph {
        val file = java.io.File(filePath)
        if (!file.exists()) {
            throw java.io.FileNotFoundException("RDF file not found: $filePath")
        }
        val baseIri = file.absoluteFile.toURI().toString()
        return file.inputStream().use { stream -> parseFromInputStream(stream, format, baseIri) }
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
     * @param options Scheme allowlist, body size limit and timeouts (default: http/https only, 64 MiB)
     * @return A new MutableRdfGraph containing the parsed triples
     * @throws RdfFormatException if parsing fails or format is not supported
     * @throws IllegalArgumentException if the URL is malformed or its scheme is not allowed
     * @throws RdfInputTooLargeException if the body exceeds [UrlLoadOptions.maxBytes]
     * @throws RdfHttpStatusException if the server answers with a non-2xx status
     * @throws RdfLoadTimeoutException if loading exceeds [UrlLoadOptions.totalTimeoutMillis]
     * @throws java.io.IOException if network access fails
     *
     * The request asks for the format's media type (`Accept`), and relative IRIs resolve against the
     * final URL.
     */
    fun parseFromUrl(
        url: String,
        format: String = "TURTLE",
        options: UrlLoadOptions = UrlLoadOptions.DEFAULT,
    ): MutableRdfGraph {
        val body = openRdfUrlStream(url, RdfFormat.fromString(format), options)
        return body.read { stream -> parseFromInputStream(stream, format, body.baseIri) }
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
    fun parseFromUrl(url: String, format: RdfFormat, options: UrlLoadOptions = UrlLoadOptions.DEFAULT): MutableRdfGraph {
        return parseFromUrl(url, format.formatName, options)
    }

    /**
     * Parse RDF data from a URL asynchronously into a graph.
     *
     * This runs the blocking network call on the provided executor and never on the calling thread. The default
     * executor has 4 threads and queues up to 256 loads; beyond that this call returns at once with a future
     * completed exceptionally with [java.util.concurrent.RejectedExecutionException]. A custom executor that
     * rejects a task yields a future completed exceptionally with that exception.
     *
     * @param url The URL to load RDF data from
     * @param format The RDF format (default: "TURTLE")
     * @param executor Executor used for the blocking operation
     * @param options Scheme allowlist, body size limit and timeouts (default: http/https only, 64 MiB)
     * @return A future with the parsed graph; it completes exceptionally with [IllegalArgumentException]
     *   for a disallowed scheme and [RdfInputTooLargeException] for an oversized body
     */
    fun parseFromUrlAsync(
        url: String,
        format: String = "TURTLE",
        executor: Executor = urlIoExecutor,
        options: UrlLoadOptions = UrlLoadOptions.DEFAULT,
    ): CompletableFuture<MutableRdfGraph> {
        val active = java.util.concurrent.atomic.AtomicReference<java.net.URLConnection?>()
        val input = java.util.concurrent.atomic.AtomicReference<java.io.InputStream?>()
        val result = object : CompletableFuture<MutableRdfGraph>() {
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
                val cancelled = super.cancel(mayInterruptIfRunning)
                if (cancelled) {
                    (active.get() as? java.net.HttpURLConnection)?.disconnect()
                    runCatching { input.get()?.close() }
                }
                return cancelled
            }
        }
        try { executor.execute {
            if (!result.isCancelled) {
                try {
                    val body = openRdfUrlStream(url, RdfFormat.fromString(format), options) {
                        active.set(it)
                        // Cancelled before this hop's connection existed: cancel() found nothing to disconnect, so
                        // stop here instead of letting the connect run to its timeout.
                        if (result.isCancelled) throw java.util.concurrent.CancellationException("Cancelled")
                    }
                    body.read { stream ->
                        input.set(stream)
                        if (!result.isCancelled) result.complete(parseFromInputStream(stream, format, body.baseIri))
                    }
                } catch (e: Throwable) { result.completeExceptionally(e)
                } finally { input.set(null) }
            }
        } } catch (e: java.util.concurrent.RejectedExecutionException) { result.completeExceptionally(e) }
        return result
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
        executor: Executor = urlIoExecutor,
        options: UrlLoadOptions = UrlLoadOptions.DEFAULT,
    ): CompletableFuture<MutableRdfGraph> {
        return parseFromUrlAsync(url, format.formatName, executor, options)
    }
    
    /**
     * Parse RDF data from an input stream into a graph.
     * 
     * **The caller owns [inputStream]:** it is read (to its end, or up to the error) but never closed, whichever
     * provider parses it and whether parsing succeeds or fails. Close it yourself, for example with `use`:
     * `stream.use { Rdf.parseFromInputStream(it, "TURTLE") }`. The same holds for every `parseFromInputStream`,
     * `parseStreaming` and `parseDataset` overload that takes a stream. Only the scoped [openTripleStream] (and
     * `parseStreamingFlow`, which is built on it) takes ownership of the stream it is given.
     * 
     * @param inputStream The input stream containing RDF data; not closed
     * @param format The RDF format
     * @return A new MutableRdfGraph containing the parsed triples
     * @throws RdfFormatException if parsing fails or format is not supported
     */
    fun parseFromInputStream(inputStream: InputStream, format: String): MutableRdfGraph =
        parseFromInputStream(inputStream, format, null)

    /**
     * Parse RDF data from an input stream into a graph, resolving relative IRIs against [baseIri].
     *
     * **The caller owns [inputStream]:** it is read but never closed, whether parsing succeeds or fails.
     *
     * @param inputStream The input stream containing RDF data; not closed
     * @param format The RDF format
     * @param baseIri Absolute IRI for relative references, passed to [RdfProvider.parseGraph]; null keeps the
     *   provider's default (relative IRIs are then errors with the bundled providers)
     * @return A new MutableRdfGraph containing the parsed triples
     * @throws RdfFormatException if parsing fails or format is not supported
     */
    fun parseFromInputStream(inputStream: InputStream, format: String, baseIri: String?): MutableRdfGraph {
        val formatEnum = RdfFormat.fromStringOrThrow(format)
        val providers = RdfProviderRegistry.discoverProviders()
        // The stream goes straight to the highest-priority provider that declares the input format; nothing
        // is buffered. Another provider is only tried if one declines before consuming any input.
        val input = CountingInputStream(inputStream)

        for (provider in providers) {
            if (!provider.supportsInputFormat(formatEnum.formatName)) continue
            try {
                return provider.parseGraph(input, formatEnum.formatName, baseIri)
            } catch (e: UnsupportedOperationException) {
                if (input.count > 0) throw providerConsumedInput(provider, formatEnum, e)
                continue
            } catch (e: RdfFormatException) {
                throw e.inputLimitCause() ?: input.failure ?: e
            } catch (e: Exception) {
                (e.inputLimitCause() ?: input.failure)?.let { throw it }
                // Extract parsing error context with line/column information
                val parseError = extractParseErrorContext(e, formatEnum.formatName, null)
                throw RdfFormatException.ParseError(parseError)
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

    /** Type-safe [parseFromInputStream] with a base IRI for relative references. */
    fun parseFromInputStream(inputStream: InputStream, format: RdfFormat, baseIri: String?): MutableRdfGraph =
        parseFromInputStream(inputStream, format.formatName, baseIri)
    
    /**
     * Legacy sequence convenience API. Bundled providers materialize the input before returning.
     * Use [openTripleStream] for bounded, scoped consumption and deterministic early-stop cleanup.
     * 
     * **Example:**
     * ```kotlin
     * Rdf.openTripleStream(File("large.ttl").inputStream(), RdfFormat.TURTLE).use { triples ->
     *     triples.chunked(1000).forEach { batch -> repo.addTriples(batch) }
     * }
     * ```
     * 
     * @param inputStream The input stream containing RDF data; the caller owns it, it is not closed
     * @param format The RDF format
     * @return A sequence over the parsed triples
     * @throws RdfFormatException if parsing fails or format is not supported
     */
    fun parseStreaming(inputStream: InputStream, format: String): Sequence<RdfTriple> =
        parseStreaming(inputStream, format, null)

    /**
     * [parseStreaming] resolving relative IRIs against [baseIri] (see [RdfProvider.parseStreaming]).
     *
     * @param baseIri Absolute IRI for relative references; null keeps the provider's default
     */
    fun parseStreaming(inputStream: InputStream, format: String, baseIri: String?): Sequence<RdfTriple> {
        val formatEnum = RdfFormat.fromStringOrThrow(format)
        val providers = RdfProviderRegistry.discoverProviders()
        // Streaming must not buffer the input, so a declining provider may only be skipped if it
        // has not consumed anything.
        val input = CountingInputStream(inputStream)

        for (provider in providers) {
            if (provider.supportsInputFormat(formatEnum.formatName)) {
                try {
                    return provider.parseStreaming(input, formatEnum.formatName, baseIri)
                } catch (e: UnsupportedOperationException) {
                    if (input.count > 0) throw providerConsumedInput(provider, formatEnum, e)
                    continue
                } catch (e: RdfFormatException) {
                    // Format error, rethrow (or the URL-loading limit it wraps)
                    throw e.inputLimitCause() ?: input.failure ?: e
                } catch (e: Exception) {
                    (e.inputLimitCause() ?: input.failure)?.let { throw it }
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
     * Type-safe legacy sequence API; use [openTripleStream] for scoped streaming.
     * 
     * @param inputStream The input stream containing RDF data
     * @param format The RDF format enum value
     * @return A sequence over the parsed triples
     * @throws RdfFormatException if parsing fails or format is not supported
     */
    fun parseStreaming(inputStream: InputStream, format: RdfFormat): Sequence<RdfTriple> {
        return parseStreaming(inputStream, format.formatName)
    }

    /** Type-safe [parseStreaming] with a base IRI for relative references. */
    fun parseStreaming(inputStream: InputStream, format: RdfFormat, baseIri: String?): Sequence<RdfTriple> =
        parseStreaming(inputStream, format.formatName, baseIri)
    
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
        try {
            parseDataset(repo, data.byteInputStream(), format)
        } catch (e: Throwable) {
            runCatching { repo.close() }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
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
     *
     * Relative IRIs resolve against the file's absolute `file:` URI. The repository is closed if parsing fails.
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
        val baseIri = file.absoluteFile.toURI().toString()
        val repo = memory()
        try {
            file.inputStream().use { stream -> parseDataset(repo, stream, format, baseIri) }
        } catch (e: Throwable) {
            runCatching { repo.close() }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
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
     * @param options Scheme allowlist, body size limit and timeouts (default: http/https only, 64 MiB)
     * @return A new Dataset containing the parsed data
     * @throws RdfFormatException if parsing fails or format is not supported
     * @throws IllegalArgumentException if the URL is malformed or its scheme is not allowed
     * @throws RdfInputTooLargeException if the body exceeds [UrlLoadOptions.maxBytes]
     * @throws java.io.IOException if network access fails
     */
    fun parseDatasetFromUrl(
        url: String,
        format: String = "TRIG",
        options: UrlLoadOptions = UrlLoadOptions.DEFAULT,
    ): Dataset {
        val formatEnum = RdfFormat.fromStringOrThrow(format)
        if (!RdfFormat.isQuadFormat(formatEnum)) {
            throw IllegalArgumentException("Format '${formatEnum.formatName}' is not a quad format. Use parseFromUrl() for graph formats, or use TRIG or N-QUADS for datasets.")
        }
        val body = openRdfUrlStream(url, formatEnum, options)
        val repo = try {
            memory()
        } catch (e: Throwable) {
            runCatching { body.stream.close() }
            throw e
        }
        try {
            body.read { stream -> parseDataset(repo, stream, format, body.baseIri) }
        } catch (e: Throwable) {
            runCatching { repo.close() }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
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
    fun parseDatasetFromUrl(url: String, format: RdfFormat, options: UrlLoadOptions = UrlLoadOptions.DEFAULT): Dataset {
        return parseDatasetFromUrl(url, format.formatName, options)
    }
    
    /**
     * Parse RDF dataset (with named graphs) from an input stream into a repository.
     * 
     * The parsed data will be added to the provided repository, preserving
     * named graph structure.
     * 
     * @param repository The repository to populate with parsed data
     * @param inputStream The input stream containing RDF dataset data; the caller owns it, it is not closed
     * @param format The RDF quad format
     * @throws RdfFormatException if parsing fails or format is not supported
     */
    fun parseDataset(repository: RdfRepository, inputStream: InputStream, format: String) {
        parseDataset(repository, inputStream, format, null)
    }

    /**
     * Parse an RDF dataset from an input stream into [repository], resolving relative IRIs against [baseIri]
     * (passed to [RdfProvider.parseDataset]). The caller owns [inputStream]: it is read but not closed.
     *
     * @param baseIri Absolute IRI for relative references; null keeps the provider's default
     * @throws RdfFormatException if parsing fails or format is not supported
     */
    fun parseDataset(repository: RdfRepository, inputStream: InputStream, format: String, baseIri: String?) {
        val formatEnum = RdfFormat.fromStringOrThrow(format)
        if (!RdfFormat.isQuadFormat(formatEnum)) {
            throw IllegalArgumentException("Format '${formatEnum.formatName}' is not a quad format. Use parseFromInputStream() for graph formats, or use TRIG or N-QUADS for datasets.")
        }
        val providers = RdfProviderRegistry.discoverProviders()
        // Datasets can be large and are streamed into the repository, so the input is not buffered;
        // a declining provider may only be skipped if it has not consumed anything.
        val input = CountingInputStream(inputStream)

        for (provider in providers) {
            if (provider.supportsInputFormat(formatEnum.formatName)) {
                try {
                    provider.parseDataset(repository, input, formatEnum.formatName, baseIri)
                    return
                } catch (e: UnsupportedOperationException) {
                    if (input.count > 0) throw providerConsumedInput(provider, formatEnum, e)
                    continue
                } catch (e: RdfFormatException) {
                    // Format error, rethrow (or the URL-loading limit it wraps)
                    throw e.inputLimitCause() ?: input.failure ?: e
                } catch (e: Exception) {
                    (e.inputLimitCause() ?: input.failure)?.let { throw it }
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

    /** Type-safe [parseDataset] into a repository with a base IRI for relative references. */
    fun parseDataset(repository: RdfRepository, inputStream: InputStream, format: RdfFormat, baseIri: String?) {
        parseDataset(repository, inputStream, format.formatName, baseIri)
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
