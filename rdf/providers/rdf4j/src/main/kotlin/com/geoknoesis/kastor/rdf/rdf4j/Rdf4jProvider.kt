package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*

/**
 * RDF4J provider implementation for the RDF API.
 *
 * **Base direction** ([ProviderCapabilities.supportsBaseDirection] is `false`): RDF4J 5.3 literals have no
 * base-direction field, so a directional [LangString] is stored with the combined language tag `lang--dir`
 * (e.g. `ar--rtl`), which Rio reads and writes unchanged. Kastor graph reads decode it back, but inside RDF4J
 * itself the combined tag is visible:
 * - SPARQL `LANG(?o)` returns `"ar--rtl"` and `LANGMATCHES` sees the combined tag (there is no fix inside RDF4J);
 * - RDF4J's SPARQL 1.1 parser rejects the literal syntax `"x"@ar--rtl` (reported as an explicit
 *   [RdfQueryException]; pass such literals as query bindings instead);
 * - RDF4J SHACL validation sees the values as plain `rdf:langString` literals.
 *
 * **Configuration options** ([RdfConfig.options]): `location` (native variants, default `data`) and `lenientRead`
 * (`"true"` to skip statements that are not valid Kastor terms on graph reads, see [Rdf4jRepository]).
 */
class Rdf4jProvider : RdfProvider {
    
    override val id: String = "rdf4j"
    override val name: String = "RDF4J Repository"
    override val version: String = org.eclipse.rdf4j.model.impl.SimpleValueFactory::class.java.`package`.implementationVersion ?: "unknown"
    
    override fun variants(): List<RdfVariant> {
        return listOf(
            RdfVariant("memory", "In-memory store"),
            RdfVariant("native", "Native persistent store"),
            RdfVariant("memory-star", "Alias of memory (RDF-star triple terms are enabled on memory stores)"),
            RdfVariant("native-star", "Alias of native (RDF4J's NativeStore cannot store RDF-star triple terms)"),
            RdfVariant("memory-rdfs", "In-memory store with RDFS inference"),
            RdfVariant("native-rdfs", "Native store with RDFS inference"),
            RdfVariant("memory-shacl", "In-memory store with SHACL"),
            RdfVariant("native-shacl", "Native store with SHACL")
        )
    }
    
    override fun createRepository(variantId: String, config: RdfConfig): RdfRepository {
        val repository = createVariant(variantId, config)
        return if (config.options["lenientRead"]?.toBoolean() == true) repository.lenient() else repository
    }

    private fun createVariant(variantId: String, config: RdfConfig): Rdf4jRepository {
        return when (variantId) {
            "memory" -> Rdf4jRepository.MemoryRepository()
            "native" -> {
                val location = config.options["location"] ?: "data"
                Rdf4jRepository.NativeRepository(location)
            }
            "memory-star" -> Rdf4jRepository.MemoryStarRepository()
            "native-star" -> {
                val location = config.options["location"] ?: "data"
                Rdf4jRepository.NativeStarRepository(location)
            }
            "memory-rdfs" -> Rdf4jRepository.MemoryRdfsRepository()
            "native-rdfs" -> {
                val location = config.options["location"] ?: "data"
                Rdf4jRepository.NativeRdfsRepository(location)
            }
            "memory-shacl" -> Rdf4jRepository.MemoryShaclRepository()
            "native-shacl" -> {
                val location = config.options["location"] ?: "data"
                Rdf4jRepository.NativeShaclRepository(location)
            }
            else -> throw IllegalArgumentException("Unsupported RDF4J repository variant: $variantId")
        }
    }
    
