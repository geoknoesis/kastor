package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.dsl.GraphDsl
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Kastor RDF - The Most Elegant RDF API for Kotlin
 *
 * A modern, type-safe, and intuitive API for working with RDF data.
 * Designed for maximum developer productivity and code elegance.
 *
 * **API Stability:** The factory methods (`memory()`, `persistent()`, `repository()`)
 * and `graph()` DSL are stable and part of the public API.
 *
 * **Java.** Every function is `@JvmStatic`, so Java calls `Rdf.parse(text)` rather than `Rdf.INSTANCE.parse(text)`,
 * and functions with default arguments are `@JvmOverloads`. Each function lists what it can throw (`@Throws`):
 * [RdfException] (unchecked: Java needs no `catch`, and may catch it or a subclass by type) for parse, format and
 * provider failures, and the checked [IOException] for functions that read a stream, a file or a URL. URL refusals,
 * HTTP errors, timeouts and size limits ([RdfAddressRefusedException], [RdfHttpStatusException],
 * [RdfLoadTimeoutException], [RdfInputTooLargeException]) are [IOException]s: `catch (RdfException e)` misses them.
 *
 * The provider dispatch behind the parse functions is in [RdfParsing], the URL loads in [RdfUrlParsing].
 */
object Rdf {
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
    @JvmStatic
    @Throws(RdfException::class)
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
    @JvmStatic
    @Throws(RdfException::class)
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
    @JvmStatic
    @JvmOverloads
    @Throws(RdfException::class)
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
     * @throws RdfProviderException if no registered provider matches the configuration
     */
    @JvmStatic
    @Throws(RdfException::class)
    fun repository(
        configure: RdfRepositoryBuilder.() -> Unit
    ): RdfRepository {
        return repository(RdfProviderRegistry, configure)
    }

    /**
     * Create a repository using a specific provider registry.
     * Useful for tests or custom registry instances.
     *
     * @throws RdfProviderException if no provider of [registry] matches the configuration
     */
    @JvmStatic
    @Throws(RdfException::class)
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
    @JvmStatic
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
    @JvmStatic
    @JvmOverloads
    @Throws(RdfException::class)
    fun parse(data: String, format: String = "TURTLE"): MutableRdfGraph =
        RdfParsing.graph(data.byteInputStream(), format, null)

    /**
     * Parse RDF data from a string into a graph (type-safe version).
     *
     * @param data The RDF data as a string
     * @param format The RDF format enum value
     * @return A new MutableRdfGraph containing the parsed triples
     * @throws RdfFormatException if parsing fails or format is not supported
     */
    @JvmStatic
    @Throws(RdfException::class)
    fun parse(data: String, format: RdfFormat): MutableRdfGraph = parse(data, format.formatName)

    /**
     * Parse RDF data from a string, resolving relative IRIs against [baseIri].
     *
     * @param baseIri Absolute IRI for relative references; null keeps the provider's default (relative IRIs
     *   are then errors with the bundled providers)
     * @throws RdfFormatException if parsing fails or format is not supported
     */
    @JvmStatic
    @Throws(RdfException::class)
    fun parse(data: String, format: RdfFormat, baseIri: String?): MutableRdfGraph =
        parse(data, format.formatName, baseIri)

