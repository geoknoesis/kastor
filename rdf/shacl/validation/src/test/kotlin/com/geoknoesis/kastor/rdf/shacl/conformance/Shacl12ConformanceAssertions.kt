package com.geoknoesis.kastor.rdf.shacl.conformance

import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.WeisfeilerLehmanIsomorphism
import com.geoknoesis.kastor.rdf.shacl.KastorShaclVocabulary
import com.geoknoesis.kastor.rdf.shacl.ValidationReport
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import com.geoknoesis.kastor.rdf.shacl.toShaclValidationReportRdf
import com.geoknoesis.kastor.rdf.vocab.SHACL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

internal fun ViolationSeverity.toDefaultShaclResultSeverityIri(): String =
    when (this) {
        ViolationSeverity.VIOLATION -> SHACL.Violation.value
        ViolationSeverity.WARNING -> SHACL.Warning.value
        ViolationSeverity.INFO -> SHACL.Info.value
        ViolationSeverity.ERROR -> SHACL.Violation.value
        ViolationSeverity.DEBUG -> SHACL.Debug.value
        ViolationSeverity.TRACE -> SHACL.Trace.value
    }

/**
 * Actual report as a result graph comparable with [ExpectedConformanceReport.results]: `sh:conforms` and
 * `sh:detail` removed; engine-generated `sh:resultMessage` values kept only when the manifest lists that exact
 * literal (manifests specify messages only for shapes declaring `sh:message`).
 */
internal fun ValidationReport.toW3cResultGraph(expected: ExpectedConformanceReport): RdfGraph {
    val full = toShaclValidationReportRdf(W3C_REPORT_NODE)
    return Rdf.graph {
        for (t in full.getTriples()) {
            if (t.predicate == SHACL.conforms || t.predicate == SHACL.detail) continue
            // Kastor extension properties (ksh:reifier, ksh:resultStatus, ksh:warning) are additions to the standard
            // report. Stripping ksh:resultStatus makes an undecided result look like a failure: assertNoUndecidedResults
            // rejects such results before the graphs are compared.
            if (t.predicate.value.startsWith(KastorShaclVocabulary.NAMESPACE)) continue
            if (t.predicate == SHACL.resultMessage && (t.obj !is Literal || t.obj !in expected.expectedMessages)) continue
            triple(t.subject, t.predicate, t.obj)
        }
    }
}

/**
 * [undecidedAllowed] is the documented reason why this case may contain undecided results
 * ([W3cKnownDeviations.undecidedResultsAllowed]); `null` for every other case.
 */
internal fun assertMatchesW3cExpected(
    report: ValidationReport,
    expected: ExpectedConformanceReport,
    label: String,
    undecidedAllowed: String? = null,
) {
    assertNoUndecidedResults(report, label, undecidedAllowed)
    // The engine decides conformance; sh:conformanceDisallows is passed to it through ValidationConfig by the runner.
    val disallows = expected.conformanceDisallowsSeverityIrises?.let { " under sh:conformanceDisallows $it" }.orEmpty()
    assertEquals(expected.conforms, report.isValid, "$label: sh:conforms (ValidationReport.isValid)$disallows")
    val exported = report.toShaclValidationReportRdf(W3C_REPORT_NODE).getTriples()
        .single { it.predicate == SHACL.conforms }.obj
    assertEquals(expected.conforms, (exported as Literal).lexical == "true", "$label: exported sh:conforms$disallows")
    assertResultGraphsIsomorphic(expected.results, report.toW3cResultGraph(expected), label)
}

/**
 * The results of a W3C expected report are definite: the constraint failed. A Kastor result marked with
 * `ksh:resultStatus` (undefined recursion, pattern timeout, pattern too complex) says the engine could **not decide**
 * the constraint; once the `ksh:` triples are stripped it is indistinguishable from a failure, so it would satisfy an
 * expected definite result. Such a result fails the case unless the case is a documented deviation.
 */
internal fun assertNoUndecidedResults(report: ValidationReport, label: String, undecidedAllowed: String? = null) {
    if (undecidedAllowed != null) return
    val exported = report.toShaclValidationReportRdf(W3C_REPORT_NODE).getTriples().filter { it.predicate == KastorShaclVocabulary.resultStatus }
    val undecided = report.violations.filter { it.isUndecided }
    assertTrue(exported.isEmpty() && undecided.isEmpty()) {
        "$label: the engine emitted ${maxOf(exported.size, undecided.size)} undecided result(s) (ksh:resultStatus " +
            "${exported.map { it.obj }.distinct()}) for a case whose expected report only has definite results: " +
            undecided.map { "${it.focusNode} / ${it.constraint.constraintType}: ${it.message}" }
    }
}

internal fun assertResultGraphsIsomorphic(expected: RdfGraph, actual: RdfGraph, label: String) {
    val iso = WeisfeilerLehmanIsomorphism().areIsomorphic(expected, actual)
    assertTrue(iso) {
        val e = erased(expected)
        val a = erased(actual)
        "$label: validation results are not isomorphic to the expected report " +
            "(expected ${expected.size()} triples, actual ${actual.size()}); blank-node-erased multiset diff:\n" +
            "  only expected:\n${diff(e, a)}\n  only actual:\n${diff(a, e)}"
    }
}

private fun erased(g: RdfGraph): List<String> {
    fun t(term: com.geoknoesis.kastor.rdf.RdfTerm) = if (term is com.geoknoesis.kastor.rdf.BlankNode) "_:" else term.toString()
    return g.getTriples().map { "${t(it.subject)} <${it.predicate.value.substringAfterLast('#')}> ${t(it.obj)}" }
}

private fun diff(left: List<String>, right: List<String>): String {
    val rest = right.groupingBy { it }.eachCount().toMutableMap()
    val out = mutableListOf<String>()
    for (x in left) {
        val n = rest[x] ?: 0
        if (n > 0) rest[x] = n - 1 else out.add("    $x")
    }
    return out.sorted().joinToString("\n").ifEmpty { "    (none)" }
}

internal fun assertNoUnexpectedWarnings(report: ValidationReport, label: String) {
    assertTrue(report.warnings.isEmpty(), "$label: unexpected warnings ${report.warnings}")
}
