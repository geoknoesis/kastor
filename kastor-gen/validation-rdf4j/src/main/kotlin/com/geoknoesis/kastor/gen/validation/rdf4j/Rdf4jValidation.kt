package com.geoknoesis.kastor.gen.validation.rdf4j

import com.geoknoesis.kastor.gen.runtime.GraphStateCache
import com.geoknoesis.kastor.gen.runtime.ShaclSeverity
import com.geoknoesis.kastor.gen.runtime.ShaclViolation
import com.geoknoesis.kastor.gen.runtime.ValidationContext
import com.geoknoesis.kastor.gen.runtime.ValidationResult
import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.TypedLiteral
import org.eclipse.rdf4j.model.BNode
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.model.Triple
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.model.vocabulary.OWL
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.eclipse.rdf4j.model.vocabulary.RDF4J
import org.eclipse.rdf4j.model.vocabulary.RDFS
import org.eclipse.rdf4j.model.vocabulary.SHACL
import org.eclipse.rdf4j.repository.RepositoryConnection
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.eclipse.rdf4j.sail.shacl.ShaclSail
import org.eclipse.rdf4j.sail.shacl.ShaclSailValidationException
import com.geoknoesis.kastor.rdf.VersionedRdfGraph
import com.geoknoesis.kastor.rdf.vocab.SHACL as KSHACL
import org.eclipse.rdf4j.model.Literal as Rdf4jLiteral

/**
 * RDF4J-based SHACL validation adapter backed by [ShaclSail].
 *
 * Shapes can be supplied in two ways:
 * - **Separate shapes graph** (recommended): `Rdf4jValidation(shapesGraph)` or [fromTurtle]; the shapes are
 *   converted once at construction.
 * - **Shapes embedded in the data graph** (no-arg constructor, backward compatible): the shapes are extracted from
 *   the data graph (once per graph version). If the data graph declares no shapes the result is
 *   [ValidationResult.Ok].
 *
 * ## Cost model
 * ShaclSail validates data held in its own store, so the Kastor graph is converted to RDF4J statements. The
 * validator keeps one in-memory repository per data graph, for up to [maxCachedGraphs] graphs (see
 * [GraphStateCache] for the exact rules). A graph is (re)loaded only when it is new or its content changed:
 * - Graphs that implement [VersionedRdfGraph] (such as `MemoryGraph` and the named graphs of the memory repository)
 *   are checked in O(1) by comparing the modification stamp with the one of the loaded copy. A new handle of the same
 *   named graph (`repository.getGraph(name)` on every call) finds the copy loaded for an equal handle.
 * - Other graphs (e.g. graphs of an RDF4J or Jena repository) are identified by an order-independent SHA-256-based
 *   digest of their triples, which costs one pass over the triples per call but no conversion or store writes; a new
 *   handle with unchanged content finds the loaded copy.
 *
 * Validating many nodes of one graph therefore converts and loads it once. A reload is atomic: the data, the change
 * marker and the embedded shapes are replaced together after the store transaction commits, so a failed conversion
 * or load leaves the previous state intact.
 *
 * ## Cache size
 * The number of cached graphs defaults to [DEFAULT_MAX_CACHED_GRAPHS] and can be set per validator (constructor
 * argument) or process-wide with the system property [MAX_CACHED_GRAPHS_PROPERTY] (read when a validator is created;
 * this is how to size the validators shared by generated wrappers). When more graphs than that are validated, the
 * least recently used copy that no call is using is released. A call never waits for the validation of another
 * graph: when every cached copy is in use, the call validates in a private temporary store that is released when
 * the call returns. Raise the limit when more graphs than the default are validated repeatedly (each miss converts
 * and loads the whole graph).
 *
 * ## Locking
 * The data graph is read only while the validator holds no lock, so [validate] may be called inside a repository
 * transaction (`repository.transaction { wrapper.validate() }`) while other threads validate the same graph.
 *
 * Each [validate] call evaluates only the shapes that target the focus node: the target declarations
 * (`sh:targetClass` including `rdfs:subClassOf` instances and implicit class targets, `sh:targetNode`,
 * `sh:targetSubjectsOf`, `sh:targetObjectsOf`) are resolved for the focus node against the loaded data, and those
 * shapes are validated with `sh:targetNode <focus>` in a transaction that is always rolled back. Shapes reached
 * through `sh:node`, `sh:property` etc. are evaluated as usual. When no shape targets the focus node the result is
 * [ValidationResult.Ok] without running the engine. Shapes with other target kinds (e.g. SPARQL-based `sh:target`)
 * are validated for all their targets and the results are filtered to the focus node.
 *
 * Calls for the same data graph are serialized on that graph's repository; calls for different graphs run
 * concurrently. [close] releases every repository (together with the loaded data); a repository that a call is
 * still using is released when that call returns. A validator is safe to share between threads.
 *
 * [validate] returns the `sh:ValidationResult`s whose `sh:focusNode` is the requested node. Engine failures other
 * than SHACL validation results are rethrown, never mapped to `Ok` or to a violation.
 *
 * Term conversion is exact (IRIs, blank node ids, language tags, datatypes). RDF4J 5.x has no literal
 * base-direction support, so the direction of an RDF 1.2 directional language string is dropped (the language tag
 * is kept).
 */
