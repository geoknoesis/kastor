package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTerm

/**
 * Graph operations utility for Kastor RDF.
 * Provides efficient access to triples and property values.
 */
object KastorGraphOps {
  
  /**
   * Creates a property bag for unmapped triples.
   * 
   * @param graph The RDF graph to query
   * @param subj The subject node
   * @param exclude Set of predicates to exclude from the bag
   * @return A property bag with access to unmapped properties
   */
  fun extras(graph: RdfGraph, subj: RdfTerm, exclude: Set<Iri>): PropertyBag =
    PropertyBagImpl(graph, subj, exclude)

  /**
   * Retrieves all literal values for a given subject and predicate.
   * 
   * @param graph The RDF graph to query
   * @param subj The subject node
   * @param pred The predicate IRI
   * @return List of literal values (empty if none found)
   */
  fun getLiteralValues(graph: RdfGraph, subj: RdfTerm, pred: Iri): List<Literal> {
    return graph.getTriples()
      .filter { it.subject == subj && it.predicate == pred }
      .mapNotNull { it.obj as? Literal }
  }

  /**
   * Counts literal values for a given subject and predicate.
   */
  fun countLiteralValues(graph: RdfGraph, subj: RdfTerm, pred: Iri): Int {
    return graph.getTriples()
      .count { it.subject == subj && it.predicate == pred && it.obj is Literal }
  }

  /**
   * Retrieves a required literal value, throwing an error if missing.
   * 
   * @param graph The RDF graph to query
   * @param subj The subject node
   * @param pred The predicate IRI
   * @return The first literal value found
   * @throws IllegalStateException if no value is found
   */
  fun getRequiredLiteralValue(graph: RdfGraph, subj: RdfTerm, pred: Iri): Literal {
    val values = getLiteralValues(graph, subj, pred)
    return values.firstOrNull() ?: error("Required literal $pred missing for $subj")
  }

  /**
   * Retrieves and materializes object values for a given subject and predicate.
   * 
   * @param graph The RDF graph to query
   * @param subj The subject node
   * @param pred The predicate IRI
   * @param factory Factory function to materialize each object node
   * @return List of materialized objects (empty if none found)
   */
  fun <T: Any> getObjectValues(
    graph: RdfGraph,
    subj: RdfTerm,
    pred: Iri,
    factory: (RdfTerm) -> T
  ): List<T> {
    return graph.getTriples()
      .filter { it.subject == subj && it.predicate == pred }
      .mapNotNull { triple ->
        when (val obj = triple.obj) {
          is Iri, is BlankNode -> try { factory(obj) } catch (e: Exception) { null }
          else -> null
        }
      }
  }

  /**
   * Counts object values (IRI or BlankNode) for a given subject and predicate.
   */
  fun countObjectValues(graph: RdfGraph, subj: RdfTerm, pred: Iri): Int {
    return graph.getTriples()
      .count { it.subject == subj && it.predicate == pred && (it.obj is Iri || it.obj is BlankNode) }
  }
}












