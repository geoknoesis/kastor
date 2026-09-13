package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*

/**
 * RDF4J provider implementation for the RDF API.
 */
class Rdf4jProvider : RdfProvider {
    
    override val id: String = "rdf4j"
    override val name: String = "RDF4J Repository"
    override val version: String = org.eclipse.rdf4j.model.impl.SimpleValueFactory::class.java.`package`.implementationVersion ?: "unknown"
    
    override fun variants(): List<RdfVariant> {
        return listOf(
            RdfVariant("memory", "In-memory store"),
            RdfVariant("native", "Native persistent store"),
            RdfVariant("memory-star", "Alias of memory (RDF-star triple terms are enabled on every RDF4J store)"),
            RdfVariant("native-star", "Alias of native (RDF-star triple terms are enabled on every RDF4J store)"),
            RdfVariant("memory-rdfs", "In-memory store with RDFS inference"),
            RdfVariant("native-rdfs", "Native store with RDFS inference"),
            RdfVariant("memory-shacl", "In-memory store with SHACL"),
            RdfVariant("native-shacl", "Native store with SHACL")
        )
    }
    
    override fun createRepository(variantId: String, config: RdfConfig): RdfRepository {
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
        // RDF4J's MemoryStore/NativeStore support RDF-star (RDF 1.2 triple terms
        // for newer versions). All variants advertise it.
        val supportsRdfStar = true

        return ProviderCapabilities(
            // Rio has partial RDF 1.2 term support; it does not implement the full 1.2 syntax suite.
            rdfVersion = "1.1",
            supportsTripleTerms = true,
            supportsInference = supportsInference,
            supportsTransactions = true,
            supportsNamedGraphs = true,
            supportsUpdates = true,
            supportsRdfStar = supportsRdfStar,
            supportsShacl = supportsShacl,
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
    
    override fun supportsFormat(format: String): Boolean {
        val normalized = format.uppercase().trim()
        return normalized in getCapabilities(null).supportedInputFormats
    }
    
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
        Rdf4jFormatSupport.openTripleStream(inputStream, format)

    /** Compatibility API is eager so abandoning an ordinary Sequence cannot leak a producer thread. */
    override fun parseStreaming(inputStream: java.io.InputStream, format: String): Sequence<RdfTriple> =
        openTripleStream(object : java.io.FilterInputStream(inputStream) { override fun close() = Unit }, format)
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









