package com.geoknoesis.kastor.gen.validation.jena

import com.geoknoesis.kastor.gen.runtime.GraphStateCache
import com.geoknoesis.kastor.gen.runtime.KastorGenInternalApi
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
import com.geoknoesis.kastor.rdf.VersionedRdfGraph
import com.geoknoesis.kastor.rdf.jena.JenaBridge
import org.apache.jena.graph.Graph
import org.apache.jena.graph.GraphUtil
import org.apache.jena.graph.Node
import org.apache.jena.graph.NodeFactory
import org.apache.jena.graph.Triple
import org.apache.jena.riot.Lang
import org.apache.jena.riot.RDFParser
import org.apache.jena.shacl.ShaclValidator
import org.apache.jena.shacl.Shapes
import org.apache.jena.shacl.validation.ReportEntry
import org.apache.jena.shacl.validation.Severity
import org.apache.jena.sparql.core.GraphView
import org.apache.jena.sparql.graph.GraphFactory
import org.apache.jena.sparql.path.P_Link
import java.util.concurrent.atomic.AtomicIntegerArray
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
 * whose `sh:focusNode` is that node.
 *
 * ## Cost model
 * - A **standalone Jena graph** (`JenaBridge.fromJenaModel`, a graph parsed by the Jena provider) is validated in
 *   place: nothing is copied, nothing is cached (embedded shapes are parsed per call; use [validateAll] for many
 *   nodes).
 * - **Every other graph** - a graph of a Jena repository (whose store is only readable inside a transaction, so the
 *   engine needs a detached copy), a graph of another provider - is copied into a Jena graph, and the copy (with the
 *   shapes parsed from it, for embedded shapes) is cached per data graph, for up to [maxCachedGraphs] graphs: a
 *   graph is copied again only when it is new or its content changed. A Jena-backed graph is copied **natively**
 *   (Jena graph to Jena graph, inside one read transaction of its repository), so statements that Kastor's own term
 *   model rejects (a language tag such as `"x"@abcdefghi`, legal in Turtle) are validated like any other.
 *   - Graphs with a modification stamp ([VersionedRdfGraph]: `MemoryGraph`, the named graphs of the memory
 *     repository, repositories whose provider stamps its graphs) are checked in O(1), and a new handle of the same
 *     graph finds the copy of an equal handle. When the stamp moved (it is usually repository-wide) the graph is
 *     read once, by one caller - concurrent callers wait for it - and the copy is kept when the content of this
 *     graph turns out to be unchanged.
 *   - Graphs without a stamp are **read in full on every call** (`getTriples()`, a digest of every triple) to find
 *     out whether the copy is still current; only the copy and the shapes parse are saved. Validate many nodes of
 *     such a graph with one [validateAll] call (one read), or create the validator with `assumeImmutable = true`
 *     when the graphs do not change while the validator lives (a graph found again by its handle is then not read
 *     again; changes are not detected).
 *
 * The data graph is read while the validator holds no lock, and on the calling thread, so [validate] may be called
 * inside a repository transaction: it validates what that thread reads (its uncommitted writes included), and a copy
 * is only ever served to a caller whose own modification stamp it was made for. Validations of one graph run
 * concurrently on its copy.
 *
 * The cache size defaults to [DEFAULT_MAX_CACHED_GRAPHS]; set it with the constructor argument or process-wide with
 * the system property [MAX_CACHED_GRAPHS_PROPERTY] (read when a validator is created). When every cached copy is in
 * use, a call converts into a private temporary copy instead of waiting; at most [maxCachedGraphs] temporary copies
 * are made that way at a time. Beyond that a call waits for a free copy for a quarter of a second and then makes a
 * private copy all the same: a validation is slowed down under such a load, it neither stalls nor fails. A thread
 * that is interrupted while it waits gets a
 * [com.geoknoesis.kastor.gen.runtime.GraphStateCacheInterruptedException] (its interrupt status stays set). [close]
 * drops the cached copies.
 *
 * Engine failures (malformed shapes, unsupported focus terms, ...) are thrown rather than being
 * reported as violations or silently accepted.
 */
