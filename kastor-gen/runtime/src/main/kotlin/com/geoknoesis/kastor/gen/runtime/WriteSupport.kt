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

/**
 * Atomically replaces the values of [predicates] for [subject] and adds [triples]: every existing
 * `(subject, p, *)` with `p` in [predicates] is removed, then [triples] are added. Triples of other predicates
 * (such as `rdf:type`) are added next to the existing values, and every other triple of [subject] is left alone.
 *
 * This is the scoped write of a record that models only some predicates of a resource (the generated
 * `<Record>Factory.replaceInGraph`): unmodelled predicates, links the record does not serialize and
 * complex-path triples survive, unlike [replaceResource]. Out-of-scope subjects are rejected before editing;
 * graphs without atomic mutation support fail before changing data.
 */
fun MutableRdfGraph.replacePredicates(
    subject: RdfResource,
    predicates: Collection<Iri>,
    triples: Collection<RdfTriple>,
) {
    val replaced = predicates.toSet()
    val replacement = triples.toList()
    require(replacement.all { it.subject == subject }) { "Replacement triples must match the requested subject" }
    atomicEdit {
        val stale = replaced.flatMap { find(subject, it) }
        if (stale.isNotEmpty()) removeTriples(stale)
        if (replacement.isNotEmpty()) addTriples(replacement)
    }
}

/**
 * Atomically replaces **every** triple of [subject] (all predicates, including ones the replacement does not
 * model) with [triples]. To write a generated data-class record without dropping predicates it does not model,
 * use [replacePredicates] (or the generated `replaceInGraph`). Graphs without atomic mutation support fail before
 * changing data.
 */
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
