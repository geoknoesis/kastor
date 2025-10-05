package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*

/**
 * SPARQL provider implementation for the RDF API.
 */
class SparqlProvider : RdfRepositoryProvider {
    
    override val name: String = "sparql"
    override val version: String = "1.0.0"
    
    override fun supports(type: String): Boolean {
        return type == "sparql"
    }
    
    override fun getSupportedTypes(): List<String> {
        return listOf("sparql")
    }
    
    override fun createRepository(config: RdfConfig): RdfRepository {
        return when (config.type) {
            "sparql" -> {
                val endpoint = config.params["location"] ?: throw IllegalArgumentException("SPARQL endpoint URL required")
                SparqlRepository(endpoint)
            }
            else -> throw IllegalArgumentException("Unsupported SPARQL repository type: ${config.type}")
        }
    }
    
    override fun getConfigVariants(): List<ConfigVariant> {
        return listOf(
            ConfigVariant(
                type = "sparql",
                description = "Remote SPARQL endpoint repository",
                parameters = listOf(
                    ConfigParameter(
                        name = "location",
                        description = "SPARQL endpoint URL",
                        type = "String",
                        optional = false,
                        examples = listOf(
                            "http://dbpedia.org/sparql",
                            "https://query.wikidata.org/sparql",
                            "http://localhost:8080/sparql"
                        )
                    )
                )
            )
        )
    }
}
