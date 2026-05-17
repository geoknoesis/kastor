package com.geoknoesis.kastor.ontoquality.metrics.serialize

import com.geoknoesis.kastor.ontoquality.metrics.MetricValue
import com.geoknoesis.kastor.ontoquality.metrics.VocabularyMetricsReport

internal object TextRenderer {
    fun render(report: VocabularyMetricsReport): String {
        val sb = StringBuilder()
        sb.appendLine("Vocabulary metrics (${report.moduleVersion}, ${report.oquareVersion})")
        sb.appendLine("Computed at: ${report.computedAt}")
        sb.appendLine()
        sb.appendLine("[Graph]")
        val g = report.graph
        sb.appendLine("  tripleCount=${g.tripleCount} distinctSubjects=${g.distinctSubjectCount} predicates=${g.distinctPredicateCount} objects=${g.distinctObjectCount}")
        sb.appendLine("  blankNodeSubjects=${g.blankNodeSubjectCount} literals=${g.literalObjectCount} iris=${g.iriObjectCount} classesUsed=${g.distinctClassesUsed}")
        sb.appendLine()
        sb.appendLine("[OQuaRE]")
        for (m in report.owl.oquare.toList().sortedBy { it.metricIri }) {
            line(sb, m)
        }
        sb.appendLine()
        sb.appendLine("[SKOS]")
        for (m in
            listOf(
                report.skos.conceptCount,
                report.skos.prefLabelCoverage,
                report.skos.definitionCoverage,
                report.skos.orphanConceptCount,
                report.skos.siblingCohorts.cohortCount,
                report.skos.siblingCohorts.maxCohortSize,
            )) {
            line(sb, m)
        }
        return sb.toString().trimEnd()
    }

    private fun line(sb: StringBuilder, m: MetricValue) {
        sb.append("  ")
        sb.append(local(m.metricIri))
        sb.append(" [${m.oquareName ?: "-"}] ")
        sb.append(String.format(java.util.Locale.US, "%.4f", m.rawValue))
        sb.append(" score=")
        sb.append(m.score ?: "-")
        sb.append(" computable=")
        sb.append(m.computable)
        if (m.notes != null) {
            sb.append(" — ")
            sb.append(m.notes)
        }
        sb.appendLine()
    }

    private fun local(iri: String): String = iri.substringAfterLast('#').substringAfterLast('/')
}
