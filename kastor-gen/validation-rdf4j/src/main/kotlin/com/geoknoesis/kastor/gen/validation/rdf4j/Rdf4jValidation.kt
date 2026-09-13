package com.geoknoesis.kastor.gen.validation.rdf4j

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
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.eclipse.rdf4j.model.vocabulary.RDF4J
import org.eclipse.rdf4j.model.vocabulary.SHACL
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.eclipse.rdf4j.sail.shacl.ShaclSail
import org.eclipse.rdf4j.sail.shacl.ShaclSailValidationException
import com.geoknoesis.kastor.rdf.vocab.SHACL as KSHACL
import org.eclipse.rdf4j.model.Literal as Rdf4jLiteral

/**
 * RDF4J-based SHACL validation adapter backed by [ShaclSail].
 *
 * Shapes can be supplied in two ways:
 * - **Separate shapes graph** (recommended): `Rdf4jValidation(shapesGraph)` or [fromTurtle]. The
 *   shapes are converted once and loaded into the [RDF4J.SHACL_SHAPE_GRAPH] context of a single
 *   in-memory [ShaclSail] repository that is reused across calls. Each [validate] call adds the
 *   data in a transaction, runs validation via `prepare()`, and always rolls back, so no data is
 *   retained between calls. Calls are serialized on that repository; call [close] to release it.
 * - **Shapes embedded in the data graph** (no-arg constructor, backward compatible): a short-lived
 *   repository is built per call because the shapes can differ from call to call. If the data graph
 *   declares no shapes the result is [ValidationResult.Ok].
 *
 * The data graph is always converted to RDF4J statements: ShaclSail validates data held in its own
 * store, so the Kastor graph cannot be validated in place even when it is RDF4J-backed.
 *
 * [validate] returns the `sh:ValidationResult`s whose `sh:focusNode` is the requested node. Engine
 * failures other than SHACL validation results are rethrown, never mapped to `Ok` or to a violation.
 *
 * Term conversion is exact (IRIs, blank node ids, language tags, datatypes). RDF4J 5.x has no
 * literal base-direction support, so the direction of an RDF 1.2 directional language string is
 * dropped (the language tag is kept).
 */
class Rdf4jValidation private constructor(
  private val fixedShapes: List<Statement>?,
) : ValidationContext, AutoCloseable {

  /** Validates against SHACL shapes found in the data graph passed to [validate]. */
  constructor() : this(null as List<Statement>?)

  /** Validates against the SHACL shapes in [shapes] (converted once at construction). */
  constructor(shapes: RdfGraph) : this(toStatements(shapes))

  private val lock = Any()
  private var sharedRepository: SailRepository? = null
  private var closed = false
  private val fixedShapesModel: Model? by lazy(LazyThreadSafetyMode.PUBLICATION) {
    fixedShapes?.let { LinkedHashModel(it) }
  }

  companion object {
    private val vf = SimpleValueFactory.getInstance()
    private val TARGET_PREDICATES: Set<IRI> =
      setOf(SHACL.TARGET_CLASS, SHACL.TARGET_NODE, SHACL.TARGET_SUBJECTS_OF, SHACL.TARGET_OBJECTS_OF)
    private const val MAX_SHAPE_ANCESTOR_DEPTH = 16

    /** Creates a validator from SHACL shapes written in Turtle. */
    @JvmStatic
    fun fromTurtle(shapesTurtle: String): Rdf4jValidation = Rdf4jValidation(Rdf.parse(shapesTurtle, "TURTLE"))

    private fun toStatements(graph: RdfGraph): List<Statement> =
      graph.getTriples().map { t -> vf.createStatement(toResource(t.subject), vf.createIRI(t.predicate.value), toValue(t.obj)) }

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

    private fun newRepository(shapes: Collection<Statement>): SailRepository {
      val sail = ShaclSail(MemoryStore()).apply {
        // Report every result rather than the engine's default per-constraint cap.
        validationResultsLimitTotal = -1
        validationResultsLimitPerConstraint = -1
      }
      val repository = SailRepository(sail)
      repository.init()
      try {
        repository.connection.use { connection ->
          connection.begin()
          connection.add(shapes, RDF4J.SHACL_SHAPE_GRAPH)
          connection.commit()
        }
      } catch (e: Exception) {
        runCatching { repository.shutDown() }
        throw e
      }
      return repository
    }
  }

  override fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult {
    if (focus !is Iri && focus !is BlankNode) {
      throw IllegalArgumentException("SHACL focus node must be an IRI or blank node, got: $focus")
    }
    val focusValue = toResource(focus)
    val dataStatements = toStatements(data)

    val shapes = fixedShapes
    if (shapes == null) {
      if (!declaresShapes(dataStatements)) return ValidationResult.Ok
      val repository = newRepository(dataStatements)
      try {
        return validateIn(repository, dataStatements, focusValue) { LinkedHashModel(dataStatements) }
      } finally {
        repository.shutDown()
      }
    }

    synchronized(lock) {
      check(!closed) { "Rdf4jValidation has been closed" }
      val repository = sharedRepository ?: newRepository(shapes).also { sharedRepository = it }
      return validateIn(repository, dataStatements, focusValue) { fixedShapesModel!! }
    }
  }

  /** Releases the reusable ShaclSail repository (only created when shapes were supplied). */
  override fun close() {
    synchronized(lock) {
      closed = true
      sharedRepository?.shutDown()
      sharedRepository = null
    }
  }

  private fun declaresShapes(statements: List<Statement>): Boolean = statements.any { st ->
    st.predicate in TARGET_PREDICATES ||
      (st.predicate == RDF.TYPE && (st.`object` == SHACL.NODE_SHAPE || st.`object` == SHACL.PROPERTY_SHAPE))
  }

  private fun validateIn(
    repository: SailRepository,
    data: List<Statement>,
    focus: Resource,
    shapesModel: () -> Model,
  ): ValidationResult {
    repository.connection.use { connection ->
      connection.begin()
      try {
        connection.add(data)
        connection.prepare()
        return ValidationResult.Ok
      } catch (e: Exception) {
        val validationFailure = generateSequence<Throwable>(e) { it.cause }
          .firstOrNull { it is ShaclSailValidationException } as ShaclSailValidationException?
          ?: throw e
        return toResult(validationFailure.validationReportAsModel(), focus, shapesModel())
      } finally {
        if (connection.isActive) connection.rollback()
      }
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
