package com.geoknoesis.kastor.ontoquality.metrics.integration

import com.geoknoesis.kastor.ontoquality.integration.MetricsContext
import com.geoknoesis.kastor.ontoquality.integration.MetricsProvider
import com.geoknoesis.kastor.ontoquality.metrics.ImportanceWeights
import com.geoknoesis.kastor.ontoquality.metrics.MetricsConfig
import com.geoknoesis.kastor.ontoquality.metrics.VocabularyMetrics
import com.geoknoesis.kastor.ontoquality.metrics.VocabularyMetricsReport
import com.geoknoesis.kastor.rdf.RdfGraph

/**
 * Default implementation of [MetricsProvider] using Kastor's
 * [VocabularyMetrics] computation. Computes an entity importance score
 * per named OWL class from fan-out, depth, incoming property references,
 * and label presence.
 *
 * Usage:
 * ```
 * val checker = QualityChecker.builder(validator)
 *     .withAllBundledCatalogs()
 *     .withMetricsProvider(KastorMetricsProvider())
 *     .build()
 * ```
 */
class KastorMetricsProvider(
    config: MetricsConfig = MetricsConfig.default(),
    private val importanceWeights: ImportanceWeights = ImportanceWeights.default(),
) : MetricsProvider {

    /** Ensures uncapped subclass breakdown is available for integration scoring. */
    private val effectiveConfig: MetricsConfig = config.copy(unboundedFanOutBreakdown = true)

    override fun compute(ontology: RdfGraph): MetricsContext {
        val report = VocabularyMetrics.compute(ontology, effectiveConfig)
        val computed = computeImportance(ontology, report, importanceWeights)
        val summary = renderSummary(report)
        return MetricsContext(
            summary = summary,
            entityImportance = computed.importance,
            entityHints = computed.hints,
        )
    }

    private fun renderSummary(report: VocabularyMetricsReport): String =
        buildString {
            appendLine("**OQuaRE metric summary**")
            appendLine()
            val oq = report.owl.oquare
            appendLine(
                "- Depth (DITOnto): ${formatDouble(oq.depthOfInheritanceTree.rawValue)} " +
                    "(score ${oq.depthOfInheritanceTree.score?.toString() ?: "-"})",
            )
            appendLine(
                "- Tangledness (TMOnto): ${formatDouble(oq.tangledness.rawValue)} " +
                    "(score ${oq.tangledness.score?.toString() ?: "-"})",
            )
            appendLine(
                "- Relationship richness (RROnto): ${formatPercent(oq.relationshipRichness.rawValue)} " +
                    "(score ${oq.relationshipRichness.score?.toString() ?: "-"})",
            )
            appendLine(
                "- Annotation richness (ANOnto): ${formatDouble(oq.annotationRichness.rawValue)} " +
                    "(score ${oq.annotationRichness.score?.toString() ?: "-"})",
            )
            appendLine(
                "- Classes: ${report.owl.entityCounts.totalNamedClasses}, " +
                    "Properties: ${report.owl.entityCounts.totalProperties}",
            )
        }

    private fun formatDouble(d: Double): String = "%.2f".format(d)

    private fun formatPercent(d: Double): String = "%.1f%%".format(d * 100.0)
}
