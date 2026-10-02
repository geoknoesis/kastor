package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.apache.jena.graph.Node
import org.apache.jena.rdf.model.Model

/**
 * Jena-backed [MutableRdfGraph].
 *
 * A standalone graph (no [repository]) operates directly on [model]. A repository-backed graph
 * runs every operation inside a repository transaction: reads see the repository's read view
 * (including RDFS inferences for `*-inference` variants, served from a cached inference model),
 * writes always target the asserted base [model].
 *
 * **Write cost:** outside an explicit `transaction { }`, every [addTriple]/[removeTriple] call is its
 * own write transaction. Use [addTriples]/[removeTriples] (one transaction per call) or wrap many
 * calls in `repository.transaction { }` for bulk changes.
 *
 * **Streaming-write atomicity:** unlike the core default for `addTriples(Sequence)` / `removeTriples(Sequence)`
 * (which commits in chunks), these overrides apply the whole sequence in **one** write transaction: a failure
 * part-way rolls everything back, and the transaction holds all changes until the sequence ends.
 *
 * **Lenient reads** ([lenientRead], used for wrapped foreign models): statements that cannot be converted to
 * Kastor terms (e.g. `xml:lang="en_US"`) are skipped with a logged warning instead of failing the whole read.
 */
internal open class JenaGraph(
    val model: Model,
    private val repository: JenaRepository? = null,
    /** Cache key of this graph inside [repository]: "" for the default graph, else the graph name. */
    private val graphKey: String = "",
    /** Skip statements that are not representable as Kastor terms (with a warning) instead of throwing. */
    private val lenientRead: Boolean = false,
) : MutableRdfGraph {

    /** Runs [block] against this graph's read view (inference view for inference repositories). */
    internal fun <T> read(block: (Model) -> T): T =
        repository?.withRead { block(repository.readModel(graphKey, model)) } ?: block(model)

    internal val isRepositoryBacked: Boolean get() = repository != null

    /** Whether writes through this handle are refused (the union graph of a repository, see [JenaRepository.getGraph]). */
    protected open val readOnly: Boolean get() = false

    /**
     * The Jena model handed to callers of `JenaBridge.getJenaModel` / `getJenaGraph`: [model] itself for a standalone
     * graph. For a repository graph it is a model over an [AccountedStoreGraph], never the store's own model, so that
     * every write made through the Jena API is a write of the repository (see [AccountedStoreGraph]).
     */
    internal val nativeModel: Model by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (repository == null) model
        else org.apache.jena.rdf.model.ModelFactory.createModelForGraph(AccountedStoreGraph(model.graph, repository, graphKey, readOnly))
    }

    private fun <T> write(block: () -> T): T {
        if (readOnly) throw UnsupportedOperationException(UNION_GRAPH_READ_ONLY)
        return if (repository != null) repository.withWrite(graphKey, block) else block()
    }

    override fun addTriple(triple: RdfTriple): Unit = write {
        model.graph.add(JenaTerms.toJenaTriple(triple))
    }

    override fun addTriples(triples: Collection<RdfTriple>): Unit = write { addInCurrentTransaction(triples.iterator()) }

    /** Streams [triples] into a single write transaction. */
    override fun addTriples(triples: Iterable<RdfTriple>): Unit = write { addInCurrentTransaction(triples.iterator()) }

    /** Streams [triples] into a single write transaction without materialising the sequence. */
    override fun addTriples(triples: Sequence<RdfTriple>): Unit = write { addInCurrentTransaction(triples.iterator()) }

    private fun addInCurrentTransaction(triples: Iterator<RdfTriple>) {
        val graph = model.graph
        triples.forEach { graph.add(JenaTerms.toJenaTriple(it)) }
    }

    override fun removeTriple(triple: RdfTriple): Boolean = write { removeInCurrentTransaction(triple) }

    override fun removeTriples(triples: Collection<RdfTriple>): Boolean = write { removeAllInCurrentTransaction(triples.iterator()) }

    /** Streams removals into a single write transaction. */
    override fun removeTriples(triples: Iterable<RdfTriple>): Boolean = write { removeAllInCurrentTransaction(triples.iterator()) }

    /** Streams removals into a single write transaction without materialising the sequence. */
    override fun removeTriples(triples: Sequence<RdfTriple>): Boolean = write { removeAllInCurrentTransaction(triples.iterator()) }

    private fun removeAllInCurrentTransaction(triples: Iterator<RdfTriple>): Boolean {
        var changed = false
        triples.forEach { if (removeInCurrentTransaction(it)) changed = true }
        return changed
    }

    private fun removeInCurrentTransaction(triple: RdfTriple): Boolean {
        val graph = model.graph
        val jenaTriple = JenaTerms.toJenaTriple(triple)
        val exists = graph.contains(jenaTriple)
        if (exists) graph.delete(jenaTriple)
        return exists
    }

    override fun hasTriple(triple: RdfTriple): Boolean = read { it.graph.contains(JenaTerms.toJenaTriple(triple)) }

    override fun getTriples(): List<RdfTriple> = find()

    override fun find(subject: RdfResource?, predicate: Iri?, obj: RdfTerm?): List<RdfTriple> = read { view ->
        val iterator = view.graph.find(
            subject?.let(JenaTerms::toJenaNode) ?: Node.ANY,
            predicate?.let(JenaTerms::toJenaNode) ?: Node.ANY,
            obj?.let(JenaTerms::toJenaNode) ?: Node.ANY,
        )
        try {
            if (!lenientRead) {
                iterator.asSequence().map(JenaTerms::fromJenaTriple).toList()
            } else {
                val result = ArrayList<RdfTriple>()
                var skipped = 0
                var firstProblem: String? = null
                iterator.forEachRemaining { triple ->
                    try {
                        result.add(JenaTerms.fromJenaTriple(triple))
                    } catch (e: IllegalArgumentException) {
                        skipped++
                        if (firstProblem == null) firstProblem = "$triple (${e.message})"
                    }
                }
                if (skipped > 0) {
                    LOG.warn("Skipped {} statement(s) of a wrapped Jena model that are not valid RDF terms for Kastor; first: {}", skipped, firstProblem)
                }
                result
            }
        } finally {
            iterator.close()
        }
    }

    private companion object {
        val LOG: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(JenaGraph::class.java)
    }

    override fun clear(): Boolean = write { val changed = !model.isEmpty; model.removeAll(); changed }

    override fun size(): Int = read { view ->
        if (lenientRead) {
            // Lenient reads skip unrepresentable statements; count exactly what find() returns.
            val iterator = view.graph.find()
            try {
                var count = 0
                iterator.forEachRemaining { triple ->
                    if (runCatching { JenaTerms.fromJenaTriple(triple) }.exceptionOrNull() !is IllegalArgumentException) count++
                }
                count
            } finally {
                iterator.close()
            }
        } else if (view is org.apache.jena.rdf.model.InfModel || view.graph is org.apache.jena.reasoner.InfGraph) {
            // InfModel.size() does not count every entailed statement; count what find() exposes
            // so size() always agrees with getTriples().
            val iterator = view.graph.find()
            try {
                var count = 0
                while (iterator.hasNext()) { iterator.next(); count++ }
                count
            } finally {
                iterator.close()
            }
        } else {
            Math.toIntExact(view.size())
        }
    }
}

