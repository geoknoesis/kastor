package com.geoknoesis.kastor.rdf.rdf4j.shacl

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple

/**
 * RDF lists of a shapes graph. SHACL allows a list node to be an IRI or a blank node; the shape analyzers of this
 * package must read both the same way ([members]), and ShaclSail must only ever see blank list nodes
 * ([withBlankListNodes]).
 */
internal object ShaclLists {
  private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
  private const val FIRST = RDF + "first"
  private const val REST = RDF + "rest"
  private const val NIL = RDF + "nil"

  /**
   * The members of the list at [head], following `rdf:rest` through any node (IRI or blank) up to `rdf:nil`, a node
   * without `rdf:rest`, or a node already visited (a cycle); [values] gives the objects of a node's predicate.
   */
  fun members(head: RdfTerm, values: (RdfTerm, String) -> List<RdfTerm>): List<RdfTerm> {
    val members = ArrayList<RdfTerm>()
    val seen = HashSet<RdfTerm>()
    var node = head
    while (node is RdfResource && !(node is Iri && node.value == NIL) && seen.add(node)) {
      members.addAll(values(node, FIRST))
      node = values(node, REST).firstOrNull() ?: break
    }
    return members
  }

  /**
   * [triples] with every IRI that is only a list node (all its triples as a subject are `rdf:first` / `rdf:rest`)
   * replaced by a fresh blank node, as subject and as object.
   *
   * RDF4J 5.3 ShaclSail writes the `sh:or` / `sh:and` lists of a shape into its validation report by turning the
   * list nodes' identifiers into blank node identifiers (`ShaclAstLists.listToRdf`), which fails with "Illegal blank
   * node ID character" for an IRI: a legal shapes graph with IRI list nodes could not be validated when it was
   * violated. The list nodes have no meaning of their own, so naming them with blank nodes changes nothing else.
   */
  fun withBlankListNodes(triples: List<RdfTriple>): List<RdfTriple> {
    val bySubject = triples.groupBy { it.subject }
    val listNodes = bySubject.filter { (subject, own) ->
      subject is Iri && subject.value != NIL && own.all { it.predicate.value == FIRST || it.predicate.value == REST } &&
          own.any { it.predicate.value == REST }
    }.keys
    if (listNodes.isEmpty()) return triples
    val taken = triples.flatMap { listOf(it.subject, it.obj) }.filterIsInstance<BlankNode>().mapTo(HashSet()) { it.id }
    var prefix = "kastorList"
    while (taken.any { it.startsWith(prefix) }) prefix += "x"
    val renamed = HashMap<RdfTerm, BlankNode>()
    listNodes.forEachIndexed { k, node -> renamed[node] = BlankNode("$prefix$k") }
    return triples.map { t ->
      if (t.subject !in renamed && t.obj !in renamed) t else RdfTriple(renamed[t.subject] ?: t.subject, t.predicate, renamed[t.obj] ?: t.obj)
    }
  }
}
