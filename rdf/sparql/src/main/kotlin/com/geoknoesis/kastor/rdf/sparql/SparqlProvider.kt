package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*

/**
 * SPARQL provider implementation for the RDF API.
 */
class SparqlProvider : RdfApiProvider {
    
    override fun getType(): String = "sparql"
    
    override val name: String = "SPARQL Repository"
    
    override val version: String = "1.0.0"
    
    override fun createRepository(config: RdfConfig): RdfRepository {
        return when (config.type) {
            "sparql" -> {
                val endpoint = config.params["location"] ?: throw IllegalArgumentException("SPARQL endpoint URL required")
                SparqlRepository(endpoint)
            }
            else -> throw IllegalArgumentException("Unsupported SPARQL repository type: ${config.type}")
        }
    }
    
    override fun getCapabilities(): ProviderCapabilities {
        return ProviderCapabilities(
            supportsInference = false,
            supportsTransactions = false,
            supportsNamedGraphs = false,
            supportsUpdates = false,
            supportsRdfStar = false, // Depends on endpoint support
            maxMemoryUsage = Long.MAX_VALUE
        )
    }
    
    override fun getSupportedTypes(): List<String> {
        return listOf("sparql")
    }
    
    override fun isSupported(type: String): Boolean {
        return type == "sparql"
    }
}
