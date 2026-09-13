package com.geoknoesis.kastor.ontoquality.explanation

import com.geoknoesis.kastor.ontoquality.MarkdownReportOptions
import com.geoknoesis.kastor.ontoquality.QualityReport

/**
 * [QualityReport] plus optional v0.3 LLM explanation annotations.
 *
 * @param failures batches that produced no explanation; [explanations] holds whatever succeeded.
 */
data class ExplainedQualityReport @JvmOverloads constructor(
    val report: QualityReport,
    val explanations: List<FindingExplanation>,
    val failures: List<ExplanationFailure> = emptyList(),
) {
    val conforms: Boolean get() = report.conforms

    /** True when v0.3 LLM rows exist (calls succeeded and produced at least one parsed explanation). */
    val hasLlmExplanations: Boolean get() = explanations.isNotEmpty()

    /** True when at least one batch of findings could not be explained. */
    val hasExplanationFailures: Boolean get() = failures.isNotEmpty()

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
            if (failures.isNotEmpty()) {
                appendLine("LLM explanation failures: ${failures.sumOf { it.findingRefs.size }} finding(s) not explained")
                for (f in failures) appendLine("  - ${f.reason}")
            }
        }

    fun describeMarkdown(): String = describeMarkdown(MarkdownReportOptions())

    /**
     * LLM-generated text is untrusted (it may echo prompt-injected ontology content), so every LLM field is
     * rendered as escaped inline text: Markdown punctuation is backslash-escaped, line breaks are collapsed,
     * raw HTML is neutralised and link / image / autolink syntax cannot form.
     */
    fun describeMarkdown(options: MarkdownReportOptions): String =
        buildString {
            appendLine(report.describeMarkdown(options))
            if (explanations.isNotEmpty()) {
                appendLine()
                appendLine("## LLM explanations *(advisory — not SHACL entailment)*")
                appendLine()
                for (e in explanations) {
                    appendLine(
                        "### `${e.findingRef.hexSha256.take(12)}…` — ${escapeMarkdownInline(e.providerKind)} / " +
                            "`${codeSpanSafe(e.modelId)}`",
                    )
                    appendLine()
                    appendLine(escapeMarkdownInline(e.summary))
                    appendLine()
                    e.whyItMatters?.let {
                        appendLine("**Why it matters:** ${escapeMarkdownInline(it)}")
                        appendLine()
                    }
                    if (e.suggestedActions.isNotEmpty()) {
                        appendLine("**Suggested actions:**")
                        for (a in e.suggestedActions) {
                            appendLine("- ${escapeMarkdownInline(a)}")
                        }
                        appendLine()
                    }
                    e.confidenceNote?.let {
                        appendLine("*${escapeMarkdownInline(it)}*")
                        appendLine()
                    }
                }
            }
            if (failures.isNotEmpty()) {
                appendLine()
                appendLine(
                    "> **LLM explanation failures:** ${failures.sumOf { it.findingRefs.size }} finding(s) not explained.",
                )
                for (f in failures) appendLine("> - ${escapeMarkdownInline(f.reason)}")
                appendLine()
            }
        }
}

private val MARKDOWN_SPECIAL = setOf('\\', '`', '*', '_', '{', '}', '[', ']', '(', ')', '#', '+', '-', '!', '|', '~', '>', '<', '&', '"')

/**
 * Renders untrusted text as a single inline Markdown run: every CommonMark-significant ASCII punctuation
 * character is backslash-escaped (so `[x](y)`, `![x](y)`, `<a>`, `<http://…>`, emphasis and headings cannot
 * form), line breaks and other control characters become spaces, and bare-URL autolinks (`http://`, `www.`)
 * are broken so GFM does not linkify them.
 */
internal fun escapeMarkdownInline(text: String): String {
    val sb = StringBuilder(text.length + 16)
    for (ch in text) {
        when {
            ch == '\r' || ch == '\n' || ch == ' ' || ch == ' ' -> sb.append(' ')
            ch.isISOControl() -> sb.append(' ')
            ch in MARKDOWN_SPECIAL -> sb.append('\\').append(ch)
            else -> sb.append(ch)
        }
    }
    var out = sb.toString()
    out = out.replace("://", ":\\/\\/")
    out = Regex("(?i)www\\.").replace(out) { "${it.value.dropLast(1)}\\." }
    // Ordered-list markers only matter at the start of the run.
    out = Regex("^(\\s*\\d+)([.)])").replace(out) { "${it.groupValues[1]}\\${it.groupValues[2]}" }
    return out
}

/** Code spans cannot contain backticks or line breaks; strip them. */
private fun codeSpanSafe(text: String): String = text.filter { it != '`' && !it.isISOControl() }