    override fun getCapabilities(variantId: String?): ProviderCapabilities {
        val formats = Rdf4jFormatSupport.ADVERTISED_FORMATS

        // Variant-specific capability flags. The plain `memory`/`native` variants
        // do not perform inference and have no SHACL validation; the dedicated
        // `*-rdfs` and `*-shacl` variants advertise those capabilities. The
        // `*-star` variants explicitly advertise RDF-star, although RDF4J 5.x
        // enables RDF-star on every store by default.
        val supportsInference = variantId?.contains("rdfs") == true
        val supportsShacl = variantId?.contains("shacl") == true
        // RDF4J's MemoryStore holds RDF-star triples (used for RDF 1.2 triple terms); its NativeStore rejects
        // them ("value parameter should be a URI, BNode or Literal"), so native variants do not advertise them.
        val supportsRdfStar = variantId?.startsWith("native") != true

        return ProviderCapabilities(
            // Rio has partial RDF 1.2 term support; it does not implement the full 1.2 syntax suite.
            rdfVersion = "1.1",
            supportsTripleTerms = supportsRdfStar,
            supportsInference = supportsInference,
            supportsTransactions = true,
            supportsNamedGraphs = true,
            supportsUpdates = true,
            supportsRdfStar = supportsRdfStar,
            supportsShacl = supportsShacl,
            // No native base direction: Kastor encodes it into the language tag as "lang--dir". Visible effects:
            // SPARQL LANG() returns "ar--rtl"; RDF4J's SPARQL 1.1 parser rejects the literal syntax "x"@ar--rtl
            // (the repository reports this with an explicit RdfQueryException; pass such literals as bindings);
            // RDF4J SHACL sees such values as rdf:langString; Rio writers emit the tag "ar--rtl".
            supportsBaseDirection = false,
            maxMemoryUsage = Long.MAX_VALUE,
            sparqlVersion = "1.1",
            supportsPropertyPaths = true,
            supportsAggregation = true,
            supportsSubSelect = true,
            supportsVersionDeclaration = false,
            // generateServiceDescription is not implemented by this provider.
            supportsServiceDescription = false,
            supportedInputFormats = formats,
            supportedOutputFormats = formats // RDF4J supports same formats for input and output
        )
    }
    
    /** Ranked below Jena (50) and above the in-core memory store (-100) for format-based selection. */
    override val priority: Int = 40
    
    override fun serializeGraph(graph: RdfGraph, format: String, options: SerializationOptions): String {
        return Rdf4jFormatSupport.serializeGraph(graph, format, options)
    }
    
    override fun serializeDataset(repository: RdfRepository, format: String, options: SerializationOptions): String {
        return Rdf4jFormatSupport.serializeDataset(repository, format, options)
    }
    
    override fun parseGraph(inputStream: java.io.InputStream, format: String): MutableRdfGraph {
        return Rdf4jFormatSupport.parseGraph(inputStream, format)
    }

    override fun parseGraph(
        inputStream: java.io.InputStream,
        format: String,
        baseIri: String?,
    ): MutableRdfGraph =
        if (baseIri == null) Rdf4jFormatSupport.parseGraph(inputStream, format)
        else Rdf4jFormatSupport.parseGraph(inputStream, format, baseIri)

    /** Streaming parse: Rio runs on a background thread and hands triples over through a bounded queue. */
    override fun openTripleStream(inputStream: java.io.InputStream, format: String): TripleStream =
        openTripleStreamWithBase(inputStream, format, null)

    /** Compatibility API is eager so abandoning an ordinary Sequence cannot leak a producer thread. */
    override fun parseStreaming(inputStream: java.io.InputStream, format: String): Sequence<RdfTriple> =
        parseStreamingWithBase(inputStream, format, null)

    override fun openTripleStream(inputStream: java.io.InputStream, format: String, baseIri: String?): TripleStream =
        openTripleStreamWithBase(inputStream, format, baseIri)

    override fun parseStreaming(inputStream: java.io.InputStream, format: String, baseIri: String?): Sequence<RdfTriple> =
        parseStreamingWithBase(inputStream, format, baseIri)

    /**
     * Streaming parse resolving relative IRIs against [baseIri] (`null`: relative IRIs are a parse error).
     * Implementation target for the core `openTripleStream(inputStream, format, baseIri)` provider method.
     */
    internal fun openTripleStreamWithBase(inputStream: java.io.InputStream, format: String, baseIri: String?): TripleStream =
        Rdf4jFormatSupport.openTripleStream(inputStream, format, baseIri)

    /**
     * Eager compatibility parse with a base IRI; the caller's stream is not closed.
     * Implementation target for the core `parseStreaming(inputStream, format, baseIri)` provider method.
     */
    internal fun parseStreamingWithBase(inputStream: java.io.InputStream, format: String, baseIri: String?): Sequence<RdfTriple> =
        openTripleStreamWithBase(object : java.io.FilterInputStream(inputStream) { override fun close() = Unit }, format, baseIri)
            .use { it.toList().asSequence() }

    override fun parseDataset(repository: RdfRepository, inputStream: java.io.InputStream, format: String) {
        Rdf4jFormatSupport.parseDataset(repository, inputStream, format)
    }

    override fun parseDataset(
        repository: RdfRepository,
        inputStream: java.io.InputStream,
        format: String,
        baseIri: String?,
    ) {
        if (baseIri == null) {
            Rdf4jFormatSupport.parseDataset(repository, inputStream, format)
        } else {
            Rdf4jFormatSupport.parseDataset(repository, inputStream, format, baseIri)
        }
    }
}









