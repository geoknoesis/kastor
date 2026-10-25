package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.MutableRdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.vocab.RDF

/**
 * One modelled predicate of a record written by [mergeRecord]: the record's [values] for [predicate] and how its
 * reader sees the graph's terms.
 *
 * @param T the record's value type, compared with `equals` (e.g. `String`, `BigInteger`, `LocalDate`, an enum,
 *   or the node of a nested resource)
 * @property singleValued the record holds at most one value although the graph may hold several
 * @property decode the value the record's reader takes from a graph term, or `null` when the record cannot
 *   represent that term (ill-typed literal, other datatype, wrong term kind)
 * @property encode the term written for a new value
 */
class RecordField<T : Any>(
  val predicate: Iri,
  val values: List<T>,
  val singleValued: Boolean,
  val decode: (RdfTerm) -> T?,
  val encode: (T) -> RdfTerm,
) {
  init {
    require(!singleValued || values.size <= 1) { "A single-valued field holds at most one value: <${predicate.value}>" }
  }

  /** The terms of [terms] to remove and the terms to add so that the predicate holds this field's values. */
  internal fun diff(terms: List<RdfTerm>): Pair<List<RdfTerm>, List<RdfTerm>> {
    val representable = terms.mapNotNull { term -> decode(term)?.let { term to it } }
    // Unchanged only when the value is the one the reader returns (the first representable term in `find` order).
    // A value equal to another term beyond sh:maxCount is an update: the other representable terms are removed so
    // that the next read returns it.
    if (singleValued && values.isNotEmpty() && representable.firstOrNull()?.second == values.single()) {
      return emptyList<RdfTerm>() to emptyList()
    }
    val pending = values.toMutableList()
    val remove = mutableListOf<RdfTerm>()
    for ((term, value) in representable) {
      if (!pending.remove(value)) remove += term
    }
    return remove to pending.map(encode).filter { it !in terms }
  }
}

/**
 * Writes a record's modelled predicates to [subject] in one atomic edit, keeping every term the record could not
 * have changed. For each [field]:
 *
 * - a graph term the record cannot represent (`decode` returns `null`) is kept;
 * - a single-valued field whose value equals the value its reader returns (the decoded value of the first
 *   representable term, in `find` order) is unchanged: all of the predicate's terms are kept, including values
 *   beyond `sh:maxCount` that the reader did not return. Any other value is written, even when the graph already
 *   holds it beyond `sh:maxCount`: one term of that value is kept and every other representable term is removed,
 *   so the next read returns it;
 * - otherwise each graph term whose decoded value is still among the record's values is kept as it is (its
 *   language tag, datatype and lexical form survive), the other representable terms are removed, and the record's
 *   remaining values are added with `encode`.
 *
 * `rdf:type` triples for [types] are added when missing. Triples of other predicates are never touched. Only values
 * that are added are encoded, all of them before the first change, so an encoding failure (an ill-typed value)
 * leaves [this] graph unchanged; graphs without atomic mutation support are rejected before any change.
 *
 * @throws IllegalArgumentException when two fields name the same predicate or `rdf:type`
 */
fun MutableRdfGraph.mergeRecord(subject: RdfResource, types: Collection<Iri>, fields: Collection<RecordField<*>>) {
  val predicates = fields.map { it.predicate }
  require(predicates.toSet().size == predicates.size) { "Each predicate is written by one field: $predicates" }
  require(RDF.type !in predicates) { "rdf:type is written through the types parameter" }
  atomicEdit {
    // Every read, decode and encode happens before the first change, so a failure leaves the graph as it was.
    val stale = mutableListOf<RdfTriple>()
    val added = mutableListOf<RdfTriple>()
    for (field in fields) {
      val (remove, add) = field.diff(find(subject, field.predicate).map { it.obj })
      remove.mapTo(stale) { RdfTriple(subject, field.predicate, it) }
      add.mapTo(added) { RdfTriple(subject, field.predicate, it) }
    }
    types.filter { find(subject, RDF.type, it).isEmpty() }.mapTo(added) { RdfTriple(subject, RDF.type, it) }
    if (stale.isNotEmpty()) removeTriples(stale)
    if (added.isNotEmpty()) addTriples(added)
  }
}
