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
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
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
 * validator keeps one in-memory repository per data graph instance, for up to [MAX_CACHED_GRAPHS] graphs (the least
 * recently used one is released when another graph arrives; entries whose graph was garbage collected are released
 * first). A graph is (re)loaded only when it is new or its content changed. Change detection is O(1) for graphs that
 * implement [VersionedRdfGraph] (such as `MemoryGraph`): the modification stamp is compared with the one of the loaded
 * copy. Other graphs are checked with an order-independent SHA-256-based digest of their triples, which costs one pass
 * over the triples but no conversion or store writes. Validating many nodes of one graph therefore converts and loads
 * it once. A reload is atomic: the data, the change marker and the embedded shapes are replaced together after the
 * store transaction commits, so a failed conversion or load leaves the previous state intact.
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
 * concurrently. [close] releases every repository (together with the loaded data). A validator is safe to share
 * between threads.
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
) : ValidationContext, AutoCloseable {

  /** Validates against SHACL shapes found in the data graph passed to [validate]. */
  constructor() : this(null as List<Statement>?)

  /** Validates against the SHACL shapes in [shapes] (converted once at construction). */
  constructor(shapes: RdfGraph) : this(toStatements(shapes))

  /** Guards [slots], [closed] and [useCounter]; never held while a slot lock is acquired. */
  private val slotsLock = Any()
  private val slots = ArrayList<Slot>()
  private var closed = false
  private var useCounter = 0L

  /** Test hook: runs inside the load transaction, before commit (to simulate a store failure). */
  @Volatile internal var beforeLoadCommit: (() -> Unit)? = null
  /** Test hook: number of content digests computed. */
  internal val digestCount = AtomicInteger()
  /** Test hook: number of graphs converted and loaded into a store. */
  internal val loadCount = AtomicInteger()
  private val fixedShapeIndex: ShapeIndex? by lazy(LazyThreadSafetyMode.PUBLICATION) {
    fixedShapes?.let { ShapeIndex(it) }
  }

  companion object {
    private val vf = SimpleValueFactory.getInstance()
    private val TARGET_PREDICATES: Set<IRI> =
      setOf(SHACL.TARGET_CLASS, SHACL.TARGET_NODE, SHACL.TARGET_SUBJECTS_OF, SHACL.TARGET_OBJECTS_OF)
    private const val MAX_SHAPE_ANCESTOR_DEPTH = 16

    /** Maximum number of data graphs whose converted copy is kept (one in-memory store each). */
    const val MAX_CACHED_GRAPHS: Int = 4

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

    /**
     * Order-independent, collision-resistant digest of [graph]'s content: the SHA-256 of an unambiguous encoding of
     * each triple (term kind tags, length-prefixed UTF-8 values), added up modulo 2^256, plus the triple count.
     * Unlike a sum of `hashCode()`s, distinct contents (e.g. literals `"Aa"` and `"BB"`, which share a String hash
     * code) never produce the same digest in practice.
     */
    private fun digest(graph: RdfGraph): GraphDigest {
      val sha = MessageDigest.getInstance("SHA-256")
      val sum = LongArray(4)
      var count = 0L
      for (triple in graph.getTriples()) {
        encodeTerm(sha, triple.subject)
        encodeTerm(sha, triple.predicate)
        encodeTerm(sha, triple.obj)
        addModulo(sum, sha.digest())
        count++
      }
      return GraphDigest(sum.toList(), count)
    }

    private fun encodeTerm(sha: MessageDigest, term: RdfTerm) {
      fun field(value: String?) {
        if (value == null) {
          sha.update(0)
          return
        }
        val bytes = value.toByteArray(Charsets.UTF_8)
        sha.update(1)
        sha.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        sha.update(bytes)
      }
      when (term) {
        is Iri -> { sha.update('I'.code.toByte()); field(term.value) }
        is BlankNode -> { sha.update('B'.code.toByte()); field(term.id) }
        is LangString -> {
          sha.update('L'.code.toByte()); field(term.lexical); field(term.lang); field(term.direction?.toString())
        }
        is Literal -> { sha.update('T'.code.toByte()); field(term.lexical); field(term.datatype.value) }
        is TripleTerm -> {
          sha.update('R'.code.toByte())
          encodeTerm(sha, term.triple.subject); encodeTerm(sha, term.triple.predicate); encodeTerm(sha, term.triple.obj)
        }
        else -> { sha.update('?'.code.toByte()); field(term.toString()) }
      }
    }

    /** [sum] += [digest] (32 bytes, big-endian) modulo 2^256; [sum] holds four big-endian 64-bit words. */
    private fun addModulo(sum: LongArray, digest: ByteArray) {
      val words = ByteBuffer.wrap(digest)
      val add = LongArray(4) { words.long }
      var carry = 0L
      for (i in 3 downTo 0) {
        val a = sum[i]
        val b = add[i]
        val s = a + b + carry
        carry = if (java.lang.Long.compareUnsigned(s, a) < 0 || (carry == 1L && s == a)) 1L else 0L
        sum[i] = s
      }
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

  /** Content digest of a graph: four 64-bit words of a sum of SHA-256 triple digests, plus the triple count. */
  private data class GraphDigest(val words: List<Long>, val count: Long)

  /**
   * The cached store of one data graph. [lastUsed] is guarded by [slotsLock]; the other mutable fields by the slot's
   * own monitor.
   */
  private class Slot(val graph: WeakReference<RdfGraph>, var lastUsed: Long) {
    var repository: SailRepository? = null
    var loaded = false
    var loadedStamp: Long? = null
    var loadedDigest: GraphDigest? = null
    var embeddedShapes: ShapeIndex? = null
    var closed = false

    fun release() {
      closed = true
      repository?.shutDown()
      repository = null
      embeddedShapes = null
      loadedDigest = null
    }
  }

  /** The slot of [data], creating one (and evicting dead or least recently used slots) when needed. */
  private fun acquire(data: RdfGraph): Slot {
    val evicted = ArrayList<Slot>()
    val slot = synchronized(slotsLock) {
      check(!closed) { "Rdf4jValidation has been closed" }
      val use = ++useCounter
      slots.firstOrNull { it.graph.get() === data }?.let { it.lastUsed = use; return@synchronized it }
      slots.removeAll { candidate -> (candidate.graph.get() == null).also { dead -> if (dead) evicted += candidate } }
      while (slots.size >= MAX_CACHED_GRAPHS) {
        val lru = slots.minBy { it.lastUsed }
        slots.remove(lru)
        evicted += lru
      }
      Slot(WeakReference(data), use).also { slots += it }
    }
    // Release outside the slots lock: this waits for an in-flight validation of an evicted graph to finish.
    evicted.forEach { synchronized(it) { it.release() } }
    return slot
  }

  /** Number of data graphs currently cached (for tests). */
  internal fun cachedGraphCount(): Int = synchronized(slotsLock) { slots.size }

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
    while (true) {
      val slot = acquire(data)
      synchronized(slot) {
        // Evicted (or the validator closed) between acquire and lock: look the graph up again.
        if (slot.closed) continue
        return loadAndValidate(slot, data, focusValue)
      }
    }
  }

  /** Loads [data] into [slot]'s store when it is new or changed, then validates [focusValue]; holds [slot]'s lock. */
  private fun loadAndValidate(slot: Slot, data: RdfGraph, focusValue: Resource): ValidationResult {
    // Read the stamp before converting: a concurrent change makes the next call reload (never a stale hit).
    val stamp = (data as? VersionedRdfGraph)?.modificationStamp
    val digest = if (stamp == null) digest(data).also { digestCount.incrementAndGet() } else null
    val stale = !slot.loaded || (if (stamp != null) slot.loadedStamp != stamp else slot.loadedDigest != digest)
    val repo = slot.repository ?: newRepository().also { slot.repository = it }
    if (stale) {
      // Convert and index first, then replace the store content in one transaction; the change marker and the
      // embedded shapes change together only after the commit, so a failure at any step leaves the previously
      // loaded data and shapes in place.
      val statements = toStatements(data)
      val newShapes = if (fixedShapes == null) ShapeIndex(extractEmbeddedShapes(statements)) else null
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
      loadCount.incrementAndGet()
      slot.embeddedShapes = newShapes
      slot.loadedStamp = stamp
      slot.loadedDigest = digest
      slot.loaded = true
    }
    val shapes = fixedShapeIndex ?: slot.embeddedShapes ?: return ValidationResult.Ok
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

  /** Releases the repositories and the data loaded into them. */
  override fun close() {
    val released = synchronized(slotsLock) {
      closed = true
      slots.toList().also { slots.clear() }
    }
    released.forEach { synchronized(it) { it.release() } }
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
