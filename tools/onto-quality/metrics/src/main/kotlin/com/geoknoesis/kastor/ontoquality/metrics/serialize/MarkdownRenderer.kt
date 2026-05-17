package com.geoknoesis.kastor.ontoquality.metrics.serialize

import com.geoknoesis.kastor.ontoquality.metrics.MetricValue
import com.geoknoesis.kastor.ontoquality.metrics.VocabularyMetricsReport

internal object MarkdownRenderer {
    fun render(report: VocabularyMetricsReport): String {
        val sb = StringBuilder()
        sb.appendLine("# Vocabulary metrics report")
        sb.appendLine()
        sb.appendLine("- Module: `${report.moduleVersion}`")
        sb.appendLine("- OQuaRE pin: ${report.oquareVersion}")
        sb.appendLine("- Computed at: `${report.computedAt}`")
        sb.appendLine()

        sb.appendLine("## VoID-style graph counts")
        sb.appendLine()
        sb.appendLine("| Field | Value |")
        sb.appendLine("| --- | ---: |")
        val g = report.graph
        sb.appendLine("| tripleCount | ${g.tripleCount} |")
        sb.appendLine("| distinctSubjectCount | ${g.distinctSubjectCount} |")
        sb.appendLine("| distinctPredicateCount | ${g.distinctPredicateCount} |")
        sb.appendLine("| distinctObjectCount | ${g.distinctObjectCount} |")
        sb.appendLine("| blankNodeSubjectCount | ${g.blankNodeSubjectCount} |")
        sb.appendLine("| literalObjectCount | ${g.literalObjectCount} |")
        sb.appendLine("| iriObjectCount | ${g.iriObjectCount} |")
        sb.appendLine("| distinctClassesUsed | ${g.distinctClassesUsed} |")
        sb.appendLine()

        fun table(title: String, rows: List<MetricValue>) {
            sb.appendLine("### $title")
            sb.appendLine()
            sb.appendLine("| Kastor metric | OQuaRE | Raw | Score | Computable | Notes |")
            sb.appendLine("| --- | --- | ---: | ---: | --- | --- |")
            for (m in rows.sortedBy { it.metricIri }) {
                sb.appendLine(
                    "| `${local(m.metricIri)}` | ${m.oquareName ?: "—"} | ${fmt(m.rawValue)} | ${m.score ?: "—"} | ${m.computable} | ${m.notes ?: ""} |",
                )
            }
            sb.appendLine()
        }

        val om = report.owl.oquare
        table(
            "Structural",
            listOf(om.depthOfInheritanceTree, om.numberOfAncestorClasses, om.numberOfChildren, om.couplingBetweenObjects),
        )
        table(
            "Complexity",
            listOf(om.weightedMethodCount, om.responseForClass, om.numberOfProperties, om.lackOfCohesionInMethods),
        )
        table(
            "Richness",
            listOf(
                om.relationshipRichness,
                om.inheritanceRichness,
                om.attributeRichness,
                om.classRichness,
                om.annotationRichness,
                om.propertiesRichness,
            ),
        )
        table("Other", listOf(om.tangledness))

        sb.appendLine("## SKOS extensions")
        sb.appendLine()
        val s = report.skos
        sb.appendLine("| Metric | Raw | Notes |")
        sb.appendLine("| --- | ---: | --- |")
        for (m in listOf(s.conceptCount, s.prefLabelCoverage, s.definitionCoverage, s.orphanConceptCount)) {
            sb.appendLine("| `${local(m.metricIri)}` | ${fmt(m.rawValue)} | ${m.notes ?: ""} |")
        }
        sb.appendLine("| siblingCohortCount | ${fmt(s.siblingCohorts.cohortCount.rawValue)} | ${s.siblingCohorts.cohortCount.notes ?: ""} |")
        sb.appendLine("| maxSiblingCohortSize | ${fmt(s.siblingCohorts.maxCohortSize.rawValue)} | ${s.siblingCohorts.maxCohortSize.notes ?: ""} |")

        return sb.toString()
    }

    private fun local(iri: String): String = iri.substringAfterLast('#').substringAfterLast('/')

    private fun fmt(d: Double): String = String.format(java.util.Locale.US, "%.4f", d)
}
