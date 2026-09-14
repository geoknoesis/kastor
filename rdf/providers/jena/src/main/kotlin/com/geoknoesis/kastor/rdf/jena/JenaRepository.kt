package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.apache.jena.query.Dataset
import org.apache.jena.query.DatasetFactory
import org.apache.jena.query.QueryExecution
import org.apache.jena.query.QueryExecutionFactory
import org.apache.jena.query.QueryFactory
import org.apache.jena.query.ReadWrite
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.tdb2.TDB2Factory
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Transactional Jena store. Inference is a read view and never replaces asserted data.
 *
 * **Inference views** (`*-inference` variants) are RDFS closures computed once per graph and per committed
 * state, instead of per query. Jena inference graphs are not safe for concurrent use, so the closure is
 * materialised into an immutable plain model and cached keyed by the **snapshot generation** the read
 * transaction actually sees; readers share it without any lock. A reader whose snapshot cannot be tied to a
 * generation (a commit raced with its `begin`) gets a private, uncached view. Only building a cache entry
 * is serialised (per graph); caller-supplied consumers never run under a lock, so they may freely wait on
 * reads performed by other threads. Inside a write transaction the view is built fresh so uncommitted
 * changes are visible.
 *
 * **Query errors:** failures while preparing or evaluating a query surface as [RdfQueryException];
 * exceptions thrown by a caller-supplied `consume` lambda propagate unchanged.
 */
