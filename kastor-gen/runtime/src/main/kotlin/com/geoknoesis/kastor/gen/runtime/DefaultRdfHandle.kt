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
  internal val validationContext: ValidationContext? = null,
) : RdfHandle {

  override val isValidationConfigured: Boolean get() = validationContext != null

  // PUBLICATION is sufficient: values are idempotent and immutable
  override val extras: PropertyBag by lazy(LazyThreadSafetyMode.PUBLICATION) {
    KastorGraphOps.extras(graph, node, exclude = known)
  }

  override fun validate(): ValidationResult {
    return validationContext?.validate(graph, node)
      ?: error("Validation context not configured for this handle")
  }
  
  /**
   * Validates this resource against a validation context.
   * 
   * @param validation The validation context to use
   * @return Validation result
   */
  fun validate(validation: ValidationContext): ValidationResult {
    return validation.validate(graph, node)
  }
}

/**
 * Narrows the "known predicates" set used to populate [RdfHandle.extras].
 *
 * If this handle is not a [DefaultRdfHandle], the result is a new [DefaultRdfHandle]
 * **without** a validation context; call sites that need validated handles should build a
 * [DefaultRdfHandle] directly with a non-null [ValidationContext].
 */
fun RdfHandle.withKnownPredicates(known: Set<Iri>): RdfHandle =
  when (this) {
    is DefaultRdfHandle -> DefaultRdfHandle(node, graph, known, validationContext)
    else -> DefaultRdfHandle(node, graph, known, validationContext = null)
  }

