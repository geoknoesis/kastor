package com.geoknoesis.kastor.rdf.rdf4j.shacl

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.shacl.UnsupportedShaclFeature

/**
 * What RDF4J 5.3 `ShaclSail` does **not** evaluate, found by reading a shapes graph. `ShaclSail` skips such a
 * construct without a word (it logs at most), so the data would "conform" to a shape that was never checked; the
 * bridge reports it instead (see [Rdf4jShaclValidator]).
 *
 * The list was established against `rdf4j-shacl` 5.3.2 (its shape parser `ShaclProperties`, the `isSupported()` of its
 * path classes, and a violated shape per SHACL Core component, see `Rdf4jShaclCoverageTest`):
 *
 * - **Implemented:** every SHACL Core constraint component except the ones below; the targets `sh:targetNode`,
 *   `sh:targetClass` (with `rdfs:subClassOf` in the data graph), implicit class targets, `sh:targetSubjectsOf`,
 *   `sh:targetObjectsOf`; predicate, sequence, alternative and inverse paths; `sh:deactivated`, `sh:severity`,
 *   `sh:message`; of SHACL-SPARQL, `sh:sparql` constraints with `sh:select` (and `sh:prefixes` / `sh:declare`); of
 *   SHACL-AF, `sh:target` with a `sh:SPARQLTarget`.
 * - **Not implemented (SHACL Core):** `sh:xone`; the paths `sh:zeroOrMorePath`, `sh:oneOrMorePath` and
 *   `sh:zeroOrOnePath` (a property shape with such a path, also nested in another path, is skipped);
 *   `sh:qualifiedValueShapesDisjoint true`.
 * - **Not implemented (SHACL-SPARQL):** SPARQL-based constraint components (`sh:ConstraintComponent` with
 *   `sh:validator` / `sh:nodeValidator` / `sh:propertyValidator`), and `sh:sparql` constraints without `sh:select`
 *   (`sh:ask`), which `ShaclSail` rejects.
 * - **Not implemented (other):** every other property of the SHACL namespace on a shape - the SHACL 1.2 additions
 *   (`sh:memberShape`, `sh:values`, `sh:reifierShape`, ...), `sh:rule`, `sh:js` - and `sh:target` values that are
 *   not a `sh:SPARQLTarget` with `sh:select`.
 */
internal object ShaclSailFeatures {
  private const val SH = "http://www.w3.org/ns/shacl#"
  private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"

  /** The properties of the SHACL namespace that `ShaclSail` reads from a shape (`sh:xone` is read but not evaluated). */
  private val READ_BY_SHACL_SAIL: Set<String> =
      setOf(
              "or", "and", "not", "property", "node", "message", "name", "description", "severity", "defaultValue", "group",
              "order", "languageIn", "nodeKind", "datatype", "minCount", "maxCount", "minLength", "maxLength", "minExclusive",
              "maxExclusive", "minInclusive", "maxInclusive", "pattern", "class", "targetNode", "targetClass",
              "targetSubjectsOf", "targetObjectsOf", "deactivated", "uniqueLang", "closed", "ignoredProperties", "flags", "path",
              "in", "equals", "disjoint", "lessThan", "lessThanOrEquals", "target", "hasValue", "qualifiedValueShape",
              "qualifiedValueShapesDisjoint", "qualifiedMinCount", "qualifiedMaxCount", "sparql",
          )
          .mapTo(HashSet()) { SH + it }

  private val SHAPE_TYPES = setOf(SH + "NodeShape", SH + "PropertyShape")
  private val TARGETS = setOf(SH + "targetNode", SH + "targetClass", SH + "targetSubjectsOf", SH + "targetObjectsOf", SH + "target")
  private val SHAPE_VALUED = setOf(SH + "property", SH + "node", SH + "not", SH + "qualifiedValueShape")
  private val SHAPE_LIST_VALUED = setOf(SH + "and", SH + "or", SH + "xone")
  private val UNSUPPORTED_PATHS = setOf(SH + "zeroOrMorePath", SH + "oneOrMorePath", SH + "zeroOrOnePath")
  private val VALIDATORS = setOf(SH + "validator", SH + "nodeValidator", SH + "propertyValidator")

  /**
   * The unsupported constructs of a shapes graph.
   *
   * @property descriptions one line per construct, in a stable order.
   * @property features the categories of [UnsupportedShaclFeature] among them (the enumeration was made for the
   *   native engine: constructs it has no category for are described only).
   * @property rejected the triples that make `ShaclSail` fail instead of skipping the construct; leaving them out makes
   *   it skip the construct like the others.
   */
  class Findings(val descriptions: List<String>, val features: Set<UnsupportedShaclFeature>, val rejected: Set<RdfTriple>)

