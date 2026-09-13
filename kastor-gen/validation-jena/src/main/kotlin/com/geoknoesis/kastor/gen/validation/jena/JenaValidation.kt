package com.geoknoesis.kastor.gen.validation.jena

import com.geoknoesis.kastor.gen.runtime.ShaclSeverity
import com.geoknoesis.kastor.gen.runtime.ShaclViolation
import com.geoknoesis.kastor.gen.runtime.ValidationContext
import com.geoknoesis.kastor.gen.runtime.ValidationResult
import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Direction
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.jena.JenaBridge
import org.apache.jena.graph.Graph
import org.apache.jena.graph.GraphUtil
import org.apache.jena.graph.Node
import org.apache.jena.graph.NodeFactory
import org.apache.jena.riot.Lang
import org.apache.jena.riot.RDFParser
import org.apache.jena.shacl.ShaclValidator
import org.apache.jena.shacl.Shapes
import org.apache.jena.shacl.validation.ReportEntry
import org.apache.jena.shacl.validation.Severity
import org.apache.jena.sparql.graph.GraphFactory
import org.apache.jena.sparql.path.P_Link
import com.geoknoesis.kastor.rdf.vocab.SHACL as KSHACL

/**
 * Jena-based SHACL validation adapter backed by `jena-shacl`.
 *
 * Shapes can be supplied in two ways:
 * - **Separate shapes graph** (recommended): `JenaValidation(shapesGraph)` or [fromTurtle]. The
 *   shapes are copied and parsed once at construction and reused for every [validate] call.
 * - **Shapes embedded in the data graph** (no-arg constructor, backward compatible): shapes are
 *   parsed from the data graph on each call. If the data graph declares no shapes the result is
 *   [ValidationResult.Ok].
 *
 * [validate] evaluates only the shapes that target the given focus node and returns the results
 * whose `sh:focusNode` is that node. Jena-backed Kastor graphs are validated in place (no copy);
 * other graphs are converted to a Jena graph per call.
 *
 * Engine failures (malformed shapes, unsupported focus terms, ...) are thrown rather than being
 * reported as violations or silently accepted.
 */
class JenaValidation private constructor(private val fixedShapes: Shapes?) : ValidationContext {

  /** Validates against SHACL shapes found in the data graph passed to [validate]. */
  constructor() : this(null as Shapes?)

  /** Validates against the SHACL shapes in [shapes] (copied and parsed once). */
  constructor(shapes: RdfGraph) : this(parseShapes(copyOf(JenaBridge.toJenaGraph(shapes))))

  companion object {
    private const val SH = "http://www.w3.org/ns/shacl#"
    private const val RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"
    private const val MAX_SHAPE_ANCESTOR_DEPTH = 16

    private val SHAPE_MARKERS: List<Pair<Node, Node?>> = listOf(
      NodeFactory.createURI(RDF_TYPE) to NodeFactory.createURI("${SH}NodeShape"),
      NodeFactory.createURI(RDF_TYPE) to NodeFactory.createURI("${SH}PropertyShape"),
      NodeFactory.createURI("${SH}targetClass") to null,
      NodeFactory.createURI("${SH}targetNode") to null,
      NodeFactory.createURI("${SH}targetSubjectsOf") to null,
      NodeFactory.createURI("${SH}targetObjectsOf") to null,
    )
    private val SH_MESSAGE: Node = NodeFactory.createURI("${SH}message")

    /** Creates a validator from SHACL shapes written in Turtle. */
    @JvmStatic
    fun fromTurtle(shapesTurtle: String): JenaValidation {
      val graph = RDFParser.create().fromString(shapesTurtle).lang(Lang.TURTLE).toGraph()
      return JenaValidation(parseShapes(graph))
    }

    private fun parseShapes(graph: Graph): Shapes = Shapes.parse(graph)

    private fun copyOf(graph: Graph): Graph =
      GraphFactory.createDefaultGraph().also { GraphUtil.addInto(it, graph) }
  }

  override fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult {
    val focusNode = toFocusNode(focus)
    val dataGraph = JenaBridge.toJenaGraph(data)
    val shapes = fixedShapes ?: run {
      if (!declaresShapes(dataGraph)) return ValidationResult.Ok
      parseShapes(dataGraph)
    }

    val report = ShaclValidator.get().validate(shapes, dataGraph, focusNode)
    if (report.conforms()) return ValidationResult.Ok

    val items = report.entries
      .filter { it.focusNode() == focusNode }
      .map { toViolation(it, shapes.graph) }
    return if (items.isEmpty()) ValidationResult.Ok else ValidationResult.Violations(items)
  }

