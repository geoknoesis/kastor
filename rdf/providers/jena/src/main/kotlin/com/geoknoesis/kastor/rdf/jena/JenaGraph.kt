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
internal class JenaGraph(
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

    private fun <T> write(block: () -> T): T = repository?.withWrite(block) ?: block()

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
