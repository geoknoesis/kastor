package com.geoknoesis.kastor.ontoquality.integration

import com.geoknoesis.kastor.ontoquality.QualityFinding
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity

/**
 * Sorts a list of [QualityFinding] by importance, then severity, then IRI.
 *
 * Sort order (descending where appropriate):
 *   1. Importance score (higher first; absent entities use [MetricsContext.DEFAULT_IMPORTANCE])
 *   2. Severity (Violation / Error > Warning > Info > Debug / Trace)
 *   3. Focus node IRI (alphabetical, for stability)
 *
 * The result is deterministic for the same inputs.
 */
internal object FindingPrioritizer {

    fun sort(
        findings: List<QualityFinding>,
        importance: Map<String, Double>,
    ): List<QualityFinding> {
        return findings.sortedWith(
            compareByDescending<QualityFinding> { f ->
                val focusIri = extractFocusNodeIri(f)
                    ?: return@compareByDescending MetricsContext.DEFAULT_IMPORTANCE
                importance[focusIri] ?: MetricsContext.DEFAULT_IMPORTANCE
            }.thenByDescending { f ->
                severityRank(f.violation.severity)
            }.thenBy { f ->
                extractFocusNodeIri(f) ?: ""
            },
        )
    }

    /**
     * Top N findings by importance score, useful for the
     * "Top findings by importance" section of the Markdown report.
     */
    fun topN(
        findings: List<QualityFinding>,
        importance: Map<String, Double>,
        n: Int,
    ): List<QualityFinding> = sort(findings, importance).take(n)

    private fun extractFocusNodeIri(f: QualityFinding): String? {
        val term = f.violation.focusNode
        return if (term is Iri) term.value else null
    }

    private fun severityRank(s: ViolationSeverity): Int =
        when (s) {
            ViolationSeverity.VIOLATION,
            ViolationSeverity.ERROR,
            -> 4
            ViolationSeverity.WARNING -> 3
            ViolationSeverity.INFO -> 2
            ViolationSeverity.DEBUG,
            ViolationSeverity.TRACE,
            -> 1
        }
}