/**
 * Handle of a graph of a [JenaRepository] (the default graph, or a named graph).
 *
 * **Identity:** two handles are equal when they belong to the same repository *instance* and name the same graph,
 * however they were obtained (`defaultGraph`, `editDefaultGraph()`, `getGraph(name)`, `editGraph(name)`,
 * `createGraph(name)`), so a handle can be used as a cache key. Handles of two repositories are never equal, also when
 * both are connected to the same TDB2 location.
 *
 * **Modification stamp** ([VersionedRdfGraph]): see [JenaRepository.modificationStamp]. It is the stamp of the
 * store, not of the single graph: a write to any graph of the repository changes the stamp of all its handles. For
 * `*-inference` variants it is the stamp of the underlying store too (entailments are a function of the store).
 */
internal class JenaRepositoryGraph(
    model: Model,
    private val owner: JenaRepository,
    private val key: String,
    override val readOnly: Boolean = false,
) : JenaGraph(model, owner, key), VersionedRdfGraph {

    override val modificationStamp: Long get() = owner.modificationStamp()

    override fun equals(other: Any?): Boolean =
        this === other || (other is JenaRepositoryGraph && other.owner === owner && other.key == key)

    override fun hashCode(): Int = 31 * System.identityHashCode(owner) + key.hashCode()

    override fun toString(): String = "JenaRepositoryGraph(${if (key.isEmpty()) "default graph" else key})"
}

internal const val UNION_GRAPH_READ_ONLY = "The union graph is read-only: write to the graph the triple belongs to"

