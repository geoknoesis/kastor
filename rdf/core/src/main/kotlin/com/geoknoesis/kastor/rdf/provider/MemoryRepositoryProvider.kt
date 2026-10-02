package com.geoknoesis.kastor.rdf.provider

import com.geoknoesis.kastor.rdf.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Memory repository provider implementation.
 *
 * Provides a minimal in-memory RDF repository intended for graph-only testing
 * and development. It does **not** support:
 * - SPARQL queries (`select`, `ask`, `construct`, `describe`, `update`)
 * - RDF parsing or serialization
 *
 * For full functionality (SPARQL, parsing, serialization), depend on
 * `:rdf:jena` or `:rdf:rdf4j` and use [Rdf.memory] / [Rdf.persistent], which
 * pick a real provider via `ServiceLoader`. This provider is registered
 * unconditionally so that `Rdf.repository { providerId = "memory" }` always
 * resolves, but it is never selected by the `Rdf.memory()` / `Rdf.persistent()`
 * factory methods.
 */
class MemoryRepositoryProvider : RdfProvider {

    override val id: String = "memory"

    override val name: String = "Memory Repository"

    override val version: String = "1.0.0"

    /** Graph-only (no parsing, serialization or SPARQL): try every other provider first. */
    override val priority: Int = -100

    override fun variants(): List<RdfVariant> {
        return listOf(RdfVariant("memory", "In-memory store"))
    }

    override fun createRepository(variantId: String, config: RdfConfig): RdfRepository {
        if (variantId != "memory") {
            throw IllegalArgumentException("Unsupported memory variant: $variantId")
        }
        return MemoryRepository(config)
    }

    override fun getCapabilities(variantId: String?): ProviderCapabilities {
        // Graph-only provider with serializable, undo-log backed transactions. Terms are stored as
        // given, so RDF 1.2 triple terms (object position) round-trip.
        return ProviderCapabilities(
            rdfVersion = "1.2",
            supportsTripleTerms = true,
            supportsInference = false,
            supportsTransactions = true,
            supportsNamedGraphs = true,
            supportsUpdates = false,
            supportsRdfStar = true,
            // LangString terms (including base direction) are stored as given.
            supportsBaseDirection = true,
            maxMemoryUsage = Long.MAX_VALUE
        )
    }
}

/**
 * Simple in-memory RDF repository implementation.
 *
 * - **Concurrency:** one read/write lock guards all graphs. Reads and [readTransaction] blocks run
 *   concurrently; writes and [transaction] blocks are exclusive.
 * - **Transactions** keep an undo log, so a rollback costs the size of the change, not of the repository.
 * - **Graph handles** from [getGraph] / [editGraph] / [createGraph] are live views of a graph name: they
 *   stay valid across [clear] and [removeGraph], and reading a graph never creates it.
 * - **Graph existence:** a graph created with [createGraph] is reported by [hasGraph] / [listGraphs] even while
 *   empty, until [removeGraph] or [clear]. A graph that only came into existence by writing to it is reported
 *   while it holds triples.
 * - **Transactions hold the lock for the whole block:** [transaction] holds the write lock and [readTransaction]
 *   the read lock until the block returns. Work handed to another thread that touches this repository blocks
 *   until then, so waiting for such work from inside a block deadlocks.
 * - **Rollback** runs every undo action even if one fails; undo failures are attached to the original
 *   exception as suppressed exceptions, and the original exception is rethrown.
 * - **Modification stamps** ([VersionedRdfGraph]) respect transaction isolation: the thread inside a [transaction]
 *   reads transaction-private stamps for the graphs it has changed (a new one after each of its writes), every other
 *   thread keeps reading the last committed stamp until the commit completes, a commit gives every changed graph a
 *   new committed stamp, and a rollback leaves the committed stamps as they were. Stamp reads never take the lock.
 *
 * **Resource Management:**
 * - Always use [use] or call [close] explicitly
 */
