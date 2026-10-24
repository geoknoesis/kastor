package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import com.geoknoesis.kastor.rdf.shacl.SOURCE_CONSTRAINT_TRIPLES_CONTEXT_KEY
import com.geoknoesis.kastor.rdf.shacl.ShaclConstraint
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.ValidationStatistics
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import com.geoknoesis.kastor.rdf.shacl.ValidationWarning
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import java.time.Duration
import com.geoknoesis.kastor.rdf.shacl.native.CompiledShapeGraph

internal fun violation(
    focus: RdfTerm,
    tpl: ResultTemplate,
    constraint: ShaclConstraint,
    message: String,
    value: RdfTerm? = null,
    severity: ViolationSeverity = tpl.severity,
    severityIri: Iri? = tpl.severityCustomIri,
    messages: List<Literal> = tpl.messages,
    sourceConstraint: RdfTerm? = null,
    path: ReportPath? = tpl.path,
    sourceConstraintComponent: Iri? = null,
    resultAnnotations: Map<Iri, List<RdfTerm>> = emptyMap(),
): ValidationViolation =
    ValidationViolation(
        severity = severity,
        constraint = constraint,
        focusNode = focus,
        message = messages.firstOrNull()?.lexical ?: message,
        path = path?.terms,
        shapeUri = tpl.shape.displayId(),
        value = value,
        resultSeverityIri = severityIri?.value,
        resultPathNode = path?.node,
        resultPathTriples = path?.triples ?: emptyList(),
        resultMessages = messages,
        sourceConstraint = sourceConstraint,
        sourceConstraintComponent = sourceConstraintComponent?.value,
        resultAnnotations = resultAnnotations,
    )

/**
 * [validatedFocusNodes] is the number of distinct focus nodes that were validated against at least one active
 * shape, whether they have results or not; the average time is the elapsed time of the run divided by it.
 */
internal fun buildStatistics(
    totalResources: Int,
    validatedFocusNodes: Int,
    elapsed: Duration,
    violations: List<ValidationViolation>,
    warnings: List<ValidationWarning>,
    compiled: CompiledShapeGraph,
    validatedConstraintSlots: Int,
): ValidationStatistics {
    val constraintsByType = violations.groupingBy { it.constraint.constraintType }.eachCount()
    val violationsByType = constraintsByType
    val warningsByType = warnings.mapNotNull { it.constraint?.constraintType }.groupingBy { it }.eachCount()
    val propConstraints =
        compiled.orderedNodeShapes.sumOf { ns ->
            ns.nodeConstraints.size +
                ns.logicalParts.size +
                ns.propertyShapes.sumOf { ps -> ps.constraints.size + ps.logicalParts.size }
        }
    return ValidationStatistics(
        totalResources = totalResources,
        validatedResources = validatedFocusNodes,
        totalConstraints = propConstraints,
        validatedConstraints = validatedConstraintSlots.coerceAtLeast(violations.size),
        shapesProcessed = compiled.orderedNodeShapes.size,
        constraintsByType = constraintsByType,
        violationsByType = violationsByType,
        warningsByType = warningsByType,
        averageValidationTimePerResource = if (validatedFocusNodes == 0) Duration.ZERO else elapsed.dividedBy(validatedFocusNodes.toLong()),
    )
}

/**
 * Most triples of the `sh:sourceConstraint` deep copies of one run, at least: the limit is this, or twice the size of
 * the shapes graph when that is larger ([SourceConstraintCopies]). More is a validation failure, not a truncated report.
 */
internal const val MAX_SOURCE_CONSTRAINT_COPY_TRIPLES = 100_000

/**
 * The deep copies of the blank-node `sh:sourceConstraint` values of one run, one per node, and the triples they hold
 * together. Copies of distinct nodes may share structure, so the limit is on their total: it is
 * [MAX_SOURCE_CONSTRAINT_COPY_TRIPLES], or twice the size of the shapes graph [shapesTriples] when that is larger. A
 * shapes graph whose expressions share no structure copies at most its own size; one whose many expressions share a
 * large structure, which would make the total grow with their product, fails validation once it passes the limit.
 */
internal class SourceConstraintCopies(shapesTriples: Int) {
    private val limit = maxOf(MAX_SOURCE_CONSTRAINT_COPY_TRIPLES.toLong(), 2L * shapesTriples)
    private val copies = HashMap<BlankNode, List<RdfTriple>>()
    private var total = 0L

    fun of(node: BlankNode, ctx: NativeValidationContext): List<RdfTriple> =
        copies.getOrPut(node) {
            ctx.compiled.index.deepCopyTriples(node, Int.MAX_VALUE) {
                ctx.budget.tick("sh:sourceConstraint deep copy")
                if (++total > limit) {
                    throw ShaclValidationException(
                        "The sh:sourceConstraint deep copies of this validation have more than $limit triples (at $node)",
                    )
                }
            }
        }
}

/**
 * [v] with the deep copy (SHACL 1.2 Core, "Deep Copy") of its blank-node `sh:sourceConstraint`, for the results whose
 * source constraint Node Expressions §7.1 (`sh:expression`) and §7.2 (`sh:nodeByExpression`) define as a deep copy:
 * the triples go in [ValidationViolation.context] under [SOURCE_CONSTRAINT_TRIPLES_CONTEXT_KEY], and
 * [com.geoknoesis.kastor.rdf.shacl.toShaclValidationReportRdf] writes them. [copies] keeps one copy per node for the
 * run and bounds their total; copying ticks the run budget per triple.
 */
internal fun withSourceConstraintCopy(v: ValidationViolation, ctx: NativeValidationContext, copies: SourceConstraintCopies): ValidationViolation {
    val node = v.sourceConstraint as? BlankNode ?: return v
    val type = v.constraint.constraintType
    if (type != ConstraintType.EXPRESSION && type != ConstraintType.NODE_BY_EXPRESSION) return v
    val triples = copies.of(node, ctx)
    if (triples.isEmpty()) return v
    return v.copy(context = v.context + (SOURCE_CONSTRAINT_TRIPLES_CONTEXT_KEY to triples))
}
