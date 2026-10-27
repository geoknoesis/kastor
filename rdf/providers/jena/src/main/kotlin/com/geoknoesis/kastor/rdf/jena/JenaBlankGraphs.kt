package com.geoknoesis.kastor.rdf.jena

import org.apache.jena.graph.Node
import org.apache.jena.graph.NodeFactory
import org.apache.jena.query.Dataset

/**
 * Graphs named by a blank node that a SPARQL UPDATE creates (`LOAD` without `INTO GRAPH`, an `INSERT` template with a
 * graph variable bound to a blank node), moved to their skolem graph names ([JenaRepository.update]).
 */
internal object JenaBlankGraphs {
    /** The start of a SPARQL codepoint escape (a backslash and `u`; the `U` form is matched ignoring case). */
    private val UNICODE_ESCAPE: String = Char(92) + "u"

    /**
     * A cheap, conservative look at the text of an update request: false only when it certainly has neither a `LOAD`
     * nor a `GRAPH` with a variable (the two ways of naming a graph by a blank node). Codepoint escapes could spell
     * either keyword, so a text with a backslash-u escape always counts as "may".
     */
    fun textMayCreateBlankGraph(sparql: String): Boolean =
        sparql.contains("LOAD", ignoreCase = true) ||
            sparql.contains(UNICODE_ESCAPE, ignoreCase = true) ||
            (sparql.contains("GRAPH", ignoreCase = true) && (sparql.contains('?') || sparql.contains('$')))

    /** Whether [request] can create a graph named by a blank node (see [JenaRepository.update]). */
    fun mayCreateBlankGraph(request: org.apache.jena.update.UpdateRequest): Boolean = request.operations.any { operation ->
        when (operation) {
            is org.apache.jena.sparql.modify.request.UpdateLoad -> operation.dest == null
            // The default graph of a template is a (generated) IRI too.
            is org.apache.jena.sparql.modify.request.UpdateModify -> operation.insertQuads.any { !it.graph.isURI }
            else -> false
        }
    }

    /**
     * Runs [execute], the execution of [request] on [dataset]; when the request may create blank-node graphs, moves
     * those it created to their skolem graphs afterwards, also when it fails (an outer transaction may catch the
     * error and retain earlier successful operations).
     */
    inline fun executeSkolemizing(request: org.apache.jena.update.UpdateRequest, dataset: Dataset, execute: () -> Unit) {
        val foreign = if (mayCreateBlankGraph(request)) blankGraphNames(dataset) else null
        var failure: Throwable? = null
        try {
            execute()
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            if (foreign != null) {
                try {
                    skolemizeBlankGraphs(dataset, foreign)
                } catch (e: Exception) {
                    failure?.addSuppressed(e) ?: throw e
                }
            }
        }
    }

    fun blankGraphNames(dataset: Dataset): Set<Node> {
        val names = dataset.asDatasetGraph().listGraphNodes()
        try {
            return names.asSequence().filter { it.isBlank }.toSet()
        } finally {
            org.apache.jena.atlas.iterator.Iter.close(names)
        }
    }

    /** Moves every blank-node graph that is not in [keep] to its skolem graph name (see [JenaRepository.update]). */
    fun skolemizeBlankGraphs(dataset: Dataset, keep: Set<Node>) {
        val created = blankGraphNames(dataset) - keep
        if (created.isEmpty()) return
        val store = dataset.asDatasetGraph()
        val load = java.util.UUID.randomUUID().toString().replace("-", "")
        for (blank in created) {
            val name = NodeFactory.createURI(JenaParsing.skolemGraphName(load, blank.blankNodeLabel))
            val quads = store.find(blank, Node.ANY, Node.ANY, Node.ANY)
            val triples = try { quads.asSequence().map { it.asTriple() }.toList() } finally { org.apache.jena.atlas.iterator.Iter.close(quads) }
            triples.forEach { store.add(name, it.subject, it.predicate, it.`object`) }
            store.deleteAny(blank, Node.ANY, Node.ANY, Node.ANY)
        }
    }
}
