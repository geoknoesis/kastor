package com.geoknoesis.kastor.rdf.shacl.conformance

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.shacl.ValidationReport
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import com.geoknoesis.kastor.rdf.shacl.toSourceConstraintComponentIri
import com.geoknoesis.kastor.rdf.vocab.SHACL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/** Same lexical conventions as [Shacl12ExpectedReport] / Jena `mf:result` parsing. */
internal fun w3cViolationFocusKey(term: RdfTerm): String =
    when (term) {
        is Iri -> "I|${term.value}"
        is BlankNode -> "B|${term.id}"
        is LangString -> "LANG|${term.lexical}|${term.lang}|${term.direction?.token ?: ""}"
        is TypedLiteral -> "L|${term.lexical}|${term.datatype.value}"
        is Literal -> "L|${term.lexical}|${term.datatype.value}"
        is TripleTerm ->
            "TT|${w3cViolationFocusKey(term.triple.subject)}|${w3cViolationFocusKey(term.triple.predicate)}|${w3cViolationFocusKey(term.triple.obj)}"
        else -> "X|$term"
    }

private fun w3cPathSegmentForConformance(term: RdfTerm): String =
    when (term) {
        is Iri -> term.value
        else -> term.toString()
    }

internal fun ValidationViolation.validationViolationConformanceSortKey(): String {
    val focus = w3cViolationFocusKey(focusNode)
    val pathKey =
        when {
            path.isNullOrEmpty() -> ""
            path.size == 1 && path[0] is Iri -> "P|${(path[0] as Iri).value}"
            else -> "COMPLEX|" + path.joinToString("|") { w3cPathSegmentForConformance(it) }
        }
    val comp =
        constraint.constraintType.toSourceConstraintComponentIri()?.value
            ?: "MISSING|${constraint.constraintType}"
    val shape = shapeUri ?: ""
    val sev =
        resultSeverityIri
            ?: when (severity) {
                ViolationSeverity.VIOLATION -> SHACL.Violation.value
                ViolationSeverity.WARNING -> SHACL.Warning.value
                ViolationSeverity.INFO -> SHACL.Info.value
                ViolationSeverity.ERROR -> SHACL.Violation.value
                ViolationSeverity.DEBUG -> SHACL.Debug.value
                ViolationSeverity.TRACE -> SHACL.Trace.value
            }
    return listOf(focus, pathKey, comp, shape, sev).joinToString("\u0001")
}

internal fun ViolationSeverity.toDefaultShaclResultSeverityIri(): String =
    when (this) {
        ViolationSeverity.VIOLATION -> SHACL.Violation.value
        ViolationSeverity.WARNING -> SHACL.Warning.value
        ViolationSeverity.INFO -> SHACL.Info.value
        ViolationSeverity.ERROR -> SHACL.Violation.value
        ViolationSeverity.DEBUG -> SHACL.Debug.value
        ViolationSeverity.TRACE -> SHACL.Trace.value
    }

/** Interprets [violations] the way W3C SHACL 1.2 manifest rows describe via optional `sh:conformanceDisallows`. */
internal fun ValidationReport.conformsForW3cHarness(expected: ExpectedConformanceReport): Boolean {
    val diss = expected.conformanceDisallowsSeverityIrises
    if (diss.isNullOrEmpty()) {
        return violations.none {
            when (it.severity) {
                ViolationSeverity.DEBUG,
                ViolationSeverity.TRACE,
                ViolationSeverity.INFO,
                -> false
                else -> true
            }
        }
    }
    return violations.none { v ->
        val iri = v.resultSeverityIri ?: v.severity.toDefaultShaclResultSeverityIri()
        iri in diss
    }
}

/**
 * When `mf:result` describes no `sh:ValidationResult` rows ([ExpectedConformanceReport.violationSortKeys]
 * empty), W3C harness parity compares only blocking severities. Engines still emit INFO/DEBUG/TRACE
 * diagnostics (e.g. SHACL-SHACL dogfood); those must not fail row-key equality vs an empty manifest.
 */
internal fun violationsForW3cViolationRowComparison(
    violations: List<ValidationViolation>,
    expected: ExpectedConformanceReport,
): List<ValidationViolation> {
    if (expected.violationSortKeys.isNotEmpty()) return violations
    return violations.filter {
        when (it.severity) {
            ViolationSeverity.INFO,
            ViolationSeverity.DEBUG,
            ViolationSeverity.TRACE,
            -> false
            else -> true
        }
    }
}