class JenaRepository private constructor(
    private val dataset: Dataset,
    internal val inference: Boolean = false,
    private val variantId: String = if (inference) "memory-inference" else "memory",
) : RdfRepository {
    private val closed = AtomicBoolean(false)

    companion object {
        fun MemoryRepository(): JenaRepository = JenaRepository(DatasetFactory.createTxnMem())
        fun MemoryRepositoryWithInference(): JenaRepository = JenaRepository(DatasetFactory.createTxnMem(), true)
        fun Tdb2Repository(location: String): JenaRepository =
            JenaRepository(TDB2Factory.connectDataset(Paths.get(location).toAbsolutePath().toString()), false, "tdb2")
        fun Tdb2RepositoryWithInference(location: String): JenaRepository =
            JenaRepository(TDB2Factory.connectDataset(Paths.get(location).toAbsolutePath().toString()), true, "tdb2-inference")

        private const val DEFAULT_GRAPH_KEY = ""
    }

    /**
     * Commit sequence number, used like a seqlock: even while no commit is in flight, odd while one is.
     * Every write commit moves it by two (before and after `commit()`), so a read transaction whose
     * `begin` happened while the value stayed the same even number sees exactly the committed state of
     * that generation.
     */
    private val generation = AtomicLong()
    private class CachedInference(val generation: Long, val model: Model)
    private val inferenceCache = ConcurrentHashMap<String, CachedInference>()
    private val buildLocks = ConcurrentHashMap<String, Any>()

    /** Generation of the snapshot seen by this thread's read transaction, or null when it is not provable. */
    private val readGeneration = ThreadLocal<Long?>()

    internal fun <T> withRead(block: () -> T): T = inTransaction(ReadWrite.READ, block)

    internal fun <T> withWrite(block: () -> T): T = inTransaction(ReadWrite.WRITE, block)

    private fun <T> inTransaction(mode: ReadWrite, block: () -> T): T {
        check(!closed.get()) { "Repository is closed" }
        if (dataset.isInTransaction) {
            check(mode != ReadWrite.WRITE || dataset.transactionMode() == ReadWrite.WRITE) { "Cannot write inside a read transaction" }
            return block()
        }
        val before = generation.get()
        dataset.begin(mode)
        try {
            if (inference && mode == ReadWrite.READ) {
                val after = generation.get()
                readGeneration.set(if (before == after && before % 2 == 0L) before else null)
            }
            val result = block()
            if (mode == ReadWrite.WRITE) commit()
            return result
        } catch (e: Throwable) {
            if (mode == ReadWrite.WRITE) dataset.abort()
            throw e
        } finally {
            readGeneration.remove()
            dataset.end()
        }
    }

    private fun commit() {
        if (!inference) {
            dataset.commit()
            return
        }
        generation.incrementAndGet()
        try {
            dataset.commit()
        } finally {
            generation.incrementAndGet()
            inferenceCache.clear()
        }
    }

    /**
     * Read view of [model] (identified by [graphKey]). Must be called inside a transaction.
     * Inference views are cached per graph for the snapshot generation of the current read transaction;
     * inside a write transaction a fresh, uncached view is built so uncommitted changes are visible.
     */
    internal fun readModel(graphKey: String, model: Model): Model {
        if (!inference) return model
        if (dataset.transactionMode() == ReadWrite.WRITE) return ModelFactory.createRDFSModel(model)
        val snapshot = readGeneration.get() ?: return materializedInference(model)
        inferenceCache[graphKey]?.takeIf { it.generation == snapshot }?.let { return it.model }
        synchronized(buildLocks.computeIfAbsent(graphKey) { Any() }) {
            inferenceCache[graphKey]?.takeIf { it.generation == snapshot }?.let { return it.model }
            val built = materializedInference(model)
            // Never replace an entry for a newer snapshot with one built by an older reader.
            inferenceCache.compute(graphKey) { _, existing ->
                if (existing == null || existing.generation < snapshot) CachedInference(snapshot, built) else existing
            }
            return built
        }
    }

    /** RDFS closure of [model] copied into a plain in-memory model, which is safe for concurrent reads. */
    private fun materializedInference(model: Model): Model =
        ModelFactory.createDefaultModel().add(ModelFactory.createRDFSModel(model))

    /** Dataset used for queries and dataset serialization: the store, or its inference view. Call inside a transaction. */
    internal fun queryDataset(): Dataset {
        if (!inference) return dataset
        // Facade models borrow the store; closing them would close the borrowed models.
        return DatasetFactory.create(readModel(DEFAULT_GRAPH_KEY, dataset.defaultModel)).also { view ->
            dataset.listNames().forEachRemaining { view.addNamedModel(it, readModel(it, dataset.getNamedModel(it))) }
        }
    }

    private val defaultGraphView by lazy { JenaGraph(dataset.defaultModel, this, DEFAULT_GRAPH_KEY) }
    override val defaultGraph: RdfGraph get() = withRead { defaultGraphView }
    override fun getGraph(name: Iri): RdfGraph = withRead { JenaGraph(dataset.getNamedModel(name.value), this, name.value) }
    override fun hasGraph(name: Iri): Boolean = withRead { !dataset.getNamedModel(name.value).isEmpty }
    override fun listGraphs(): List<Iri> = withRead { dataset.listNames().asSequence().map(::Iri).toList() }
    override fun createGraph(name: Iri): RdfGraph = getGraph(name)
    override fun removeGraph(name: Iri): Boolean = withWrite {
        val existed = dataset.containsNamedModel(name.value)
        dataset.removeNamedModel(name.value)
        existed
    }
    override fun editDefaultGraph(): MutableRdfGraph = defaultGraph as MutableRdfGraph
    override fun editGraph(name: Iri): MutableRdfGraph = getGraph(name) as MutableRdfGraph

    override fun select(query: SparqlSelect): SparqlQueryResult = withSelectRows(query) { JenaResultSet(it.toList()) }

    override fun <T> withSelectRows(query: SparqlSelect, consume: (Sequence<BindingSet>) -> T): T = withRead {
        val exec = queryOperation(query.sparql) { QueryExecutionFactory.create(QueryFactory.create(query.sparql), queryDataset()) }
        exec.use { consumeRows(it, query.sparql, consume) }
    }

    override fun ask(query: SparqlAsk): Boolean = withRead {
        queryOperation(query.sparql) {
            QueryExecutionFactory.create(QueryFactory.create(query.sparql), queryDataset()).use { it.execAsk() }
        }
    }

    override fun <T> withSelectRows(query: SparqlSelect, bindings: Map<String, RdfTerm>, timeout: java.time.Duration,
        consume: (Sequence<BindingSet>) -> T): T = withRead {
        val exec = queryOperation(query.sparql) {
            val initial = org.apache.jena.query.QuerySolutionMap()
            bindings.forEach { (name, term) -> initial.add(name, ModelFactory.createDefaultModel().asRDFNode(JenaTerms.toJenaNode(term))) }
            QueryExecution.dataset(queryDataset()).query(query.sparql).substitution(initial)
                .timeout(timeout.toMillis().coerceAtLeast(1), java.util.concurrent.TimeUnit.MILLISECONDS).build()
        }
        exec.use { consumeRows(it, query.sparql, consume) }
    }

    private fun <T> consumeRows(exec: QueryExecution, sparql: String, consume: (Sequence<BindingSet>) -> T): T {
        val results = queryOperation(sparql) { exec.execSelect() }
        val rows = results.asSequence().map { row ->
            MapBindingSet(row.varNames().asSequence().associateWith { JenaTerms.fromNode(row.get(it)) }) as BindingSet
        }
        return consume(rows.guardedBy(sparql))
    }

    override fun <T> withConstructTriples(query: SparqlConstruct, consume: (Sequence<RdfTriple>) -> T): T = withRead {
        val exec = queryOperation(query.sparql) { QueryExecutionFactory.create(QueryFactory.create(query.sparql), queryDataset()) }
        exec.use {
            val triples = queryOperation(query.sparql) { it.execConstructTriples() }
            consume(triples.asSequence().map(JenaTerms::fromJenaTriple).guardedBy(query.sparql))
        }
    }

    override fun construct(query: SparqlConstruct): Sequence<RdfTriple> = withConstructTriples(query) { it.toList().asSequence() }

    override fun describe(query: SparqlDescribe): Sequence<RdfTriple> = withRead {
        queryOperation(query.sparql) {
            QueryExecutionFactory.create(QueryFactory.create(query.sparql), queryDataset()).use { exec ->
                val model = exec.execDescribe()
                try { JenaGraph(model).getTriples().asSequence() } finally { model.close() }
            }
        }
    }

    override fun update(query: UpdateQuery): Unit = withWrite {
        queryOperation(query.sparql) { org.apache.jena.update.UpdateAction.parseExecute(query.sparql, dataset) }
    }

    override fun transaction(operations: RdfRepository.() -> Unit): Unit = withWrite { operations(this) }
    override fun readTransaction(operations: RdfRepository.() -> Unit): Unit = withRead { operations(this) }

    override fun clear(): Boolean = withWrite {
        val hadData = !dataset.isEmpty
        dataset.defaultModel.removeAll()
        dataset.listNames().asSequence().toList().forEach { dataset.removeNamedModel(it) }
        hadData
    }

    override fun isClosed(): Boolean = closed.get()

    override fun close() {
        check(!dataset.isInTransaction) { "Cannot close inside a transaction" }
        if (closed.compareAndSet(false, true)) {
            inferenceCache.clear()
            dataset.close()
        }
    }

    override fun getCapabilities(): ProviderCapabilities = JenaProvider().getCapabilities(variantId)

    internal fun getJenaDataset(): Dataset = dataset

    /** Wraps failures of the query engine itself; never used around caller-supplied consumers. */
    private inline fun <T> queryOperation(query: String, operation: () -> T): T = try {
        operation()
    } catch (e: RdfException) {
        throw e
    } catch (e: Exception) {
        throw RdfQueryException("SPARQL execution failed: ${e.message}", query = query, cause = e)
    }

    /**
     * Wraps engine failures raised while *iterating* results (e.g. timeouts, evaluation errors) as
     * [RdfQueryException], while exceptions thrown by the consumer's own code are left untouched.
     */
    private fun <T> Sequence<T>.guardedBy(query: String): Sequence<T> {
        val source = this
        return Sequence {
            val iterator = queryOperation(query) { source.iterator() }
            object : Iterator<T> {
                override fun hasNext(): Boolean = queryOperation(query) { iterator.hasNext() }
                override fun next(): T = queryOperation(query) { iterator.next() }
            }
        }.constrainOnce()
    }
}
