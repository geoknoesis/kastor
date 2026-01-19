package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.*

/** Marker: domain instances backed by an RDF node in a graph. */
interface RdfBacked {
  val rdf: RdfHandle
}

/** Side-channel handle for RDF power without polluting domain API. */
interface RdfHandle {
  val node: RdfTerm          // Iri or BlankNode
  val graph: RdfGraph        // Kastor Graph (Jena/RDF4J under the hood)
  val extras: PropertyBag    // Unmapped triples (lazy & memoized)

  /** Validate the focus node against configured SHACL shapes. */
  fun validate(): ValidationResult

  /** Validate and throw on failure. */
  fun validateOrThrow() = validate().orThrow()
}

/** Strongly typed property bag; no Any/Strings for RDF terms. */
interface PropertyBag {
  fun predicates(): Set<Iri>

  fun values(pred: Iri): List<RdfTerm>
  fun literals(pred: Iri): List<Literal>
  fun strings(pred: Iri): List<String>
  fun iris(pred: Iri): List<Iri>

  /** Materialize object values as domain types via registry. */
  fun <T : Any> objects(pred: Iri, asType: Class<T>): List<T>
}












