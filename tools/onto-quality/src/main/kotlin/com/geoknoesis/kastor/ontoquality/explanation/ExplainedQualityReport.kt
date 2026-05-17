package com.geoknoesis.kastor.ontoquality.explanation

import com.geoknoesis.kastor.ontoquality.QualityReport

/**
 * [QualityReport] plus optional v0.3 LLM explanation annotations.
 */
data class ExplainedQualityReport(
    val report: QualityReport,
    val explanations: List<FindingExplanation>,
) {
    val conforms: Boolean get() = report.conforms

    /** True when v0.3 LLM rows exist (calls succeeded and produced at least one parsed explanation). */
    val hasLlmExplanations: Boolean get() = explanations.isNotEmpty()

    fun explanationsByRef(): Map<FindingRef, FindingExplanation> =
        explanations.associateBy { it.findingRef }

    fun describeText(): String =
        buildString {
            append(report.describeText())
            if (explanations.isNotEmpty()) {
                appendLine()
                appendLine("=== LLM explanations (advisory; not SHACL entailment) ===")
                for (e in explanations) {
                    appendLine("[${e.findingRef.hexSha256}] (${e.providerKind} / ${e.modelId})")
                    appendLine("  ${e.summary}")
                    e.whyItMatters?.let { appendLine("  Why it matters: $it") }
                    if (e.suggestedActions.isNotEmpty()) {
                        appendLine("  Suggested actions:")
                        for (a in e.suggestedActions) {
                            appendLine("    - $a")
                        }
                    }
                    e.confidenceNote?.let { appendLine("  Note: $it") }
                    appendLine()
                }
            }
        }

    fun describeMarkdown(): String =
        buildString {
            appendLine(report.describeMarkdown())
            if (explanations.isNotEmpty()) {
                appendLine()
                appendLine("## LLM explanations *(advisory — not SHACL entailment)*")
                appendLine()
                for (e in explanations) {
                    appendLine("### `${e.findingRef.hexSha256.take(12)}…` — ${e.providerKind} / `${e.modelId}`")
                    appendLine()
                    appendLine(e.summary)
                    appendLine()
                    e.whyItMatters?.let {
                        appendLine("**Why it matters:** $it")
                        appendLine()
                    }
                    if (e.suggestedActions.isNotEmpty()) {
                        appendLine("**Suggested actions:**")
                        for (a in e.suggestedActions) {
                            appendLine("- $a")
                        }
                        appendLine()
                    }
                    e.confidenceNote?.let {
                        appendLine("*${it}*")
                        appendLine()
                    }
                }
            }
        }
}