  fun scan(shapes: List<RdfTriple>): Findings {
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

    // The shapes: declared ones, the ones with a target, and every shape those refer to.
    val shapeNodes = LinkedHashSet<RdfResource>()
    val pending = ArrayDeque<RdfResource>()
    fun shape(node: RdfTerm) {
      if (node is RdfResource && shapeNodes.add(node)) pending.addLast(node)
    }
    for (triple in shapes) {
      val obj = triple.obj
      if (triple.predicate.value == RDF + "type" && obj is Iri && obj.value in SHAPE_TYPES) shape(triple.subject)
      if (triple.predicate.value in TARGETS) shape(triple.subject)
    }
    while (pending.isNotEmpty()) {
      val node = pending.removeFirst()
      for (triple in bySubject[node].orEmpty()) {
        when (triple.predicate.value) {
          in SHAPE_VALUED -> shape(triple.obj)
          in SHAPE_LIST_VALUED -> list(triple.obj).forEach(::shape)
        }
      }
    }

    val descriptions = LinkedHashSet<String>()
    val features = LinkedHashSet<UnsupportedShaclFeature>()
    val rejected = LinkedHashSet<RdfTriple>()

    fun unsupportedPath(path: RdfTerm, seen: MutableSet<RdfTerm> = HashSet()): String? {
      if (path !is BlankNode || !seen.add(path)) return null
      for (triple in bySubject[path].orEmpty()) {
        val predicate = triple.predicate.value
        when {
          predicate in UNSUPPORTED_PATHS -> return "sh:" + predicate.removePrefix(SH)
          predicate == SH + "inversePath" -> unsupportedPath(triple.obj, seen)?.let { return it }
          predicate == SH + "alternativePath" -> list(triple.obj).forEach { member -> unsupportedPath(member, seen)?.let { return it } }
          predicate == RDF + "first" -> list(path).forEach { member -> unsupportedPath(member, seen)?.let { return it } }
        }
      }
      return null
    }

    // Parameters of SPARQL-based constraint components, by the property that activates the component on a shape.
    val componentParameters = HashMap<String, RdfResource>()
    for (triple in shapes) {
      val obj = triple.obj
      val declares = (triple.predicate.value == RDF + "type" && obj is Iri && obj.value == SH + "ConstraintComponent") ||
          triple.predicate.value in VALIDATORS
      if (!declares) continue
      for (parameter in values(triple.subject, SH + "parameter")) {
        values(parameter, SH + "path").filterIsInstance<Iri>().forEach { componentParameters.putIfAbsent(it.value, triple.subject) }
      }
    }

    for (node in shapeNodes) {
      for (triple in bySubject[node].orEmpty()) {
        val predicate = triple.predicate.value
        val local = "sh:" + predicate.removePrefix(SH)
        when {
          predicate == SH + "xone" -> descriptions.add("sh:xone on $node")
          predicate == SH + "qualifiedValueShapesDisjoint" ->
              if ((triple.obj as? Literal)?.lexical in setOf("true", "1")) descriptions.add("sh:qualifiedValueShapesDisjoint true on $node")
          predicate == SH + "path" ->
              unsupportedPath(triple.obj)?.let { descriptions.add("the path of $node uses $it") }
          predicate == SH + "sparql" ->
              if (values(triple.obj, SH + "select").isEmpty()) {
                descriptions.add("sh:sparql on $node without sh:select (sh:ask constraints are not supported)")
                rejected.add(triple)
              }
          predicate == SH + "target" -> {
            val target = triple.obj
            val sparqlTarget = values(target, RDF + "type").any { it is Iri && it.value == SH + "SPARQLTarget" } &&
                values(target, SH + "select").isNotEmpty()
            if (!sparqlTarget) {
              descriptions.add("sh:target on $node that is not a sh:SPARQLTarget with sh:select")
              features.add(UnsupportedShaclFeature.CUSTOM_TARGET)
              rejected.add(triple)
            }
          }
          predicate in componentParameters -> {
            descriptions.add("the SPARQL-based constraint component ${componentParameters.getValue(predicate)} (<$predicate> on $node)")
            features.add(UnsupportedShaclFeature.SPARQL_CONSTRAINT_COMPONENT)
          }
          predicate.startsWith(SH) && predicate !in READ_BY_SHACL_SAIL && predicate != SH + "xone" -> {
            descriptions.add("$local on $node")
            if (predicate == SH + "values" || predicate == SH + "expression") features.add(UnsupportedShaclFeature.NODE_EXPRESSION)
          }
        }
      }
    }
    return Findings(descriptions.toList(), features, rejected)
  }
}
