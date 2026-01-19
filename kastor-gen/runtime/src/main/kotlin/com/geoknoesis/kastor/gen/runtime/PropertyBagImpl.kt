package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTerm

/**
 * Internal implementation of [PropertyBag] that provides access to unmapped RDF properties.
 * 
 * This class filters triples by subject and excludes known predicates (those mapped to domain properties).
 * Results are cached lazily for performance.
 * 
 * @property graph The RDF graph to query
 * @property subj The subject node to filter by
 * @property exclude Set of predicates to exclude (known/mapped predicates)
 */
internal class PropertyBagImpl(
  private val graph: RdfGraph,
  private val subj: RdfTerm,
  private val exclude: Set<Iri>
) : PropertyBag {

  /**
   * Cached map of predicates to their object values.
   * Computed once on first access and reused for all subsequent queries.
   */
  private val byPred: Map<Iri, List<RdfTerm>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
    graph.getTriples()
      .filter { it.subject == subj }
      .filter { it.predicate !in exclude }
      .groupBy { it.predicate }
      .mapValues { (_, triples) -> triples.map { it.obj } }
  }

  override fun predicates(): Set<Iri> = byPred.keys.toSortedSet(compareBy { it.value })

  override fun values(pred: Iri): List<RdfTerm> = byPred[pred] ?: emptyList()

  override fun literals(pred: Iri): List<Literal> = values(pred).filterIsInstance<Literal>()

  override fun strings(pred: Iri): List<String> = literals(pred).map { it.lexical }

  override fun iris(pred: Iri): List<Iri> = values(pred).filterIsInstance<Iri>()

  override fun <T : Any> objects(pred: Iri, asType: Class<T>): List<T> =
    values(pred).mapNotNull { term ->
      when (term) {
        is Iri, is BlankNode -> OntoMapper.materialize(RdfRef(term, graph), asType)
        else -> null
      }
    }
}












