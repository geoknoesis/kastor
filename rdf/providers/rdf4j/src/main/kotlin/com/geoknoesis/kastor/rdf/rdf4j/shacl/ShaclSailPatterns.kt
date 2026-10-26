package com.geoknoesis.kastor.rdf.rdf4j.shacl

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple

/**
 * The `sh:pattern` constraints that RDF4J 5.3 `ShaclSail` would evaluate with **java.util.regex** instead of the
 * sandboxed SPARQL `REGEX` (the linear-time engine of Kastor's sandbox).
 *
 * `ShaclSail` evaluates a pattern either in a SPARQL validation query, which runs in Kastor's sandbox, or in a
 * transactional plan with its own `PatternFilter` (`Pattern.matcher(label).find()`), which nothing can bound: an
 * untrusted pattern there backtracks for as long as it likes, and the validation worker stays busy after the
 * deadline. Probed against `rdf4j-shacl` 5.3.2 (`Rdf4jShaclSandboxTest`): a pattern under `sh:or`, `sh:not` or
 * `sh:qualifiedValueShape` takes the transactional plan; a pattern directly on a shape, or reached through
 * `sh:node`, takes the SPARQL one. `sh:and` and `sh:xone` are treated like `sh:or`.
 *
 * [Rdf4jShaclValidator] refuses (fails closed) a shapes graph with such a pattern; the native engine evaluates it.
 */
internal object ShaclSailPatterns {
  private const val SH = "http://www.w3.org/ns/shacl#"
  private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"

  /** Operands that `ShaclSail` evaluates in transactional plans. */
  private val NESTING = setOf(SH + "not", SH + "qualifiedValueShape")
  private val NESTING_LISTS = setOf(SH + "or", SH + "and", SH + "xone")

  /** Links from a shape to the shapes evaluated as part of it. */
  private val PARTS = setOf(SH + "node", SH + "property") + NESTING

  /** The shapes of [shapes] that have `sh:pattern` and are evaluated inside `sh:or`, `sh:and`, `sh:xone`, `sh:not` or `sh:qualifiedValueShape`. */
  fun javaEvaluated(shapes: List<RdfTriple>): List<RdfTerm> {
    val bySubject = shapes.groupBy { it.subject }
    fun values(node: RdfTerm, predicate: String): List<RdfTerm> =
        (node as? RdfResource)?.let { bySubject[it] }.orEmpty().filter { it.predicate.value == predicate }.map { it.obj }

    fun list(head: RdfTerm): List<RdfTerm> {
      val members = ArrayList<RdfTerm>()
      val seen = HashSet<RdfTerm>()
      var node = head
      while (node is BlankNode && seen.add(node)) {
        members.addAll(values(node, RDF + "first"))
        node = values(node, RDF + "rest").firstOrNull() ?: break
      }
      return members
    }

    val nested = ArrayDeque<RdfTerm>()
    for (t in shapes) {
      when (t.predicate.value) {
        in NESTING -> nested.add(t.obj)
        in NESTING_LISTS -> nested.addAll(list(t.obj))
      }
    }
    val seen = HashSet<RdfTerm>()
    val found = ArrayList<RdfTerm>()
    while (nested.isNotEmpty()) {
      val shape = nested.removeFirst()
      if (!seen.add(shape)) continue
      if (values(shape, SH + "pattern").isNotEmpty()) found.add(shape)
      PARTS.forEach { nested.addAll(values(shape, it)) }
      NESTING_LISTS.forEach { p -> values(shape, p).forEach { nested.addAll(list(it)) } }
    }
    return found
  }
}