/**
 * The Jena graph of a [JenaRepository] graph as it is handed to callers (`JenaBridge.getJenaModel`, `getJenaGraph`).
 *
 * The repository's modification stamps, its commit generations and the bookkeeping of its inference views all rely
 * on every write being one of the repository's own write transactions. This graph therefore never exposes the
 * store's graph object: it forwards reads to it and turns **every mutation** into a repository write of [graphKey]:
 * - `add`, `delete`, `remove(s, p, o)` and `clear` (and with them everything Jena builds on those: `Model.add`,
 *   `Model.remove`, `Model.removeAll`, `GraphUtil` bulk operations, `Statement` / `Resource` mutators) run in
 *   [JenaRepository.withWrite]: outside a repository transaction each call is its own write transaction (what Jena's
 *   auto-commit did before), inside `repository.transaction { }` it joins that transaction;
 * - iterators do not support `remove()`;
 * - the transaction handler never begins a transaction on the store directly: `execute` / `calculate` (and so
 *   `Model.executeInTxn` / `calculateInTxn`) run their action in a repository write transaction, while `begin`,
 *   `commit` and `abort` are refused (use `repository.transaction { }`);
 * - `close()` does nothing: the store graph belongs to the repository.
 *
 * It deliberately is **not** a Jena `GraphWrapper` / `WrappedGraph` / `GraphView`: those hand out the graph they
 * wrap or its dataset.
 */
internal class AccountedStoreGraph(
    private val base: org.apache.jena.graph.Graph,
    private val repository: JenaRepository,
    private val graphKey: String,
    private val readOnly: Boolean,
) : org.apache.jena.graph.Graph {

    private fun <T> write(block: () -> T): T {
        if (readOnly) throw UnsupportedOperationException(UNION_GRAPH_READ_ONLY)
        return repository.withWrite(graphKey, block)
    }

    private val transactions = object : org.apache.jena.graph.TransactionHandler {
        override fun transactionsSupported(): Boolean = false
        override fun begin() = refuse("begin")
        override fun abort() = refuse("abort")
        override fun commit() = refuse("commit")
        override fun execute(action: Runnable) = repository.inWriteTransaction { action.run() }
        override fun executeAlways(action: Runnable) = execute(action)
        override fun <T> calculate(action: java.util.function.Supplier<T>): T = repository.inWriteTransaction { action.get() }
        override fun <T> calculateAlways(action: java.util.function.Supplier<T>): T = calculate(action)

        private fun refuse(operation: String): Nothing = throw UnsupportedOperationException(
            "$operation(): transactions of a Kastor repository graph are not started through the Jena API. " +
                "Use repository.transaction { } (or Model.executeInTxn / calculateInTxn).",
        )
    }

    override fun getTransactionHandler(): org.apache.jena.graph.TransactionHandler = transactions
    override fun getEventManager(): org.apache.jena.graph.GraphEventManager = base.eventManager
    override fun getPrefixMapping(): org.apache.jena.shared.PrefixMapping = base.prefixMapping

    override fun add(triple: org.apache.jena.graph.Triple): Unit = write { base.add(triple) }
    override fun delete(triple: org.apache.jena.graph.Triple): Unit = write { base.delete(triple) }
    override fun remove(s: Node?, p: Node?, o: Node?): Unit = write { base.remove(s, p, o) }
    override fun clear(): Unit = write { base.clear() }

    override fun find(triple: org.apache.jena.graph.Triple): org.apache.jena.util.iterator.ExtendedIterator<org.apache.jena.graph.Triple> =
        readOnlyIterator(base.find(triple))

    override fun find(s: Node?, p: Node?, o: Node?): org.apache.jena.util.iterator.ExtendedIterator<org.apache.jena.graph.Triple> =
        readOnlyIterator(base.find(s, p, o))

    private fun readOnlyIterator(
        source: org.apache.jena.util.iterator.ExtendedIterator<org.apache.jena.graph.Triple>,
    ): org.apache.jena.util.iterator.ExtendedIterator<org.apache.jena.graph.Triple> =
        object : org.apache.jena.util.iterator.NiceIterator<org.apache.jena.graph.Triple>() {
            override fun hasNext(): Boolean = source.hasNext()
            override fun next(): org.apache.jena.graph.Triple = source.next()
            override fun close() = source.close()
            override fun remove(): Unit = throw UnsupportedOperationException(
                "Iterators of a Kastor repository graph are read-only: delete the triple through the graph or the model",
            )
        }

    override fun isIsomorphicWith(other: org.apache.jena.graph.Graph): Boolean = base.isIsomorphicWith(other)
    override fun contains(s: Node?, p: Node?, o: Node?): Boolean = base.contains(s, p, o)
    override fun contains(triple: org.apache.jena.graph.Triple): Boolean = base.contains(triple)
    override fun close() = Unit
    override fun isEmpty(): Boolean = base.isEmpty
    override fun size(): Int = base.size()
    override fun isClosed(): Boolean = base.isClosed

    override fun toString(): String = "AccountedStoreGraph(${if (graphKey.isEmpty()) "default graph" else graphKey})"
}
