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
 */
internal class JenaGraph(
    val model: Model,
    private val repository: JenaRepository? = null,
    /** Cache key of this graph inside [repository]: "" for the default graph, else the graph name. */
    private val graphKey: String = "",
) : MutableRdfGraph {

    /** Runs [block] against this graph's read view (inference view for inference repositories). */
    internal fun <T> read(block: (Model) -> T): T =
        repository?.withRead { block(repository.readModel(graphKey, model)) } ?: block(model)

    internal val isRepositoryBacked: Boolean get() = repository != null

    private fun <T> write(block: () -> T): T = repository?.withWrite(block) ?: block()

    override fun addTriple(triple: RdfTriple): Unit = write {
        model.graph.add(JenaTerms.toJenaTriple(triple))
    }

    override fun addTriples(triples: Collection<RdfTriple>): Unit = write {
        val graph = model.graph
        triples.forEach { graph.add(JenaTerms.toJenaTriple(it)) }
    }

    override fun removeTriple(triple: RdfTriple): Boolean = write { removeInCurrentTransaction(triple) }

    override fun removeTriples(triples: Collection<RdfTriple>): Boolean = write {
        var changed = false
        triples.forEach { if (removeInCurrentTransaction(it)) changed = true }
        changed
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
            iterator.asSequence().map(JenaTerms::fromJenaTriple).toList()
        } finally {
            iterator.close()
        }
    }

    override fun clear(): Boolean = write { val changed = !model.isEmpty; model.removeAll(); changed }

    override fun size(): Int = read { view ->
        if (view is org.apache.jena.rdf.model.InfModel) {
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
