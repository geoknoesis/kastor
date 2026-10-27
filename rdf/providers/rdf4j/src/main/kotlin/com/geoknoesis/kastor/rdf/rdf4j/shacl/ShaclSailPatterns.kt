package com.geoknoesis.kastor.rdf.rdf4j.shacl

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
 * `sh:node`, takes the SPARQL one. `sh:and` and `sh:xone` are treated like `sh:or`. A SHACL list may have IRI or
 * blank nodes; the search follows the graph, not the syntax, so neither hides a pattern, and a malformed list (a
 * missing `rdf:rest`, a cycle) cannot hide one either: every `rdf:first` reachable from the operand is searched.
 *
 * [Rdf4jShaclValidator] refuses (fails closed) a shapes graph with such a pattern; the native engine evaluates it.
 */
internal object ShaclSailPatterns {
  private const val SH = "http://www.w3.org/ns/shacl#"
  private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"

  /** Operands that `ShaclSail` evaluates in transactional plans (the shapes and the lists of shapes). */
  private val NESTING = setOf(SH + "not", SH + "qualifiedValueShape", SH + "or", SH + "and", SH + "xone")

  /**
   * Properties whose values are never shapes evaluated as part of the shape (classes, datatypes, paths, value lists,
   * targets, annotations): not followed. Every other link is, so a shape reached by any reference, through list
   * nodes that are IRIs or blank nodes, is found.
   */
  private val NOT_SHAPES =
      setOf(
              "class", "datatype", "path", "in", "hasValue", "languageIn", "ignoredProperties", "nodeKind", "targetClass",
              "targetNode", "targetSubjectsOf", "targetObjectsOf", "equals", "disjoint", "lessThan", "lessThanOrEquals",
              "severity", "message", "name", "description", "group", "order", "defaultValue", "flags", "pattern", "sparql",
              "target", "prefixes", "declare",
          )
          .mapTo(HashSet()) { SH + it } + (RDF + "type")

  /**
   * The shapes of [shapes] that have `sh:pattern` and are evaluated inside `sh:or`, `sh:and`, `sh:xone`, `sh:not` or
   * `sh:qualifiedValueShape`: every node reachable from such an operand (through list nodes, `sh:node`,
   * `sh:property`, nested operators and any other link but [NOT_SHAPES]), whatever the kind of its nodes.
   */
  fun javaEvaluated(shapes: List<RdfTriple>, check: () -> Unit = {}): List<RdfTerm> {
    val bySubject = shapes.groupBy { it.subject }
    val nested = ArrayDeque<RdfTerm>()
    for (t in shapes) if (t.predicate.value in NESTING) nested.add(t.obj)
    val seen = HashSet<RdfTerm>()
    val found = ArrayList<RdfTerm>()
    while (nested.isNotEmpty()) {
      val node = nested.removeFirst()
      if (node !is RdfResource || !seen.add(node)) continue
      if (seen.size % ShaclLists.CHECK_INTERVAL == 0) check()
      val own = bySubject[node].orEmpty()
      if (own.any { it.predicate.value == SH + "pattern" }) found.add(node)
      for (t in own) if (t.predicate.value !in NOT_SHAPES) nested.add(t.obj)
    }
    return found
  }
}
