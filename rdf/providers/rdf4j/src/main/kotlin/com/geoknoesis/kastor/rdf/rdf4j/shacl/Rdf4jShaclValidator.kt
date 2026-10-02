package com.geoknoesis.kastor.rdf.rdf4j.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jTerms
import com.geoknoesis.kastor.rdf.vocab.RDF as KastorRdf
import com.geoknoesis.kastor.rdf.vocab.SHACL as KastorShacl
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import com.geoknoesis.kastor.rdf.shacl.ShaclConstraint
import com.geoknoesis.kastor.rdf.shacl.ShaclShape
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.ShaclValidator
import com.geoknoesis.kastor.rdf.shacl.UnsupportedFeatureHandling
import com.geoknoesis.kastor.rdf.shacl.UnsupportedShaclFeatureException
import com.geoknoesis.kastor.rdf.shacl.UnsupportedShaclOperationException
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.ValidationReport
import com.geoknoesis.kastor.rdf.shacl.ValidationStatistics
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import com.geoknoesis.kastor.rdf.shacl.ValidationWarning
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import java.time.Duration
import org.eclipse.rdf4j.common.exception.ValidationException
import org.eclipse.rdf4j.model.Literal as Rdf4jLiteral
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.eclipse.rdf4j.model.vocabulary.RDF4J
import org.eclipse.rdf4j.model.vocabulary.SHACL
import org.eclipse.rdf4j.repository.RepositoryException
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.eclipse.rdf4j.sail.shacl.ShaclSail

/**
 * [ShaclValidator] backed by Eclipse RDF4J's [ShaclSail] (SHACL shapes & validation at commit time).
 *
 * **Performance:** Each [validate] call creates a fresh in-memory [ShaclSail] repository, loads all
 * triples, and shuts it down—fine for correctness and moderate graphs; for high throughput, batch
 * validations at the application layer or use the native Kastor engine (`providerId = kastor`).
 *
 * **What is validated:** `ShaclSail` implements most of SHACL Core and the `sh:sparql` constraints of SHACL-SPARQL,
 * and silently skips what it does not implement ([ShaclSailFeatures] has the list: `sh:xone`, the `*OrMore` /
 * `zeroOrOne` paths, `sh:qualifiedValueShapesDisjoint`, SPARQL-based constraint components, the SHACL 1.2
 * additions, ...). The bridge reads the shapes graph for those constructs before validating and applies
 * [ValidationConfig.unsupportedFeatures]: with `FAIL` (the default) validation fails with a
 * [ShaclValidationException] caused by an [UnsupportedShaclFeatureException], so data never "conforms" to a shape
 * that was not evaluated; with `IGNORE_WITH_WARNING` the construct is skipped and the report carries a
 * [ValidationWarning] for it (unless [ValidationConfig.includeWarnings] is false).
 *
 * **Configuration:**
 * - [ValidationConfig.timeout] bounds one validation call: `ShaclSail` runs on a worker thread, and the call fails
 *   with a [ShaclValidationException] when the time is up (the worker is interrupted and cleans up on its own).
 * - [ValidationConfig.maxViolations] is also given to `ShaclSail` as its result limit, so it stops producing results
 *   once the report is full; [ValidationReport.violationsTruncated] says that results were left out. (RDF4J's own
 *   default caps results at 1000 per constraint without saying so in the report Kastor returns.)
 *   [validateResource] validates without a limit, because results are filtered by focus node afterwards.
 * - [ValidationConfig.strictMode]: fail with a [ShaclValidationException] instead of returning a report that leaves
 *   out a result of RDF4J's report the bridge cannot represent (a result without `sh:focusNode`).
 * - [ValidationConfig.parallelValidation] and [ValidationConfig.streamingMode] are rejected with an
 *   [UnsupportedShaclOperationException] when the validator is created, like on the native engine.
 * - [ValidationConfig.maxCombinedGraphTriples] rejects oversized combined data+shapes graphs before materializing
 *   RDF4J statements. Use [ValidationConfig.rdf4jUntrustedInputLimits] for conservative defaults.
 *
 * Kastor [RdfGraph]s are converted to RDF4J statements; the validation report RDF produced on failure is
 * mapped into [ValidationReport]: focus node, value, severity, source shape, constraint component, `sh:resultPath`
 * ([ValidationViolation.path] for a predicate path, [ValidationViolation.resultPathNode] with
 * [ValidationViolation.resultPathTriples] for every path) and `sh:sourceConstraint`.
 *
 * @param sailPhaseStarted test seam: runs on the worker thread before `ShaclSail` is given anything.
 */
