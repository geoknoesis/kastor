package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*

/**
 * RDF4J provider implementation for the RDF API.
 */
class Rdf4jProvider : RdfApiProvider {
    
    override fun getType(): String = "rdf4j"
    override val name: String = "RDF4J Repository"
    override val version: String = "4.0.0"
    
    override fun isSupported(type: String): Boolean {
        return type.startsWith("rdf4j:")
    }
    
    override fun getSupportedTypes(): List<String> {
        return listOf(
            "rdf4j:memory",
            "rdf4j:native",
            "rdf4j:memory:star",
            "rdf4j:native:star",
            "rdf4j:memory:rdfs",
            "rdf4j:native:rdfs",
            "rdf4j:memory:shacl",
            "rdf4j:native:shacl"
        )
    }
    
    override fun createRepository(config: RdfConfig): RdfRepository {
        return when (config.type) {
            "rdf4j:memory" -> Rdf4jRepository.MemoryRepository()
            "rdf4j:native" -> {
                val location = config.params["location"] ?: "data"
                Rdf4jRepository.NativeRepository(location)
            }
            "rdf4j:memory:star" -> Rdf4jRepository.MemoryStarRepository()
            "rdf4j:native:star" -> {
                val location = config.params["location"] ?: "data"
                Rdf4jRepository.NativeStarRepository(location)
            }
            "rdf4j:memory:rdfs" -> Rdf4jRepository.MemoryRdfsRepository()
            "rdf4j:native:rdfs" -> {
                val location = config.params["location"] ?: "data"
                Rdf4jRepository.NativeRdfsRepository(location)
            }
            "rdf4j:memory:shacl" -> Rdf4jRepository.MemoryShaclRepository()
            "rdf4j:native:shacl" -> {
                val location = config.params["location"] ?: "data"
                Rdf4jRepository.NativeShaclRepository(location)
            }
            else -> throw IllegalArgumentException("Unsupported RDF4J repository type: ${config.type}")
        }
    }
    
    override fun getCapabilities(): ProviderCapabilities {
        return ProviderCapabilities(
            supportsInference = true,
            supportsTransactions = true,
            supportsNamedGraphs = true,
            supportsUpdates = true,
            supportsRdfStar = true,
            maxMemoryUsage = Long.MAX_VALUE
        )
    }
}