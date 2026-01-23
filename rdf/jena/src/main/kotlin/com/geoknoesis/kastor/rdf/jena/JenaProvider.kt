package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*

/**
 * Jena provider implementation for the RDF API.
 */
class JenaProvider : RdfProvider {
    
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
        return JenaBridge.toString(graph, format)
    }
    
    override fun serializeDataset(repository: RdfRepository, format: String): String {
        // Get the underlying Jena Dataset if available
        val jenaRepo = repository as? JenaRepository
            ?: throw UnsupportedOperationException("JenaProvider can only serialize Jena repositories")
        
        val dataset = jenaRepo.getJenaDataset()
        return JenaBridge.serializeDataset(dataset, format)
    }
    
    override fun parseGraph(inputStream: java.io.InputStream, format: String): MutableRdfGraph {
        val data = inputStream.readBytes().toString(Charsets.UTF_8)
        return JenaBridge.fromString(data, format)
    }
    
    override fun parseDataset(repository: RdfRepository, inputStream: java.io.InputStream, format: String) {
        // If it's a Jena repository, we can directly use the dataset
        val jenaRepo = repository as? JenaRepository
        if (jenaRepo != null) {
            val dataset = jenaRepo.getJenaDataset()
            val parsedDataset = JenaBridge.parseDatasetFromStream(inputStream, format)
            
            // Copy default graph
            dataset.defaultModel.removeAll()
            dataset.defaultModel.add(parsedDataset.defaultModel)
            
            // Copy named graphs
            parsedDataset.listNames().asSequence().forEach { graphName: String ->
                if (dataset.containsNamedModel(graphName)) {
                    dataset.removeNamedModel(graphName)
                }
                dataset.addNamedModel(graphName, parsedDataset.getNamedModel(graphName))
            }
        } else {
            // For non-Jena repositories, copy triples manually
            val dataset = JenaBridge.parseDatasetFromStream(inputStream, format)
            
            // Copy default graph to repository
            dataset.defaultModel.listStatements().asSequence().forEach { statement: org.apache.jena.rdf.model.Statement ->
                val subject = JenaTerms.fromResource(statement.subject)
                val predicate = JenaTerms.fromProperty(statement.predicate)
                val obj = JenaTerms.fromNode(statement.`object`)
                repository.editDefaultGraph().addTriple(RdfTriple(subject, predicate, obj))
            }
            
            // Copy named graphs to repository
            dataset.listNames().asSequence().forEach { graphName: String ->
                val namedModel = dataset.getNamedModel(graphName)
                val graphIri = Iri(graphName)
                if (!repository.hasGraph(graphIri)) {
                    repository.createGraph(graphIri)
                }
                namedModel.listStatements().asSequence().forEach { statement: org.apache.jena.rdf.model.Statement ->
                    val subject = JenaTerms.fromResource(statement.subject)
                    val predicate = JenaTerms.fromProperty(statement.predicate)
                    val obj = JenaTerms.fromNode(statement.`object`)
                    repository.editGraph(graphIri).addTriple(RdfTriple(subject, predicate, obj))
                }
            }
        }
    }
}