internal class Rdf4jShaclValidator(
    private val config: ValidationConfig,
    private val sailPhaseStarted: (() -> Unit)? = null,
) : ShaclValidator {

  private val vf = SimpleValueFactory.getInstance()

  init {
    if (config.parallelValidation) {
      throw UnsupportedShaclOperationException(
          "ValidationConfig.parallelValidation = true is not supported by the RDF4J SHACL bridge (provider id \"rdf4j\"): " +
              "each validation runs one ShaclSail commit. Leave parallelValidation at false.",
      )
    }
    if (config.streamingMode) {
      throw UnsupportedShaclOperationException(
          "ValidationConfig.streamingMode = true is not supported by the RDF4J SHACL bridge (provider id \"rdf4j\"): " +
              "it validates an in-memory copy of the data graph. Leave streamingMode at false.",
      )
    }
    require(config.maxViolations > 0) { "maxViolations must be positive" }
    require(!config.timeout.isNegative && !config.timeout.isZero) { "timeout must be positive" }
  }

  override fun validate(graph: RdfGraph, shapes: RdfGraph): ValidationReport = runValidation(graph, shapes, null)

  /** What `ShaclSail` reported: its report as RDF, or null when the data conforms. */
  private class SailOutcome(val report: Model?)

  /**
   * Validates [graph] against [shapes] with RDF4J's [ShaclSail]. When [focus] is set, only validation
   * results for that focus node are reported (the whole data graph is still validated, so targets
   * reached through `rdfs:subClassOf` and nested blank-node values are evaluated correctly).
   *
   * Validity follows SHACL 1.2 `sh:conforms` under [ValidationConfig.conformanceDisallows]: by default any
   * result except `sh:Debug` / `sh:Trace` makes the report non-conforming. It is decided on every result,
   * independent of [ValidationConfig.maxViolations] truncation: when `ShaclSail` stopped at its result limit and no
   * result it produced decides conformance, the validation is run again without a limit.
   */
  private fun runValidation(graph: RdfGraph, shapes: RdfGraph, focus: RdfResource?): ValidationReport {
    val start = System.nanoTime()
    val deadline = start + config.timeout.toNanos().coerceAtMost(Long.MAX_VALUE - start)
    fun elapsed(): Duration = Duration.ofNanos(System.nanoTime() - start)
    // Admission uses size() (cheap for most graphs); each graph is then materialised exactly once, and every
    // statement list and statistic below is derived from that single copy.
    val combined = graph.size().toLong() + shapes.size().toLong()
    if (combined > config.maxCombinedGraphTriples) {
      throw ShaclValidationException(
          "Combined data + shapes triple count ($combined) exceeds ValidationConfig.maxCombinedGraphTriples (${config.maxCombinedGraphTriples})",
      )
    }
    val dataTriples = graph.getTriples()
    val shapeTriples = shapes.getTriples()

    val unsupported = ShaclSailFeatures.scan(shapeTriples)
    if (unsupported.descriptions.isNotEmpty() && config.unsupportedFeatures == UnsupportedFeatureHandling.FAIL) {
      val cause =
          UnsupportedShaclFeatureException(
              "Unsupported SHACL feature(s) for RDF4J ShaclSail: ${unsupported.descriptions.joinToString("; ")}. " +
                  "Set ValidationConfig.unsupportedFeatures = IGNORE_WITH_WARNING to skip these constructs, or use the " +
                  "native engine (providerId = \"kastor\").",
              unsupported.features,
          )
      throw ShaclValidationException("SHACL validation failed: ${cause.message}", cause)
    }
    val warnings =
        if (config.includeWarnings) unsupported.descriptions.map { ValidationWarning("Unsupported SHACL feature ignored: $it") }
        else emptyList()

    val shapeStmts = shapeTriples.filterNot { it in unsupported.rejected }.map(::tripleToStatement)
    val dataStmts = dataTriples.map(::tripleToStatement)

    val cap = config.maxViolations
    // One result more than the report takes shows that results were left out. Results are filtered by focus node
    // afterwards, so a validation for one resource cannot be limited.
    var limit = if (focus == null) cap.toLong() + 1 else UNLIMITED
    while (true) {
      val outcome = runSail(shapeStmts, dataStmts, limit, deadline)
      val model = outcome.report ?: return emptyReport(dataTriples, shapeTriples, elapsed(), warnings)
      val reported = violationsFromReport(model)
      val allViolations = if (focus == null) reported else reported.filter { it.focusNode == focus }
      val sailTruncated = model.contains(null, RDF4J.TRUNCATED, vf.createLiteral(true))
      // Validity is decided on the complete result set, before maxViolations truncation, under
      // ValidationConfig.conformanceDisallows (the same rule as the native engine).
      // A report whose results could not be read (no focus node) falls back to RDF4J's own sh:conforms flag.
      val conforms =
          if (focus == null && reported.isEmpty()) {
            val flag = model.filter(null, SHACL.CONFORMS, null).objects().asSequence().firstOrNull() as? Rdf4jLiteral
            flag?.booleanValue() ?: false
          } else {
            allViolations.none { config.disallowsConformance(it) }
          }
      if (conforms && sailTruncated && limit != UNLIMITED) {
        // The results ShaclSail left out may hold one that blocks conformance.
        limit = UNLIMITED
        continue
      }
      if (focus != null && conforms) return emptyReport(dataTriples, shapeTriples, elapsed(), warnings)
      val truncated = allViolations.size > cap || (sailTruncated && focus == null)
      return reportFromViolations(dataTriples, shapeTriples, allViolations.take(cap), elapsed(), truncated, conforms, warnings)
    }
  }

  /**
   * Loads the shapes and the data into a fresh [ShaclSail] and commits, on a worker thread, waiting for it until
   * [deadline] (a `System.nanoTime` value).
   *
   * @param limit the most validation results `ShaclSail` produces, in total and per constraint; [UNLIMITED] for all.
   */
  private fun runSail(shapeStmts: List<Statement>, dataStmts: List<Statement>, limit: Long, deadline: Long): SailOutcome {
    val task = java.util.concurrent.FutureTask<SailOutcome> {
      sailPhaseStarted?.invoke()
      val sail = ShaclSail(MemoryStore())
      sail.validationResultsLimitTotal = limit
      sail.validationResultsLimitPerConstraint = limit
      val repo = SailRepository(sail)
      repo.init()
      try {
        repo.connection.use { conn ->
          conn.begin()
          try {
            conn.add(shapeStmts, RDF4J.SHACL_SHAPE_GRAPH)
            conn.add(dataStmts)
            conn.commit()
            SailOutcome(null)
          } catch (e: RepositoryException) {
            runCatching { conn.rollback() }
            val cause = e.cause
            if (cause is ValidationException) SailOutcome(cause.validationReportAsModel()) else throw e
          }
        }
      } finally {
        repo.shutDown()
      }
    }
    Thread(task, WORKER_THREAD).apply { isDaemon = true }.start()
    try {
      return task.get((deadline - System.nanoTime()).coerceAtLeast(0), java.util.concurrent.TimeUnit.NANOSECONDS)
    } catch (e: java.util.concurrent.TimeoutException) {
      task.cancel(true)
      throw ShaclValidationException("SHACL validation timed out or was cancelled (ValidationConfig.timeout = ${config.timeout})", e)
    } catch (e: InterruptedException) {
      task.cancel(true)
      Thread.currentThread().interrupt()
      throw ShaclValidationException("SHACL validation timed out or was cancelled", e)
    } catch (e: java.util.concurrent.ExecutionException) {
      val cause = e.cause ?: e
      if (cause is Error) throw cause
      // Everything ShaclSail throws for a shapes graph it cannot work with (ShaclShapeParsingException, ...).
      throw ShaclValidationException("RDF4J SHACL validation failed: ${cause.message}", cause)
    }
  }

  override fun validate(graph: RdfGraph, shapes: List<ShaclShape>): ValidationReport {
    if (shapes.isNotEmpty()) {
      throw UnsupportedShaclOperationException(
          "RDF4J ShaclSail does not support validate(graph, shapes: List<ShaclShape>). " +
              "Pass shapes as an RdfGraph via validate(graph, shapesGraph). (providerId=rdf4j)",
      )
    }
    return validate(graph, Rdf.graph { })
  }

  override fun validateResource(graph: RdfGraph, shapes: RdfGraph, resource: RdfResource): ValidationReport {
    // Validate the complete data graph and keep only results for this focus node: pruning the data to
    // triples that touch the resource would drop nested blank-node values and rdfs:subClassOf targets.
    return runValidation(graph, shapes, resource)
  }

  override fun validateConstraints(graph: RdfGraph, constraints: List<com.geoknoesis.kastor.rdf.shacl.ShaclConstraint>): ValidationReport {
    if (constraints.isNotEmpty()) {
      throw UnsupportedShaclOperationException(
          "RDF4J ShaclSail does not support validateConstraints; pass SHACL shapes as an RdfGraph. (providerId=rdf4j)",
      )
    }
    return validate(graph, Rdf.graph { })
  }

  override fun conforms(graph: RdfGraph, shapes: RdfGraph): Boolean = validate(graph, shapes).isValid

  override fun getValidationStatistics(graph: RdfGraph, shapes: RdfGraph): ValidationStatistics =
      validate(graph, shapes).statistics

  private fun tripleToStatement(t: RdfTriple): Statement =
      vf.createStatement(
          Rdf4jTerms.toRdf4jResource(t.subject),
          Rdf4jTerms.toRdf4jIri(t.predicate),
          Rdf4jTerms.toRdf4jValue(t.obj),
      )

  private fun distinctSubjects(triples: List<RdfTriple>): Int = triples.mapTo(HashSet()) { it.subject }.size

  private fun emptyReport(
      dataTriples: List<RdfTriple>,
      shapeTriples: List<RdfTriple>,
      elapsed: Duration,
      warnings: List<ValidationWarning>,
  ): ValidationReport {
    val violations = emptyList<ValidationViolation>()
    return ValidationReport(
        isValid = true,
        violations = violations,
        warnings = warnings,
        statistics = buildStatistics(dataTriples, shapeTriples, violations, warnings),
        validationTime = elapsed,
        validatedResources = distinctSubjects(dataTriples),
        validatedConstraints = shapeTriples.count { it.predicate.value.startsWith(SHACL.NAMESPACE) },
    )
  }

  private fun reportFromViolations(
      dataTriples: List<RdfTriple>,
      shapeTriples: List<RdfTriple>,
      violations: List<ValidationViolation>,
      elapsed: Duration,
      violationsTruncated: Boolean,
      isValid: Boolean,
      warnings: List<ValidationWarning>,
  ): ValidationReport {
    return ValidationReport(
        isValid = isValid,
        violations = violations,
        warnings = warnings,
        statistics = buildStatistics(dataTriples, shapeTriples, violations, warnings),
        validationTime = elapsed,
        validatedResources = distinctSubjects(dataTriples),
        validatedConstraints = violations.size.coerceAtLeast(1),
        shapeViolations = violations.groupBy { it.shapeUri ?: "unknown" },
        constraintViolations = violations.groupBy { it.constraint.constraintType.name },
        violationsTruncated = violationsTruncated,
    )
  }

  private fun buildStatistics(
      triples: List<RdfTriple>,
      shapeTriples: List<RdfTriple>,
      violations: List<ValidationViolation>,
      warnings: List<ValidationWarning>,
  ): ValidationStatistics {
    val constraintsByType = violations.groupBy { it.constraint.constraintType }.mapValues { it.value.size }
    val violationsByType = constraintsByType
    val warningsByType =
        warnings.mapNotNull { w -> w.constraint?.constraintType }.groupingBy { it }.eachCount()
    return ValidationStatistics(
        totalResources = distinctSubjects(triples),
        validatedResources = violations.map { it.focusNode }.distinct().size,
        totalConstraints =
            shapeTriples.count { it.predicate.value.startsWith(SHACL.NAMESPACE) },
        validatedConstraints = violations.size,
        shapesProcessed =
            shapeTriples.count { t ->
              val obj = t.obj
              t.predicate == KastorRdf.type && obj is Iri && obj.value == KastorShacl.NodeShape.value
            },
        constraintsByType = constraintsByType,
        violationsByType = violationsByType,
        warningsByType = warningsByType,
        averageValidationTimePerResource = Duration.ZERO,
    )
  }

  /**
   * The results of RDF4J's validation report. A result without `sh:focusNode` cannot be represented: it is left out,
   * or fails the validation in [ValidationConfig.strictMode].
   */
  internal fun violationsFromReport(model: Model): List<ValidationViolation> {
    val out = mutableListOf<ValidationViolation>()
    val reports = model.filter(null, RDF.TYPE, SHACL.VALIDATION_REPORT).subjects()
    for (reportNode in reports) {
      val resultObjs = model.filter(reportNode, SHACL.RESULT, null).objects()
      for (res in resultObjs) {
        if (res !is Resource) continue
        val focus = model.filter(res, SHACL.FOCUS_NODE, null).objects().asSequence().firstOrNull()
        val focusTerm =
            focus?.let { rdf4jValueToTerm(it) }
                ?: if (config.strictMode) {
                  throw ShaclValidationException(
                      "RDF4J's validation report holds a result without sh:focusNode ($res), which the report cannot " +
                          "represent (ValidationConfig.strictMode)",
                  )
                } else {
                  continue
                }
        val messages =
            model.filter(res, SHACL.RESULT_MESSAGE, null).objects().asSequence().mapNotNull { lit ->
              (lit as? Rdf4jLiteral)?.stringValue()
            }
        val message = messages.joinToString(" ").ifEmpty { "SHACL violation" }
        val sevIri =
            model.filter(res, SHACL.RESULT_SEVERITY, null).objects().asSequence().firstOrNull() as?
                org.eclipse.rdf4j.model.IRI
        val severity = shaclSeverityToViolation(sevIri?.stringValue())
        val shapeObj = model.filter(res, SHACL.SOURCE_SHAPE, null).objects().asSequence().firstOrNull()
        val shapeUri =
            when (shapeObj) {
              is org.eclipse.rdf4j.model.IRI -> shapeObj.stringValue()
              is org.eclipse.rdf4j.model.BNode -> "_:${shapeObj.id}"
              else -> null
            }
        val value =
            model.filter(res, SHACL.VALUE, null).objects().asSequence().firstOrNull()?.let { rdf4jValueToTerm(it) }
        val cc =
            model.filter(res, SHACL.SOURCE_CONSTRAINT_COMPONENT, null).objects().asSequence().firstOrNull() as?
                org.eclipse.rdf4j.model.IRI
        val constraintType = constraintTypeFromConstraintComponent(cc?.stringValue())
        val pathValue = model.filter(res, SHACL.RESULT_PATH, null).objects().asSequence().firstOrNull()
        val pathNode = pathValue?.let { rdf4jValueToTerm(it) }
        val sourceConstraint =
            model.filter(res, SHACL.SOURCE_CONSTRAINT, null).objects().asSequence().firstOrNull()?.let { rdf4jValueToTerm(it) }
        val constraint =
            ShaclConstraint(
                constraintType = constraintType,
                path = (pathNode as? Iri)?.value,
                severity = severity,
                message = message,
            )
        out.add(
            ValidationViolation(
                severity = severity,
                constraint = constraint,
                focusNode = focusTerm,
                message = message,
                path = (pathNode as? Iri)?.let { listOf(it) },
                value = value,
                shapeUri = shapeUri,
                resultSeverityIri = sevIri?.stringValue(),
                resultPathNode = pathNode,
                resultPathTriples = (pathValue as? org.eclipse.rdf4j.model.BNode)?.let { pathStructure(model, it) }.orEmpty(),
                sourceConstraint = sourceConstraint,
            ),
        )
      }
    }
    return out
  }

  /** The triples of the report that describe the path structure rooted at the blank node [root]. */
  private fun pathStructure(model: Model, root: org.eclipse.rdf4j.model.BNode): List<RdfTriple> {
    val out = ArrayList<RdfTriple>()
    val seen = HashSet<Resource>()
    val pending = ArrayDeque<Resource>(listOf(root))
    while (pending.isNotEmpty()) {
      val node = pending.removeFirst()
      if (!seen.add(node)) continue
      for (statement in model.filter(node, null, null)) {
        out.addAll(Rdf4jTerms.triplesOf(statement))
        (statement.`object` as? org.eclipse.rdf4j.model.BNode)?.let(pending::addLast)
      }
    }
    return out
  }

  private fun rdf4jValueToTerm(v: org.eclipse.rdf4j.model.Value): RdfTerm = Rdf4jTerms.fromRdf4jValue(v)

  private fun shaclSeverityToViolation(iri: String?): ViolationSeverity {
    if (iri == null) return ViolationSeverity.VIOLATION
    return when (iri) {
      SHACL.INFO.stringValue() -> ViolationSeverity.INFO
      SHACL.WARNING.stringValue() -> ViolationSeverity.WARNING
      SHACL.VIOLATION.stringValue() -> ViolationSeverity.VIOLATION
      SHACL.NAMESPACE + "Debug" -> ViolationSeverity.DEBUG
      SHACL.NAMESPACE + "Trace" -> ViolationSeverity.TRACE
      else -> ViolationSeverity.VIOLATION
    }
  }

  private fun constraintTypeFromConstraintComponent(iri: String?): ConstraintType {
    if (iri == null) return ConstraintType.CUSTOM_CONSTRAINT
    val local = iri.removePrefix(SHACL.NAMESPACE)
    return when (local) {
      "MinCountConstraintComponent" -> ConstraintType.MIN_COUNT
      "MaxCountConstraintComponent" -> ConstraintType.MAX_COUNT
      "DatatypeConstraintComponent" -> ConstraintType.DATATYPE
      "ClassConstraintComponent" -> ConstraintType.CLASS
      "NodeKindConstraintComponent" -> ConstraintType.NODE_KIND
      "PatternConstraintComponent" -> ConstraintType.PATTERN
      "MinLengthConstraintComponent" -> ConstraintType.MIN_LENGTH
      "MaxLengthConstraintComponent" -> ConstraintType.MAX_LENGTH
      "InConstraintComponent" -> ConstraintType.IN
      "HasValueConstraintComponent" -> ConstraintType.HAS_VALUE
      "ClosedConstraintComponent" -> ConstraintType.CLOSED
      "NodeConstraintComponent" -> ConstraintType.NODE
      "NotConstraintComponent" -> ConstraintType.NOT
      "AndConstraintComponent" -> ConstraintType.AND
      "OrConstraintComponent" -> ConstraintType.OR
      "XoneConstraintComponent" -> ConstraintType.XONE
      "MinExclusiveConstraintComponent" -> ConstraintType.MIN_EXCLUSIVE
      "MinInclusiveConstraintComponent" -> ConstraintType.MIN_INCLUSIVE
      "MaxExclusiveConstraintComponent" -> ConstraintType.MAX_EXCLUSIVE
      "MaxInclusiveConstraintComponent" -> ConstraintType.MAX_INCLUSIVE
      "LanguageInConstraintComponent" -> ConstraintType.LANGUAGE_IN
      "UniqueLangConstraintComponent" -> ConstraintType.UNIQUE_LANG
      "EqualsConstraintComponent" -> ConstraintType.EQUALS
      "DisjointConstraintComponent" -> ConstraintType.DISJOINT
      "LessThanConstraintComponent" -> ConstraintType.LESS_THAN
      "LessThanOrEqualsConstraintComponent" -> ConstraintType.LESS_THAN_OR_EQUALS
      "QualifiedMinCountConstraintComponent" -> ConstraintType.QUALIFIED_MIN_COUNT
      "QualifiedMaxCountConstraintComponent" -> ConstraintType.QUALIFIED_MAX_COUNT
      "PropertyConstraintComponent" -> ConstraintType.PROPERTY_SHAPE
      "QualifiedValueShapeConstraintComponent" -> ConstraintType.QUALIFIED_VALUE_SHAPE
      "ShapeConstraintComponent" -> ConstraintType.SHAPE
      "SPARQLConstraintComponent" -> ConstraintType.SPARQL_CONSTRAINT_COMPONENT
      else -> ConstraintType.CUSTOM_CONSTRAINT
    }
  }

  private companion object {
    const val WORKER_THREAD = "kastor-rdf4j-shacl"

    /** `ShaclSail`'s value for "no limit" on validation results. */
    const val UNLIMITED = -1L
  }
}
