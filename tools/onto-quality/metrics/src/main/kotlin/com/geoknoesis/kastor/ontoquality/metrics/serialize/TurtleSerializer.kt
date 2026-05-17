package com.geoknoesis.kastor.ontoquality.metrics.serialize

import com.geoknoesis.kastor.ontoquality.metrics.KastorMetricsVocab
import com.geoknoesis.kastor.ontoquality.metrics.MetricValue
import com.geoknoesis.kastor.ontoquality.metrics.VocabularyMetricsReport
import java.util.UUID

internal object TurtleSerializer {
    fun toTurtle(report: VocabularyMetricsReport): String {
        val node = "urn:kastor:metrics:report:${UUID.randomUUID()}"
        val props = LinkedHashMap<String, String>()
        props["kastor-m:moduleVersion"] = "\"${report.moduleVersion}\""
        props["dct:created"] = "\"${report.computedAt}\"^^xsd:dateTime"

        val g = report.graph
        props["void:triples"] = "${g.tripleCount}"
        props["void:distinctSubjects"] = "${g.distinctSubjectCount}"
        props["void:properties"] = "${g.distinctPredicateCount}"
        props["void:distinctObjects"] = "${g.distinctObjectCount}"
        props["void:classes"] = "${g.distinctClassesUsed}"

        val metrics =
            (report.owl.oquare.toList() +
                listOf(
                    report.skos.conceptCount,
                    report.skos.prefLabelCoverage,
                    report.skos.definitionCoverage,
                    report.skos.orphanConceptCount,
                    report.skos.siblingCohorts.cohortCount,
                    report.skos.siblingCohorts.maxCohortSize,
                ))
                .filter { it.computable }
                .sortedBy { it.metricIri }

        for (m in metrics) {
            props["<${m.metricIri}>"] = turtleLexical(m).trim()
        }

        val sortedKeys = props.keys.sorted()
        val tripleLines = ArrayList<String>()
        tripleLines.add("@prefix void: <http://rdfs.org/ns/void#> .")
        tripleLines.add("@prefix kastor-m: <https://w3id.org/kastor/metrics#> .")
        tripleLines.add("@prefix kastor-m-oquare: <https://w3id.org/kastor/metrics/oquare#> .")
        tripleLines.add("@prefix dct: <http://purl.org/dc/terms/> .")
        tripleLines.add("@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .")
        tripleLines.add("")
        tripleLines.add("<$node>")
        val inner = ArrayList<String>()
        inner.add("    a void:Dataset, kastor-m:MetricsReport")
        for (k in sortedKeys) {
            inner.add("    $k ${props[k]}")
        }

        val scored = report.owl.oquare.toList().filter { it.computable && it.score != null }.sortedBy { it.metricIri }
        if (scored.isNotEmpty()) {
            val blocks =
                scored.joinToString(" ,\n        ") { m ->
                    """
                    [
                        a kastor-m:Score ;
                        kastor-m:onMetric <${m.metricIri}> ;
                        kastor-m:scoreValue ${m.score} ;
                        kastor-m:scoringScheme <${KastorMetricsVocab.oquareScoring}> ;
                    ]
                    """.trimIndent().replace("\n", "\n        ")
                }
            inner.add("    kastor-m:hasScore $blocks")
        }

        tripleLines.add(inner.joinToString(" ;\n"))
        tripleLines.add(" .")
        tripleLines.add("")
        return tripleLines.joinToString("\n")
    }

    private fun turtleLexical(m: MetricValue): String {
        val v = m.rawValue
        val isInt = kotlin.math.abs(v - kotlin.math.round(v)) < 1e-9 && kotlin.math.abs(v) < 1e15
        return if (isInt) {
            "${v.toLong()}"
        } else {
            "\"${String.format(java.util.Locale.US, "%.4f", v)}\"^^xsd:decimal"
        }
    }
}