class MemoryRepository(private val config: RdfConfig) : RdfRepository {
    private val lock = ReentrantReadWriteLock()
    private val graphs = linkedMapOf<Iri, MemoryGraph>()
    /** Graphs created explicitly with [createGraph]; listed even while empty. Guarded by [lock]. */
    private val createdGraphs = HashSet<Iri>()
    @Volatile private var closed = false
    private val transactionMode = ThreadLocal<Boolean?>()
    /** Undo actions of the active write transaction; only touched while holding the write lock. */
    private var undoLog: MutableList<() -> Unit>? = null
    /**
     * Source of the modification stamps of every graph of this repository, so a named graph's stamp never repeats a
     * value when its backing graph is removed, re-created or restored by a rollback.
     */
    private val stamps = java.util.concurrent.atomic.AtomicLong()
    /**
     * The committed stamp of the default graph ([DEFAULT_GRAPH_KEY]) and of every named graph that ever had a backing
     * graph (its [Iri]): the stamp of the content every thread outside the open write transaction reads. Written
     * while holding the write lock, each time with a fresh value of [stamps] - by a write outside a transaction, and
     * by the commit of a transaction for the graphs it changed; read without a lock, so a stamp read never waits for
     * a writer.
     */
    private val committedStamps = java.util.concurrent.ConcurrentHashMap<Any, Long>()
    /** The thread inside the open write transaction, or null: the only thread that reads [transactionStamps]. */
    @Volatile private var transactionThread: Thread? = null
    /**
     * The transaction-private stamps of the graphs the open write transaction has changed, renewed by each of its
     * writes. Only touched by [transactionThread], while it holds the write lock; no other thread ever sees a value
     * of it: a commit publishes fresh committed stamps for its keys, a rollback drops it.
     */
    private var transactionStamps: HashMap<Any, Long>? = null

    // Access is checked before taking a lock: a write attempted while holding the read lock
    // (inside readTransaction) must fail rather than deadlock on lock upgrade.
    private fun checkAccess(write: Boolean) {
        check(!closed) { "Repository is closed" }
        check(!write || transactionMode.get() != false) { "Cannot write inside a read transaction" }
    }

    internal fun recordUndo(undo: () -> Unit) {
        undoLog?.add(undo)
    }

    /**
     * Gives the graph [key] a new stamp; called while holding the write lock, for every change of its content
     * (including the undo actions of a rollback). Inside a write transaction the stamp is private to it.
     */
    private fun touched(key: Any) {
        val fresh = stamps.incrementAndGet()
        val private = transactionStamps
        if (private != null) private[key] = fresh else committedStamps[key] = fresh
    }

    /**
     * The stamp of the content the calling thread would read from the graph [key] right now: the private stamp of
     * its own open write transaction if that changed the graph, otherwise the committed stamp. Takes no lock.
     */
    private fun stampOf(key: Any): Long {
        checkAccess(false)
        if (transactionThread === Thread.currentThread()) transactionStamps?.get(key)?.let { return it }
        return committedStamps[key] ?: 0L
    }

    /** A backing graph for [name] (null: the default graph); its stamps are kept by the repository under its name. */
    private fun newGraph(name: Iri?): MemoryGraph {
        val key: Any = name ?: DEFAULT_GRAPH_KEY
        touched(key)
        return MemoryGraph(emptyList(), lock, ::checkAccess, ::recordUndo, { touched(key) }, { stampOf(key) })
    }

    /** Records that [name] lost its backing graph; called while holding the write lock. */
    private fun markAbsent(name: Iri) = touched(name)

    /** Puts [graph] back as [name] (rollback) with a fresh stamp; called while holding the write lock. */
    private fun restore(name: Iri, graph: MemoryGraph) {
        graphs[name] = graph
        graph.touch()
    }

    private val default = newGraph(null)

