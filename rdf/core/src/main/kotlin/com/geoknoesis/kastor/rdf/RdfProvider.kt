package com.geoknoesis.kastor.rdf

import java.io.InputStream

/** Matches [format] (name or alias, case-insensitive) against a provider's declared format list. */
private fun formatListContains(formats: List<String>, format: String): Boolean {
    if (formats.isEmpty()) return false
    val normalized = format.uppercase().trim()
    val canonical = RdfFormat.fromString(normalized)?.formatName?.uppercase()
    return formats.any { declared ->
        val upper = declared.uppercase()
        upper == normalized || (canonical != null && upper == canonical)
    }
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
     * Selection priority. Registries order providers by descending priority, then by
     * registration order; parsing and serialization use the first provider that supports the
     * format. Bundled SPARQL-capable providers use 0; the graph-only memory provider uses -100.
     */
    val priority: Int get() = 0
    
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
     * Check if this provider can **parse** a format (name or alias, case-insensitive).
     *
     * Default implementation checks [ProviderCapabilities.supportedInputFormats]; if a provider
     * declares no input formats at all, its output formats are assumed to be parseable too.
     * Parsing entry points ([Rdf.parse], [Rdf.parseStreaming], [Rdf.parseDataset],
     * [Rdf.openTripleStream]) select providers with this method.
     *
     * The output-format fallback is kept for compatibility with third-party providers written before input
     * and output formats were declared separately; no bundled provider relies on it (Jena and RDF4J declare
     * both lists, the SPARQL endpoint provider declares neither). A provider that declares output formats
     * but cannot parse should declare `supportedInputFormats` explicitly or override this method; otherwise
     * it is offered the input first and must decline with [UnsupportedOperationException] before reading.
     */
    fun supportsInputFormat(format: String): Boolean {
        val capabilities = getCapabilities()
        return formatListContains(capabilities.supportedInputFormats.ifEmpty { capabilities.supportedOutputFormats }, format)
    }

    /**
     * Check if this provider can **serialize** a format (name or alias, case-insensitive).
     *
     * Default implementation checks [ProviderCapabilities.supportedOutputFormats]; if a provider
     * declares no output formats at all, its input formats are assumed to be writable too.
     * Serialization entry points select providers with this method.
     */
    fun supportsOutputFormat(format: String): Boolean {
        val capabilities = getCapabilities()
        return formatListContains(capabilities.supportedOutputFormats.ifEmpty { capabilities.supportedInputFormats }, format)
    }

    /**
     * Check if this provider supports a format for parsing **or** serialization.
     *
     * Core selects parsers with [supportsInputFormat] and serializers with [supportsOutputFormat];
     * this method is kept for callers that do not care about the direction.
     *
     * @param format The RDF format (can be a string or RdfFormat enum value)
     * @return true if the format is supported in either direction, false otherwise
     */
    fun supportsFormat(format: String): Boolean = supportsInputFormat(format) || supportsOutputFormat(format)
    
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
    /** Explicit resource scope for parsers; the fallback materializes a graph before returning. */
    fun openTripleStream(inputStream: java.io.InputStream, format: String): TripleStream {
        val rows = inputStream.use { parseGraph(it, format).getTriplesSequence().constrainOnce() }
        return object : TripleStream {
            override fun iterator(): Iterator<RdfTriple> = rows.iterator()
            override fun close() = Unit
        }
    }

    /**
     * [openTripleStream] with an explicit base IRI for relative references.
     *
     * The default implementation calls [openTripleStream] when [baseIri] is null, and otherwise materializes
     * [parseGraph] with the base before returning, logging a one-time warning per provider class. Overriding this
     * method is how a provider declares base-IRI streaming support; providers with a streaming parser should do so.
     * Note that [Rdf.parseFromFile] and URL loading always pass a base IRI.
     */
    fun openTripleStream(inputStream: java.io.InputStream, format: String, baseIri: String?): TripleStream {
        if (baseIri == null) return openTripleStream(inputStream, format)
        EagerBaseIriFallback.DEFAULT.record(this)
        val rows = inputStream.use { parseGraph(it, format, baseIri).getTriplesSequence().constrainOnce() }
        return object : TripleStream {
            override fun iterator(): Iterator<RdfTriple> = rows.iterator()
            override fun close() = Unit
        }
    }

    fun parseStreaming(inputStream: java.io.InputStream, format: String): Sequence<RdfTriple> {
        // Default implementation: parse to graph, then return triples as sequence
        val graph = parseGraph(inputStream, format)
        return graph.getTriplesSequence()
    }

    /**
     * [parseStreaming] with an explicit base IRI for relative references.
     *
     * The default implementation calls [parseStreaming] when [baseIri] is null, and otherwise returns the
     * triples of [parseGraph] with the base (eager), logging a one-time warning per provider class. Providers with a
     * streaming parser should override it.
     */
    fun parseStreaming(inputStream: java.io.InputStream, format: String, baseIri: String?): Sequence<RdfTriple> {
        if (baseIri == null) return parseStreaming(inputStream, format)
        EagerBaseIriFallback.DEFAULT.record(this)
        return parseGraph(inputStream, format, baseIri).getTriplesSequence()
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
