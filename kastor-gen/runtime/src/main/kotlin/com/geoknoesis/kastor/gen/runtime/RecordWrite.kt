package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.MutableRdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.xsd.XsdDecimal
import java.math.BigDecimal

/**
 * One modelled predicate of a record written by [mergeRecord]: the record's [values] for [predicate] and how its
 * reader sees the graph's terms.
 *
 * @param T the record's value type, compared by value: with `equals` after a round trip through [encode] and
 *   [decode] (e.g. `String`, `BigInteger`, `LocalDate`, an enum, or the node of a nested resource), and numerically
 *   for `BigDecimal` (`1E+3` equals `1000`)
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

  /**
   * The terms of [terms] to remove and the terms to add so that the predicate holds this field's values.
   *
   * Graph terms and record values are matched by [valueKey]: by value, not by `equals` of the record's objects, so
   * a `BigDecimal` of another scale (`1E+3` for a graph `"1000"`) is the same value and its term is kept. Adds are
   * checked against the terms that remain, not against every term: a value whose encoded term is about to be
   * removed is written again rather than lost.
   */
  internal fun diff(terms: List<RdfTerm>): Pair<List<RdfTerm>, List<RdfTerm>> {
    val representable = terms.mapNotNull { term -> decode(term)?.let { term to valueKey(it) } }
    val wanted = values.map(::valueKey)
    // Unchanged only when the value is the one the reader returns (the first representable term in `find` order).
    // A value equal to another term beyond sh:maxCount is an update: the other representable terms are removed so
    // that the next read returns it.
    if (singleValued && wanted.isNotEmpty() && representable.firstOrNull()?.second == wanted.single()) {
      return emptyList<RdfTerm>() to emptyList()
    }
    val pending = values.indices.toMutableList()
    val remove = mutableListOf<RdfTerm>()
    for ((term, key) in representable) {
      val match = pending.indexOfFirst { wanted[it] == key }
      if (match >= 0) pending.removeAt(match) else remove += term
    }
    val kept = terms.toMutableList().apply { removeAll(remove.toSet()) }.toSet()
    return remove to pending.map { encode(values[it]) }.filter { it !in kept }.distinct()
  }

  /**
   * The value [value] stands for: the value its encoded term reads back as (so a value the codec normalises matches
   * the term it writes), with decimals compared by value, independent of scale.
   */
  private fun valueKey(value: T): Any {
    val read = runCatching { decode(encode(value)) }.getOrNull() ?: value
    return if (read is BigDecimal) XsdDecimal.of(read) else read
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
