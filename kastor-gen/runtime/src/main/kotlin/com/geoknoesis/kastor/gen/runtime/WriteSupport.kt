package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.MutableRdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.AtomicMutableRdfGraph

internal fun MutableRdfGraph.atomicEdit(operations: () -> Unit) {
    val atomic = this as? AtomicMutableRdfGraph ?: throw UnsupportedOperationException(
        "Replacement requires an AtomicMutableRdfGraph; use a repository-backed graph or a MemoryGraph",
    )
    atomic.mutateAtomically { operations() }
}

/** Atomically replaces a predicate's values. Rejects out-of-scope triples before editing. */
fun MutableRdfGraph.replaceValues(
    subject: RdfResource,
    predicate: Iri,
    newTriples: Collection<RdfTriple>,
) {
    val replacement = newTriples.toList()
    require(replacement.all { it.subject == subject && it.predicate == predicate }) {
        "Replacement triples must match the requested subject and predicate"
    }
    atomicEdit {
        val stale = find(subject, predicate)
        if (stale.isNotEmpty()) removeTriples(stale)
        if (replacement.isNotEmpty()) addTriples(replacement)
    }
}

/** Atomically replaces a resource. Graphs without atomic mutation support fail before changing data. */
fun MutableRdfGraph.replaceResource(
    subject: RdfResource,
    triples: Collection<RdfTriple>,
) {
    val replacement = triples.toList()
    require(replacement.all { it.subject == subject }) { "Replacement triples must match the requested subject" }
    atomicEdit {
        val stale = find(subject)
        if (stale.isNotEmpty()) removeTriples(stale)
        if (replacement.isNotEmpty()) addTriples(replacement)
    }
}
