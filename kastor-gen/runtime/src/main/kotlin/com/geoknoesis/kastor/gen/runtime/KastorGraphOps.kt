package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
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
    return graph.find(subj as? com.geoknoesis.kastor.rdf.RdfResource ?: return emptyList(), pred)
      .mapNotNull { it.obj as? Literal }
  }

  /**
   * Counts literal values for a given subject and predicate.
   */
  fun countLiteralValues(graph: RdfGraph, subj: RdfTerm, pred: Iri): Int {
    return graph.find(subj as? com.geoknoesis.kastor.rdf.RdfResource ?: return 0, pred)
      .count { true && it.obj is Literal }
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
   * Only IRI and blank-node objects are passed to [factory]; literal objects are skipped. Failures are
   * never silently dropped: [Error], [ValidationException] and [MaterializationException] propagate
   * unchanged, and any other exception is rethrown as a [MaterializationException] naming the
   * subject, predicate and object that could not be materialized.
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
    return graph.find(subj as? com.geoknoesis.kastor.rdf.RdfResource ?: return emptyList(), pred)
      .mapNotNull { triple ->
        when (val obj = triple.obj) {
          is Iri, is BlankNode ->
            try {
              factory(obj)
            } catch (e: Error) {
              throw e
            } catch (e: ValidationException) {
              throw e
            } catch (e: MaterializationException) {
              throw e
            } catch (e: Exception) {
              throw MaterializationException(
                "Failed to materialize value of <${pred.value}> for $subj (object $obj): ${e.message ?: e::class.java.name}",
                e,
              )
            }
          else -> null
        }
      }
  }

  /**
   * Counts object values (IRI or BlankNode) for a given subject and predicate.
   */
  fun countObjectValues(graph: RdfGraph, subj: RdfTerm, pred: Iri): Int {
    return graph.find(subj as? com.geoknoesis.kastor.rdf.RdfResource ?: return 0, pred)
      .count { true && (it.obj is Iri || it.obj is BlankNode) }
  }

  /** All values (IRIs, blank nodes, literals, triple terms) of [pred] for [subj]; used by generated validation. */
  fun getValues(graph: RdfGraph, subj: RdfTerm, pred: Iri): List<RdfTerm> =
    graph.find(subj as? RdfResource ?: return emptyList(), pred).map { it.obj }

  /**
   * SHACL `sh:nodeKind`: whether [term] is of [nodeKind] (`sh:IRI`, `sh:BlankNode`, `sh:Literal`,
   * `sh:BlankNodeOrIRI`, `sh:BlankNodeOrLiteral`, `sh:IRIOrLiteral`).
   *
   * @throws IllegalArgumentException for any other node kind IRI
   */
  fun hasNodeKind(term: RdfTerm, nodeKind: Iri): Boolean {
    val iri = term is Iri
    val blank = term is BlankNode
    val literal = term is Literal
    return when (nodeKind.value.removePrefix(SH)) {
      "IRI" -> iri
      "BlankNode" -> blank
      "Literal" -> literal
      "BlankNodeOrIRI" -> iri || blank
      "BlankNodeOrLiteral" -> blank || literal
      "IRIOrLiteral" -> iri || literal
      else -> throw IllegalArgumentException("Unknown sh:nodeKind <${nodeKind.value}>")
    }
  }

  /**
   * SHACL `sh:class`: whether [term] is a SHACL instance of [cls] in [graph], i.e. has an `rdf:type` that is [cls]
   * or a transitive `rdfs:subClassOf` of it. Literals are never instances.
   */
  fun isInstanceOf(graph: RdfGraph, term: RdfTerm, cls: Iri): Boolean {
    val node = term as? RdfResource ?: return false
    val pending = ArrayDeque<RdfResource>()
    graph.find(node, RDF_TYPE).mapNotNullTo(pending) { it.obj as? RdfResource }
    val seen = HashSet<RdfResource>()
    while (pending.isNotEmpty()) {
      val type = pending.removeFirst()
      if (!seen.add(type)) continue
      if (type == cls) return true
      graph.find(type, RDFS_SUB_CLASS_OF).mapNotNullTo(pending) { it.obj as? RdfResource }
    }
    return false
  }

  private const val SH = "http://www.w3.org/ns/shacl#"
  private val RDF_TYPE = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
  private val RDFS_SUB_CLASS_OF = Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")
}








