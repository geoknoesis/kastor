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

class Rdf4jRepository(private val repository: Repository) : RdfRepository {
    
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
    
    override val defaultGraph: RdfGraph = Rdf4jGraph(repository.connection)
    
    override fun getGraph(name: Iri): RdfGraph {
        val context = valueFactory.createIRI(name.value)
        return Rdf4jGraph(repository.connection, context)
    }
    
    override fun hasGraph(name: Iri): Boolean {
        val context = valueFactory.createIRI(name.value)
        return repository.connection.hasStatement(null, null, null, false, context)
    }
    
    override fun listGraphs(): List<Iri> {
        val contexts = repository.connection.contextIDs
        return contexts.map { Iri(it.stringValue()) }
    }
    
    override fun createGraph(name: Iri): RdfGraph {
        val context = valueFactory.createIRI(name.value)
        return Rdf4jGraph(repository.connection, context)
    }
    
    override fun removeGraph(name: Iri): Boolean {
        val context = valueFactory.createIRI(name.value)
        val wasEmpty = repository.connection.hasStatement(null, null, null, false, context)
        repository.connection.remove(null as org.eclipse.rdf4j.model.Resource?, null as org.eclipse.rdf4j.model.IRI?, null as org.eclipse.rdf4j.model.Value?, context)
        return !wasEmpty
    }
    
    override fun query(sparql: String): QueryResult {
        val connection = repository.connection
        val query = connection.prepareTupleQuery(QueryLanguage.SPARQL, sparql)
        return Rdf4jResultSet(query.evaluate())
    }
    
    override fun ask(sparql: String): Boolean {
        val connection = repository.connection
        val query = connection.prepareBooleanQuery(QueryLanguage.SPARQL, sparql)
        return query.evaluate()
    }
    
    override fun construct(sparql: String): List<RdfTriple> {
        val connection = repository.connection
        val query = connection.prepareGraphQuery(QueryLanguage.SPARQL, sparql)
        val result = query.evaluate()
        return result.map { statement ->
            RdfTriple(
                Rdf4jTerms.fromRdf4jResource(statement.subject),
                Rdf4jTerms.fromRdf4jIri(statement.predicate),
                Rdf4jTerms.fromRdf4jValue(statement.`object`)
            )
        }
    }
    
    override fun describe(sparql: String): List<RdfTriple> {
        val connection = repository.connection
        val query = connection.prepareGraphQuery(QueryLanguage.SPARQL, sparql)
        val result = query.evaluate()
        return result.map { statement ->
            RdfTriple(
                Rdf4jTerms.fromRdf4jResource(statement.subject),
                Rdf4jTerms.fromRdf4jIri(statement.predicate),
                Rdf4jTerms.fromRdf4jValue(statement.`object`)
            )
        }
    }
    
    override fun update(sparql: String) {
        val connection = repository.connection
        val update = connection.prepareUpdate(QueryLanguage.SPARQL, sparql)
        update.execute()
    }
    
    override fun transaction(operations: RdfRepository.() -> Unit) {
        val connection = repository.connection
        connection.begin()
        try {
            operations()
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        }
    }
    
    override fun readTransaction(operations: RdfRepository.() -> Unit) {
        val connection = repository.connection
        connection.begin()
        try {
            operations()
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        }
    }
    
    override fun clear(): Boolean {
        val wasEmpty = repository.connection.isEmpty
        repository.connection.clear()
        return !wasEmpty
    }
    
    override fun getStatistics(): RepositoryStatistics {
        return RepositoryStatistics(
            tripleCount = repository.connection.size().toLong(),
            graphCount = repository.connection.contextIDs.count(),
            memoryUsage = 0L,
            diskUsage = 0L,
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
    
    override fun isClosed(): Boolean = !repository.isInitialized
    
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
        repository.shutDown()
    }
}