    override val defaultGraph: RdfGraph get() { checkAccess(false); return default }
    override fun getGraph(name: Iri): RdfGraph { checkAccess(false); return NamedGraphView(name) }
    private fun existsUnlocked(name: Iri): Boolean = name in createdGraphs || (graphs[name]?.size() ?: 0) > 0
    override fun hasGraph(name: Iri): Boolean = lock.read { checkAccess(false); existsUnlocked(name) }
    override fun listGraphs(): List<Iri> = lock.read { checkAccess(false); graphs.keys.filter(::existsUnlocked) }
    override fun createGraph(name: Iri): RdfGraph {
        checkAccess(true)
        lock.write {
            if (name !in graphs) {
                graphs[name] = newGraph(name)
                recordUndo { lock.write { graphs.remove(name); markAbsent(name) } }
            }
            if (createdGraphs.add(name)) recordUndo { lock.write { createdGraphs.remove(name) } }
        }
        return NamedGraphView(name)
    }
    override fun removeGraph(name: Iri): Boolean {
        checkAccess(true)
        return lock.write {
            val wasCreated = createdGraphs.remove(name)
            val graph = graphs.remove(name)
            if (graph == null && !wasCreated) return@write false
            recordUndo {
                lock.write {
                    if (graph != null) restore(name, graph)
                    if (wasCreated) createdGraphs.add(name)
                }
            }
            val changed = graph?.clear() ?: false
            markAbsent(name)
            changed || wasCreated
        }
    }
    override fun editDefaultGraph(): MutableRdfGraph { checkAccess(false); return default }
    override fun editGraph(name: Iri): MutableRdfGraph { checkAccess(false); return NamedGraphView(name) }
    override fun select(query: SparqlSelect): SparqlQueryResult = throw UnsupportedOperationException("Memory provider has no SPARQL engine")
    override fun ask(query: SparqlAsk): Boolean = throw UnsupportedOperationException("Memory provider has no SPARQL engine")
    override fun construct(query: SparqlConstruct): Sequence<RdfTriple> = throw UnsupportedOperationException("Memory provider has no SPARQL engine")
    override fun describe(query: SparqlDescribe): Sequence<RdfTriple> = throw UnsupportedOperationException("Memory provider has no SPARQL engine")
    override fun update(query: UpdateQuery): Unit = throw UnsupportedOperationException("Memory provider has no SPARQL engine")

    override fun transaction(operations: RdfRepository.() -> Unit) {
        checkAccess(true)
        if (transactionMode.get() != null) { operations(this); return } // nested: join the outer transaction
        lock.write {
            checkAccess(true)
            val log = ArrayList<() -> Unit>()
            val private = HashMap<Any, Long>()
            undoLog = log
            transactionStamps = private
            transactionThread = Thread.currentThread()
            transactionMode.set(true)
            // True when the content other threads will read may differ from what they read before the transaction.
            var publish = true
            try {
                operations(this)
            } catch (e: Throwable) {
                undoLog = null // undo actions must not journal themselves
                publish = false
                for (i in log.indices.reversed()) {
                    // Keep rolling back and keep the caller's failure as the primary exception.
                    try { log[i]() } catch (undoFailure: Throwable) {
                        // The content may not be what it was: the changed graphs get new committed stamps.
                        publish = true
                        if (undoFailure !== e) e.addSuppressed(undoFailure)
                    }
                }
                throw e
            } finally {
                undoLog = null
                // Still holding the write lock: the committed stamps change before the content becomes visible. A
                // complete rollback publishes nothing: the content is what the committed stamps already stand for.
                if (publish) private.keys.forEach { committedStamps[it] = stamps.incrementAndGet() }
                transactionStamps = null
                transactionThread = null
                transactionMode.remove()
            }
        }
    }

    override fun readTransaction(operations: RdfRepository.() -> Unit) {
        checkAccess(false)
        if (transactionMode.get() != null) { operations(this); return }
        lock.read {
            transactionMode.set(false)
            try { operations(this) } finally { transactionMode.remove() }
        }
    }

    override fun clear(): Boolean {
        checkAccess(true)
        return lock.write {
            var changed = default.clear()
            graphs.values.forEach { if (it.clear()) changed = true }
            val removed = LinkedHashMap(graphs)
            val removedCreated = HashSet(createdGraphs)
            if (removedCreated.isNotEmpty()) changed = true
            graphs.clear()
            createdGraphs.clear()
            removed.keys.forEach(::markAbsent)
            recordUndo { lock.write { removed.forEach { (name, graph) -> restore(name, graph) }; createdGraphs.addAll(removedCreated) } }
            changed
        }
    }

    override fun isClosed(): Boolean = closed

    override fun close() {
        check(transactionMode.get() == null) { "Cannot close inside a transaction" }
        lock.write {
            if (!closed) {
                clear(); closed = true
            }
        }
    }

