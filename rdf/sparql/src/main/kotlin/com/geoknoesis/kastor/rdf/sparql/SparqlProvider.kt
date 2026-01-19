package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*

/**
 * SPARQL provider implementation for the RDF API.
 */
class SparqlProvider : RdfApiProvider {
    
    override val id: String = "sparql"
    
    override val name: String = "SPARQL Repository"
    
    override val version: String = "1.0.0"
    
    override fun variants(): List<RdfVariant> {
        return listOf(RdfVariant("sparql", "Remote SPARQL endpoint"))
    }
    
    override fun createRepository(variantId: String, config: RdfConfig): RdfRepository {
        return when (variantId) {
            "sparql" -> {
                val endpoint = config.options["location"]
                    ?: throw IllegalArgumentException("SPARQL endpoint URL required")
                SparqlRepository(endpoint)
            }
            else -> throw IllegalArgumentException("Unsupported SPARQL repository variant: $variantId")
        }
    }
    
    override fun getCapabilities(variantId: String?): ProviderCapabilities {
        return ProviderCapabilities(
            supportsInference = false,
            supportsTransactions = false,
            supportsNamedGraphs = true,
            supportsUpdates = true,
            supportsRdfStar = true, // SPARQL 1.2 supports RDF-star
            maxMemoryUsage = Long.MAX_VALUE,
            sparqlVersion = "1.2",
            supportsPropertyPaths = true,
            supportsAggregation = true,
            supportsSubSelect = true,
            supportsFederation = true,
            supportsVersionDeclaration = true,
            supportsServiceDescription = true
        )
    }
}