internal fun assertMatchesW3cExpected(report: ValidationReport, expected: ExpectedConformanceReport, label: String) {
    assertEquals(
        expected.conforms,
        report.conformsForW3cHarness(expected),
        "$label: expected sh:conforms=${expected.conforms} vs actual rows under W3C conformanceDisallows rules",
    )
    compareViolationKeys(
        actualKeys =
            violationsForW3cViolationRowComparison(report.violations, expected)
                .map { it.validationViolationConformanceSortKey() }
                .sorted(),
        expected = expected,
        actualSkipDetailedComparison = false,
        actualSkipReason = null,
        label = label,
    )
}

internal fun ValidationReport.toExpectedConformanceReport(): ExpectedConformanceReport {
    val keys = violations.map { it.validationViolationConformanceSortKey() }.sorted()
    return ExpectedConformanceReport(
        conforms = isValid,
        violationSortKeys = keys,
        skipDetailedComparison = false,
        skipReason = null,
        conformanceDisallowsSeverityIrises = null,
    )
}

internal fun assertMatchesW3cExpected(
    actual: ExpectedConformanceReport,
    expected: ExpectedConformanceReport,
    label: String,
) {
    assertEquals(
        expected.conforms,
        actual.conforms,
        "$label: expected sh:conforms=${expected.conforms} vs actual conforms=${actual.conforms}",
    )

    compareViolationKeys(
        actualKeys = actual.violationSortKeys,
        expected = expected,
        actualSkipDetailedComparison = actual.skipDetailedComparison,
        actualSkipReason = actual.skipReason,
        label = label,
    )
}

private fun compareViolationKeys(
    actualKeys: List<String>,
    expected: ExpectedConformanceReport,
    actualSkipDetailedComparison: Boolean,
    actualSkipReason: String?,
    label: String,
) {
    val relaxed =
        expected.skipDetailedComparison || actualSkipDetailedComparison || actualSkipReason != null
    if (relaxed) {
        assertEquals(
            expected.violationSortKeys.size,
            actualKeys.size,
            "$label: violation count (relaxed: expected=${expected.skipReason} actual=$actualSkipReason)",
        )
        return
    }

    assertEquals(
        normalizeConformanceSortKeys(expected.violationSortKeys),
        normalizeConformanceSortKeys(actualKeys),
        {
            "$label: normalized violation rows mismatch\n" +
                "  expected (${expected.violationSortKeys.size}): ${expected.violationSortKeys}\n" +
                "  actual (${actualKeys.size}): ${actualKeys}"
        },
    )
}

/**
 * Strips `sh:sourceShape` from comparison keys. Manifest `mf:result` typically uses shape IRIs while
 * engines (e.g. Jena) often emit blank nodes for `sh:sourceShape`, which would otherwise never align.
 *
 * Blank-node focus ids (`B|…`) are also erased so different RDF serializers / blank-node skolemizations
 * still compare equal when they denote the same blank focus in spirit (tests rarely distinguish two
 * distinct blank focuses solely by label).
 */
private fun normalizeConformanceSortKeys(keys: List<String>): List<String> {
    val sep = "\u0001"
    return keys
        .map { row ->
            val p = row.split(sep)
            if (p.size == 5) {
                val focus = normalizeConformanceFocusSegment(p[0])
                listOf(focus, p[1], p[2], "", p[4]).joinToString(sep)
            } else {
                row
            }
        }
        .sorted()
}

private fun normalizeConformanceFocusSegment(focus: String): String =
    when {
        focus.startsWith("B|") -> "B|"
        else -> focus
    }

internal fun assertNoUnexpectedWarnings(report: ValidationReport, label: String) {
    assertTrue(report.warnings.isEmpty(), "$label: unexpected warnings ${report.warnings}")
}

internal fun assertValidMeansZeroViolations(actual: ExpectedConformanceReport, label: String) {
    val violationIri = SHACL.Violation.value
    val hasViolationSeverity =
        actual.violationSortKeys.any { row ->
            row.split('\u0001').getOrNull(4) == violationIri
        }
    if (actual.conforms) {
        assertFalse(hasViolationSeverity, "$label: conforms=true but report contains sh:Violation severity rows")
    }
}

internal fun assertValidMeansZeroViolations(report: ValidationReport, label: String) {
    assertValidMeansZeroViolations(report.toExpectedConformanceReport(), label)
}
