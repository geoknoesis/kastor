package com.geoknoesis.kastor.ontoquality

import com.geoknoesis.kastor.ontoquality.catalog.ShapeCatalog
import com.geoknoesis.kastor.ontoquality.catalog.ShapeMetadata
import com.geoknoesis.kastor.rdf.shacl.ValidationReport
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity

data class QualityReport(
    val underlying: ValidationReport,
    val findings: List<QualityFinding>,
    val byCategory: Map<QualityCategory, List<QualityFinding>>,
    val byTier: Map<QualityTier, List<QualityFinding>>,
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

    fun describeText(): String = buildString {
        appendLine("Ontology quality (SHACL validation): ${if (conforms) "conforms" else "does not conform"}")
        appendLine("Violations: $violationCount, warnings: $warningCount, info: $infoCount")
        appendLine()
        for (cat in QualityCategory.entries.sortedBy { it.name }) {
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

    fun describeMarkdown(): String = buildString {
        appendLine("# Ontology quality report")
        appendLine()
        appendLine("- **Conforms:** `$conforms`")
        appendLine("- **Violation-level rows:** ${underlying.violations.count { it.severity == ViolationSeverity.VIOLATION || it.severity == ViolationSeverity.ERROR }}")
        appendLine("- **SHACL warnings (report):** ${underlying.warnings.size}")
        appendLine()
        for (cat in QualityCategory.entries.sortedBy { it.name }) {
            val items = byCategory[cat].orEmpty().filter {
                it.violation.severity == ViolationSeverity.VIOLATION ||
                    it.violation.severity == ViolationSeverity.ERROR
            }
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
        fun from(raw: ValidationReport, catalogs: List<ShapeCatalog>): QualityReport {
            val meta = catalogs.fold(emptyMap<String, ShapeMetadata>()) { acc, c -> acc + c.shapeMetadata }
            val findings = raw.violations.map { v -> QualityFinding.from(v, meta) }
            val byCategory = findings.groupBy { it.category }
            val byTier = findings.groupBy { it.tier }
            return QualityReport(
                underlying = raw,
                findings = findings,
                byCategory = byCategory,
                byTier = byTier,
            )
        }
    }
}

data class QualityFinding(
    val violation: ValidationViolation,
    val category: QualityCategory,
    val pitfall: PitfallReference?,
    val tier: QualityTier,
) {
    companion object {
        fun from(violation: ValidationViolation, shapeMetadata: Map<String, ShapeMetadata>): QualityFinding =
            violation.shapeUri?.let { shapeMetadata[it] }?.let { sm ->
                QualityFinding(
                    violation = violation,
                    category = sm.category,
                    pitfall = sm.pitfall,
                    tier = sm.tier,
                )
            } ?: QualityFinding(
                violation = violation,
                category = QualityCategory.UNCATEGORIZED,
                pitfall = null,
                tier = QualityTier.STRUCTURAL,
            )
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

    object Convention : PitfallReference()

    fun shortLabel(): String =
        when (this) {
            is Oops -> "OOPS! $number"
            is Skos -> "SKOS $number"
            is OntoQuality -> "Onto-quality $number"
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
