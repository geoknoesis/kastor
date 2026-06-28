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
) : RdfRepository {

    /** Connection pinned to the current thread's active `transaction { }`, if any. */
    private val txConnection = ThreadLocal<RepositoryConnection?>()

    /**
     * Runs [block] with a RepositoryConnection. Inside a `transaction { }` on the
     * current thread it reuses that transaction's connection; otherwise it borrows a
     * fresh connection from the repository (RDF4J manages a connection pool) and closes
     * it when [block] returns. RDF4J connections are not thread-safe, so borrowing one
     * per operation is what makes concurrent reads/writes safe.
     */
    internal fun <T> withConnection(block: (RepositoryConnection) -> T): T {
        val tx = txConnection.get()
        return if (tx != null) block(tx) else repository.connection.use { block(it) }
    }

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

    // Guards against double close() (e.g. two threads, or close() inside a use{}
    // after an explicit close) shutting the repository down twice.
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    
    override val defaultGraph: RdfGraph = Rdf4jGraph(this, null)

    override fun getGraph(name: Iri): RdfGraph =
        Rdf4jGraph(this, valueFactory.createIRI(name.value))

    override fun hasGraph(name: Iri): Boolean = withConnection { conn ->
        conn.hasStatement(null, null, null, false, valueFactory.createIRI(name.value))
    }

    override fun listGraphs(): List<Iri> = withConnection { conn ->
        // RDF4J's `contextIDs` includes blank-node contexts (graph names that
        // were generated for an unnamed `GRAPH _:b { ... }` block in TriG).
        // Those are reported as `BNode` values whose string form (`genid-...-g`)
        // is not a valid absolute IRI - constructing an `Iri` from them
        // throws. RDF 1.1/1.2 only allows IRI-named graphs to be referenced
        // via `GRAPH <iri> { ... }`, so we filter out blank-node contexts here.
        conn.contextIDs.use { iter ->
            val out = mutableListOf<Iri>()
            while (iter.hasNext()) {
                val ctx = iter.next()
                if (ctx is org.eclipse.rdf4j.model.IRI) {
                    out.add(Iri(ctx.stringValue()))
                }
            }
            out
        }
    }

    override fun createGraph(name: Iri): RdfGraph =
        Rdf4jGraph(this, valueFactory.createIRI(name.value))

    override fun removeGraph(name: Iri): Boolean = withConnection { conn ->
        val context = valueFactory.createIRI(name.value)
        val had = conn.hasStatement(null, null, null, false, context)
        conn.remove(null as org.eclipse.rdf4j.model.Resource?, null as org.eclipse.rdf4j.model.IRI?, null as org.eclipse.rdf4j.model.Value?, context)
        had
    }

    override fun editDefaultGraph(): MutableRdfGraph {
        return defaultGraph as MutableRdfGraph
    }

    override fun editGraph(name: Iri): MutableRdfGraph {
        return getGraph(name) as MutableRdfGraph
    }
    
    override fun select(query: SparqlSelect): SparqlQueryResult = withConnection { conn ->
        val startTime = System.currentTimeMillis()
        val prepared = try {
            conn.prepareTupleQuery(QueryLanguage.SPARQL, query.sparql)
        } catch (e: Exception) {
            RdfDebug.logQueryError("SELECT", query.sparql, "Failed to prepare: ${e.message}")
            throw RdfQueryException(
                message = "Failed to prepare SPARQL query: ${e.message}",
                query = query.sparql,
                cause = e
            )
        }
        try {
            prepared.evaluate().use { result ->
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
                RdfDebug.logQueryTrace("SELECT", query.sparql, null, System.currentTimeMillis() - startTime, rows.size)
                Rdf4jResultSet(rows)
            }
        } catch (e: RdfQueryException) {
            throw e
        } catch (e: Exception) {
            RdfDebug.logQueryError("SELECT", query.sparql, "Failed to execute: ${e.message}")
            throw RdfQueryException(
                message = "Failed to execute SPARQL query: ${e.message}",
                query = query.sparql,
                cause = e
            )
        }
    }
    
    override fun ask(query: SparqlAsk): Boolean = withConnection { conn ->
        val startTime = System.currentTimeMillis()
        val prepared = try {
            conn.prepareBooleanQuery(QueryLanguage.SPARQL, query.sparql)
        } catch (e: Exception) {
            RdfDebug.logQueryError("ASK", query.sparql, "Failed to prepare: ${e.message}")
            throw RdfQueryException(
                message = "Failed to prepare SPARQL ASK query: ${e.message}",
                query = query.sparql,
                cause = e
            )
        }
        try {
            val result = prepared.evaluate()
            RdfDebug.logQueryTrace("ASK", query.sparql, null, System.currentTimeMillis() - startTime, if (result) 1 else 0)
            result
        } catch (e: Exception) {
            RdfDebug.logQueryError("ASK", query.sparql, "Failed to execute: ${e.message}")
            throw RdfQueryException(
                message = "Failed to execute SPARQL ASK query: ${e.message}",
                query = query.sparql,
                cause = e
            )
        }
    }
    
    override fun construct(query: SparqlConstruct): Sequence<RdfTriple> =
        graphQuery("CONSTRUCT", query.sparql)

    override fun describe(query: SparqlDescribe): Sequence<RdfTriple> =
        graphQuery("DESCRIBE", query.sparql)

    /**
     * Shared CONSTRUCT/DESCRIBE execution. The result is materialized to a list inside
     * the connection scope because the borrowed connection (and its GraphQueryResult
     * cursor) is closed as soon as [withConnection] returns — a lazy sequence over a
     * closed connection would fail. Callers that need lazy streaming over large graphs
     * should use a scoped query API.
     */
    private fun graphQuery(kind: String, sparql: String): Sequence<RdfTriple> = withConnection { conn ->
        val startTime = System.currentTimeMillis()
        val prepared = try {
            conn.prepareGraphQuery(QueryLanguage.SPARQL, sparql)
        } catch (e: Exception) {
            RdfDebug.logQueryError(kind, sparql, "Failed to prepare: ${e.message}")
            throw RdfQueryException(
                message = "Failed to prepare SPARQL $kind query: ${e.message}",
                query = sparql,
                cause = e
            )
        }
        prepared.evaluate().use { graphResult ->
            val triples = graphResult.iterator().asSequence().map { statement ->
                RdfTriple(
                    Rdf4jTerms.fromRdf4jResource(statement.subject),
                    Rdf4jTerms.fromRdf4jIri(statement.predicate),
                    Rdf4jTerms.fromRdf4jValue(statement.`object`)
                )
            }.toList()
            RdfDebug.logQueryTrace(kind, sparql, null, System.currentTimeMillis() - startTime, triples.size)
            triples.asSequence()
        }
    }
    
    override fun update(query: UpdateQuery) {
        withConnection { conn ->
            val startTime = System.currentTimeMillis()
            try {
                conn.prepareUpdate(QueryLanguage.SPARQL, query.sparql).execute()
                RdfDebug.logQueryTrace("UPDATE", query.sparql, null, System.currentTimeMillis() - startTime, null)
            } catch (e: Exception) {
                RdfDebug.logQueryError("UPDATE", query.sparql, "Failed to execute: ${e.message}")
                throw RdfQueryException(
                    message = "Failed to execute SPARQL UPDATE: ${e.message}",
                    query = query.sparql,
                    cause = e
                )
            }
        }
    }

    override fun transaction(operations: RdfRepository.() -> Unit) = runInTransaction(operations)

    override fun readTransaction(operations: RdfRepository.() -> Unit) = runInTransaction(operations)

    /**
     * Runs [operations] inside a single RDF4J transaction. A fresh connection is
     * borrowed and pinned to the current thread (via [txConnection]) so every
     * operation in the block — including those on graphs obtained from this
     * repository — shares the same transaction. A nested `transaction { }` on the
     * same thread joins the outer one instead of calling `begin()` again (which RDF4J
     * rejects); only the outermost call begins/commits/rolls back.
     */
    private fun runInTransaction(operations: RdfRepository.() -> Unit) {
        if (txConnection.get() != null) {
            // Already inside a transaction on this thread — join it.
            operations(this)
            return
        }
        repository.connection.use { conn ->
            txConnection.set(conn)
            try {
                conn.begin()
                operations(this)
                conn.commit()
            } catch (e: Exception) {
                if (conn.isActive) conn.rollback()
                throw e
            } finally {
                txConnection.remove()
            }
        }
    }

    override fun clear(): Boolean = withConnection { conn ->
        val wasEmpty = conn.isEmpty
        conn.clear()
        !wasEmpty
    }

    override fun isClosed(): Boolean = closed.get() || !repository.isInitialized
    
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
        if (!closed.compareAndSet(false, true)) return
        // No long-lived connection to close (connections are per-operation); just shut
        // down the repository. Guarded by the AtomicBoolean against double-close.
        repository.shutDown()
    }
}