@OptIn(KastorGenInternalApi::class)
class JenaValidation private constructor(
  private val fixedShapes: Shapes?,
  /** Maximum number of data graphs whose converted copy is kept. */
  val maxCachedGraphs: Int,
  /** Whether graphs without a modification stamp are assumed not to change while this validator lives. */
  val assumeImmutable: Boolean,
) : ValidationContext {

  /** Validates against SHACL shapes found in the data graph passed to [validate]. */
  constructor() : this(null as Shapes?, configuredMaxCachedGraphs(), false)

  /** Validates against the SHACL shapes in [shapes] (copied and parsed once). */
  constructor(shapes: RdfGraph) : this(parseShapes(copyOf(JenaBridge.toJenaGraph(shapes))), configuredMaxCachedGraphs(), false)

  /**
   * Validates against the SHACL shapes in [shapes] (or, when null, those embedded in the data graph), keeping the
   * converted copies of at most [maxCachedGraphs] data graphs.
   *
   * @throws IllegalArgumentException when [maxCachedGraphs] is less than 1
   */
  constructor(shapes: RdfGraph?, maxCachedGraphs: Int) : this(shapes, maxCachedGraphs, false)

  /**
   * Validates against the SHACL shapes in [shapes] (or, when null, those embedded in the data graph).
   *
   * @param maxCachedGraphs maximum number of data graphs whose converted copy is kept (by default the configured
   *   size, see [MAX_CACHED_GRAPHS_PROPERTY])
   * @param assumeImmutable the caller guarantees that data graphs **without a modification stamp** do not change
   *   while this validator lives: such a graph, once copied, is not read again when it is passed again (the same
   *   graph object, or a handle equal to it), instead of being read in full on every call to detect changes. A
   *   change of such a graph is then **not detected**: results keep describing the content first seen. Graphs with
   *   a modification stamp are still checked on every call.
   * @throws IllegalArgumentException when [maxCachedGraphs] is less than 1
   */
  constructor(shapes: RdfGraph? = null, maxCachedGraphs: Int = configuredMaxCachedGraphs(), assumeImmutable: Boolean) :
    this(shapes?.let { parseShapes(copyOf(JenaBridge.toJenaGraph(it))) }, maxCachedGraphs, assumeImmutable)

  /** The Jena copy of a data graph, with the shapes embedded in it (parsed on first use). */
  private class Converted(val graph: Graph) {
    val embeddedShapes: Shapes? by lazy { if (declaresShapes(graph)) parseShapes(graph) else null }
  }

  /** A fixed list of triples as a graph, for the provider's converter. */
  private class Snapshot(private val triples: List<RdfTriple>) : RdfGraph {
    override fun hasTriple(triple: RdfTriple): Boolean = triple in triples
    override fun getTriples(): List<RdfTriple> = triples
    override fun size(): Int = triples.size
  }

  private val events = AtomicIntegerArray(GraphStateCache.Event.entries.size)

  /** Test hook: called for every event of the cache, on the validating thread. */
  @Volatile internal var onCacheEvent: ((GraphStateCache.Event) -> Unit)? = null

  /**
   * Jena-backed graphs are copied natively: the statements never go through Kastor's term model (which rejects some
   * that Jena and Turtle accept), and a repository graph is copied inside one read transaction.
   */
  private object NativeCopy : GraphStateCache.NativeLoader<Converted> {
    override fun snapshot(graph: RdfGraph): Any? =
      if (JenaBridge.isJenaBacked(graph)) JenaBridge.copyToJenaModel(graph).graph else null

    override fun encode(snapshot: Any): Iterable<String> {
      val triples = ArrayList<String>()
      val it = (snapshot as Graph).find()
      try {
        while (it.hasNext()) triples += encode(it.next())
      } finally {
        it.close()
      }
      return triples
    }

    override fun load(snapshot: Any, previous: Converted?): Converted = Converted(snapshot as Graph)

    private fun encode(triple: Triple): String =
      StringBuilder().also { out ->
        encode(triple.subject, out)
        encode(triple.predicate, out)
        encode(triple.`object`, out)
      }.toString()

    /** Kind tag and length-prefixed fields: two different nodes never have the same encoding. */
    private fun encode(node: Node, out: StringBuilder) {
      fun field(value: String?) {
        val text = value ?: ""
        out.append(text.length).append(':').append(text)
      }
      when {
        node.isURI -> { out.append('U'); field(node.uri) }
        node.isBlank -> { out.append('B'); field(node.blankNodeLabel) }
        node.isLiteral -> {
          out.append('L')
          field(node.literalLexicalForm)
          field(node.literalDatatypeURI)
          field(node.literalLanguage)
          field(node.literalBaseDirection?.toString())
        }
        node.isTripleTerm -> {
          out.append('T')
          encode(node.triple.subject, out)
          encode(node.triple.predicate, out)
          encode(node.triple.`object`, out)
        }
        else -> { out.append('?'); field(node.toString()) }
      }
    }
  }

  private val cache = GraphStateCache<Converted>(
    maxEntries = maxCachedGraphs,
    // A converted copy is only read, so validations of one graph share it concurrently.
    exclusive = false,
    load = { triples, _ -> Converted(JenaBridge.toJenaGraph(Snapshot(triples))) },
    // Nothing to release: an in-memory Jena graph is reclaimed by the garbage collector (and may still be read).
    release = { },
    owner = "JenaValidation",
    settings = GraphStateCache.Settings(
      assumeImmutable = assumeImmutable,
      probe = { event ->
        events.incrementAndGet(event.ordinal)
        onCacheEvent?.invoke(event)
      },
    ),
    native = NativeCopy,
  )

  /** Test hook: number of graphs converted. */
  internal val loadCount: Int get() = events.get(GraphStateCache.Event.LOAD.ordinal)
  /** Test hook: number of times the content of a data graph was read. */
  internal val readCount: Int get() = events.get(GraphStateCache.Event.GRAPH_READ.ordinal)
  /** Test hook: number of converted graphs currently cached. */
  internal fun cachedGraphCount(): Int = cache.size

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

    /** Default maximum number of data graphs whose converted copy is kept. */
    const val DEFAULT_MAX_CACHED_GRAPHS: Int = 16

    /**
     * System property that overrides [DEFAULT_MAX_CACHED_GRAPHS] for validators created without an explicit size: a
     * positive integer. Any other value (`0`, `abc`) is reported once with a WARN log entry (`System.Logger`
     * `com.geoknoesis.kastor.gen.runtime.GraphStateCache`) and the default is used.
     */
    const val MAX_CACHED_GRAPHS_PROPERTY: String = "kastor.validation.jena.maxCachedGraphs"

    private fun configuredMaxCachedGraphs(): Int =
      GraphStateCache.configuredMaxEntries(MAX_CACHED_GRAPHS_PROPERTY, DEFAULT_MAX_CACHED_GRAPHS)

    private fun declaresShapes(graph: Graph): Boolean =
      SHAPE_MARKERS.any { (p, o) -> graph.contains(Node.ANY, p, o ?: Node.ANY) }

    /** Creates a validator from SHACL shapes written in Turtle. */
    @JvmStatic
    fun fromTurtle(shapesTurtle: String): JenaValidation {
      val graph = RDFParser.create().fromString(shapesTurtle).lang(Lang.TURTLE).toGraph()
      return JenaValidation(parseShapes(graph), configuredMaxCachedGraphs(), false)
    }

    private val shapeParses = java.util.concurrent.atomic.AtomicInteger()

    /** Test hook: number of shapes graphs parsed so far in this process. */
    internal val shapeParseCount: Int get() = shapeParses.get()

    private fun parseShapes(graph: Graph): Shapes {
      shapeParses.incrementAndGet()
      return Shapes.parse(graph)
    }

    private fun copyOf(graph: Graph): Graph =
      GraphFactory.createDefaultGraph().also { GraphUtil.addInto(it, graph) }
  }

  override fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult {
    val focusNode = toFocusNode(focus)
    return withData(data) { shapes, graph -> if (shapes == null) ValidationResult.Ok else validate(shapes, graph, focusNode) }
  }

  /**
   * Validates every node of [focuses] against one state of [data]: the graph is checked for changes (and, without a
   * modification stamp, read) once for all of them, and embedded shapes are parsed at most once.
   */
  override fun validateAll(data: RdfGraph, focuses: Collection<RdfTerm>): Map<RdfTerm, ValidationResult> {
    val nodes = LinkedHashMap<RdfTerm, Node>()
    focuses.forEach { focus -> if (focus !in nodes) nodes[focus] = toFocusNode(focus) }
    if (nodes.isEmpty()) return emptyMap()
    return withData(data) { shapes, graph ->
      nodes.mapValuesTo(LinkedHashMap()) { (_, node) -> if (shapes == null) ValidationResult.Ok else validate(shapes, graph, node) }
    }
  }

  /** Runs [block] with the shapes (null when the data declares none) and a Jena graph holding the content of [data]. */
  private fun <R> withData(data: RdfGraph, block: (Shapes?, Graph) -> R): R {
    val direct = standaloneJenaGraph(data)
    if (direct != null) {
      return block(fixedShapes ?: if (declaresShapes(direct)) parseShapes(direct) else null, direct)
    }
    // The cache reads the data graph (stamp or triples) before it takes any lock.
    return cache.use(data) { converted -> block(fixedShapes ?: converted.embeddedShapes, converted.graph) }
  }

  /**
   * The Jena graph behind a standalone Jena-backed Kastor graph, which the engine can read in place; null for every
   * graph that needs a copy. A graph of a Jena repository is a view of a dataset ([GraphView]) that is only readable
   * inside a transaction: `JenaBridge.toJenaGraph` would copy it in full on every call, so it goes through the cache
   * like the graphs of other providers.
   */
  private fun standaloneJenaGraph(data: RdfGraph): Graph? {
    if (data is VersionedRdfGraph || !JenaBridge.isJenaBacked(data)) return null
    return JenaBridge.getJenaGraph(data)?.takeUnless { it is GraphView }
  }

  /** Drops the cached copies of converted data graphs. */
  override fun close() {
    cache.close()
  }

  private fun validate(shapes: Shapes, dataGraph: Graph, focusNode: Node): ValidationResult {

    val report = ShaclValidator.get().validate(shapes, dataGraph, focusNode)
    if (report.conforms()) return ValidationResult.Ok

    val items = report.entries
      .filter { it.focusNode() == focusNode }
      .map { toViolation(it, shapes.graph) }
    return if (items.isEmpty()) ValidationResult.Ok else ValidationResult.Violations(items)
  }

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
