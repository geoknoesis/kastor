package com.geoknoesis.kastor.ontoquality

import com.geoknoesis.kastor.ontoquality.explanation.sanitizeTerminalText
import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TripleTerm

/**
 * Where a finding is: its focus node, result path and value as short N-Triples-like text for the reports. A blank
 * node is shown by its parse-independent key ([QualityFinding.blankNodeKeys]) when one is known, so the text does not
 * change from one parse to the next. The text is not sanitised: callers escape it for their output (terminal, Markdown).
 */
internal object FindingTerms {
    /** Longest rendering of one term, in characters; longer literals and IRIs are cut with an ellipsis. */
    const val MAX_TERM_CHARS = 300

    private const val XSD_STRING = "http://www.w3.org/2001/XMLSchema#string"
    private const val MAX_DEPTH = 8

    fun focus(finding: QualityFinding): String = term(finding.violation.focusNode, finding.blankNodeKeys)

    /** The result path, or null when the result has none. A complex path (a blank node of the shapes graph) is named by its key. */
    fun path(finding: QualityFinding): String? {
        val steps = finding.violation.path.orEmpty()
        if (steps.isNotEmpty()) return cut(steps.joinToString(" / ") { term(it, finding.blankNodeKeys) })
        return finding.violation.resultPathNode?.let { term(it, finding.blankNodeKeys) }
    }

    fun value(finding: QualityFinding): String? = finding.violation.value?.let { term(it, finding.blankNodeKeys) }

    fun term(value: RdfTerm, keys: Map<BlankNode, String>): String = cut(render(value, keys, 0))

    private fun render(value: RdfTerm, keys: Map<BlankNode, String>, depth: Int): String =
        when (value) {
            is Iri -> "<${value.value}>"
            is BlankNode -> keys[value] ?: value.toString()
            is Literal -> literal(value)
            is TripleTerm ->
                if (depth >= MAX_DEPTH) {
                    "<<( … )>>"
                } else {
                    val t = value.triple
                    "<<( ${render(t.subject, keys, depth + 1)} ${render(t.predicate, keys, depth + 1)} ${render(t.obj, keys, depth + 1)} )>>"
                }
            else -> value.toString()
        }

    private fun literal(value: Literal): String {
        if (value is LangString) return value.toString()
        val lexical = "\"" + value.lexical.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val datatype = value.datatype.value
        return if (datatype == XSD_STRING) lexical else "$lexical^^<$datatype>"
    }

    private fun cut(text: String): String = if (text.length <= MAX_TERM_CHARS) text else text.take(MAX_TERM_CHARS - 1) + "…"
}

/** The plain-text report of [QualityReport.describeText]: every ontology-derived string is sanitised for a terminal. */
internal fun renderTextReport(report: QualityReport): String =
    buildString {
        appendLine("Ontology quality (SHACL validation): ${if (report.conforms) "conforms" else "does not conform"}")
        appendLine("Violations: ${report.violationCount}, warnings: ${report.warningCount}, info: ${report.infoCount}")
        if (report.truncated) {
            appendLine(
                "TRUNCATED: the validator stopped recording results after ${report.findings.size} finding(s); more exist. " +
                    "The counts above cover the listed findings only. Raise the limit (ValidationConfig.maxViolations, " +
                    "onto-qa --max-findings) to see them all.",
            )
        }
        appendLine()
        for (cat in enumValues<QualityCategory>().sortedBy { it.name }) {
            val items = report.byCategory[cat].orEmpty()
            if (items.isEmpty()) continue
            appendLine("## ${cat.name.replace('_', ' ')} (${items.size})")
            for (f in items) {
                appendLine(" - [${f.tier}] ${f.violation.severity}: ${sanitizeTerminalText(f.stableMessage)}")
                appendLine("   Focus: ${sanitizeTerminalText(FindingTerms.focus(f))}")
                FindingTerms.path(f)?.let { appendLine("   Path: ${sanitizeTerminalText(it)}") }
                FindingTerms.value(f)?.let { appendLine("   Value: ${sanitizeTerminalText(it)}") }
                f.pitfall?.let { appendLine("   Pitfall: ${it.shortLabel()}") }
            }
            appendLine()
        }
    }
