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

/**
 * Jena-based implementation of [RdfRepository].
 * 
 * **Note on Backend Types:**
 * This implementation uses Jena's `Dataset` type internally. This is an implementation detail
 * and does not leak into the public API. All public methods return Kastor types only.
 * 
 * @param dataset Jena Dataset instance (internal implementation detail)
 */
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

    override fun editDefaultGraph(): GraphEditor {
        return defaultGraph as MutableRdfGraph
    }

    override fun editGraph(name: Iri): GraphEditor {
        return getGraph(name) as MutableRdfGraph
    }
    
    override fun select(query: SparqlSelect): SparqlQueryResult {
        val jenaQuery = try {
            QueryFactory.create(query.sparql)
        } catch (e: Exception) {
            throw RdfQueryException(
                message = "Failed to parse SPARQL query: ${e.message}",
                query = query.sparql,
                cause = e
            )
        }
        val exec = QueryExecutionFactory.create(jenaQuery, dataset)
        try {
            val jenaResultSet = exec.execSelect()
            val rows = mutableListOf<BindingSet>()
            while (jenaResultSet.hasNext()) {
                val jenaBinding = jenaResultSet.next()
                val values = mutableMapOf<String, RdfTerm>()
                jenaBinding.varNames().asSequence().forEach { name ->
                    val term = jenaBinding.get(name)?.let { convertNode(it) }
                    if (term != null) {
                        values[name] = term
                    }
                }
                rows.add(MapBindingSet(values))
            }
            return JenaResultSet(rows)
        } catch (e: Exception) {
            throw RdfQueryException(
                message = "Failed to execute SPARQL query: ${e.message}",
                query = query.sparql,
                cause = e
            )
        } finally {
            exec.close()
        }
    }
    
    override fun ask(query: SparqlAsk): Boolean {
        val jenaQuery = try {
            QueryFactory.create(query.sparql)
        } catch (e: Exception) {
            throw RdfQueryException(
                message = "Failed to parse SPARQL ASK query: ${e.message}",
                query = query.sparql,
                cause = e
            )
        }
        val exec = QueryExecutionFactory.create(jenaQuery, dataset)
        try {
            return exec.execAsk()
        } catch (e: Exception) {
            throw RdfQueryException(
                message = "Failed to execute SPARQL ASK query: ${e.message}",
                query = query.sparql,
                cause = e
            )
        } finally {
            exec.close()
        }
    }
    
    override fun construct(query: SparqlConstruct): Sequence<RdfTriple> {
        val jenaQuery = QueryFactory.create(query.sparql)
        val exec = QueryExecutionFactory.create(jenaQuery, dataset)
        try {
            val resultModel = exec.execConstruct()
            return resultModel.listStatements().asSequence().map { statement ->
                RdfTriple(
                    convertResource(statement.subject),
                    Iri(statement.predicate.uri),
                    convertNode(statement.`object`)
                )
            }
        } finally {
            exec.close()
        }
    }
    
    override fun describe(query: SparqlDescribe): Sequence<RdfTriple> {
        val jenaQuery = QueryFactory.create(query.sparql)
        val exec = QueryExecutionFactory.create(jenaQuery, dataset)
        try {
            val resultModel = exec.execDescribe()
            return resultModel.listStatements().asSequence().map { statement ->
                RdfTriple(
                    convertResource(statement.subject),
                    Iri(statement.predicate.uri),
                    convertNode(statement.`object`)
                )
            }
        } finally {
            exec.close()
        }
    }
    
    override fun update(query: UpdateQuery) {
        val jenaUpdate = org.apache.jena.update.UpdateFactory.create(query.sparql)
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









