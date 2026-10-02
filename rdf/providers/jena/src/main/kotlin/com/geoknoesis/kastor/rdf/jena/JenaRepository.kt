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
 *   step they belong to.
 *
 * **Poisoned views.** A step that was cancelled after it started, or that failed, may leave the shared goal tables
 * half-updated, so the snapshot's view is then *poisoned*:
 * - it is never handed to another read transaction; later readers get a fresh view of the snapshot;
 * - every further step on it fails with [RdfInferenceException], whoever the reader is: an iterator that was open
 *   on the view delivers the results it had already fetched and then fails, it never ends as if the results were
 *   complete. (The reader whose step was cancelled gets the cancellation itself, as for any query timeout.)
 * - a read transaction that held the view switches to a fresh view of the same snapshot for its *next* reads (of any
 *   graph); the iterators it still has open on the poisoned view fail as described above. Retrying the read is safe.
 *
 * A step cancelled while it was still queued never ran and does not poison the view.
 *
 * Named graphs are prepared lazily, when a read first touches them. A view is released when a newer snapshot
 * replaces it (after commits) and no read transaction uses it any more, after the view idle timeout (default
 * [DEFAULT_VIEW_IDLE_TIMEOUT]) without readers (one cancellable idle check per view), or when the repository is
 * closed. A reader whose snapshot cannot be identified (a commit raced with its `begin`) gets a private lazy view;
 * inside a write transaction the view is private too, so uncommitted changes are visible. Private views are kept for
 * the transaction and, in a write transaction, rebuilt after its next write.
 *
 * **Closing.** [close] retires every view and waits up to 10 seconds for the read transactions that still hold
 * one, so that no worker's read transaction outlives the store. Readers still running at that limit are named in a
 * WARN log entry; their views are then stopped and their further reads fail with [RdfRepositoryException]
 * ([RdfErrorCode.REPOSITORY_CLOSED]). Operations started after [close] fail with [IllegalStateException]
 * ("Repository is closed").
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

    /** Source of modification stamps (see [modificationStamp]): every value is handed out for one state only. */
    private val stamps = AtomicLong()

    /** Guards [stampedSnapshot] and [snapshotStamp]. */
    private val stampLock = Any()

    /** The committed snapshot [snapshotStamp] was issued for; null after a write transaction of this repository ended. */
    private var stampedSnapshot: Snapshot? = null
    private var snapshotStamp = 0L

    /** Stamp of the calling thread's write transaction; renewed by each of its writes. */
    private val writeStamp = ThreadLocal<Long?>()

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

    /** Test seams of the inference views. */
    internal val hooks = JenaInferenceHooks()

    /** Transaction-private inference models built so far (diagnostic). */
    private val privateViewsBuilt = AtomicLong()

    /**
     * Private inference models of this thread's transaction, by graph key (see [privateView]). Dropped when the
     * transaction ends and, in a write transaction, by every write.
     */
    private val privateViews = ThreadLocal<HashMap<String, Model>?>()

    internal fun <T> withRead(block: () -> T): T = inTransaction(ReadWrite.READ, block)

    internal fun <T> withWrite(block: () -> T): T = inTransaction(ReadWrite.WRITE, block)

    private fun <T> inTransaction(mode: ReadWrite, block: () -> T): T {
        check(!closed.get()) { "Repository is closed" }
        if (dataset.isInTransaction) {
            check(mode != ReadWrite.WRITE || dataset.transactionMode() == ReadWrite.WRITE) { "Cannot write inside a read transaction" }
            if (mode != ReadWrite.WRITE) return block()
            // Every write of a transaction goes through a nested withWrite: the private inference models built
            // before it are stale afterwards, and the transaction's modification stamp changes.
            privateViews.remove()
            try {
                return block()
            } finally {
                privateViews.remove()
                writeStamp.set(stamps.incrementAndGet())
            }
        }
        val before = generation.get()
        dataset.begin(mode)
        try {
            if (mode == ReadWrite.READ) readSnapshot.set(snapshotAfterBegin(before)) else writeStamp.set(stamps.incrementAndGet())
            val result = block()
            if (mode == ReadWrite.WRITE) commit()
            return result
        } catch (e: Throwable) {
            if (mode == ReadWrite.WRITE) dataset.abort()
            throw e
        } finally {
            if (mode == ReadWrite.WRITE) {
                writeStamp.remove()
                // Committed or rolled back: the next stamp read of any reader gets a new value.
                synchronized(stampLock) { stampedSnapshot = null }
            }
            readSnapshot.remove()
            privateViews.remove()
            transactionView.get()?.let { transactionView.remove(); it.release() }
            try {
                dataset.end()
            } catch (e: RuntimeException) {
                // close() gave up waiting for this transaction and closed the store under it: nothing left to end.
                if (!closed.get()) throw e
            }
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
        if (tdb2) {
            dataset.commit()
            if (inference) retireCurrentView()
            return
        }
        generation.incrementAndGet()
        try {
            dataset.commit()
        } finally {
            generation.incrementAndGet()
            if (inference) retireCurrentView()
        }
    }

    /**
     * Modification stamp of the store, as seen by the calling thread (see `VersionedRdfGraph` for the contract; read
     * it before the content it describes). Every graph handle of this repository reports this value.
     *
     * The stamp is a function of the **committed state the caller reads**, not of the wall-clock moment:
     * - outside a transaction and in a read transaction it identifies the snapshot the read sees: the TDB2 data
     *   version of the storage (shared by every repository and every other TDB2 client of the location, so a commit
     *   through another instance changes it), or the commit generation of an in-memory store. A read transaction
     *   that is older than a commit therefore keeps reporting a value different from the one readers of the newer
     *   content get;
     * - inside a write transaction it is private to that transaction and changes with each of its writes, so
     *   uncommitted content is never confused with committed content, nor with the content after a rollback;
     * - a commit **and a rollback** through this repository always change it (also when the content is the same
     *   afterwards), and so does every write path, since all of them run in a write transaction: graph edits,
     *   `removeGraph`, `clear`, `update`, dataset loads.
     *
     * Values come from one increasing counter per repository and are never reused. Two readers of the same snapshot
     * get the same value as long as no reader of another snapshot asks in between (a new value is then issued, which
     * only costs consumers a reload). Reading it costs one read transaction when the caller has none open.
     *
     * A stamp is claimed because every write to the store goes through this class: the dataset is created by the
     * factory functions and never handed out for writing. Views over a caller's own Jena `Model` (`JenaBridge`)
     * have no stamp.
     */
    internal fun modificationStamp(): Long = withRead {
        if (dataset.transactionMode() == ReadWrite.WRITE) return@withRead writeStamp.get() ?: stamps.incrementAndGet()
        // Unprovable snapshot (a commit raced with this transaction's begin): a value nobody else gets.
        val snapshot = readSnapshot.get() ?: return@withRead stamps.incrementAndGet()
        synchronized(stampLock) {
            val stamped = stampedSnapshot
            if (stamped == null || !stamped.sameAs(snapshot)) {
                stampedSnapshot = snapshot
                snapshotStamp = stamps.incrementAndGet()
            }
            snapshotStamp
        }
    }

    private fun retireCurrentView() {
        val view = synchronized(viewLock) { currentView.also { currentView = null } }
        view?.retire()
    }

    /**
     * Read view of [model] (identified by [graphKey]). Must be called inside a transaction.
     * Inference views are shared per snapshot of the current read transaction and prepared per graph on first use;
     * inside a write transaction a private view is used so uncommitted changes are visible.
     *
     * A read transaction whose shared view was poisoned (see the class documentation) lets go of it here and
     * continues on a fresh view of its snapshot; whatever it still has open on the poisoned view fails on its
     * next step.
     */
    internal fun readModel(graphKey: String, model: Model): Model {
        if (!inference) return model
        if (dataset.transactionMode() == ReadWrite.WRITE) return privateView(graphKey, model)
        // Unprovable snapshot: a private lazy view, confined to this thread and never shared.
        val snapshot = readSnapshot.get() ?: return privateView(graphKey, model)
        var view = transactionView.get()
        if (view != null && view.poisoned) {
            transactionView.remove()
            view.release()
            view = null
        }
        if (view == null) {
            view = acquireView(snapshot) ?: return privateView(graphKey, model)
            transactionView.set(view)
        }
        return ModelFactory.createModelForGraph(view.graph(graphKey))
    }

    /**
     * Inference model private to the current thread's transaction. It is built once per graph and reused by the
     * following reads of the transaction; a write transaction drops it at every write (see [inTransaction]), so the
     * next read sees the uncommitted change.
     */
    private fun privateView(graphKey: String, model: Model): Model {
        val cache = privateViews.get() ?: HashMap<String, Model>().also { privateViews.set(it) }
        return cache.getOrPut(graphKey) {
            privateViewsBuilt.incrementAndGet()
            ModelFactory.createInfModel(
                org.apache.jena.reasoner.rulesys.RDFSRuleReasonerFactory.theInstance().create(null),
                ModelFactory.createModelForGraph(CancellableGraph(model.graph, baseReads) { hooks.onStoreRead() }),
            )
        }
    }

    /** The shared view of [snapshot], acquired for the calling transaction; null when none can be opened. */
    private fun acquireView(snapshot: Snapshot): SnapshotView? {
        currentView?.takeIf { it.snapshot.sameAs(snapshot) && it.tryAcquire() }?.let { return it }
        // Opened outside viewLock: starting the worker and beginning its read transaction may block, and commits
        // (which retire the current view) must not wait for it. Readers racing for the same snapshot may each open
        // a view; all but the first to publish its view discard theirs.
        val opened = openView(snapshot) ?: return null
        var replaced: SnapshotView? = null
        val view = synchronized(viewLock) {
            val existing = currentView
            if (existing != null && existing.snapshot.sameAs(snapshot) && existing.tryAcquire()) {
                existing
            } else if (!opened.tryAcquire()) {
                null // close() retired it in the meantime
            } else {
                // Never replace the view of a newer snapshot with one opened for an older reader: that one stays
                // private to its reader and is released when the reader's transaction ends.
                if (existing == null || !existing.usable() || snapshot.newerThan(existing.snapshot)) {
                    currentView = opened
                    replaced = existing
                } else {
                    opened.retire()
                }
                opened
            }
        }
        if (view !== opened) opened.retire() // no holders: stops at once
        replaced?.retire()
        return view
    }

    private fun forgetView(view: SnapshotView) {
        synchronized(viewLock) { if (currentView === view) currentView = null }
    }

    /** Store triples read by inference views so far (diagnostic: views must stream, not drain the store). */
    internal fun inferenceBaseReads(): Long = baseReads.get()

    /** Transaction-private inference models built so far (diagnostic: they are cached until the next write). */
    internal fun privateInferenceViewsBuilt(): Long = privateViewsBuilt.get()

    /** Identity of the shared inference view of the newest snapshot, if any (diagnostic). */
    internal fun currentInferenceView(): Any? = currentView

    /** Steps waiting for the worker of the current shared view (diagnostic). */
    internal fun queuedInferenceSteps(): Int = currentView?.queuedSteps() ?: 0

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
     * a newer snapshot replaces it, when it is poisoned by a cancelled or failed step, after the view idle timeout
     * without holders, or when the repository closes. A retired view stops (its worker ends the read transaction and
     * exits) once it has no holders; [abandon] stops it under its holders when [close] gives up waiting for them.
     */
    private inner class SnapshotView(val snapshot: Snapshot, private val worker: InferenceWorker) : InferenceExecutor {
        private val graphs = java.util.concurrent.ConcurrentHashMap<String, SharedInferenceGraph>()
        private var holders = 0
        private var retired = false
        private var stopped = false
        private var idleSince = 0L

        /** The single pending idle check of this view, if any. */
        private var idleCheck: java.util.concurrent.ScheduledFuture<*>? = null

        /** Released when the view has stopped (its worker was told to end the read transaction and exit). */
        private val stoppedSignal = java.util.concurrent.CountDownLatch(1)

        @Volatile var poisoned = false
            private set

        /** Set when [close] stopped this view under readers that were still holding it. */
        @Volatile private var abandoned = false

        fun usable(): Boolean = !poisoned && synchronized(this) { !retired && !stopped }

        fun tryAcquire(): Boolean = synchronized(this) {
            if (retired || stopped || poisoned) false else { holders++; true }
        }

        fun release() = synchronized(this) {
            holders--
            if (holders == 0) {
                idleSince = hooks.clock()
                stopIfUnused()
                // At most one check per view: a pending one re-arms itself for the time that is left when it fires.
                if (!retired && idleCheck == null) scheduleIdleCheck(viewIdleNanos)
            }
        }

        fun retire() = synchronized(this) {
            retired = true
            idleCheck?.cancel(false)
            idleCheck = null
            stopIfUnused()
        }

        private fun scheduleIdleCheck(delayNanos: Long) {
            idleCheck = hooks.schedule(delayNanos, Runnable { idleCheckFired() })
        }

        private fun idleCheckFired() {
            val idle = synchronized(this) {
                idleCheck = null
                if (retired || holders > 0) {
                    false // in use (the release that makes it idle schedules the next check) or already retired
                } else {
                    val left = viewIdleNanos - (hooks.clock() - idleSince)
                    if (left > 0) scheduleIdleCheck(left)
                    left <= 0
                }
            }
            if (!idle) return
            forgetView(this)
            // No longer reachable by new readers; a reader that acquired it just before keeps it until it is done.
            retire()
        }

        private fun stopIfUnused() {
            if (retired && holders == 0) stop()
        }

        private fun stop() {
            if (stopped) return
            stopped = true
            graphs.clear()
            worker.shutdown {
                try { if (dataset.isInTransaction) dataset.end() } finally { openViews.remove(this) }
            }
            stoppedSignal.countDown()
        }

        /** Stops the view although read transactions still hold it; their further steps fail with "closed". */
        fun abandon() = synchronized(this) {
            abandoned = true
            retired = true
            stop()
        }

        /**
         * Waits until [deadlineNanos] (a [System.nanoTime] value) for the view to stop, that is for its last holder
         * to release it, and then for its worker thread to exit. True when the worker has exited.
         */
        fun awaitStopped(deadlineNanos: Long): Boolean {
            fun leftMillis() = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()).coerceAtLeast(1)
            if (!stoppedSignal.await(leftMillis(), TimeUnit.MILLISECONDS)) return false
            return worker.awaitTermination(leftMillis())
        }

        /** What still keeps this view running, for the warning of [close]. */
        fun describe(): String = synchronized(this) {
            val readers = if (holders == 1) "1 reader" else "$holders readers"
            "inference view of snapshot ${snapshot.version} ($readers, ${worker.queuedSteps()} queued step(s))"
        }

        fun queuedSteps(): Int = worker.queuedSteps()

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
                .bind(CancellableGraph(base, baseReads) { hooks.onStoreRead() })
            inf.prepare()
            return SharedInferenceGraph(inf, this)
        }

        /**
         * Runs [block] as a step on the worker. Fails without running it when the view is poisoned or was stopped by
         * [close]; the check is repeated on the worker because a step may wait behind the one that poisons the view.
         */
        override fun <T> call(block: () -> T): T {
            checkUsable()
            return worker.call(onBroken = ::poison) {
                checkUsable()
                block()
            }
        }

        private fun checkUsable() {
            if (abandoned) throw RdfRepositoryException("Repository is closed", RdfErrorCode.REPOSITORY_CLOSED)
            if (poisoned) {
                throw RdfInferenceException(
                    "The shared inference view of this snapshot was invalidated because a reasoning step of one of " +
                        "its readers was cancelled or failed half-way; results read from it could be incomplete. " +
                        "Retry the read: it is served by a fresh view.",
                )
            }
        }

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
            hooks.onOpenView()
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

    private val defaultGraphView by lazy { JenaRepositoryGraph(dataset.defaultModel, this, DEFAULT_GRAPH_KEY) }
    override val defaultGraph: RdfGraph get() = withRead { defaultGraphView }
    override fun getGraph(name: Iri): RdfGraph = withRead { JenaRepositoryGraph(dataset.getNamedModel(name.value), this, name.value) }
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

    /**
     * Describes the resources of a `DESCRIBE` query **from the default graph of the query's dataset**: the store's
     * default graph or, when the query has `FROM` / `FROM NAMED` clauses, the default graph they declare (the merge of
     * the `FROM` graphs; empty with only `FROM NAMED`). This is the contract of every provider (see the RDF4J
     * provider and `Dataset.describe`). Named graphs are read by the `WHERE` clause inside `GRAPH`, never by the
     * description itself.
     *
     * Jena's own `execDescribe` is not used: its describe handler reads the store's default graph **and every named
     * graph of the store**, whatever the query's dataset. The query is evaluated as Jena does (the `WHERE` clause with
     * its solution modifiers yields the values of the described variables, the IRIs of the `DESCRIBE` clause are added)
     * and each resource is described as Jena's handler does (its statements and the closure over blank nodes), but
     * in that one graph. On an inference repository that graph is the inference view, so entailed triples are included.
     */
    override fun describe(query: SparqlDescribe): Sequence<RdfTriple> = withRead {
        queryOperation(query.sparql) {
            val parsed = QueryFactory.create(query.sparql)
            if (!parsed.isDescribeType) throw IllegalArgumentException("Not a DESCRIBE query")
            val dataset = queryDataset()
            val resources = LinkedHashSet<Node>()
            if (parsed.queryPattern != null) {
                // The same query as a SELECT of the described variables (DESCRIBE * selects every variable).
                val select = QueryFactory.create(query.sparql).apply { setQuerySelectType() }
                QueryExecutionFactory.create(select, dataset).use { exec ->
                    val rows = exec.execSelect()
                    val names = rows.resultVars
                    rows.forEachRemaining { row -> names.forEach { name -> row.get(name)?.asNode()?.let(resources::add) } }
                }
            }
            parsed.resultURIs?.let(resources::addAll)
            val source = ModelFactory.createModelForGraph(
                if (parsed.hasDatasetDescription()) {
                    org.apache.jena.sparql.core.DynamicDatasets
                        .dynamicDataset(parsed.datasetDescription, dataset.asDatasetGraph(), false).defaultGraph
                } else {
                    dataset.asDatasetGraph().defaultGraph
                },
            )
            val described = ModelFactory.createDefaultModel()
            try {
                for (node in resources) {
                    if (!node.isURI && !node.isBlank) continue
                    org.apache.jena.sparql.util.Closure.closure(source.asRDFNode(node).asResource(), false, described)
                }
                JenaGraph(described).getTriples().asSequence()
            } finally {
                described.close()
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
            // for those reads and for the workers, so that no read transaction outlives close().
            val views = snapshotOf(openViews)
            views.forEach { it.retire() }
            hooks.onCloseWaiting()
            val waitNanos = hooks.closeWaitNanos
            val deadline = System.nanoTime() + waitNanos
            val inUse = views.filterNot { awaitAndForget(it, deadline) }
            if (inUse.isNotEmpty()) {
                // Described before they are stopped, so the entry names the readers that were still there.
                val description = inUse.joinToString("; ") { it.describe() }
                hooks.warn(
                    "Closing Jena repository '$variantId' although ${inUse.size} inference view(s) were still in use after " +
                        "${TimeUnit.NANOSECONDS.toMillis(waitNanos)} ms: $description. " +
                        "Their views are stopped now; further reads of those readers fail with 'Repository is closed'.",
                )
                inUse.forEach { it.abandon() }
                val grace = System.nanoTime() + hooks.closeGraceNanos
                val running = inUse.filterNot { awaitAndForget(it, grace) }
                if (running.isNotEmpty()) {
                    hooks.warn(
                        "Jena repository '$variantId': ${running.size} inference worker(s) are still running a reasoning " +
                            "step and hold a read transaction while the store is closed: ${running.joinToString("; ") { it.describe() }}",
                    )
                }
            }
            dataset.close()
        }
    }

    /** Waits for [view]'s worker to exit (see [SnapshotView.awaitStopped]) and then stops tracking it. */
    private fun awaitAndForget(view: SnapshotView, deadlineNanos: Long): Boolean =
        view.awaitStopped(deadlineNanos).also { stopped -> if (stopped) openViews.remove(view) }

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
