package com.geoknoesis.kastor.ontoquality

import com.geoknoesis.kastor.ontoquality.catalog.ShapeCatalog
import com.geoknoesis.kastor.ontoquality.catalog.ShapeMetadata
import com.geoknoesis.kastor.ontoquality.integration.FindingPrioritizer
import com.geoknoesis.kastor.ontoquality.integration.MetricsContext
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.shacl.ValidationReport
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity

data class QualityReport(
    val underlying: ValidationReport,
    val findings: List<QualityFinding>,
    val byCategory: Map<QualityCategory, List<QualityFinding>>,
    val byTier: Map<QualityTier, List<QualityFinding>>,
    val metricsContext: MetricsContext? = null,
) {
    val conforms: Boolean get() = underlying.isValid

    val violationCount: Int =
        underlying.violations.count {
            it.severity == ViolationSeverity.VIOLATION || it.severity == ViolationSeverity.ERROR
        }

    val warningCount: Int =
        underlying.violations.count { it.severity == ViolationSeverity.WARNING } +
            underlying.warnings.size

    val infoCount: Int =
        underlying.violations.count {
            it.severity == ViolationSeverity.INFO ||
                it.severity == ViolationSeverity.DEBUG ||
                it.severity == ViolationSeverity.TRACE
        }

    /** Counts aligned with [underlying] violation rows (after any merges performed before [from]). */
    fun violationsBySeverity(): Map<ViolationSeverity, Int> =
        underlying.violations.groupingBy { it.severity }.eachCount()

    fun describeText(): String = buildString {
        appendLine("Ontology quality (SHACL validation): ${if (conforms) "conforms" else "does not conform"}")
        appendLine("Violations: $violationCount, warnings: $warningCount, info: $infoCount")
        appendLine()
        for (cat in enumValues<QualityCategory>().sortedBy { it.name }) {
            val items = byCategory[cat].orEmpty()
            if (items.isEmpty()) continue
            appendLine("## ${cat.name.replace('_', ' ')} (${items.size})")
            for (f in items) {
                appendLine(" - [${f.tier}] ${f.violation.severity}: ${f.violation.message}")
                f.pitfall?.let { appendLine("   Pitfall: ${it.shortLabel()}") }
            }
            appendLine()
        }
    }

    /**
     * First [n] rows of [findings]. When [metricsContext] is non-null, [findings] is already sorted by importance
     * (then severity, then focus IRI), so this matches "top by importance" without re-sorting.
     */
    fun topFindings(n: Int = 10): List<QualityFinding> = findings.take(n)

    /**
     * Human-readable Markdown report (default options: emoji severity markers, top-10 when metrics are on).
     *
     * Without [metricsContext], category sections list **VIOLATION** / **ERROR** rows only (executive-style digest).
     * With metrics, [topFindings] may surface warnings and info; per-category sections list **all** finding rows so
     * nothing promoted in "Top findings" is omitted below.
     */
    fun describeMarkdown(): String = describeMarkdown(MarkdownReportOptions())

    fun describeMarkdown(options: MarkdownReportOptions): String = buildString {
        appendLine("# Ontology quality report")
        appendLine()
        appendLine("- **Conforms:** `$conforms`")
        appendLine("- **Violation-level rows:** ${underlying.violations.count { it.severity == ViolationSeverity.VIOLATION || it.severity == ViolationSeverity.ERROR }}")
        appendLine("- **SHACL warnings (report):** ${underlying.warnings.size}")
        appendLine()
        val ctx = metricsContext
        if (ctx != null) {
            appendLine("## Top findings by importance")
            appendLine()
            appendLine(
                "These findings affect entities that are structurally central in the " +
                    "ontology — high fan-out, deep position, or many incoming references. " +
                    "Address these first.",
            )
            appendLine()
            val topN = options.maxTopFindings.coerceAtLeast(0)
            for (f in findings.take(topN)) {
                val focusIri = (f.violation.focusNode as? Iri)?.value
                val focusLabel = focusIri?.let(::markdownShortIri) ?: "(non-IRI focus)"
                val marker = markdownSeverityMarker(f.violation.severity, options.useAsciiSeverityMarkers)
                appendLine("### $marker $focusLabel — ${markdownHeadline(f.violation.message)}")
                val imp = focusIri?.let { ctx.entityImportance[it] ?: MetricsContext.DEFAULT_IMPORTANCE }
                    ?: MetricsContext.DEFAULT_IMPORTANCE
                val hintParts = mutableListOf<String>()
                focusIri?.let { ctx.entityHints[it] }?.trim()?.takeIf { it.isNotEmpty() }?.let(hintParts::add)
                hintParts.add("centrality ${"%.2f".format(imp)}")
                appendLine("*${hintParts.joinToString("; ")}*")
                appendLine()
                val pit = f.pitfall?.shortLabel()?.let { " [$it]" } ?: ""
                appendLine("${f.violation.message.trimEnd()}$pit")
                val shape = f.violation.shapeUri?.let(::markdownShortIri)?.let { "Source shape: **$it**" }
                if (shape != null) {
                    appendLine(shape)
                }
                appendLine()
            }
            appendLine("## Metrics summary")
            appendLine()
            appendLine(ctx.summary.trimEnd())
            appendLine()
            appendLine("## All findings by category")
            appendLine()
        }
        val categorySeverityFilter: (QualityFinding) -> Boolean =
            if (ctx != null) {
                // Keep Markdown consistent with "Top findings": include warnings/info when metrics mode is on.
                { true }
            } else {
                {
                    it.violation.severity == ViolationSeverity.VIOLATION ||
                        it.violation.severity == ViolationSeverity.ERROR
                }
            }
        for (cat in enumValues<QualityCategory>().sortedBy { it.name }) {
            val items = byCategory[cat].orEmpty().filter(categorySeverityFilter)
            if (items.isEmpty()) continue
            appendLine("## ${cat.name.replace('_', ' ')}")
            appendLine()
            for (f in items) {
                val pit = f.pitfall?.shortLabel()?.let { " — **$it**" } ?: ""
                appendLine("- ${f.violation.message}$pit")
            }
            appendLine()
        }
    }

    companion object {
        fun from(
            raw: ValidationReport,
            catalogs: List<ShapeCatalog>,
            metricsContext: MetricsContext? = null,
        ): QualityReport {
            val meta = catalogs.fold(emptyMap<String, ShapeMetadata>()) { acc, c -> acc + c.shapeMetadata }
            val rawFindings = raw.violations.map { v -> QualityFinding.from(v, meta) }
            val sorted =
                metricsContext?.let { FindingPrioritizer.sort(rawFindings, it.entityImportance) }
                    ?: rawFindings
            val byCategory = sorted.groupBy { it.category }
            val byTier = sorted.groupBy { it.tier }
            return QualityReport(
                underlying = raw,
                findings = sorted,
                byCategory = byCategory,
                byTier = byTier,
                metricsContext = metricsContext,
            )
        }
    }
}

