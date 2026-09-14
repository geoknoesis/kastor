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
 * **Resource limits:** [ValidationConfig.maxCombinedGraphTriples] rejects oversized combined
 * data+shapes graphs before materializing RDF4J statements. Use [ValidationConfig.rdf4jUntrustedInputLimits]
 * for conservative defaults.
 *
 * Kastor [RdfGraph]s are converted to RDF4J statements; the validation report RDF produced on failure is
 * mapped into [ValidationReport] (best-effort on [ValidationViolation] fields).
 */
internal class Rdf4jShaclValidator(private val config: ValidationConfig) : ShaclValidator {

  private val vf = SimpleValueFactory.getInstance()

  override fun validate(graph: RdfGraph, shapes: RdfGraph): ValidationReport = runValidation(graph, shapes, null)

  /**
   * Validates [graph] against [shapes] with RDF4J's [ShaclSail]. When [focus] is set, only validation
   * results for that focus node are reported (the whole data graph is still validated, so targets
   * reached through `rdfs:subClassOf` and nested blank-node values are evaluated correctly).
   *
   * Validity follows SHACL: the report conforms only if there is no validation result at all
   * (`sh:conforms`), regardless of severity and independent of [ValidationConfig.maxViolations] truncation.
   */
  private fun runValidation(graph: RdfGraph, shapes: RdfGraph, focus: RdfResource?): ValidationReport {
    val start = System.currentTimeMillis()
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
    val shapeStmts = shapeTriples.map(::tripleToStatement)
    val dataStmts = dataTriples.map(::tripleToStatement)

    val sail = ShaclSail(MemoryStore())
    val repo = SailRepository(sail)
    repo.init()
    try {
      repo.connection.use { conn ->
        conn.begin()
        try {
          conn.add(shapeStmts, RDF4J.SHACL_SHAPE_GRAPH)
          conn.add(dataStmts)
          conn.commit()
          val elapsed = Duration.ofMillis(System.currentTimeMillis() - start)
          return emptyReport(dataTriples, shapeTriples, elapsed)
        } catch (e: RepositoryException) {
          runCatching { conn.rollback() }
          val cause = e.cause
          val elapsed = Duration.ofMillis(System.currentTimeMillis() - start)
          if (cause is ValidationException) {
            val model = cause.validationReportAsModel()
            val reported = violationsFromReport(model)
            val allViolations = if (focus == null) reported else reported.filter { it.focusNode == focus }
            // Validity is decided on the complete result set, before maxViolations truncation.
            val conforms =
                if (focus == null) {
                  val flag = model.filter(null, SHACL.CONFORMS, null).objects().asSequence().firstOrNull() as? Rdf4jLiteral
                  (flag?.booleanValue() ?: false) && allViolations.isEmpty()
                } else {
                  allViolations.isEmpty()
                }
            if (focus != null && conforms) return emptyReport(dataTriples, shapeTriples, elapsed)
            val cap = config.maxViolations.coerceAtLeast(1)
            val truncated = allViolations.size > cap
            val violations = allViolations.take(cap)
            return reportFromViolations(dataTriples, shapeTriples, violations, elapsed, truncated, conforms)
          }
          throw ShaclValidationException(
              "RDF4J SHACL validation failed: ${e.message}",
              e,
          )
        }
      }
    } finally {
      repo.shutDown()
    }
  }

  override fun validate(graph: RdfGraph, shapes: List<ShaclShape>): ValidationReport {
    if (shapes.isNotEmpty()) {
      throw UnsupportedOperationException(
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
      throw UnsupportedOperationException(
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

  private fun emptyReport(dataTriples: List<RdfTriple>, shapeTriples: List<RdfTriple>, elapsed: Duration): ValidationReport {
    val violations = emptyList<ValidationViolation>()
    val warnings = emptyList<ValidationWarning>()
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
  ): ValidationReport {
    return ValidationReport(
        isValid = isValid,
        violations = violations,
        warnings = emptyList(),
        statistics = buildStatistics(dataTriples, shapeTriples, violations, emptyList()),
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

  private fun violationsFromReport(model: Model): List<ValidationViolation> {
    val out = mutableListOf<ValidationViolation>()
    val reports = model.filter(null, RDF.TYPE, SHACL.VALIDATION_REPORT).subjects()
    for (reportNode in reports) {
      val resultObjs = model.filter(reportNode, SHACL.RESULT, null).objects()
      for (res in resultObjs) {
        if (res !is Resource) continue
        val focus = model.filter(res, SHACL.FOCUS_NODE, null).objects().asSequence().firstOrNull()
        val focusTerm = focus?.let { rdf4jValueToTerm(it) } ?: continue
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
        val constraint =
            ShaclConstraint(
                constraintType = constraintType,
                severity = severity,
                message = message,
            )
        out.add(
            ValidationViolation(
                severity = severity,
                constraint = constraint,
                focusNode = focusTerm,
                message = message,
                value = value,
                shapeUri = shapeUri,
                resultSeverityIri = sevIri?.stringValue(),
            ),
        )
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
      "QualifiedValueShapeConstraintComponent" -> ConstraintType.QUALIFIED_VALUE_SHAPE
      "ShapeConstraintComponent" -> ConstraintType.SHAPE
      "SPARQLConstraintComponent" -> ConstraintType.SPARQL_CONSTRAINT_COMPONENT
      else -> ConstraintType.CUSTOM_CONSTRAINT
    }
  }
}
