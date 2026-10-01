package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.apache.jena.graph.Graph
import org.apache.jena.graph.Node
import org.apache.jena.graph.NodeFactory
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Transactional Jena store. Inference is a read view and never replaces asserted data.
 *
 * **Inference views** (`*-inference` variants) are lazy RDFS inference graphs over the store, prepared once per
 * graph and per committed state instead of per query, and **never copied into memory**: backward-chained
 * entailments are computed on demand from the (possibly disk-backed) store, so a large TDB2 dataset is not
 * materialised on the heap. Prepared graphs are shared by every reader of the same **snapshot**:
 * - TDB2: the transaction's data version, which belongs to the storage shared by every repository connected to the
 *   same location, so a commit through any instance (or any other TDB2 client of that location) is seen by all;
 * - in-memory stores: a commit generation of this repository.
 *
 * Jena inference graphs are not safe for concurrent use (backward chaining updates shared goal tables while
 * reading), and TDB2 store iterators may only be advanced inside the transaction that opened them, while the
 * reasoner keeps suspended store iterators in those shared tables. Each snapshot's inference graphs are therefore
 * owned by a dedicated worker thread holding its own read transaction on that snapshot ([SnapshotView]); readers
 * hand it small steps (open a find, pull the next chunk of results, close) so that:
 * - results **stream**: a query reads only what it consumes (`LIMIT 10` never drains the closure);
 * - concurrent readers **interleave** chunk by chunk instead of waiting for whole result sets;
 * - no worker step runs while caller-supplied code runs, so consumers may freely wait on reads of other threads;
 * - a query **timeout** or an interrupted reader stops the reasoner: store reads check the cancellation of the
 *   step they belong to. A cancelled step may leave the shared goal tables half-updated, so the snapshot's view is
 *   then discarded and rebuilt for later readers.
 *
 * Named graphs are prepared lazily, when a read first touches them. A view is released when a newer snapshot
 * replaces it (after commits) and no read transaction uses it any more, after the view idle timeout (default [DEFAULT_VIEW_IDLE_TIMEOUT]) without readers,
 * or when the repository is closed. A reader whose snapshot cannot be identified (a commit raced with its `begin`)
 * gets a private, uncached lazy view; inside a write transaction the view is built fresh so uncommitted changes are
 * visible.
 *
 * Memory use of a view is Jena's own for lazy RDFS inference: the forward deductions (schema-level for the RDFS
 * rules) plus the backward-chaining tables of the goals queried so far in that snapshot, plus at most one chunk of
 * results per open iterator.
 *
 * **Query errors:** failures while preparing or evaluating a query surface as [RdfQueryException] (a timed-out
 * query as an [RdfQueryException] caused by Jena's `QueryCancelledException`); exceptions thrown by a
 * caller-supplied `consume` lambda propagate unchanged.
 */
class JenaRepository private constructor(
    private val dataset: Dataset,
    internal val inference: Boolean = false,
    private val variantId: String = if (inference) "memory-inference" else "memory",
    /** A snapshot view nobody has used for this long is released (its worker ends its read transaction). */
    private val viewIdleTimeout: java.time.Duration = DEFAULT_VIEW_IDLE_TIMEOUT,
) : RdfRepository {
    private val closed = AtomicBoolean(false)

    init {
        require(!viewIdleTimeout.isNegative && !viewIdleTimeout.isZero) { "viewIdleTimeout must be positive, got $viewIdleTimeout" }
    }

    companion object {
        /**
         * Default time an unused inference view (and, for TDB2, the read transaction its worker holds, which pins the
         * store version and can delay compaction) is kept for reuse before it is released.
         */
        @JvmField
        val DEFAULT_VIEW_IDLE_TIMEOUT: java.time.Duration = java.time.Duration.ofSeconds(1)

        fun MemoryRepository(): JenaRepository = JenaRepository(DatasetFactory.createTxnMem())

        /** In-memory store with lazy RDFS inference; an inference view unused for [viewIdleTimeout] is released. */
        @JvmOverloads
        fun MemoryRepositoryWithInference(viewIdleTimeout: java.time.Duration = DEFAULT_VIEW_IDLE_TIMEOUT): JenaRepository =
            JenaRepository(DatasetFactory.createTxnMem(), true, "memory-inference", viewIdleTimeout)

        fun Tdb2Repository(location: String): JenaRepository =
            JenaRepository(TDB2Factory.connectDataset(Paths.get(location).toAbsolutePath().toString()), false, "tdb2")

        /**
         * TDB2 store with lazy RDFS inference. Each inference view's worker holds a TDB2 read transaction, which pins
         * the store version (and can delay compaction) while the view is kept; a view unused for [viewIdleTimeout] is
         * released, and [close] releases all of them.
         */
        @JvmOverloads
        fun Tdb2RepositoryWithInference(location: String, viewIdleTimeout: java.time.Duration = DEFAULT_VIEW_IDLE_TIMEOUT): JenaRepository =
            JenaRepository(TDB2Factory.connectDataset(Paths.get(location).toAbsolutePath().toString()), true, "tdb2-inference", viewIdleTimeout)

        private const val DEFAULT_GRAPH_KEY = ""

        /** Longest time [close] waits for inference workers (still used by other threads' reads) to stop. */
        private const val CLOSE_WAIT_SECONDS = 10L
    }

    private val viewIdleNanos: Long = viewIdleTimeout.toNanos()

    /** Every inference view whose worker has not been awaited yet; [close] retires and awaits them all. */
    private val openViews: MutableSet<SnapshotView> = java.util.concurrent.ConcurrentHashMap.newKeySet()

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

    /** Guards [currentView] replacement. Lock order: [viewLock], then a [SnapshotView]'s monitor. */
    private val viewLock = Any()

    /** Shared inference view of the newest snapshot read so far, if any. */
    @Volatile private var currentView: SnapshotView? = null

    /** Snapshot seen by this thread's read transaction, or null when it is not provable. */
    private val readSnapshot = ThreadLocal<Snapshot?>()

    /** Inference view held by this thread's read transaction (released when the transaction ends). */
    private val transactionView = ThreadLocal<SnapshotView?>()

    /** Store triples read by inference views so far (diagnostic: views must stream, not drain the store). */
    private val baseReads = AtomicLong()

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
            transactionView.get()?.let { transactionView.remove(); it.release() }
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
            if (inference) retireCurrentView()
            return
        }
        generation.incrementAndGet()
        try {
            dataset.commit()
        } finally {
            generation.incrementAndGet()
            retireCurrentView()
        }
    }

    private fun retireCurrentView() {
        val view = synchronized(viewLock) { currentView.also { currentView = null } }
        view?.retire()
    }

    /**
     * Read view of [model] (identified by [graphKey]). Must be called inside a transaction.
     * Inference views are shared per snapshot of the current read transaction and prepared per graph on first use;
     * inside a write transaction a fresh, uncached view is built so uncommitted changes are visible.
     */
    internal fun readModel(graphKey: String, model: Model): Model {
        if (!inference) return model
        if (dataset.transactionMode() == ReadWrite.WRITE) return privateView(model)
        // Unprovable snapshot: a private lazy view, confined to this thread and never shared.
        val snapshot = readSnapshot.get() ?: return privateView(model)
        var view = transactionView.get()
        if (view != null && view.poisoned) {
            transactionView.remove()
            view.release()
            view = null
        }
        if (view == null) {
            view = acquireView(snapshot) ?: return privateView(model)
            transactionView.set(view)
        }
        return ModelFactory.createModelForGraph(view.graph(graphKey))
    }

    private fun privateView(model: Model): Model =
        ModelFactory.createInfModel(
            org.apache.jena.reasoner.rulesys.RDFSRuleReasonerFactory.theInstance().create(null),
            ModelFactory.createModelForGraph(CancellableGraph(model.graph, baseReads)),
        )

    /** The shared view of [snapshot], acquired for the calling transaction; null when none can be opened. */
    private fun acquireView(snapshot: Snapshot): SnapshotView? {
        currentView?.takeIf { it.snapshot.sameAs(snapshot) && it.tryAcquire() }?.let { return it }
        val (view, replaced) = synchronized(viewLock) {
            currentView?.takeIf { it.snapshot.sameAs(snapshot) && it.tryAcquire() }?.let { return it }
            val opened = openView(snapshot) ?: return null
            opened.tryAcquire()
            val existing = currentView
            // Never replace the view of a newer snapshot with one opened for an older reader: that one stays
            // private to its reader and is released when the reader's transaction ends.
            if (existing == null || !existing.usable() || snapshot.newerThan(existing.snapshot)) {
                currentView = opened
                opened to existing
            } else {
                opened.retire()
                opened to null
            }
        }
        replaced?.retire()
        return view
    }

    private fun forgetView(view: SnapshotView) {
        synchronized(viewLock) { if (currentView === view) currentView = null }
    }

    /** Store triples read by inference views so far (diagnostic: views must stream, not drain the store). */
    internal fun inferenceBaseReads(): Long = baseReads.get()

    /** Keys of the graphs whose inference view is prepared for the current snapshot (diagnostic). */
    internal fun preparedInferenceGraphs(): Set<String> = currentView?.preparedGraphs() ?: emptySet()

    /** The prepared inference graph cached for [graphKey], if any (for tests and diagnostics). */
    internal fun cachedInferenceGraph(graphKey: String = DEFAULT_GRAPH_KEY): org.apache.jena.reasoner.InfGraph? =
        currentView?.preparedGraph(graphKey)?.inf

    /**
     * Inference state of one committed snapshot: a worker thread holding a read transaction on that snapshot, and
     * the inference graphs prepared on it so far. Every access to those graphs runs on the worker (see the class
     * documentation of [JenaRepository]).
     *
     * Lifecycle: each read transaction using the view holds it ([tryAcquire] / [release]); the view is retired when
     * a newer snapshot replaces it, when it is poisoned by a cancelled step, after the view idle timeout without
     * holders, or when the repository closes. A retired view stops (its worker ends the read transaction and exits)
     * once it has no holders.
     */
    private inner class SnapshotView(val snapshot: Snapshot, private val worker: InferenceWorker) : InferenceExecutor {
        private val graphs = java.util.concurrent.ConcurrentHashMap<String, SharedInferenceGraph>()
        private var holders = 0
        private var retired = false
        private var stopped = false
        private var idleSince = 0L

        @Volatile var poisoned = false
            private set

        fun usable(): Boolean = !poisoned && synchronized(this) { !retired && !stopped }

        fun tryAcquire(): Boolean = synchronized(this) {
            if (retired || stopped || poisoned) false else { holders++; true }
        }

        fun release() {
            val idle = synchronized(this) {
                holders--
                if (holders == 0) idleSince = System.nanoTime()
                stopIfUnused()
                holders == 0 && !retired
            }
            if (idle) IDLE_TIMER.schedule({ retireIfIdle() }, viewIdleNanos, TimeUnit.NANOSECONDS)
        }

        fun retire() = synchronized(this) {
            retired = true
            stopIfUnused()
        }

        private fun retireIfIdle() {
            val idle = synchronized(this) {
                holders == 0 && !retired && System.nanoTime() - idleSince >= viewIdleNanos
            }
            if (!idle) return
            forgetView(this)
            synchronized(this) { if (holders == 0) retire() }
        }

        private fun stopIfUnused() {
            if (retired && holders == 0 && !stopped) {
                stopped = true
                graphs.clear()
                worker.shutdown {
                    try { if (dataset.isInTransaction) dataset.end() } finally { openViews.remove(this) }
                }
            }
        }

        /** Waits for a stopped view's worker thread to exit (used by [close]); true when it has. */
        fun awaitStopped(millis: Long): Boolean =
            synchronized(this) { stopped } && worker.awaitTermination(millis)

        fun preparedGraphs(): Set<String> = graphs.keys.toSet()
        fun preparedGraph(graphKey: String): SharedInferenceGraph? = graphs[graphKey]

        /** The inference graph of [graphKey] in this snapshot, prepared on first use. */
        fun graph(graphKey: String): SharedInferenceGraph =
            graphs[graphKey] ?: call { graphs.getOrPut(graphKey) { prepare(graphKey) } }

        /** Runs on the worker, inside its read transaction. */
        private fun prepare(graphKey: String): SharedInferenceGraph {
            val store = dataset.asDatasetGraph()
            val base = if (graphKey == DEFAULT_GRAPH_KEY) store.defaultGraph else store.getGraph(NodeFactory.createURI(graphKey))
            val inf = org.apache.jena.reasoner.rulesys.RDFSRuleReasonerFactory.theInstance().create(null)
                .bind(CancellableGraph(base, baseReads))
            inf.prepare()
            return SharedInferenceGraph(inf, this)
        }

        override fun <T> call(block: () -> T): T = worker.call(onBroken = ::poison, block = block)

        override fun submitQuietly(block: () -> Unit) = worker.submitQuietly(block)

        /** A step was cancelled or failed half-way: the shared goal tables may be inconsistent, so stop sharing. */
        private fun poison() {
            poisoned = true
            forgetView(this)
            retire()
        }

        override fun toString(): String = "SnapshotView(${snapshot.version})"
    }

    /**
     * Opens a view of [snapshot]: starts a worker and begins its read transaction. Null when the worker's
     * transaction does not see exactly [snapshot] (a commit happened in between).
     */
    private fun openView(snapshot: Snapshot): SnapshotView? {
        val worker = InferenceWorker("kastor-jena-inference")
        val endTransaction = { if (dataset.isInTransaction) dataset.end() }
        val opened = try {
            worker.call(onBroken = {}) {
                val before = generation.get()
                dataset.begin(ReadWrite.READ)
                val seen = snapshotAfterBegin(before)
                (seen != null && seen.sameAs(snapshot)).also { if (!it) dataset.end() }
            }
        } catch (e: Throwable) {
            worker.shutdown(endTransaction)
            throw e
        }
        if (!opened) {
            worker.shutdown(endTransaction)
            return null
        }
        val view = SnapshotView(snapshot, worker)
        openViews.add(view)
        // A view opened while close() ran is not seen by it: stop it here instead.
        if (closed.get()) { view.retire(); openViews.remove(view); return null }
        return view
    }

    /**
     * Dataset used for queries and dataset serialization: the store, or its inference view. Call inside a
     * transaction. The inference view prepares a graph only when the query first reads it.
     */
    internal fun queryDataset(): Dataset {
        if (!inference) return dataset
        return DatasetFactory.wrap(InferenceDatasetGraph())
    }

    /**
     * Read-only dataset over the inference views of the current transaction. Graphs are resolved lazily: the query
     * engine asks for the default graph up front, so each graph is a [LazyGraph] that is prepared on first read.
     */
    private inner class InferenceDatasetGraph : org.apache.jena.sparql.core.DatasetGraphCollection(),
        org.apache.jena.sparql.core.TransactionalNotSupportedMixin {
        private val store = dataset.asDatasetGraph()
        private val defaultView = LazyGraph { readModel(DEFAULT_GRAPH_KEY, dataset.defaultModel).graph }
        private val namedViews = HashMap<Node, Graph>()

        override fun listGraphNodes(): Iterator<Node> = store.listGraphNodes().asSequence().toList().iterator()
        override fun getDefaultGraph(): Graph = defaultView
        override fun getGraph(graphNode: Node): Graph {
            if (org.apache.jena.sparql.core.Quad.isDefaultGraph(graphNode)) return defaultView
            if (!graphNode.isURI) return org.apache.jena.graph.Graph.emptyGraph
            return namedViews.getOrPut(graphNode) { LazyGraph { readModel(graphNode.uri, dataset.getNamedModel(graphNode.uri)).graph } }
        }
        override fun addGraph(graphName: Node, graph: Graph) = throw UnsupportedOperationException("Inference views are read-only")
        override fun removeGraph(graphName: Node) = throw UnsupportedOperationException("Inference views are read-only")
        override fun prefixes(): org.apache.jena.riot.system.PrefixMap = store.prefixes()
        override fun supportsTransactions(): Boolean = false
        override fun supportsTransactionAbort(): Boolean = false
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

    /**
     * Timed SELECT with initial bindings, applied with Jena's `substitution`. The query is first checked against the
     * initial-bindings contract shared by every provider ([com.geoknoesis.kastor.rdf.sparql.internal.SparqlInitialBindings]):
     * a query that is not a SELECT, that assigns a bound variable (`BIND(... AS ?v)`, `(expr AS ?v)`, `VALUES ?v`), or
     * that uses it inside a sub-select that does not project it is rejected with [IllegalArgumentException], as on
     * the RDF4J provider and the SPARQL endpoint adapter. So is a literal or triple term bound to a variable that the
     * query uses as a predicate or as the name of a GRAPH or SERVICE, where only an IRI is legal.
     */
    override fun <T> withSelectRows(query: SparqlSelect, bindings: Map<String, RdfTerm>, timeout: java.time.Duration,
        consume: (Sequence<BindingSet>) -> T): T {
        // Outside queryOperation: a query the contract rejects is the caller's error (IllegalArgumentException).
        com.geoknoesis.kastor.rdf.sparql.internal.SparqlInitialBindings.validate(
            query.sparql,
            bindings.keys,
            bindings.filterValues { it is com.geoknoesis.kastor.rdf.Literal || it is TripleTerm }.keys,
        )
        return withSelectRowsSubstituted(query, bindings, timeout, consume)
    }

    private fun <T> withSelectRowsSubstituted(query: SparqlSelect, bindings: Map<String, RdfTerm>, timeout: java.time.Duration,
        consume: (Sequence<BindingSet>) -> T): T = withRead {
        val exec = queryOperation(query.sparql) {
            val initial = org.apache.jena.query.QuerySolutionMap()
            bindings.forEach { (name, term) -> initial.add(name, ModelFactory.createDefaultModel().asRDFNode(JenaTerms.toJenaNode(term))) }
            QueryExecution.dataset(queryDataset()).query(query.sparql).substitution(initial)
                .timeout(timeout.toMillis().coerceAtLeast(1), java.util.concurrent.TimeUnit.MILLISECONDS).build()
        }
        // The deadline also stops inference work that Jena's own timeout cannot interrupt (see InferenceCancellation).
        val deadline = System.nanoTime() + timeout.toNanos().coerceAtLeast(1)
        exec.use { consumeRows(it, query.sparql, deadline, consume) }
    }

    private fun <T> consumeRows(exec: QueryExecution, sparql: String, consume: (Sequence<BindingSet>) -> T): T =
        consumeRows(exec, sparql, null, consume)

    private fun <T> consumeRows(exec: QueryExecution, sparql: String, deadline: Long?, consume: (Sequence<BindingSet>) -> T): T {
        val results = queryOperation(sparql) { withDeadline(deadline) { exec.execSelect() } }
        val rows = results.asSequence().map { row ->
            MapBindingSet(row.varNames().asSequence().associateWith { JenaTerms.fromNode(row.get(it)) }) as BindingSet
        }
        return consume(rows.guardedBy(sparql, deadline))
    }

    private inline fun <T> withDeadline(deadline: Long?, crossinline block: () -> T): T =
        if (deadline == null) block() else InferenceCancellation.withDeadline(deadline) { block() }

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
            synchronized(viewLock) { currentView = null }
            // Retire every view (a view still used by another thread's read stops when that read ends), then wait
            // for the workers so that no read transaction outlives close().
            val views = snapshotOf(openViews)
            views.forEach { it.retire() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CLOSE_WAIT_SECONDS)
            for (view in views) {
                if (view.awaitStopped(TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1))) openViews.remove(view)
            }
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
    private fun <T> Sequence<T>.guardedBy(query: String, deadline: Long? = null): Sequence<T> {
        val source = this
        return Sequence {
            val iterator = queryOperation(query) { withDeadline(deadline) { source.iterator() } }
            object : Iterator<T> {
                override fun hasNext(): Boolean = queryOperation(query) { withDeadline(deadline) { iterator.hasNext() } }
                override fun next(): T = queryOperation(query) { withDeadline(deadline) { iterator.next() } }
            }
        }.constrainOnce()
    }
}

/** Copy of a concurrently modified collection that tolerates elements removed while copying (unlike `toList()`). */
internal fun <T> snapshotOf(collection: Collection<T>): List<T> = ArrayList(collection)