private fun markdownSeverityMarker(severity: ViolationSeverity, ascii: Boolean): String =
    if (ascii) {
        when (severity) {
            ViolationSeverity.VIOLATION -> "[VIOLATION]"
            ViolationSeverity.ERROR -> "[ERROR]"
            ViolationSeverity.WARNING -> "[WARNING]"
            ViolationSeverity.INFO -> "[INFO]"
            ViolationSeverity.DEBUG -> "[DEBUG]"
            ViolationSeverity.TRACE -> "[TRACE]"
        }
    } else {
        when (severity) {
            ViolationSeverity.VIOLATION,
            ViolationSeverity.ERROR,
            -> "🔴"
            ViolationSeverity.WARNING -> "🟡"
            ViolationSeverity.INFO,
            ViolationSeverity.DEBUG,
            ViolationSeverity.TRACE,
            -> "ⓘ"
        }
    }

private fun markdownShortIri(iri: String): String {
    val hash = iri.lastIndexOf('#')
    if (hash >= 0 && hash < iri.length - 1) return iri.substring(hash + 1)
    val slash = iri.lastIndexOf('/')
    if (slash >= 0 && slash < iri.length - 1) return iri.substring(slash + 1)
    return iri
}

private fun markdownHeadline(message: String): String {
    val line = message.lines().first().trim()
    return if (line.length <= 120) line else line.take(117).trimEnd() + "…"
}

data class QualityFinding(
    val violation: ValidationViolation,
    val category: QualityCategory,
    val pitfall: PitfallReference?,
    val tier: QualityTier,
) {
    companion object {
        fun from(violation: ValidationViolation, shapeMetadata: Map<String, ShapeMetadata>): QualityFinding {
            val meta =
                violation.shapeUri?.let { shapeMetadata[it] }
                    ?: violation.violationCode?.let { code -> shapeMetadata[code] }
            return meta?.let { sm ->
                QualityFinding(
                    violation = violation,
                    category = sm.category,
                    pitfall = sm.pitfall,
                    tier = sm.tier,
                )
            }
                ?: QualityFinding(
                    violation = violation,
                    category = QualityCategory.UNCATEGORIZED,
                    pitfall = null,
                    tier = QualityTier.STRUCTURAL,
                )
        }
    }
}

enum class QualityTier {
    STRUCTURAL,
    SEMANTIC,
    REASONING,
}

sealed class PitfallReference {
    data class Oops(val number: String) : PitfallReference()

    data class Skos(val number: String) : PitfallReference()

    /** N-numbered pitfalls from onto-quality catalogue shapes (beyond OOPS! / SKOS numbering). */
    data class OntoQuality(val number: String) : PitfallReference()

    /** K-numbered pitfalls: Kastor pipeline / reasoning extensions (avoid colliding with [OntoQuality] N-codes). */
    data class KastorExtension(val code: String) : PitfallReference()

    object Convention : PitfallReference()

    fun shortLabel(): String =
        when (this) {
            is Oops -> "OOPS! $number"
            is Skos -> "SKOS $number"
            is OntoQuality -> "Onto-quality $number"
            is KastorExtension -> "Kastor $code"
            is Convention -> "Convention"
        }
}

enum class QualityCategory {
    OWL_METADATA,
    OWL_ANNOTATIONS,
    OWL_PROPERTY_DECLARATIONS,
    OWL_HIERARCHY,
    OWL_VOCABULARY_INTEGRITY,
    OWL_INVERSES,
    OWL_DEPRECATION,
    OWL_NAMING,
    OWL_SEMANTICS,
    SKOS_CLASS_STRUCTURE,
    SKOS_LABELS,
    SKOS_SEMANTIC_RELATIONS,
    SKOS_CONCEPT_SCHEMES,
    SKOS_MAPPING,
    SKOS_COLLECTIONS,
    SKOS_NOTATIONS,
    DQ_INTEGRITY,
    DQ_FUNCTIONAL_DEPENDENCY,
    DQ_LISTED_VALUES,
    DQ_UNIQUENESS,
    FAIR_COMPLIANCE,
    URI_HYGIENE,
    LLM_CONSUMABILITY,
    OWL_ANTI_PATTERNS,
    MULTILINGUAL,
    VOCABULARY_REUSE,
    MAINTAINABILITY,
    RDF12_CONFORMANCE,
    UNCATEGORIZED,
}
