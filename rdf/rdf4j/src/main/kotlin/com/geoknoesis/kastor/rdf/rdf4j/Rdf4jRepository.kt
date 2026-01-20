package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import org.eclipse.rdf4j.repository.Repository
import org.eclipse.rdf4j.repository.RepositoryConnection
import org.eclipse.rdf4j.query.QueryLanguage
import org.eclipse.rdf4j.query.TupleQuery
import org.eclipse.rdf4j.query.BooleanQuery
import org.eclipse.rdf4j.query.GraphQuery
import org.eclipse.rdf4j.query.Update
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.ValueFactory
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.eclipse.rdf4j.sail.nativerdf.NativeStore

class Rdf4jRepository(
    private val repository: Repository,
    private val connection: RepositoryConnection = repository.connection
) : RdfRepository {
    
    companion object {
        fun MemoryRepository(): Rdf4jRepository {
            val memoryStore = MemoryStore()
            val repository = SailRepository(memoryStore)
            repository.init()
            return Rdf4jRepository(repository)
        }
        
        fun NativeRepository(location: String): Rdf4jRepository {
            val nativeStore = NativeStore(java.io.File(location))
            val repository = SailRepository(nativeStore)
            repository.init()
            return Rdf4jRepository(repository)
        }
        
        fun MemoryStarRepository(): Rdf4jRepository {
            val memoryStore = MemoryStore()
            val repository = SailRepository(memoryStore)
            repository.init()
            return Rdf4jRepository(repository)
        }
        
        fun NativeStarRepository(location: String): Rdf4jRepository {
            val nativeStore = NativeStore(java.io.File(location))
            val repository = SailRepository(nativeStore)
            repository.init()
            return Rdf4jRepository(repository)
        }
        
        fun MemoryRdfsRepository(): Rdf4jRepository {
            val memoryStore = MemoryStore()
            val repository = SailRepository(memoryStore)
            repository.init()
            return Rdf4jRepository(repository)
        }
        
        fun NativeRdfsRepository(location: String): Rdf4jRepository {
            val nativeStore = NativeStore(java.io.File(location))
            val repository = SailRepository(nativeStore)
            repository.init()
            return Rdf4jRepository(repository)
        }
        
        fun MemoryShaclRepository(): Rdf4jRepository {
            val memoryStore = MemoryStore()
            val repository = SailRepository(memoryStore)
            repository.init()
            return Rdf4jRepository(repository)
        }
        
        fun NativeShaclRepository(location: String): Rdf4jRepository {
            val nativeStore = NativeStore(java.io.File(location))
            val repository = SailRepository(nativeStore)
            repository.init()
            return Rdf4jRepository(repository)
        }
    }
    
    private val valueFactory: ValueFactory = SimpleValueFactory.getInstance()
    
    override val defaultGraph: RdfGraph = Rdf4jGraph(connection)
    
    override fun getGraph(name: Iri): RdfGraph {
        val context = valueFactory.createIRI(name.value)
        return Rdf4jGraph(connection, context)
    }
    
    override fun hasGraph(name: Iri): Boolean {
        val context = valueFactory.createIRI(name.value)
        return connection.hasStatement(null, null, null, false, context)
    }
    
    override fun listGraphs(): List<Iri> {
        val contexts = connection.contextIDs
        return contexts.map { Iri(it.stringValue()) }
    }
    
    override fun createGraph(name: Iri): RdfGraph {
        val context = valueFactory.createIRI(name.value)
        return Rdf4jGraph(connection, context)
    }
    
    override fun removeGraph(name: Iri): Boolean {
        val context = valueFactory.createIRI(name.value)
        val wasEmpty = connection.hasStatement(null, null, null, false, context)
        connection.remove(null as org.eclipse.rdf4j.model.Resource?, null as org.eclipse.rdf4j.model.IRI?, null as org.eclipse.rdf4j.model.Value?, context)
        return !wasEmpty
    }

    override fun editDefaultGraph(): GraphEditor {
        return defaultGraph as MutableRdfGraph
    }

    override fun editGraph(name: Iri): GraphEditor {
        return getGraph(name) as MutableRdfGraph
    }
    
    override fun select(query: SparqlSelect): QueryResult {
        val prepared = connection.prepareTupleQuery(QueryLanguage.SPARQL, query.sparql)
        val result = prepared.evaluate()
        try {
            val rows = mutableListOf<BindingSet>()
            while (result.hasNext()) {
                val bindingSet = result.next()
                val values = mutableMapOf<String, RdfTerm>()
                bindingSet.bindingNames.forEach { name ->
                    val value = bindingSet.getValue(name)
                    if (value != null) {
                        values[name] = Rdf4jTerms.fromRdf4jValue(value)
                    }
                }
                rows.add(MapBindingSet(values))
            }
            return Rdf4jResultSet(rows)
        } finally {
            result.close()
        }
    }
    
    override fun ask(query: SparqlAsk): Boolean {
        val prepared = connection.prepareBooleanQuery(QueryLanguage.SPARQL, query.sparql)
        return prepared.evaluate()
    }
    
    override fun construct(query: SparqlConstruct): Sequence<RdfTriple> {
        val prepared = connection.prepareGraphQuery(QueryLanguage.SPARQL, query.sparql)
        val result = prepared.evaluate()
        return result.use { graphResult ->
            graphResult.iterator().asSequence().map { statement ->
                RdfTriple(
                    Rdf4jTerms.fromRdf4jResource(statement.subject),
                    Rdf4jTerms.fromRdf4jIri(statement.predicate),
                    Rdf4jTerms.fromRdf4jValue(statement.`object`)
                )
            }
        }
    }
    
    override fun describe(query: SparqlDescribe): Sequence<RdfTriple> {
        val prepared = connection.prepareGraphQuery(QueryLanguage.SPARQL, query.sparql)
        val result = prepared.evaluate()
        return result.use { graphResult ->
            graphResult.iterator().asSequence().map { statement ->
                RdfTriple(
                    Rdf4jTerms.fromRdf4jResource(statement.subject),
                    Rdf4jTerms.fromRdf4jIri(statement.predicate),
                    Rdf4jTerms.fromRdf4jValue(statement.`object`)
                )
            }
        }
    }
    
    override fun update(query: UpdateQuery) {
        val update = connection.prepareUpdate(QueryLanguage.SPARQL, query.sparql)
        update.execute()
    }
    
    override fun transaction(operations: RdfRepository.() -> Unit) {
        connection.begin()
        try {
            operations(this)
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        }
    }
    
    override fun readTransaction(operations: RdfRepository.() -> Unit) {
        connection.begin()
        try {
            operations(this)
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        }
    }
    
    override fun clear(): Boolean {
        val wasEmpty = connection.isEmpty
        connection.clear()
        return !wasEmpty
    }
    
    override fun isClosed(): Boolean = !repository.isInitialized || !connection.isOpen
    
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
        if (connection.isOpen) {
            connection.close()
        }
        repository.shutDown()
    }
}