  private fun declaresShapes(graph: Graph): Boolean =
    SHAPE_MARKERS.any { (p, o) -> graph.contains(Node.ANY, p, o ?: Node.ANY) }

  private fun toFocusNode(focus: RdfTerm): Node = when (focus) {
    is Iri -> NodeFactory.createURI(focus.value)
    is BlankNode -> NodeFactory.createBlankNode(focus.id.removePrefix("_:"))
    else -> throw IllegalArgumentException("SHACL focus node must be an IRI or blank node, got: $focus")
  }

  private fun toViolation(entry: ReportEntry, shapesGraph: Graph): ShaclViolation {
    val source = entry.source()
    val component = entry.sourceConstraintComponent()?.takeIf { it.isURI }
    val path = (entry.resultPath() as? P_Link)?.node?.takeIf { it.isURI }?.let { Iri(it.uri) }
    val shapeMessage = source?.let { firstObject(shapesGraph, it, SH_MESSAGE) }
      ?.takeIf { it.isLiteral }?.literalLexicalForm
    val message = shapeMessage
      ?: entry.message()?.takeIf { it.isNotBlank() }
      ?: "SHACL constraint ${component?.localName ?: "violation"} failed"

    return ShaclViolation(
      focusNode = toTerm(entry.focusNode()) as RdfResource,
      shapeIri = source?.let { resolveShapeIri(shapesGraph, it) } ?: KSHACL.Shape,
      constraintIri = component?.let { Iri(it.uri) } ?: KSHACL.ConstraintComponent,
      path = path,
      actualValue = entry.value()?.let(::toTerm),
      expectedValue = if (source != null && component != null) parameterValue(shapesGraph, source, component) else null,
      message = message,
      severity = when (entry.severity()) {
        Severity.Warning -> ShaclSeverity.Warning
        Severity.Info -> ShaclSeverity.Info
        else -> ShaclSeverity.Violation
      },
    )
  }

  /** Returns [shape] if it is an IRI, otherwise the nearest IRI-named shape that references it. */
  private fun resolveShapeIri(graph: Graph, shape: Node): Iri? {
    if (shape.isURI) return Iri(shape.uri)
    val seen = HashSet<Node>()
    var frontier = listOf(shape)
    repeat(MAX_SHAPE_ANCESTOR_DEPTH) {
      val next = ArrayList<Node>()
      for (node in frontier) {
        if (!seen.add(node)) continue
        val it = graph.find(Node.ANY, Node.ANY, node)
        try {
          while (it.hasNext()) {
            val subject = it.next().subject
            if (subject.isURI) return Iri(subject.uri)
            next.add(subject)
          }
        } finally {
          it.close()
        }
      }
      if (next.isEmpty()) return null
      frontier = next
    }
    return null
  }

  /** e.g. sh:DatatypeConstraintComponent -> the shape's sh:datatype value (non-list values only). */
  private fun parameterValue(graph: Graph, shape: Node, component: Node): RdfTerm? {
    val local = component.localName.removeSuffix("ConstraintComponent")
    if (local.isEmpty() || local == component.localName) return null
    val parameter = NodeFactory.createURI(SH + local.replaceFirstChar { it.lowercaseChar() })
    return firstObject(graph, shape, parameter)?.takeUnless { it.isBlank }?.let(::toTerm)
  }

  private fun firstObject(graph: Graph, subject: Node, predicate: Node): Node? {
    val it = graph.find(subject, predicate, Node.ANY)
    return try {
      if (it.hasNext()) it.next().`object` else null
    } finally {
      it.close()
    }
  }

  private fun toTerm(node: Node): RdfTerm = when {
    node.isURI -> Iri(node.uri)
    node.isBlank -> BlankNode(node.blankNodeLabel)
    node.isLiteral -> {
      val lang = node.literalLanguage
      if (!lang.isNullOrEmpty()) {
        LangString(node.literalLexicalForm, lang, Direction.fromToken(node.literalBaseDirection?.direction()))
      } else {
        val datatype = Iri(node.literalDatatypeURI)
        runCatching { Literal(node.literalLexicalForm, datatype) }
          .getOrElse { TypedLiteral(node.literalLexicalForm, datatype) }
      }
    }
    node.isTripleTerm -> {
      val t = node.triple
      TripleTerm(RdfTriple(toTerm(t.subject) as RdfResource, Iri(t.predicate.uri), toTerm(t.`object`)))
    }
    else -> throw IllegalStateException("Unsupported node in SHACL report: $node")
  }
}
