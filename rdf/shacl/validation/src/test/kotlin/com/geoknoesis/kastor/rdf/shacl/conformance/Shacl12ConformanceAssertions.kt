package com.geoknoesis.kastor.rdf.shacl.conformance

import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.WeisfeilerLehmanIsomorphism
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
            if (t.predicate == SHACL.resultMessage && (t.obj !is Literal || t.obj !in expected.expectedMessages)) continue
            triple(t.subject, t.predicate, t.obj)
        }
    }
}

internal fun assertMatchesW3cExpected(report: ValidationReport, expected: ExpectedConformanceReport, label: String) {
    val disallows = expected.conformanceDisallowsSeverityIrises
    if (disallows == null) {
        assertEquals(expected.conforms, report.isValid, "$label: sh:conforms (ValidationReport.isValid)")
        val exported = report.toShaclValidationReportRdf(W3C_REPORT_NODE).getTriples()
            .single { it.predicate == SHACL.conforms }.obj
        assertEquals(expected.conforms, (exported as Literal).lexical == "true", "$label: exported sh:conforms")
    } else {
        // sh:conformanceDisallows is a validation-process parameter the native engine does not expose;
        // derive conformance from the reported severities exactly as the SHACL 1.2 definition prescribes.
        val actual = report.violations.none { (it.resultSeverityIri ?: it.severity.toDefaultShaclResultSeverityIri()) in disallows }
        assertEquals(expected.conforms, actual, "$label: sh:conforms under sh:conformanceDisallows $disallows")
    }
    assertResultGraphsIsomorphic(expected.results, report.toW3cResultGraph(expected), label)
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
