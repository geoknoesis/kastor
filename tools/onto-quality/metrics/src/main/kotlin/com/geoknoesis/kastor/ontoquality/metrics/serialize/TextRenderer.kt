package com.geoknoesis.kastor.ontoquality.metrics.serialize

import com.geoknoesis.kastor.ontoquality.metrics.MetricValue
import com.geoknoesis.kastor.ontoquality.metrics.MetricsSection
import com.geoknoesis.kastor.ontoquality.metrics.VocabularyMetricsReport

internal object TextRenderer {
    fun render(report: VocabularyMetricsReport, sections: Set<MetricsSection> = MetricsSection.ALL): String {
        val header = StringBuilder()
        header.appendLine("Vocabulary metrics (${report.moduleVersion}, ${report.oquareVersion})")
        header.appendLine("Computed at: ${report.computedAt}")
        val blocks = ArrayList<StringBuilder>()
        if (MetricsSection.GRAPH in sections) blocks += graph(report)
        if (MetricsSection.OWL in sections) blocks += owl(report)
        if (MetricsSection.SKOS in sections) blocks += skos(report)
        // A slice keeps the header and the selected sections; the whole report is the same text with every section.
        val gap = System.lineSeparator() + System.lineSeparator()
        return (listOf(header) + blocks).joinToString(gap) { it.toString().trimEnd() }
    }

    private fun graph(report: VocabularyMetricsReport): StringBuilder {
        val sb = StringBuilder()
        sb.appendLine("[Graph]")
        val g = report.graph
        sb.appendLine("  tripleCount=${g.tripleCount} distinctSubjects=${g.distinctSubjectCount} predicates=${g.distinctPredicateCount} objects=${g.distinctObjectCount}")
        sb.appendLine("  blankNodeSubjects=${g.blankNodeSubjectCount} literals=${g.literalObjectCount} iris=${g.iriObjectCount} classesUsed=${g.distinctClassesUsed}")
        return sb
    }

    private fun owl(report: VocabularyMetricsReport): StringBuilder {
        val sb = StringBuilder()
        sb.appendLine("[OQuaRE]")
        for (m in report.owl.oquare.toList().sortedBy { it.metricIri }) {
            line(sb, m)
        }
        sb.appendLine()
        sb.appendLine("[Kastor-adapted (not OQuaRE)]")
        for (m in report.owl.kastorAdapted.toList().sortedBy { it.metricIri }) {
            line(sb, m)
        }
        return sb
    }

    private fun skos(report: VocabularyMetricsReport): StringBuilder {
        val sb = StringBuilder()
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
        return sb
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
