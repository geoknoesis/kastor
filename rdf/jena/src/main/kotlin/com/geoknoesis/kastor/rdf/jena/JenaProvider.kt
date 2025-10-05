package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*

/**
 * Jena provider implementation for the RDF API.
 */
class JenaProvider : RdfApiProvider {
    
    override fun getType(): String = "jena"
    override val name: String = "Jena Repository"
    override val version: String = "4.0.0"
    
    override fun isSupported(type: String): Boolean {
        return type.startsWith("jena:")
    }
    
    override fun getSupportedTypes(): List<String> {
        return listOf(
            "jena:memory",
            "jena:memory:inference", 
            "jena:tdb2",
            "jena:tdb2:inference"
        )
    }
    
    override fun createRepository(config: RdfConfig): RdfRepository {
        return when (config.type) {
            "jena:memory" -> JenaRepository.MemoryRepository()
            "jena:memory:inference" -> JenaRepository.MemoryRepositoryWithInference()
            "jena:tdb2" -> {
                val location = config.params["location"] ?: "data"
                JenaRepository.Tdb2Repository(location)
            }
            "jena:tdb2:inference" -> {
                val location = config.params["location"] ?: "data"
                JenaRepository.Tdb2RepositoryWithInference(location)
            }
            else -> throw IllegalArgumentException("Unsupported Jena repository type: ${config.type}")
        }
    }
    
    override fun getCapabilities(): ProviderCapabilities {
        return ProviderCapabilities(
            supportsInference = true,
            supportsTransactions = true,
            supportsNamedGraphs = true,
            supportsUpdates = true,
            maxMemoryUsage = Long.MAX_VALUE
        )
    }
}