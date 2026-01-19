package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*

/**
 * Jena provider implementation for the RDF API.
 */
class JenaProvider : RdfApiProvider {
    
    override val id: String = "jena"
    override val name: String = "Jena Repository"
    override val version: String = "4.0.0"
    
    override fun variants(): List<RdfVariant> {
        return listOf(
            RdfVariant("memory", "In-memory store"),
            RdfVariant("memory-inference", "In-memory store with inference"),
            RdfVariant("tdb2", "TDB2 persistent store"),
            RdfVariant("tdb2-inference", "TDB2 store with inference")
        )
    }
    
    override fun createRepository(variantId: String, config: RdfConfig): RdfRepository {
        return when (variantId) {
            "memory" -> JenaRepository.MemoryRepository()
            "memory-inference" -> JenaRepository.MemoryRepositoryWithInference()
            "tdb2" -> {
                val location = config.options["location"] ?: "data"
                JenaRepository.Tdb2Repository(location)
            }
            "tdb2-inference" -> {
                val location = config.options["location"] ?: "data"
                JenaRepository.Tdb2RepositoryWithInference(location)
            }
            else -> throw IllegalArgumentException("Unsupported Jena repository variant: $variantId")
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
            supportsServiceDescription = true
        )
    }
}









