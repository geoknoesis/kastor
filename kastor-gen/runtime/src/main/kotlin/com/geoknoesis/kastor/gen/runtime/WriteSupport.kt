package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.MutableRdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTriple

fun MutableRdfGraph.replaceValues(
    subject: RdfResource,
    predicate: Iri,
    newTriples: Collection<RdfTriple>,
) {
    val stale = getTriples().filter { it.subject == subject && it.predicate == predicate }
    if (stale.isNotEmpty()) removeTriples(stale)
    if (newTriples.isNotEmpty()) addTriples(newTriples)
}

fun MutableRdfGraph.replaceResource(
    subject: RdfResource,
    triples: Collection<RdfTriple>,
) {
    val stale = getTriples().filter { it.subject == subject }
    if (stale.isNotEmpty()) removeTriples(stale)
    if (triples.isNotEmpty()) addTriples(triples)
}
