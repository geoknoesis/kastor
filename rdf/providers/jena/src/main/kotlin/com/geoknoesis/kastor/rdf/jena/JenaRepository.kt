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
 * **Inference views** (`*-inference` variants) are lazy RDFS inference graphs over the store, prepared once per
 * graph and per committed state instead of per query, and **never copied into memory**: backward-chained
 * entailments are computed on demand from the (possibly disk-backed) store, so a large TDB2 dataset is not
 * materialised on the heap. The prepared graph is cached keyed by the **snapshot** the read transaction sees:
 * - TDB2: the transaction's data version, which belongs to the storage shared by every repository connected to the
 *   same location, so a commit through any instance (or any other TDB2 client of that location) is seen by all;
 * - in-memory stores: a commit generation of this repository.
 *
 * Jena inference graphs are not safe for concurrent use (backward chaining updates internal tables while reading),
 * so readers sharing a cached graph take turns: each `find` runs to completion under a per-graph lock and is handed
 * out as a detached result, which also keeps store iterators inside the reader's own transaction. The lock is never
 * held while caller-supplied code runs, so consumers may freely wait on reads performed by other threads. A reader
 * whose snapshot cannot be identified (a commit raced with its `begin`) gets a private, uncached lazy view. Inside
 * a write transaction the view is built fresh so uncommitted changes are visible.
 *
 * Memory use of a cached view is Jena's own for lazy RDFS inference: the forward deductions (schema-level for the
 * RDFS rules) plus the backward-chaining tables of the goals queried so far in that snapshot. It is released when a
 * newer snapshot replaces the entry or the repository is closed.
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

    private val tdb2: Boolean = variantId.startsWith("tdb2")

    /**
     * In-memory stores only: commit sequence number, used like a seqlock: even while no commit is in flight, odd
     * while one is. Every write commit moves it by two (before and after `commit()`), so a read transaction whose
     * `begin` happened while the value stayed the same even number sees exactly the committed state of that
     * generation. (A TxnMem dataset belongs to exactly one repository, so no other writer can bypass it.)
     */
    private val generation = AtomicLong()

    /** Committed state seen by a read transaction: [version] of the storage identified by [store]. */
    private class Snapshot(val store: Any, val version: Long) {
        fun sameAs(other: Snapshot) = store === other.store && version == other.version
        fun newerThan(other: Snapshot) = store !== other.store || version > other.version
    }

    private class CachedInference(val snapshot: Snapshot, val graph: SharedInferenceGraph, val model: Model)
    private val inferenceCache = ConcurrentHashMap<String, CachedInference>()
    private val buildLocks = ConcurrentHashMap<String, Any>()

    /** Snapshot seen by this thread's read transaction, or null when it is not provable. */
    private val readSnapshot = ThreadLocal<Snapshot?>()

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
            if (inference && mode == ReadWrite.READ) readSnapshot.set(snapshotAfterBegin(before))
            val result = block()
            if (mode == ReadWrite.WRITE) commit()
            return result
        } catch (e: Throwable) {
            if (mode == ReadWrite.WRITE) dataset.abort()
            throw e
        } finally {
            readSnapshot.remove()
            dataset.end()
        }
    }

    /** Snapshot of the transaction the current thread just began. */
    private fun snapshotAfterBegin(generationBefore: Long): Snapshot? {
        if (tdb2) {
            // The data version lives in the transaction coordinator shared by every connection to the location.
            val storage = org.apache.jena.tdb2.sys.TDBInternal.getDatasetGraphTDB(dataset.asDatasetGraph()) ?: return null
            val transaction = storage.txnSystem.threadTransaction ?: return null
            return Snapshot(storage, transaction.dataVersion)
        }
        val after = generation.get()
        return if (generationBefore == after && generationBefore % 2 == 0L) Snapshot(this, generationBefore) else null
    }

    private fun commit() {
        if (!inference || tdb2) {
            dataset.commit()
            if (inference) inferenceCache.clear()
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
     * Inference views are cached per graph for the snapshot of the current read transaction;
     * inside a write transaction a fresh, uncached view is built so uncommitted changes are visible.
     */
    internal fun readModel(graphKey: String, model: Model): Model {
        if (!inference) return model
        if (dataset.transactionMode() == ReadWrite.WRITE) return ModelFactory.createRDFSModel(model)
        // Unprovable snapshot: a private lazy view, confined to this thread and never shared.
        val snapshot = readSnapshot.get() ?: return ModelFactory.createRDFSModel(model)
        inferenceCache[graphKey]?.takeIf { it.snapshot.sameAs(snapshot) }?.let { return it.model }
        synchronized(buildLocks.computeIfAbsent(graphKey) { Any() }) {
            inferenceCache[graphKey]?.takeIf { it.snapshot.sameAs(snapshot) }?.let { return it.model }
            val inf = org.apache.jena.reasoner.rulesys.RDFSRuleReasonerFactory.theInstance().create(null).bind(model.graph)
            inf.prepare()
            val shared = SharedInferenceGraph(inf)
            val view = ModelFactory.createModelForGraph(shared)
            // Never replace an entry for a newer snapshot with one built by an older reader.
            inferenceCache.compute(graphKey) { _, existing ->
                if (existing == null || snapshot.newerThan(existing.snapshot)) CachedInference(snapshot, shared, view) else existing
            }
            return view
        }
    }

    /** The prepared inference graph cached for [graphKey], if any (for tests and diagnostics). */
    internal fun cachedInferenceGraph(graphKey: String = DEFAULT_GRAPH_KEY): org.apache.jena.reasoner.InfGraph? =
        inferenceCache[graphKey]?.graph?.inf

    /**
     * Read-only facade over a prepared inference graph shared by the concurrent readers of one snapshot.
     *
     * Jena inference graphs are not thread-safe: backward chaining updates shared tables while reading. Every
     * operation therefore runs to completion under [lock] and results are handed out detached, so the lock is never
     * held while caller code consumes them. Draining completely also keeps every store iterator inside the calling
     * reader's own transaction (TDB2 rejects iterators used outside the transaction that created them).
     */
    internal class SharedInferenceGraph(val inf: org.apache.jena.reasoner.InfGraph) : org.apache.jena.graph.impl.GraphBase() {
        private val lock = Any()

        override fun graphBaseFind(triplePattern: org.apache.jena.graph.Triple): org.apache.jena.util.iterator.ExtendedIterator<org.apache.jena.graph.Triple> {
            val results = synchronized(lock) {
                val iterator = inf.find(triplePattern)
                try { iterator.toList() } finally { iterator.close() }
            }
            return org.apache.jena.util.iterator.WrappedIterator.create(results.iterator())
        }

        override fun graphBaseContains(t: org.apache.jena.graph.Triple): Boolean = synchronized(lock) { inf.contains(t) }

        /** Counts what [find] exposes (an inference graph's own size does not count every entailment). */
        override fun graphBaseSize(): Int = synchronized(lock) {
            val iterator = inf.find()
            try {
                var count = 0
                while (iterator.hasNext()) { iterator.next(); count++ }
                count
            } finally {
                iterator.close()
            }
        }

        override fun createPrefixMapping(): org.apache.jena.shared.PrefixMapping = inf.prefixMapping
    }

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
