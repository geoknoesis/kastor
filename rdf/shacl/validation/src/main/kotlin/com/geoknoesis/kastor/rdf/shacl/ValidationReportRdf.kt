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
 * `sh:conforms` mirrors [ValidationReport.isValid]: per SHACL, a report conforms only when it has no
 * validation results of any severity. `sh:resultPath` is exported in full: engines that populate
 * [ValidationViolation.resultPathNode] get their complete path structure copied (with fresh blank nodes
 * per result); otherwise a single IRI or a sequence of IRIs from [ValidationViolation.path] is used.
 * `sh:resultMessage` uses [ValidationViolation.resultMessages] (the shape's `sh:message` values, language
 * tags preserved) when present, else the engine message.
 *
 * Kastor extension properties ([KastorShaclVocabulary], namespace `https://kastor.geoknoesis.com/ns/shacl#`, declared
 * in the vocabulary document [KastorShaclVocabulary.VOCABULARY_RESOURCE]) carry what the SHACL report vocabulary
 * cannot express; consumers of the standard vocabulary can ignore them:
 * - `ksh:resultStatus` marks a result that reports an **undecided** constraint ([ValidationViolation.isUndecided])
 *   rather than a failure: `ksh:UndefinedRecursion`, `ksh:PatternTimeout` or `ksh:PatternTooComplex`
 *   ([ValidationViolation.resultStatus]). Without it an RDF consumer could not tell the two apart: such a result has
 *   the severity and constraint component a failure would have.
 * - `ksh:reifier` names the reifier of a `sh:reifierShape` result (its `sh:value` is the object of the reified
 *   triple, so several reifiers of one triple would otherwise be indistinguishable).
 * - `ksh:warning`, on the report node, carries the message of each report-level [ValidationWarning] (one without a
 *   [ValidationWarning.resource]), e.g. a construct skipped under [UnsupportedFeatureHandling.IGNORE_WITH_WARNING].
 *   A conforming report thus keeps a trace of what was not validated. A warning about a resource is exported as a
 *   `sh:ValidationResult` of severity `sh:Warning`, as before.
 *
 * When [ValidationReport.violationsTruncated] is true, not every violation from the engine is represented
 * in this serialization.
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
            addValidationResult(row, v, "resultV${idx}_")
        }
        report.warnings.forEachIndexed { idx, w ->
            val res = w.resource
            if (res == null) {
                reportNode - KastorShaclVocabulary.warning - w.message
                return@forEachIndexed
            }
            val row = bnode("resultW$idx")
            reportNode - SHACL.result - row
            row - RDF.type - SHACL.ValidationResult
            row - SHACL.focusNode - res
            row - SHACL.resultSeverity - SHACL.Warning
            row - SHACL.resultMessage - w.message
            w.shapeUri?.let { parseReportShapeRef(it) }?.let { row - SHACL.sourceShape - it }
        }
    }

private fun GraphDsl.addValidationResult(row: BlankNode, v: ValidationViolation, bnodePrefix: String) {
    row - RDF.type - SHACL.ValidationResult
    row - SHACL.focusNode - v.focusNode
    row - SHACL.resultSeverity - (v.resultSeverityIri?.let { Iri(it) } ?: v.severity.toShaclSeverityIri())
    if (v.resultMessages.isNotEmpty()) {
        v.resultMessages.forEach { row - SHACL.resultMessage - it }
    } else {
        row - SHACL.resultMessage - v.message
    }
    v.shapeUri?.let { parseReportShapeRef(it) }?.let { row - SHACL.sourceShape - it }
    v.constraint.constraintType.toSourceConstraintComponentIri()?.let { cc ->
        row - SHACL.sourceConstraintComponent - cc
    }
    val pathNode = v.resultPathNode
    if (pathNode != null) {
        row - SHACL.resultPath - copyPathStructure(pathNode, v.resultPathTriples, bnodePrefix)
    } else {
        simpleResultPath(v.path, bnodePrefix)?.let { pathTerm ->
            row - SHACL.resultPath - pathTerm
        }
    }
    v.value?.let { row - SHACL.value - it }
    v.sourceConstraint?.let { row - SHACL.sourceConstraint - it }
    // Kastor extensions (never in the sh: namespace): an undecided constraint is told apart from a failure, and a
    // sh:reifierShape result names its reifier.
    v.resultStatus?.let { row - KastorShaclVocabulary.resultStatus - it }
    (v.context[ValidationViolation.REIFIER_CONTEXT_KEY] as? RdfTerm)?.let { row - KastorShaclVocabulary.reifier - it }
}

/**
 * Copies a blank-node path structure as a tree with fresh blank nodes: results never share path nodes, and a
 * blank node referenced several times in the shapes graph (e.g. `( _:inv _:inv )`) is unfolded per occurrence.
 * List cells named with IRIs keep their IRI; their `rdf:first` / `rdf:rest` triples are copied too.
 */
private fun GraphDsl.copyPathStructure(root: RdfTerm, triples: List<com.geoknoesis.kastor.rdf.RdfTriple>, prefix: String): RdfTerm {
    if (root !is BlankNode) return root
    val bySubject = triples.groupBy { it.subject }
    var counter = 0
    fun copy(term: RdfTerm, ancestors: Set<RdfResource>): RdfTerm {
        if (term !is RdfResource || term in ancestors) return term
        val described = bySubject[term]
        // An IRI is kept; it is only expanded when the path structure describes it (an IRI-named list cell).
        if (term !is BlankNode && described == null) return term
        val copied: RdfResource = if (term is BlankNode) bnode("${prefix}p${counter++}") else term
        for (t in described.orEmpty()) {
            triple(copied, t.predicate, copy(t.obj, ancestors + term))
        }
        return copied
    }
    return copy(root, emptySet())
}

internal fun ViolationSeverity.toShaclSeverityIri(): Iri =
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
        id.contains(':') -> Iri(id)
        else -> null
    }

/** Single IRI, or a sequence path of IRIs rendered as an RDF list. */
private fun GraphDsl.simpleResultPath(path: List<RdfTerm>?, prefix: String): RdfTerm? {
    val p = path ?: return null
    if (p.isEmpty()) return null
    if (p.size == 1) return p.single() as? Iri
    if (p.any { it !is Iri }) return null
    val cells = p.indices.map { bnode("${prefix}seq$it") }
    p.forEachIndexed { i, term ->
        cells[i] - RDF.first - term
        cells[i] - RDF.rest - (cells.getOrNull(i + 1) ?: RDF.nil)
    }
    return cells.first()
}