class Rdf4jValidation private constructor(
  private val fixedShapes: List<Statement>?,
  /** Maximum number of data graphs whose converted copy is kept (one in-memory store each). */
  val maxCachedGraphs: Int,
) : ValidationContext, AutoCloseable {

  /** Validates against SHACL shapes found in the data graph passed to [validate]. */
  constructor() : this(null as List<Statement>?, configuredMaxCachedGraphs())

  /** Validates against the SHACL shapes in [shapes] (converted once at construction). */
  constructor(shapes: RdfGraph) : this(toStatements(shapes.getTriples()), configuredMaxCachedGraphs())

  /**
   * Validates against the SHACL shapes in [shapes] (or, when null, those embedded in the data graph), keeping the
   * converted copies of at most [maxCachedGraphs] data graphs.
   *
   * @throws IllegalArgumentException when [maxCachedGraphs] is less than 1
   */
  constructor(shapes: RdfGraph?, maxCachedGraphs: Int) : this(shapes?.let { toStatements(it.getTriples()) }, maxCachedGraphs)

  /** Test hook: runs inside the load transaction, before commit (to simulate a store failure). */
  @Volatile internal var beforeLoadCommit: (() -> Unit)? = null
  /** Test hook: creates the store of a data graph. */
  @Volatile internal var repositoryFactory: () -> SailRepository = ::newRepository

  /**
   * The converted copy of one data graph: its store and the shapes embedded in the data. A reload updates it in
   * place (under the cache entry lock, which also guards [embeddedShapes]).
   */
  private class Store(val repository: SailRepository, var embeddedShapes: ShapeIndex?)

  private val cache = GraphStateCache<Store>(
    maxEntries = maxCachedGraphs,
    exclusive = true,
    load = ::load,
    release = { it.repository.shutDown() },
    owner = "Rdf4jValidation",
  )

  /** Test hook: number of content digests computed. */
  internal val digestCount: Int get() = cache.digestCount
  /** Test hook: number of graphs converted and loaded into a store. */
  internal val loadCount: Int get() = cache.loadCount
  /** Test hook: number of validations that ran in a private temporary store. */
  internal val temporaryStoreCount: Int get() = cache.temporaryCount
  private val fixedShapeIndex: ShapeIndex? by lazy(LazyThreadSafetyMode.PUBLICATION) {
    fixedShapes?.let { ShapeIndex(it) }
  }

  companion object {
    private val vf = SimpleValueFactory.getInstance()
    private val TARGET_PREDICATES: Set<IRI> =
      setOf(SHACL.TARGET_CLASS, SHACL.TARGET_NODE, SHACL.TARGET_SUBJECTS_OF, SHACL.TARGET_OBJECTS_OF)
    private const val MAX_SHAPE_ANCESTOR_DEPTH = 16

    /** Default maximum number of data graphs whose converted copy is kept (one in-memory store each). */
    const val DEFAULT_MAX_CACHED_GRAPHS: Int = 16

    /**
     * System property that overrides [DEFAULT_MAX_CACHED_GRAPHS] for validators created without an explicit size
     * (a positive integer; other values are ignored).
     */
    const val MAX_CACHED_GRAPHS_PROPERTY: String = "kastor.validation.rdf4j.maxCachedGraphs"

    @Deprecated(
      "The cache size is configurable: see maxCachedGraphs, DEFAULT_MAX_CACHED_GRAPHS and MAX_CACHED_GRAPHS_PROPERTY",
      ReplaceWith("Rdf4jValidation.DEFAULT_MAX_CACHED_GRAPHS"),
    )
    const val MAX_CACHED_GRAPHS: Int = DEFAULT_MAX_CACHED_GRAPHS

    private fun configuredMaxCachedGraphs(): Int =
      System.getProperty(MAX_CACHED_GRAPHS_PROPERTY)?.trim()?.toIntOrNull()?.takeIf { it >= 1 } ?: DEFAULT_MAX_CACHED_GRAPHS

    /** Creates a validator from SHACL shapes written in Turtle. */
    @JvmStatic
    fun fromTurtle(shapesTurtle: String): Rdf4jValidation = Rdf4jValidation(Rdf.parse(shapesTurtle, "TURTLE"))

    private fun toStatements(triples: List<RdfTriple>): List<Statement> =
      triples.map { t -> vf.createStatement(toResource(t.subject), vf.createIRI(t.predicate.value), toValue(t.obj)) }

    private fun toResource(term: RdfTerm): Resource = when (term) {
      is Iri -> vf.createIRI(term.value)
      is BlankNode -> vf.createBNode(term.id.removePrefix("_:"))
      else -> throw IllegalArgumentException("Expected an IRI or blank node, got: $term")
    }

    private fun toValue(term: RdfTerm): Value = when (term) {
      is Iri, is BlankNode -> toResource(term)
      // RDF4J 5.x cannot carry a base direction; keep the language tag.
      is LangString -> vf.createLiteral(term.lexical, term.lang)
      is Literal -> vf.createLiteral(term.lexical, vf.createIRI(term.datatype.value))
      is TripleTerm -> vf.createTriple(toResource(term.triple.subject), vf.createIRI(term.triple.predicate.value), toValue(term.triple.obj))
      else -> throw IllegalArgumentException("Cannot convert $term to an RDF4J value")
    }

    private fun toTerm(value: Value): RdfTerm = when (value) {
      is IRI -> Iri(value.stringValue())
      is BNode -> BlankNode(value.id)
      is Rdf4jLiteral -> {
        val lang = value.language.orElse(null)
        if (lang != null) {
          LangString(value.label, lang)
        } else {
          val datatype = Iri(value.datatype.stringValue())
          runCatching { Literal(value.label, datatype) }.getOrElse { TypedLiteral(value.label, datatype) }
        }
      }
      is Triple -> TripleTerm(RdfTriple(toTerm(value.subject) as RdfResource, Iri(value.predicate.stringValue()), toTerm(value.`object`)))
      else -> throw IllegalStateException("Unsupported RDF4J value in SHACL report: $value")
    }

    private fun newRepository(): SailRepository {
      val sail = ShaclSail(MemoryStore()).apply {
        // Report every result rather than the engine's default per-constraint cap.
        validationResultsLimitTotal = -1
        validationResultsLimitPerConstraint = -1
      }
      return SailRepository(sail).apply { init() }
    }

    /** Shape statements embedded in a data graph: everything reachable from node/property shapes and targets. */
    private fun extractEmbeddedShapes(statements: List<Statement>): List<Statement> {
      val bySubject = statements.groupBy { it.subject }
      val roots = statements.filter { st ->
        st.predicate in TARGET_PREDICATES ||
          (st.predicate == RDF.TYPE && (st.`object` == SHACL.NODE_SHAPE || st.`object` == SHACL.PROPERTY_SHAPE))
      }.map { it.subject }
      if (roots.isEmpty()) return emptyList()
      val seen = LinkedHashSet<Resource>()
      val pending = ArrayDeque(roots)
      val result = ArrayList<Statement>()
      while (pending.isNotEmpty()) {
        val subject = pending.removeFirst()
        if (!seen.add(subject)) continue
        bySubject[subject].orEmpty().forEach { st ->
          result += st
          val obj = st.`object`
          // Follow nested shape structure (blank nodes, RDF lists, referenced shapes), not data IRIs such as targets.
          if (obj is BNode || (obj is IRI && st.predicate != SHACL.TARGET_NODE && st.predicate != SHACL.TARGET_CLASS &&
              st.predicate != RDF.TYPE && bySubject[obj]?.any { it.predicate.namespace == SHACL.NAMESPACE } == true)
          ) {
            pending += obj as Resource
          }
        }
      }
      return result
    }
  }

  /** Number of data graphs currently cached (for tests). */
  internal fun cachedGraphCount(): Int = cache.size

  /** Shapes with their target declarations, indexed once. */
  private class ShapeIndex(val statements: List<Statement>) {
    val model: Model = LinkedHashModel(statements)
    val targets: Map<Resource, List<Statement>> = statements.filter { it.predicate in TARGET_PREDICATES }.groupBy { it.subject }
    /** Node shapes that are also classes: SHACL implicit class targets. */
    val implicitClassTargets: Set<Resource> = statements
      .filter { it.predicate == RDF.TYPE && (it.`object` == RDFS.CLASS || it.`object` == OWL.CLASS) }
      .map { it.subject }
      .filter { model.contains(it, RDF.TYPE, SHACL.NODE_SHAPE) }
      .toSet()
    /** Target kinds this adapter cannot resolve per focus node (e.g. SPARQL-based sh:target). */
    val hasOtherTargets: Boolean = statements.any { it.predicate == SHACL.TARGET_PROP }
    /** Shape statements without target declarations. */
    val untargeted: List<Statement> = statements.filterNot { st ->
      st.predicate in TARGET_PREDICATES ||
        (st.subject in implicitClassTargets && st.predicate == RDF.TYPE && (st.`object` == RDFS.CLASS || st.`object` == OWL.CLASS))
    }
  }

  override fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult {
    if (focus !is Iri && focus !is BlankNode) {
      throw IllegalArgumentException("SHACL focus node must be an IRI or blank node, got: $focus")
    }
    val focusValue = toResource(focus)
    // The cache reads the data graph before it takes the graph's entry lock; the block only uses the store.
    return cache.use(data) { store -> validateLoaded(store, focusValue) }
  }

  /**
   * Loads a snapshot of a data graph into [previous]'s store (or a new one). The statements are converted and
   * indexed first and the store content is replaced in one transaction, so a failure at any step leaves the
   * previously loaded data and shapes in place.
   */
  private fun load(triples: List<RdfTriple>, previous: Store?): Store {
    val statements = toStatements(triples)
    val newShapes = if (fixedShapes == null) ShapeIndex(extractEmbeddedShapes(statements)) else null
    val repo = previous?.repository ?: repositoryFactory()
    try {
      repo.connection.use { connection ->
        connection.begin()
        try {
          connection.clear()
          connection.add(statements)
          beforeLoadCommit?.invoke()
          connection.commit()
        } catch (e: Throwable) {
          if (connection.isActive) connection.rollback()
          throw e
        }
      }
    } catch (e: Throwable) {
      if (previous == null) {
        try {
          repo.shutDown()
        } catch (shutdownFailure: Throwable) {
          e.addSuppressed(shutdownFailure)
        }
      }
      throw e
    }
    // Only after the commit: the embedded shapes change together with the data.
    return previous?.also { it.embeddedShapes = newShapes } ?: Store(repo, newShapes)
  }

  /** Validates [focusValue] against the data loaded in [store]; runs under the store's cache entry lock. */
  private fun validateLoaded(store: Store, focusValue: Resource): ValidationResult {
    val repo = store.repository
    val shapes = fixedShapeIndex ?: store.embeddedShapes ?: return ValidationResult.Ok
    if (shapes.statements.isEmpty()) return ValidationResult.Ok

    return repo.connection.use { connection ->
      if (shapes.hasOtherTargets) {
        validateIn(connection, shapes.statements, focusValue, shapes.model)
      } else {
        val targeting = targetingShapes(connection, shapes, focusValue)
        if (targeting.isEmpty()) {
          ValidationResult.Ok
        } else {
          val restricted = if (focusValue is BNode) {
            // RDF4J does not accept a blank node as sh:targetNode: keep the selected shapes' own target
            // declarations (other shapes stay untargeted) and filter the report to the focus node.
            shapes.untargeted + shapes.statements.filter { st ->
              st.subject in targeting && (st.predicate in TARGET_PREDICATES ||
                (st.subject in shapes.implicitClassTargets && st.predicate == RDF.TYPE))
            }
          } else {
            shapes.untargeted + targeting.map { vf.createStatement(it, SHACL.TARGET_NODE, focusValue) }
          }
          validateIn(connection, restricted, focusValue, shapes.model)
        }
      }
    }
  }

  /**
   * Releases the repositories and the data loaded into them. A repository that a [validate] call is still using is
   * released when that call returns (this method does not wait for it). Every repository is released even when one
   * fails to shut down; the first failure is then thrown with the others suppressed.
   */
  override fun close() {
    cache.close()
  }

  /** The shapes whose target declarations select [focus] in the loaded data. */
  private fun targetingShapes(connection: RepositoryConnection, shapes: ShapeIndex, focus: Resource): Set<Resource> {
    val types by lazy(LazyThreadSafetyMode.NONE) { instanceTypes(connection, focus) }
    val result = LinkedHashSet<Resource>()
    shapes.targets.forEach { (shape, declarations) ->
      val selected = declarations.any { st ->
        val target = st.`object`
        when (st.predicate) {
          SHACL.TARGET_NODE -> target == focus
          SHACL.TARGET_CLASS -> target in types
          SHACL.TARGET_SUBJECTS_OF -> target is IRI && connection.hasStatement(focus, target, null, false)
          SHACL.TARGET_OBJECTS_OF -> target is IRI && connection.hasStatement(null, target, focus, false)
          else -> false
        }
      }
      if (selected) result += shape
    }
    shapes.implicitClassTargets.forEach { if (it in types) result += it }
    return result
  }

  /** `rdf:type`s of [focus] and their transitive `rdfs:subClassOf` superclasses (ShaclSail's RDFS reasoning). */
  private fun instanceTypes(connection: RepositoryConnection, focus: Resource): Set<Value> {
    val seen = LinkedHashSet<Value>()
    val pending = ArrayDeque<Value>()
    connection.getStatements(focus, RDF.TYPE, null, false).use { result -> result.forEach { pending += it.`object` } }
    while (pending.isNotEmpty()) {
      val type = pending.removeFirst()
      if (!seen.add(type) || type !is Resource) continue
      connection.getStatements(type, RDFS.SUBCLASSOF, null, false).use { result -> result.forEach { pending += it.`object` } }
    }
    return seen
  }

  private fun validateIn(
    connection: RepositoryConnection,
    shapes: List<Statement>,
    focus: Resource,
    shapesModel: Model,
  ): ValidationResult {
    connection.begin()
    try {
      connection.add(shapes, RDF4J.SHACL_SHAPE_GRAPH)
      connection.prepare()
      return ValidationResult.Ok
    } catch (e: Exception) {
      val validationFailure = generateSequence<Throwable>(e) { it.cause }
        .firstOrNull { it is ShaclSailValidationException } as ShaclSailValidationException?
        ?: throw e
      return toResult(validationFailure.validationReportAsModel(), focus, shapesModel)
    } finally {
      if (connection.isActive) connection.rollback()
    }
  }

  private fun toResult(report: Model, focus: Resource, shapes: Model): ValidationResult {
    val items = report.filter(null, SHACL.RESULT, null).objects()
      .filterIsInstance<Resource>()
      .filter { first(report, it, SHACL.FOCUS_NODE) == focus }
      .map { toViolation(report, it, shapes) }
    return if (items.isEmpty()) ValidationResult.Ok else ValidationResult.Violations(items)
  }

  private fun toViolation(report: Model, result: Resource, shapes: Model): ShaclViolation {
    val source = first(report, result, SHACL.SOURCE_SHAPE) as? Resource
    val component = first(report, result, SHACL.SOURCE_CONSTRAINT_COMPONENT) as? IRI
    val shapeMessage = source?.let { first(shapes, it, SHACL.MESSAGE) as? Rdf4jLiteral }?.label
    val resultMessage = (first(report, result, SHACL.RESULT_MESSAGE) as? Rdf4jLiteral)?.label
    val message = shapeMessage
      ?: resultMessage?.takeIf { it.isNotBlank() }
      ?: "SHACL constraint ${component?.localName ?: "violation"} failed"

    return ShaclViolation(
      focusNode = toTerm(first(report, result, SHACL.FOCUS_NODE)!!) as RdfResource,
      shapeIri = source?.let { resolveShapeIri(shapes, it) } ?: KSHACL.Shape,
      constraintIri = component?.let { Iri(it.stringValue()) } ?: KSHACL.ConstraintComponent,
      path = (first(report, result, SHACL.RESULT_PATH) as? IRI)?.let { Iri(it.stringValue()) },
      actualValue = first(report, result, SHACL.VALUE)?.let(::toTerm),
      expectedValue = if (source != null && component != null) parameterValue(shapes, source, component) else null,
      message = message,
      severity = when (first(report, result, SHACL.RESULT_SEVERITY)) {
        SHACL.WARNING -> ShaclSeverity.Warning
        SHACL.INFO -> ShaclSeverity.Info
        else -> ShaclSeverity.Violation
      },
    )
  }

  private fun first(model: Model, subject: Resource, predicate: IRI): Value? =
    model.filter(subject, predicate, null).objects().firstOrNull()

  /** Returns [shape] if it is an IRI, otherwise the nearest IRI-named shape that references it. */
  private fun resolveShapeIri(shapes: Model, shape: Resource): Iri? {
    if (shape is IRI) return Iri(shape.stringValue())
    val seen = HashSet<Resource>()
    var frontier = listOf(shape)
    repeat(MAX_SHAPE_ANCESTOR_DEPTH) {
      val next = ArrayList<Resource>()
      for (node in frontier) {
        if (!seen.add(node)) continue
        for (subject in shapes.filter(null, null, node).subjects()) {
          if (subject is IRI) return Iri(subject.stringValue())
          next.add(subject)
        }
      }
      if (next.isEmpty()) return null
      frontier = next
    }
    return null
  }

  /** e.g. sh:DatatypeConstraintComponent -> the shape's sh:datatype value (non-list values only). */
  private fun parameterValue(shapes: Model, shape: Resource, component: IRI): RdfTerm? {
    val local = component.localName.removeSuffix("ConstraintComponent")
    if (local.isEmpty() || local == component.localName) return null
    val parameter = vf.createIRI(SHACL.NAMESPACE, local.replaceFirstChar { it.lowercaseChar() })
    return first(shapes, shape, parameter)?.takeUnless { it is BNode }?.let(::toTerm)
  }
}
