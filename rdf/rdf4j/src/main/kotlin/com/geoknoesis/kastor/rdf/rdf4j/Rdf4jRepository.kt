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
import org.eclipse.rdf4j.sail.inferencer.fc.SchemaCachingRDFSInferencer
import org.eclipse.rdf4j.sail.shacl.ShaclSail

/**
 * RDF4J-based implementation of [RdfRepository].
 * 
 * **Note on Backend Types:**
 * This implementation uses RDF4J's `Repository` and `RepositoryConnection` types internally.
 * This is an implementation detail and does not leak into the public API. All public methods
 * return Kastor types only.
 * 
 * @param repository RDF4J Repository instance (internal implementation detail)
 * @param connection RDF4J RepositoryConnection instance (internal implementation detail)
 */
class Rdf4jRepository(
    private val repository: Repository,
    private val connection: RepositoryConnection = repository.connection
) : RdfRepository {
    
    /**
     * Internal method to access the underlying RDF4J RepositoryConnection.
     * Used by Rdf4jProvider for dataset serialization/parsing.
     */
    internal fun getRdf4jConnection(): RepositoryConnection = connection
    
    /**
     * Internal method to access the underlying RDF4J Repository.
     * Used by Rdf4jProvider for dataset operations.
     */
    internal fun getRdf4jRepository(): Repository = repository
    
    companion object {
        fun MemoryRepository(): Rdf4jRepository {
            val repository = SailRepository(MemoryStore())
            repository.init()
            return Rdf4jRepository(repository)
        }
        
        fun NativeRepository(location: String): Rdf4jRepository {
            val repository = SailRepository(NativeStore(java.io.File(location)))
            repository.init()
            return Rdf4jRepository(repository)
        }
        
        /**
         * In-memory store with RDF-star explicitly enabled.
         *
         * RDF4J's `MemoryStore` supports RDF-star by default; this factory exists so
         * that the variant identifier is honored and the capability is advertised.
         */
        fun MemoryStarRepository(): Rdf4jRepository {
            val repository = SailRepository(MemoryStore())
            repository.init()
            return Rdf4jRepository(repository)
        }
        
        /**
         * Native (persistent) store with RDF-star explicitly enabled.
         */
        fun NativeStarRepository(location: String): Rdf4jRepository {
            val repository = SailRepository(NativeStore(java.io.File(location)))
            repository.init()
            return Rdf4jRepository(repository)
        }
        
        /**
         * In-memory store wrapped with [SchemaCachingRDFSInferencer] so that RDFS
         * entailment is materialized at query time.
         */
        fun MemoryRdfsRepository(): Rdf4jRepository {
            val repository = SailRepository(SchemaCachingRDFSInferencer(MemoryStore()))
            repository.init()
            return Rdf4jRepository(repository)
        }
        
        /**
         * Native (persistent) store wrapped with [SchemaCachingRDFSInferencer].
         */
        fun NativeRdfsRepository(location: String): Rdf4jRepository {
            val repository = SailRepository(SchemaCachingRDFSInferencer(NativeStore(java.io.File(location))))
            repository.init()
            return Rdf4jRepository(repository)
        }
        
        /**
         * In-memory [ShaclSail] that validates writes against shapes loaded into the
         * `RDF4J.SHACL_SHAPE_GRAPH` named graph. SHACL violations surface as
         * `ShaclSailValidationException` (wrapped in a `RepositoryException`) at commit time.
         */
        fun MemoryShaclRepository(): Rdf4jRepository {
            val repository = SailRepository(ShaclSail(MemoryStore()))
            repository.init()
            return Rdf4jRepository(repository)
        }
        
        /**
         * Native (persistent) [ShaclSail] backed by [NativeStore].
         */
        fun NativeShaclRepository(location: String): Rdf4jRepository {
            val repository = SailRepository(ShaclSail(NativeStore(java.io.File(location))))
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
        // RDF4J's `contextIDs` includes blank-node contexts (graph names that
        // were generated for an unnamed `GRAPH _:b { ... }` block in TriG).
        // Those are reported as `BNode` values whose string form (`genid-...-g`)
        // is not a valid absolute IRI - constructing an `Iri` from them
        // throws. RDF 1.1/1.2 only allows IRI-named graphs to be referenced
        // via `GRAPH <iri> { ... }`, so we filter out blank-node contexts
        // here. Callers that want them should drop down to the underlying
        // RDF4J connection (via [getRdf4jConnection]).
        val iter = connection.contextIDs
        try {
            val out = mutableListOf<Iri>()
            while (iter.hasNext()) {
                val ctx = iter.next()
                if (ctx is org.eclipse.rdf4j.model.IRI) {
                    out.add(Iri(ctx.stringValue()))
                }
            }
            return out
        } finally {
            iter.close()
        }
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

    override fun editDefaultGraph(): MutableRdfGraph {
        return defaultGraph as MutableRdfGraph
    }

    override fun editGraph(name: Iri): MutableRdfGraph {
        return getGraph(name) as MutableRdfGraph
    }
    
    override fun select(query: SparqlSelect): SparqlQueryResult {
        val startTime = System.currentTimeMillis()
        val prepared = try {
            connection.prepareTupleQuery(QueryLanguage.SPARQL, query.sparql)
        } catch (e: Exception) {
            RdfDebug.logQueryError("SELECT", query.sparql, "Failed to prepare: ${e.message}")
            throw RdfQueryException(
                message = "Failed to prepare SPARQL query: ${e.message}",
                query = query.sparql,
                cause = e
            )
        }
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
            val executionTime = System.currentTimeMillis() - startTime
            RdfDebug.logQueryTrace("SELECT", query.sparql, null, executionTime, rows.size)
            return Rdf4jResultSet(rows)
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            RdfDebug.logQueryError("SELECT", query.sparql, "Failed to execute: ${e.message}")
            throw RdfQueryException(
                message = "Failed to execute SPARQL query: ${e.message}",
                query = query.sparql,
                cause = e
            )
        } finally {
            result.close()
        }
    }
    
    override fun ask(query: SparqlAsk): Boolean {
        val startTime = System.currentTimeMillis()
        val prepared = try {
            connection.prepareBooleanQuery(QueryLanguage.SPARQL, query.sparql)
        } catch (e: Exception) {
            RdfDebug.logQueryError("ASK", query.sparql, "Failed to prepare: ${e.message}")
            throw RdfQueryException(
                message = "Failed to prepare SPARQL ASK query: ${e.message}",
                query = query.sparql,
                cause = e
            )
        }
        return try {
            val result = prepared.evaluate()
            val executionTime = System.currentTimeMillis() - startTime
            RdfDebug.logQueryTrace("ASK", query.sparql, null, executionTime, if (result) 1 else 0)
            result
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            RdfDebug.logQueryError("ASK", query.sparql, "Failed to execute: ${e.message}")
            throw RdfQueryException(
                message = "Failed to execute SPARQL ASK query: ${e.message}",
                query = query.sparql,
                cause = e
            )
        }
    }
    
    override fun construct(query: SparqlConstruct): Sequence<RdfTriple> {
        val startTime = System.currentTimeMillis()
        val prepared = try {
            connection.prepareGraphQuery(QueryLanguage.SPARQL, query.sparql)
        } catch (e: Exception) {
            RdfDebug.logQueryError("CONSTRUCT", query.sparql, "Failed to prepare: ${e.message}")
            throw RdfQueryException(
                message = "Failed to prepare SPARQL CONSTRUCT query: ${e.message}",
                query = query.sparql,
                cause = e
            )
        }
        val result = prepared.evaluate()
        return result.use { graphResult ->
            val triples = graphResult.iterator().asSequence().map { statement ->
                RdfTriple(
                    Rdf4jTerms.fromRdf4jResource(statement.subject),
                    Rdf4jTerms.fromRdf4jIri(statement.predicate),
                    Rdf4jTerms.fromRdf4jValue(statement.`object`)
                )
            }.toList()
            val executionTime = System.currentTimeMillis() - startTime
            RdfDebug.logQueryTrace("CONSTRUCT", query.sparql, null, executionTime, triples.size)
            triples.asSequence()
        }
    }
    
    override fun describe(query: SparqlDescribe): Sequence<RdfTriple> {
        val startTime = System.currentTimeMillis()
        val prepared = try {
            connection.prepareGraphQuery(QueryLanguage.SPARQL, query.sparql)
        } catch (e: Exception) {
            RdfDebug.logQueryError("DESCRIBE", query.sparql, "Failed to prepare: ${e.message}")
            throw RdfQueryException(
                message = "Failed to prepare SPARQL DESCRIBE query: ${e.message}",
                query = query.sparql,
                cause = e
            )
        }
        val result = prepared.evaluate()
        return result.use { graphResult ->
            val triples = graphResult.iterator().asSequence().map { statement ->
                RdfTriple(
                    Rdf4jTerms.fromRdf4jResource(statement.subject),
                    Rdf4jTerms.fromRdf4jIri(statement.predicate),
                    Rdf4jTerms.fromRdf4jValue(statement.`object`)
                )
            }.toList()
            val executionTime = System.currentTimeMillis() - startTime
            RdfDebug.logQueryTrace("DESCRIBE", query.sparql, null, executionTime, triples.size)
            triples.asSequence()
        }
    }
    
    override fun update(query: UpdateQuery) {
        val startTime = System.currentTimeMillis()
        try {
            val update = connection.prepareUpdate(QueryLanguage.SPARQL, query.sparql)
            update.execute()
            val executionTime = System.currentTimeMillis() - startTime
            RdfDebug.logQueryTrace("UPDATE", query.sparql, null, executionTime, null)
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            RdfDebug.logQueryError("UPDATE", query.sparql, "Failed to execute: ${e.message}")
            throw RdfQueryException(
                message = "Failed to execute SPARQL UPDATE: ${e.message}",
                query = query.sparql,
                cause = e
            )
        }
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