    /**
     * Parse RDF data from a string, resolving relative IRIs against [baseIri].
     *
     * @param format The RDF format name or alias (for example `"TURTLE"` or `"ttl"`)
     * @param baseIri Absolute IRI for relative references; null keeps the provider's default
     * @throws RdfFormatException if parsing fails or format is not supported
     */
    @JvmStatic
    @Throws(RdfException::class)
    fun parse(data: String, format: String, baseIri: String?): MutableRdfGraph =
        RdfParsing.graph(data.byteInputStream(), format, baseIri)

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
     * @throws IOException if reading the file fails
     *
     * Relative IRIs (`<>`, `<#Foo>`, RDF/XML `rdf:about="#Foo"`) resolve against the file's absolute
     * `file:` URI.
     */
    @JvmStatic
    @JvmOverloads
    @Throws(RdfException::class, IOException::class)
    fun parseFromFile(filePath: String, format: String = "TURTLE"): MutableRdfGraph {
        val file = existingFile(filePath)
        val baseIri = file.absoluteFile.toURI().toString()
        return file.inputStream().use { stream -> RdfParsing.graph(stream, format, baseIri) }
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
    @JvmStatic
    @Throws(RdfException::class, IOException::class)
    fun parseFromFile(filePath: String, format: RdfFormat): MutableRdfGraph = parseFromFile(filePath, format.formatName)

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
     * @param options Scheme allowlist, body size limit and timeouts (default: http/https only, public addresses only, 64 MiB)
     * @return A new MutableRdfGraph containing the parsed triples
     * @throws RdfFormatException if parsing fails or format is not supported
     * @throws IllegalArgumentException if the URL is malformed or its scheme is not allowed
     * @throws RdfAddressRefusedException if [UrlLoadOptions.addressPolicy] refuses the URL or a redirect target (by
     *   default every loopback, private, link-local or other non-public address)
     * @throws RdfInputTooLargeException if the body exceeds [UrlLoadOptions.maxBytes]
     * @throws RdfHttpStatusException if the server answers with a non-2xx status
     * @throws RdfLoadTimeoutException if loading exceeds [UrlLoadOptions.totalTimeoutMillis]
     * @throws IOException if network access fails
     *
     * The request asks for the format's media type (`Accept`), and relative IRIs resolve against the
     * final URL.
     */
    @JvmStatic
    @JvmOverloads
    @Throws(RdfException::class, IOException::class)
    fun parseFromUrl(
        url: String,
        format: String = "TURTLE",
        options: UrlLoadOptions = UrlLoadOptions.DEFAULT,
    ): MutableRdfGraph = RdfUrlParsing.graph(url, format, options)

    /**
     * Parse RDF data from a URL into a graph (type-safe version). It fails as the [parseFromUrl] that takes a format
     * name does.
     *
     * @param url The URL to load RDF data from
     * @param format The RDF format enum value
     * @return A new MutableRdfGraph containing the parsed triples
     * @throws RdfFormatException if parsing fails or format is not supported
     * @throws IOException if network access fails or [options] refuse the load (see [UrlLoadOptions])
     */
    @JvmStatic
    @JvmOverloads
    @Throws(RdfException::class, IOException::class)
    fun parseFromUrl(url: String, format: RdfFormat, options: UrlLoadOptions = UrlLoadOptions.DEFAULT): MutableRdfGraph =
        parseFromUrl(url, format.formatName, options)

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
     * @param options Scheme allowlist, body size limit and timeouts (default: http/https only, public addresses only, 64 MiB)
     * @return A future with the parsed graph; it completes exceptionally with whatever [parseFromUrl] would throw,
     *   for example [IllegalArgumentException] for a disallowed scheme, [RdfAddressRefusedException] for a refused
     *   address and [RdfInputTooLargeException] for an oversized body
     */
    @JvmStatic
    @JvmOverloads
    fun parseFromUrlAsync(
        url: String,
        format: String = "TURTLE",
        executor: Executor = RdfUrlParsing.defaultExecutor,
        options: UrlLoadOptions = UrlLoadOptions.DEFAULT,
    ): CompletableFuture<MutableRdfGraph> = RdfUrlParsing.graphAsync(url, format, executor, options)

    /**
     * Parse RDF data from a URL asynchronously into a graph (type-safe version).
     *
     * @param url The URL to load RDF data from
     * @param format The RDF format enum value
     * @param executor Executor used for the blocking operation
     * @return A future with the parsed graph
     */
    @JvmStatic
    @JvmOverloads
    fun parseFromUrlAsync(
        url: String,
        format: RdfFormat,
        executor: Executor = RdfUrlParsing.defaultExecutor,
        options: UrlLoadOptions = UrlLoadOptions.DEFAULT,
    ): CompletableFuture<MutableRdfGraph> = parseFromUrlAsync(url, format.formatName, executor, options)

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
     * @throws IOException if reading [inputStream] fails (the stream's own failure, not a parse error)
     */
    @JvmStatic
    @Throws(RdfException::class, IOException::class)
    fun parseFromInputStream(inputStream: InputStream, format: String): MutableRdfGraph =
        parseFromInputStream(inputStream, format, null)

    /**
     * Parse RDF data from an input stream into a graph, resolving relative IRIs against [baseIri].
     *
     * **The caller owns [inputStream]:** it is read but never closed, whether parsing succeeds or fails. The stream
     * goes straight to the highest-priority provider that declares the input format; nothing is buffered, and
     * another provider is only tried if one declines before consuming any input.
     *
     * @param inputStream The input stream containing RDF data; not closed
     * @param format The RDF format
     * @param baseIri Absolute IRI for relative references, passed to [RdfProvider.parseGraph]; null keeps the
     *   provider's default (relative IRIs are then errors with the bundled providers)
     * @return A new MutableRdfGraph containing the parsed triples
     * @throws RdfFormatException if parsing fails or format is not supported
     * @throws IOException if reading [inputStream] fails
     */
    @JvmStatic
    @Throws(RdfException::class, IOException::class)
    fun parseFromInputStream(inputStream: InputStream, format: String, baseIri: String?): MutableRdfGraph =
        RdfParsing.graph(inputStream, format, baseIri)

    /**
     * Parse RDF data from an input stream into a graph (type-safe version).
     *
     * @param inputStream The input stream containing RDF data
     * @param format The RDF format enum value
     * @return A new MutableRdfGraph containing the parsed triples
     * @throws RdfFormatException if parsing fails or format is not supported
     * @throws IOException if reading [inputStream] fails
     */
    @JvmStatic
    @Throws(RdfException::class, IOException::class)
    fun parseFromInputStream(inputStream: InputStream, format: RdfFormat): MutableRdfGraph =
        parseFromInputStream(inputStream, format.formatName)

    /** Type-safe [parseFromInputStream] with a base IRI for relative references. */
    @JvmStatic
    @Throws(RdfException::class, IOException::class)
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
     * @throws IOException if reading [inputStream] fails
     */
    @JvmStatic
    @Throws(RdfException::class, IOException::class)
    fun parseStreaming(inputStream: InputStream, format: String): Sequence<RdfTriple> =
        parseStreaming(inputStream, format, null)

    /**
     * [parseStreaming] resolving relative IRIs against [baseIri] (see [RdfProvider.parseStreaming]). Streaming does
     * not buffer the input, so a declining provider is skipped only if it has not consumed anything.
     *
     * @param baseIri Absolute IRI for relative references; null keeps the provider's default
     */
    @JvmStatic
    @Throws(RdfException::class, IOException::class)
    fun parseStreaming(inputStream: InputStream, format: String, baseIri: String?): Sequence<RdfTriple> =
        RdfParsing.streaming(inputStream, format, baseIri)

    /**
     * Type-safe legacy sequence API; use [openTripleStream] for scoped streaming.
     *
     * @param inputStream The input stream containing RDF data
     * @param format The RDF format enum value
     * @return A sequence over the parsed triples
     * @throws RdfFormatException if parsing fails or format is not supported
     */
    @JvmStatic
    @Throws(RdfException::class, IOException::class)
    fun parseStreaming(inputStream: InputStream, format: RdfFormat): Sequence<RdfTriple> =
        parseStreaming(inputStream, format.formatName)

    /** Type-safe [parseStreaming] with a base IRI for relative references. */
    @JvmStatic
    @Throws(RdfException::class, IOException::class)
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
     * @return A new Dataset containing the parsed data; close it after use
     * @throws RdfFormatException if parsing fails or format is not supported
     * @throws RdfProviderException if no SPARQL-capable in-memory provider is registered ([memory])
     * @throws IllegalArgumentException if the format is not a quad format
     */
    @JvmStatic
    @JvmOverloads
    @Throws(RdfException::class)
    fun parseDataset(data: String, format: String = "TRIG"): Dataset {
        RdfParsing.quadFormat(format, "parse()")
        return RdfParsing.newDataset { repository -> RdfParsing.dataset(repository, data.byteInputStream(), format, null) }
    }

    /**
     * Parse RDF dataset from a string (type-safe version).
     *
     * @param data The RDF dataset data as a string
     * @param format The RDF quad format enum value
     * @return A new Dataset containing the parsed data
     * @throws RdfFormatException if parsing fails or format is not supported
     */
    @JvmStatic
    @Throws(RdfException::class)
    fun parseDataset(data: String, format: RdfFormat): Dataset = parseDataset(data, format.formatName)

    /**
     * Parse RDF dataset (with named graphs) from a file.
     *
     * @param filePath The path to the RDF dataset file
     * @param format The RDF quad format (default: "TRIG")
     * @return A new Dataset containing the parsed data
     * @throws RdfFormatException if parsing fails or format is not supported
     * @throws java.io.FileNotFoundException if the file does not exist
     * @throws IOException if reading the file fails
     *
     * Relative IRIs resolve against the file's absolute `file:` URI. The repository is closed if parsing fails.
     */
    @JvmStatic
    @JvmOverloads
    @Throws(RdfException::class, IOException::class)
    fun parseDatasetFromFile(filePath: String, format: String = "TRIG"): Dataset {
        RdfParsing.quadFormat(format, "parseFromFile()")
        val file = existingFile(filePath)
        val baseIri = file.absoluteFile.toURI().toString()
        return RdfParsing.newDataset { repository ->
            file.inputStream().use { stream -> RdfParsing.dataset(repository, stream, format, baseIri) }
        }
    }

    /**
     * Parse RDF dataset from a file (type-safe version).
     *
     * @param filePath The path to the RDF dataset file
     * @param format The RDF quad format enum value
     * @return A new Dataset containing the parsed data
     * @throws RdfFormatException if parsing fails or format is not supported
     * @throws IOException if the file does not exist or cannot be read
     */
    @JvmStatic
    @Throws(RdfException::class, IOException::class)
    fun parseDatasetFromFile(filePath: String, format: RdfFormat): Dataset = parseDatasetFromFile(filePath, format.formatName)

    /**
     * Parse RDF dataset (with named graphs) from a URL.
     *
     * @param url The URL to load RDF dataset data from
     * @param format The RDF quad format (default: "TRIG")
     * @param options Scheme allowlist, body size limit and timeouts (default: http/https only, public addresses only, 64 MiB)
     * @return A new Dataset containing the parsed data
     * @throws RdfFormatException if parsing fails or format is not supported
     * @throws IllegalArgumentException if the URL is malformed, its scheme is not allowed or the format is not a quad format
     * @throws RdfAddressRefusedException if [UrlLoadOptions.addressPolicy] refuses the URL or a redirect target
     * @throws RdfInputTooLargeException if the body exceeds [UrlLoadOptions.maxBytes]
     * @throws RdfHttpStatusException if the server answers with a non-2xx status
     * @throws RdfLoadTimeoutException if loading exceeds [UrlLoadOptions.totalTimeoutMillis]
     * @throws IOException if network access fails
     */
    @JvmStatic
    @JvmOverloads
    @Throws(RdfException::class, IOException::class)
    fun parseDatasetFromUrl(
        url: String,
        format: String = "TRIG",
        options: UrlLoadOptions = UrlLoadOptions.DEFAULT,
    ): Dataset = RdfUrlParsing.dataset(url, format, options)

    /**
     * Parse RDF dataset from a URL (type-safe version). It fails as the [parseDatasetFromUrl] that takes a format
     * name does.
     *
     * @param url The URL to load RDF dataset data from
     * @param format The RDF quad format enum value
     * @return A new Dataset containing the parsed data
     * @throws RdfFormatException if parsing fails or format is not supported
     * @throws IOException if network access fails or [options] refuse the load (see [UrlLoadOptions])
     */
    @JvmStatic
    @JvmOverloads
    @Throws(RdfException::class, IOException::class)
    fun parseDatasetFromUrl(url: String, format: RdfFormat, options: UrlLoadOptions = UrlLoadOptions.DEFAULT): Dataset =
        parseDatasetFromUrl(url, format.formatName, options)

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
     * @throws IOException if reading [inputStream] fails
     */
    @JvmStatic
    @Throws(RdfException::class, IOException::class)
    fun parseDataset(repository: RdfRepository, inputStream: InputStream, format: String) {
        parseDataset(repository, inputStream, format, null)
    }

    /**
     * Parse an RDF dataset from an input stream into [repository], resolving relative IRIs against [baseIri]
     * (passed to [RdfProvider.parseDataset]). The caller owns [inputStream]: it is read but not closed. Datasets are
     * streamed into the repository, so the input is not buffered; a declining provider is skipped only if it has
     * not consumed anything.
     *
     * @param baseIri Absolute IRI for relative references; null keeps the provider's default
     * @throws RdfFormatException if parsing fails or format is not supported
     * @throws IOException if reading [inputStream] fails
     */
    @JvmStatic
    @Throws(RdfException::class, IOException::class)
    fun parseDataset(repository: RdfRepository, inputStream: InputStream, format: String, baseIri: String?) {
        RdfParsing.dataset(repository, inputStream, format, baseIri)
    }

    /**
     * Parse RDF dataset from an input stream into a repository (type-safe version).
     *
     * @param repository The repository to populate with parsed data
     * @param inputStream The input stream containing RDF dataset data
     * @param format The RDF quad format enum value
     * @throws RdfFormatException if parsing fails or format is not supported
     * @throws IOException if reading [inputStream] fails
     */
    @JvmStatic
    @Throws(RdfException::class, IOException::class)
    fun parseDataset(repository: RdfRepository, inputStream: InputStream, format: RdfFormat) {
        parseDataset(repository, inputStream, format.formatName)
    }

    /** Type-safe [parseDataset] into a repository with a base IRI for relative references. */
    @JvmStatic
    @Throws(RdfException::class, IOException::class)
    fun parseDataset(repository: RdfRepository, inputStream: InputStream, format: RdfFormat, baseIri: String?) {
        parseDataset(repository, inputStream, format.formatName, baseIri)
    }

    private fun existingFile(filePath: String): java.io.File {
        val file = java.io.File(filePath)
        if (!file.exists()) throw java.io.FileNotFoundException("RDF file not found: $filePath")
        return file
    }

    // === DEFAULT PROVIDER MANAGEMENT ===

    /**
     * Set the default RDF provider for factory methods.
     * Affects which backend is used when no specific type is specified.
     */
    @JvmStatic
    fun setDefaultProvider(provider: String) {
        DefaultRdfProvider.set(provider)
    }

    /**
     * Get the current default RDF provider.
     */
    @JvmStatic
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

        /** The repository this builder describes. @throws RdfProviderException if no registered provider matches. */
        @Throws(RdfException::class)
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
