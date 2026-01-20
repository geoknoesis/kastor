package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTerm

/**
 * Default implementation of [RdfHandle] that provides access to RDF node, graph, and extras.
 * 
 * @property node The RDF term (IRI or BlankNode) representing this resource
 * @property graph The RDF graph containing the triples
 * @property known Set of predicates that are mapped to domain properties (excluded from extras)
 */
class DefaultRdfHandle(
  override val node: RdfTerm,
  override val graph: RdfGraph,
  private val known: Set<Iri>,
  private val validationContext: ValidationContext? = null
) : RdfHandle {

  // PUBLICATION is sufficient: values are idempotent and immutable
  override val extras: PropertyBag by lazy(LazyThreadSafetyMode.PUBLICATION) {
    KastorGraphOps.extras(graph, node, exclude = known)
  }

  override fun validate(): ValidationResult {
    return validationContext?.validate(graph, node) ?: ValidationResult.NotConfigured
  }
}












