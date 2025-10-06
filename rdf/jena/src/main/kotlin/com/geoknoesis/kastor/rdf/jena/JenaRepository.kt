package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.query.QueryFactory
import org.apache.jena.query.QueryExecutionFactory
import org.apache.jena.query.Dataset
import org.apache.jena.query.DatasetFactory
import org.apache.jena.tdb2.TDB2Factory
import org.apache.jena.rdf.model.Statement
import org.apache.jena.rdf.model.RDFNode
import org.apache.jena.rdf.model.Resource
import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.Literal
import org.apache.jena.query.ReadWrite
import java.nio.file.Paths

class JenaRepository private constructor(
    private val dataset: Dataset
) : RdfRepository {
    
    companion object {
        fun MemoryRepository(): JenaRepository {
            val dataset = DatasetFactory.create()
            return JenaRepository(dataset)
        }
        
        fun MemoryRepositoryWithInference(): JenaRepository {
            val dataset = DatasetFactory.create()
            val defaultModel = ModelFactory.createRDFSModel(dataset.defaultModel)
            dataset.defaultModel.removeAll()
            dataset.defaultModel.add(defaultModel)
            return JenaRepository(dataset)
        }
        
        fun Tdb2Repository(location: String): JenaRepository {
            val dataset = TDB2Factory.connectDataset(Paths.get(location).toFile().absolutePath)
            return JenaRepository(dataset)
        }
        
        fun Tdb2RepositoryWithInference(location: String): JenaRepository {
            val dataset = TDB2Factory.connectDataset(Paths.get(location).toFile().absolutePath)
            val defaultModel = ModelFactory.createRDFSModel(dataset.defaultModel)
            dataset.defaultModel.removeAll()
            dataset.defaultModel.add(defaultModel)
            return JenaRepository(dataset)
        }
    }
    
    override val defaultGraph: RdfGraph = JenaGraph(dataset.defaultModel)
    
    override fun getGraph(name: Iri): RdfGraph {
        return JenaGraph(dataset.getNamedModel(name.value))
    }
    
    override fun hasGraph(name: Iri): Boolean {
        return dataset.containsNamedModel(name.value)
    }
    
    override fun listGraphs(): List<Iri> {
        return dataset.listNames().asSequence().map { Iri(it) }.toList()
    }
    
    override fun createGraph(name: Iri): RdfGraph {
        val model = dataset.getNamedModel(name.value)
        return JenaGraph(model)
    }
    
    override fun removeGraph(name: Iri): Boolean {
        return if (dataset.containsNamedModel(name.value)) {
            dataset.removeNamedModel(name.value)
            true
        } else {
            false
        }
    }
    
    override fun query(sparql: String): QueryResult {
        val jenaQuery = QueryFactory.create(sparql)
        val exec = QueryExecutionFactory.create(jenaQuery, dataset)
        val jenaResultSet = exec.execSelect()
        return JenaResultSet(jenaResultSet)
    }
    
    override fun ask(sparql: String): Boolean {
        val jenaQuery = QueryFactory.create(sparql)
        val exec = QueryExecutionFactory.create(jenaQuery, dataset)
        return exec.execAsk()
    }
    
    override fun construct(sparql: String): List<RdfTriple> {
        val jenaQuery = QueryFactory.create(sparql)
        val exec = QueryExecutionFactory.create(jenaQuery, dataset)
        val resultModel = exec.execConstruct()
        return resultModel.listStatements().asSequence().map { statement ->
            RdfTriple(
                convertResource(statement.subject),
                Iri(statement.predicate.uri),
                convertNode(statement.`object`)
            )
        }.toList()
    }
    
    override fun describe(sparql: String): List<RdfTriple> {
        val jenaQuery = QueryFactory.create(sparql)
        val exec = QueryExecutionFactory.create(jenaQuery, dataset)
        val resultModel = exec.execDescribe()
        return resultModel.listStatements().asSequence().map { statement ->
            RdfTriple(
                convertResource(statement.subject),
                Iri(statement.predicate.uri),
                convertNode(statement.`object`)
            )
        }.toList()
    }
    
    override fun update(sparql: String) {
        val jenaUpdate = org.apache.jena.update.UpdateFactory.create(sparql)
        val exec = org.apache.jena.update.UpdateExecutionFactory.create(jenaUpdate, dataset)
        exec.execute()
    }
    
    override fun transaction(operations: RdfRepository.() -> Unit) {
        dataset.begin(ReadWrite.WRITE)
        try {
            operations()
            dataset.commit()
        } catch (e: Exception) {
            dataset.abort()
            throw e
        } finally {
            dataset.end()
        }
    }
    
    override fun readTransaction(operations: RdfRepository.() -> Unit) {
        dataset.begin(ReadWrite.READ)
        try {
            operations()
            dataset.commit()
        } catch (e: Exception) {
            dataset.abort()
            throw e
        } finally {
            dataset.end()
        }
    }
    
    override fun clear(): Boolean {
        dataset.defaultModel.removeAll()
        dataset.listNames().asSequence().forEach { name ->
            dataset.removeNamedModel(name)
        }
        return true
    }
    
    override fun getStatistics(): RepositoryStatistics {
        val totalTriples = dataset.defaultModel.size() + 
            dataset.listNames().asSequence().sumOf { name ->
                dataset.getNamedModel(name).size()
            }
        return RepositoryStatistics(
            tripleCount = totalTriples.toLong(),
            graphCount = dataset.listNames().asSequence().count() + 1, // +1 for default graph
            memoryUsage = 0L, // Placeholder
            diskUsage = 0L, // Placeholder
            lastModified = System.currentTimeMillis()
        )
    }
    
    override fun getPerformanceMonitor(): PerformanceMonitor {
        return PerformanceMonitor(
            queryCount = 0,
            averageQueryTime = 0.0,
            totalQueryTime = 0,
            cacheHitRate = 1.0,
            memoryUsage = 0L
        )
    }
    
    override fun isClosed(): Boolean {
        return false // Jena Dataset doesn't have an isClosed property
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
    
    override fun close() {
        dataset.close()
    }
    
    private fun convertResource(resource: Resource): RdfResource {
        return if (resource.isAnon) {
            BlankNode(resource.id.toString())
        } else {
            Iri(resource.uri)
        }
    }
    
    private fun convertNode(node: RDFNode): RdfTerm {
        return when (node) {
            is Resource -> convertResource(node)
            is Literal -> {
                if (node.language.isNotEmpty()) {
                    Literal(node.lexicalForm, node.language)
                } else if (node.datatype != null) {
                    Literal(node.lexicalForm, Iri(node.datatype.uri))
                } else {
                    Literal(node.lexicalForm)
                }
            }
            else -> throw IllegalArgumentException("Unsupported RDF node type: ${node.javaClass.simpleName}")
        }
    }
}
