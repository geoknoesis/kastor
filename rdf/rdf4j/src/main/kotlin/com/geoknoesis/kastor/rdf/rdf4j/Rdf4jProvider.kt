package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*

/**
 * RDF4J provider implementation for the RDF API.
 */
class Rdf4jProvider : RdfProvider {
    
    override val id: String = "rdf4j"
    override val name: String = "RDF4J Repository"
    override val version: String = "4.0.0"
    
    override fun variants(): List<RdfVariant> {
        return listOf(
            RdfVariant("memory", "In-memory store"),
            RdfVariant("native", "Native persistent store"),
            RdfVariant("memory-star", "In-memory store with RDF-star"),
            RdfVariant("native-star", "Native store with RDF-star"),
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
        return ProviderCapabilities(
            supportsInference = true,
            supportsTransactions = true,
            supportsNamedGraphs = true,
            supportsUpdates = true,
            supportsRdfStar = true,
            maxMemoryUsage = Long.MAX_VALUE,
            sparqlVersion = "1.2",
            supportsPropertyPaths = true,
            supportsAggregation = true,
            supportsSubSelect = true,
            supportsVersionDeclaration = true,
            supportsServiceDescription = true,
            supportedInputFormats = listOf("TURTLE", "TTL", "JSON-LD", "JSONLD", "RDF/XML", "RDFXML", "XML", "N-TRIPLES", "NT", "NTRIPLES", "TRIG", "TRI-G", "N-QUADS", "NQUADS", "NQ")
        )
    }
    
    override fun supportsFormat(format: String): Boolean {
        val normalized = format.uppercase().trim()
        return normalized in listOf(
            "TURTLE", "TTL", "JSON-LD", "JSONLD", "RDF/XML", "RDFXML", "XML", 
            "N-TRIPLES", "NT", "NTRIPLES", "TRIG", "TRI-G", "N-QUADS", "NQUADS", "NQ"
        )
    }
    
    override fun serializeGraph(graph: RdfGraph, format: String): String {
        return Rdf4jFormatSupport.serializeGraph(graph, format)
    }
    
    override fun serializeDataset(repository: RdfRepository, format: String): String {
        return Rdf4jFormatSupport.serializeDataset(repository, format)
    }
    
    override fun parseGraph(inputStream: java.io.InputStream, format: String): MutableRdfGraph {
        return Rdf4jFormatSupport.parseGraph(inputStream, format)
    }
    
    override fun parseDataset(repository: RdfRepository, inputStream: java.io.InputStream, format: String) {
        Rdf4jFormatSupport.parseDataset(repository, inputStream, format)
    }
}