    override fun getCapabilities(): ProviderCapabilities = MemoryRepositoryProvider().getCapabilities("memory")

    /**
     * Live view of a named graph; resolves the backing graph on every call. Its [modificationStamp] is the stamp the
     * repository keeps for the name (stamps are unique across the repository), also while the name has no backing
     * graph. It is read like the stamp of the default graph: without taking the lock, as the calling thread sees the
     * graph (see [VersionedRdfGraph]), failing once the repository is closed.
     */
    private inner class NamedGraphView(private val name: Iri) : MutableRdfGraph, VersionedRdfGraph {
        private val repository: MemoryRepository get() = this@MemoryRepository

        private inline fun <T> reading(block: (MemoryGraph?) -> T): T = lock.read { checkAccess(false); block(graphs[name]) }

        private inline fun <T> writing(create: Boolean, block: (MemoryGraph?) -> T): T {
            checkAccess(true)
            return lock.write {
                block(if (create) graphs.getOrPut(name) { newGraph(name) } else graphs[name])
            }
        }

        override val modificationStamp: Long get() = stampOf(name)

        override fun find(subject: RdfResource?, predicate: Iri?, obj: RdfTerm?): List<RdfTriple> =
            reading { it?.find(subject, predicate, obj) ?: emptyList() }
        override fun hasTriple(triple: RdfTriple): Boolean = reading { it?.hasTriple(triple) ?: false }
        override fun getTriples(): List<RdfTriple> = reading { it?.getTriples() ?: emptyList() }
        override fun getTriplesSequence(): Sequence<RdfTriple> = getTriples().asSequence()
        override fun size(): Int = reading { it?.size() ?: 0 }
        override fun addTriple(triple: RdfTriple) = writing(create = true) { it!!.addTriple(triple) }
        override fun addTriples(triples: Collection<RdfTriple>) = writing(create = true) { it!!.addTriples(triples) }
        override fun removeTriple(triple: RdfTriple): Boolean = writing(create = false) { it?.removeTriple(triple) ?: false }
        override fun removeTriples(triples: Collection<RdfTriple>): Boolean = writing(create = false) { it?.removeTriples(triples) ?: false }
        override fun clear(): Boolean = writing(create = false) { it?.clear() ?: false }

        override fun equals(other: Any?): Boolean =
            other is MemoryRepository.NamedGraphView && other.repository === repository && other.name == name
        override fun hashCode(): Int = name.hashCode()
        override fun toString(): String = "MemoryGraph($name)"
    }

    private companion object {
        /** Key of the default graph in the stamp tables (named graphs are keyed by their [Iri]). */
        val DEFAULT_GRAPH_KEY = Any()
    }
}

/**
 * Insertion ordered graph with subject/predicate/object indexes.
 *
 * Reads return snapshots ([getTriples] / [getTriplesSequence] copy the current triples), so they are
 * safe to iterate while the graph is modified.
 */
