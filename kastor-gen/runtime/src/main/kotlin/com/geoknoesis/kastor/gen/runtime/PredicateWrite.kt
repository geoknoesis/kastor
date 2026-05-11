package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.MutableRdfGraph
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple

/**
 * Replaces all **literal** objects for ([RdfBacked.rdf]'s subject, [predicate]) with [literal].
 * Requires the backing [RdfGraph] to be a [MutableRdfGraph] (same instance used for reads and writes).
 */
fun RdfBacked.replacePredicateLiterals(predicate: Iri, literal: Literal) {
  val subj = rdf.node as? RdfResource ?: error("RDF subject must be an IRI or blank node")
  val editor = rdf.graph as? MutableRdfGraph ?: error(
    "Graph is not mutable; use a mutable graph/repository to assign literal-backed properties.",
  )
  val stale =
    rdf.graph
      .getTriples()
      .filter { it.subject == subj && it.predicate == predicate && it.obj is Literal }
      .toList()
  if (stale.isNotEmpty()) {
    editor.removeTriples(stale)
  }
  editor.addTriple(RdfTriple(subj, predicate, literal))
}

/**
 * Replaces all **non-literal** (IRI or blank node) objects for ([RdfBacked.rdf]'s subject, [predicate]) with [obj].
 * Requires a [MutableRdfGraph] backing store.
 */
fun RdfBacked.replacePredicateObjectTerm(predicate: Iri, obj: RdfTerm) {
  val subj = rdf.node as? RdfResource ?: error("RDF subject must be an IRI or blank node")
  val editor = rdf.graph as? MutableRdfGraph ?: error(
    "Graph is not mutable; use a mutable graph/repository to assign object-backed properties.",
  )
  val stale =
    rdf.graph
      .getTriples()
      .filter { it.subject == subj && it.predicate == predicate && (it.obj is Iri || it.obj is BlankNode) }
      .toList()
  if (stale.isNotEmpty()) {
    editor.removeTriples(stale)
  }
  editor.addTriple(RdfTriple(subj, predicate, obj))
}
