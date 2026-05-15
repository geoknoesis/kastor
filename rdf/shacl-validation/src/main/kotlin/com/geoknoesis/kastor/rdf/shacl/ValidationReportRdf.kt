package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TrueLiteral
import com.geoknoesis.kastor.rdf.FalseLiteral
import com.geoknoesis.kastor.rdf.bnode
import com.geoknoesis.kastor.rdf.dsl.GraphDsl
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL

/**
 * Serializes this in-memory report to an RDF graph using `sh:ValidationReport` and `sh:ValidationResult`
 * ([SHACL validation reports](https://www.w3.org/TR/shacl/#validation-report)).
 *
 * This is a best-effort mapping: some fields (e.g. complex `sh:resultPath`) may be omitted when the
 * portable model does not carry enough structure.
 */
fun ValidationReport.toShaclValidationReportRdf(
    reportNode: RdfResource = bnode("ValidationReport"),
): RdfGraph =
    Rdf.graph {
        val report = this@toShaclValidationReportRdf
        reportNode - RDF.type - SHACL.ValidationReport
        reportNode - SHACL.conforms - if (report.isValid) TrueLiteral else FalseLiteral
        report.violations.forEachIndexed { idx, v ->
            val row = bnode("resultV$idx")
            reportNode - SHACL.result - row
            addValidationResult(row, v)
        }
        report.warnings.forEachIndexed { idx, w ->
            val res = w.resource ?: return@forEachIndexed
            val row = bnode("resultW$idx")
            reportNode - SHACL.result - row
            row - RDF.type - SHACL.ValidationResult
            row - SHACL.focusNode - res
            row - SHACL.resultSeverity - SHACL.Warning
            row - SHACL.resultMessage - w.message
            w.shapeUri?.let { parseReportShapeRef(it) }?.let { row - SHACL.sourceShape - it }
        }
    }

private fun GraphDsl.addValidationResult(row: BlankNode, v: ValidationViolation) {
    row - RDF.type - SHACL.ValidationResult
    row - SHACL.focusNode - v.focusNode
    row - SHACL.resultSeverity - v.severity.toShaclSeverityIri()
    row - SHACL.resultMessage - v.message
    v.shapeUri?.let { parseReportShapeRef(it) }?.let { row - SHACL.sourceShape - it }
    v.constraint.constraintType.toSourceConstraintComponentIri()?.let { cc ->
        row - SHACL.sourceConstraintComponent - cc
    }
    simpleResultPath(v.path)?.let { pathTerm ->
        row - SHACL.resultPath - pathTerm
    }
    v.value?.let { row - SHACL.value - it }
}

private fun ViolationSeverity.toShaclSeverityIri(): Iri =
    when (this) {
        ViolationSeverity.INFO -> SHACL.Info
        ViolationSeverity.WARNING -> SHACL.Warning
        ViolationSeverity.VIOLATION,
        ViolationSeverity.ERROR,
        -> SHACL.Violation
        ViolationSeverity.DEBUG -> SHACL.Debug
        ViolationSeverity.TRACE -> SHACL.Trace
    }

private fun parseReportShapeRef(id: String): RdfResource? =
    when {
        id.startsWith("_:") -> BlankNode(id.removePrefix("_:"))
        id.startsWith("http://") || id.startsWith("https://") || id.startsWith("urn:") -> Iri(id)
        else -> null
    }

/** Single-IRI paths only; blank-node / sequence paths are skipped. */
private fun simpleResultPath(path: List<RdfTerm>?): RdfTerm? {
    val p = path ?: return null
    return p.singleOrNull() as? Iri
}
