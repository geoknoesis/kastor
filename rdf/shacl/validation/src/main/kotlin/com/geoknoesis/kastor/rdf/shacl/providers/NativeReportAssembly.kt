package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.shacl.ShaclConstraint
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