class MemoryGraph internal constructor(
    initialTriples: Collection<RdfTriple>,
    private val lock: ReentrantReadWriteLock,
    private val access: (Boolean) -> Unit,
    private val recordUndo: ((() -> Unit) -> Unit)?,
    /** Tells the owning repository that the content changed (it keeps the stamps); `null` for a stand-alone graph. */
    private val onChange: (() -> Unit)? = null,
    /** The stamp the owning repository keeps for this graph, as the calling thread sees it; `null` as [onChange]. */
    private val repositoryStamp: (() -> Long)? = null,
) : MutableRdfGraph, VersionedRdfGraph {
    constructor() : this(emptyList())
    constructor(initialTriples: Collection<RdfTriple>) : this(initialTriples, ReentrantReadWriteLock(), {}, null)

    private val triples = linkedSetOf<RdfTriple>()
    private val subjects = mutableMapOf<RdfResource, MutableSet<RdfTriple>>()
    private val predicates = mutableMapOf<Iri, MutableSet<RdfTriple>>()
    private val objects = mutableMapOf<RdfTerm, MutableSet<RdfTriple>>()
    /** The stamp of a stand-alone graph: moved (under the write lock) by every change of the content. */
    @Volatile private var stamp = 0L
    /**
     * The stamp of the content the calling thread would read right now (see [VersionedRdfGraph]). Read without
     * taking the lock, so it never waits for a write or a transaction of another thread: a cache keyed by the stamp
     * keeps serving the committed content while a long transaction is open. For a graph of a repository, the thread
     * inside a write transaction reads the transaction-private stamp of its latest write to the graph, and every
     * other thread the last committed stamp. It fails with [IllegalStateException] once the owning repository is
     * closed, like a read of the content (a stand-alone graph has no repository and never fails).
     */
    override val modificationStamp: Long get() {
        val ofRepository = repositoryStamp ?: return stamp
        return ofRepository()
    }

    /** Moves the stamp to a new value; called while holding the write lock. */
    internal fun touch() { if (onChange != null) onChange.invoke() else stamp++ }
    init { if (initialTriples.isNotEmpty()) addTriples(initialTriples) }

    private fun addUnlocked(triple: RdfTriple) {
        if (triples.add(triple)) {
            touch()
            subjects.getOrPut(triple.subject) { linkedSetOf() }.add(triple)
            predicates.getOrPut(triple.predicate) { linkedSetOf() }.add(triple)
            objects.getOrPut(triple.obj) { linkedSetOf() }.add(triple)
            recordUndo?.invoke { removeTriple(triple) }
        }
    }

    private fun removeUnlocked(triple: RdfTriple): Boolean {
        if (!triples.remove(triple)) return false
        touch()
        fun <K> remove(index: MutableMap<K, MutableSet<RdfTriple>>, key: K) {
            index[key]?.let { it.remove(triple); if (it.isEmpty()) index.remove(key) }
        }
        remove(subjects, triple.subject); remove(predicates, triple.predicate); remove(objects, triple.obj)
        recordUndo?.invoke { addTriple(triple) }
        return true
    }

    override fun addTriple(triple: RdfTriple) { access(true); lock.write { addUnlocked(triple) } }
    override fun addTriples(triples: Collection<RdfTriple>) { access(true); lock.write { triples.forEach(::addUnlocked) } }
    override fun removeTriple(triple: RdfTriple): Boolean { access(true); return lock.write { removeUnlocked(triple) } }
    override fun removeTriples(triples: Collection<RdfTriple>): Boolean {
        access(true)
        return lock.write {
            var changed = false
            triples.forEach { if (removeUnlocked(it)) changed = true }
            changed
        }
    }
    override fun hasTriple(triple: RdfTriple): Boolean { access(false); return lock.read { triple in triples } }
    override fun getTriples(): List<RdfTriple> { access(false); return lock.read { ArrayList(triples) } }
    override fun getTriplesSequence(): Sequence<RdfTriple> = getTriples().asSequence()
    override fun find(subject: RdfResource?, predicate: Iri?, obj: RdfTerm?): List<RdfTriple> {
        access(false)
        return lock.read {
            val candidates = listOfNotNull(subject?.let { subjects[it].orEmpty() }, predicate?.let { predicates[it].orEmpty() },
                obj?.let { objects[it].orEmpty() }).minByOrNull { it.size } ?: triples
            candidates.filter { (subject == null || it.subject == subject) && (predicate == null || it.predicate == predicate) && (obj == null || it.obj == obj) }
        }
    }
    override fun size(): Int { access(false); return lock.read { triples.size } }
    override fun clear(): Boolean {
        access(true)
        return lock.write {
            if (triples.isEmpty()) return@write false
            val snapshot = triples.toList()
            triples.clear(); subjects.clear(); predicates.clear(); objects.clear()
            touch()
            recordUndo?.invoke { addTriples(snapshot) }
            true
        }
    }
}

/**
 * Simple empty SPARQL query result implementation.
 * Implemented as a singleton object for efficiency.
 */
object EmptySparqlQueryResult : SparqlQueryResult {

    override fun iterator(): Iterator<BindingSet> = emptyList<BindingSet>().iterator()

    override fun count(): Int = 0

    override fun first(): BindingSet? = null

    override fun toList(): List<BindingSet> = emptyList()

    override fun asSequence(): Sequence<BindingSet> = emptySequence()
}
