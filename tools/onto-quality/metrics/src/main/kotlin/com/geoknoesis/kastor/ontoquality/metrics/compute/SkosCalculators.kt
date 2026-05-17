package com.geoknoesis.kastor.ontoquality.metrics.compute

import com.geoknoesis.kastor.ontoquality.metrics.CohortSizeDistribution
import com.geoknoesis.kastor.ontoquality.metrics.KastorMetricsVocab
import com.geoknoesis.kastor.ontoquality.metrics.MetricValue
import com.geoknoesis.kastor.ontoquality.metrics.MetricsConfig
import com.geoknoesis.kastor.ontoquality.metrics.SkosMappingEdges
import com.geoknoesis.kastor.ontoquality.metrics.SkosMetricsSection
import com.geoknoesis.kastor.ontoquality.metrics.SkosSiblingCohort
import com.geoknoesis.kastor.ontoquality.metrics.SkosSiblingCohorts
import com.geoknoesis.kastor.ontoquality.metrics.SkosStructuralEdges
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.SKOS

internal object SkosCalculators {
    fun compute(graph: RdfGraph, scratch: SkosScratch, config: MetricsConfig): SkosMetricsSection {
        val concepts = scratch.concepts.toSet()
        val n = concepts.size.toLong()

        val prefLabels = mutableSetOf<String>()
        val definitions = mutableSetOf<String>()
        val inSchemeConcepts = mutableSetOf<String>()
        val topConceptConcepts = mutableSetOf<String>()
        val schemesByConcept = mutableMapOf<String, Long>()
        val prefLang = mutableMapOf<String, Long>()

        for (t in graph.getTriplesSequence()) {
            val s = (t.subject as? Iri)?.value ?: continue
            if (s !in concepts) continue
            when (t.predicate) {
                SKOS.prefLabel -> {
                    prefLabels.add(s)
                    val lang = (t.obj as? LangString)?.lang?.takeIf { it.isNotBlank() } ?: ""
                    prefLang[lang] = prefLang.getOrDefault(lang, 0L) + 1L
                }
                SKOS.definition -> definitions.add(s)
                RDFS.comment -> definitions.add(s)
                SKOS.inScheme -> {
                    inSchemeConcepts.add(s)
                    val sch = (t.obj as? Iri)?.value ?: continue
                    schemesByConcept[sch] = schemesByConcept.getOrDefault(sch, 0L) + 1L
                }
                SKOS.topConceptOf -> topConceptConcepts.add(s)
                else -> Unit
            }
        }

        val orphans =
            concepts.count { it !in inSchemeConcepts && it !in topConceptConcepts }.toLong()

        val conceptMetric =
            MetricValue(
                metricIri = KastorMetricsVocab.conceptCount,
                oquareName = null,
                rawValue = n.toDouble(),
                score = null,
                computable = true,
                notes = null,
            )

        val prefCov =
            if (n == 0L) {
                MetricValue(
                    metricIri = KastorMetricsVocab.prefLabelCoverage,
                    oquareName = null,
                    rawValue = 0.0,
                    score = null,
                    computable = false,
                    notes = "no concepts",
                )
            } else {
                MetricValue(
                    metricIri = KastorMetricsVocab.prefLabelCoverage,
                    oquareName = null,
                    rawValue = prefLabels.size.toDouble() / n.toDouble(),
                    score = null,
                    computable = true,
                    notes = null,
                )
            }

        val defCov =
            if (n == 0L) {
                MetricValue(
                    metricIri = KastorMetricsVocab.definitionCoverage,
                    oquareName = null,
                    rawValue = 0.0,
                    score = null,
                    computable = false,
                    notes = "no concepts",
                )
            } else {
                MetricValue(
                    metricIri = KastorMetricsVocab.definitionCoverage,
                    oquareName = null,
                    rawValue = definitions.size.toDouble() / n.toDouble(),
                    score = null,
                    computable = true,
                    notes = null,
                )
            }

        val orphanMetric =
            MetricValue(
                metricIri = KastorMetricsVocab.orphanConceptCount,
                oquareName = null,
                rawValue = orphans.toDouble(),
                score = null,
                computable = true,
                notes = null,
            )

        val cohorts = buildSiblingCohorts(scratch, graph, concepts, config)

        return SkosMetricsSection(
            conceptCount = conceptMetric,
            conceptSchemeCount = scratch.schemes.size.toLong(),
            collectionCount = scratch.collectionCount,
            orderedCollectionCount = scratch.orderedCollectionCount,
            prefLabelCoverage = prefCov,
            definitionCoverage = defCov,
            orphanConceptCount = orphanMetric,
            structuralEdgeCounts =
                SkosStructuralEdges(
                    broaderEdges = scratch.broaderEdges,
                    narrowerEdges = scratch.narrowerEdges,
                    relatedEdges = scratch.relatedEdges,
                    broaderTransitiveEdges = scratch.broaderTransitiveEdges,
                    narrowerTransitiveEdges = scratch.narrowerTransitiveEdges,
                ),
            mappingEdgeCounts =
                SkosMappingEdges(
                    exactMatch = scratch.exactMatchEdges,
                    closeMatch = scratch.closeMatchEdges,
                    broadMatch = scratch.broadMatchEdges,
                    narrowMatch = scratch.narrowMatchEdges,
                    relatedMatch = scratch.relatedMatchEdges,
                    totalMappings =
                        scratch.exactMatchEdges +
                            scratch.closeMatchEdges +
                            scratch.broadMatchEdges +
                            scratch.narrowMatchEdges +
                            scratch.relatedMatchEdges,
                ),
            schemesByConceptCount = schemesByConcept.toSortedMap(),
            prefLabelsPerLanguage = prefLang.toSortedMap(),
            siblingCohorts = cohorts,
        )
    }

    private fun buildSiblingCohorts(
        scratch: SkosScratch,
        graph: RdfGraph,
        concepts: Set<String>,
        config: MetricsConfig,
    ): SkosSiblingCohorts {
        val parentToChildren =
            scratch.parentToNarrowers
                .mapValues { (_, ch) -> ch.filter { it in concepts }.sorted().toList() }
                .filter { it.value.size >= 2 }

        val cohortObjs =
            parentToChildren
                .map { (parent, sibs) ->
                    val related = countRelatedAmong(sibs.toSet(), graph)
                    SkosSiblingCohort(parent = parent, siblings = sibs, relatedEdgesAmongSiblings = related)
                }
                .sortedWith(compareByDescending<SkosSiblingCohort> { it.siblings.size }.thenBy { it.parent })

        val cohortCount = parentToChildren.size.toLong()
        val cohortMetric =
            MetricValue(
                metricIri = KastorMetricsVocab.siblingCohortCount,
                oquareName = null,
                rawValue = cohortCount.toDouble(),
                score = null,
                computable = true,
                notes = null,
            )

        val maxSize = cohortObjs.maxOfOrNull { it.siblings.size } ?: 0
        val maxParents =
            cohortObjs.filter { it.siblings.size == maxSize && maxSize >= 2 }.map { it.parent }.sorted()

        val maxMetric =
            MetricValue(
                metricIri = KastorMetricsVocab.maxSiblingCohortSize,
                oquareName = null,
                rawValue = maxSize.toDouble(),
                score = null,
                computable = cohortObjs.isNotEmpty(),
                notes = if (cohortObjs.isEmpty()) "no sibling cohorts" else null,
            )

        var pairSum = 0L
        for (c in cohortObjs) {
            val k = c.siblings.size.toLong()
            pairSum += k * (k - 1) / 2
        }

        var relatedWithin = 0L
        for (c in cohortObjs) relatedWithin += c.relatedEdgesAmongSiblings

        val dist = cohortSizeDistribution(cohortObjs.map { it.siblings.size })

        val listedCohorts =
            if (config.includePerParentBreakdowns) cohortObjs else emptyList()

        return SkosSiblingCohorts(
            cohortCount = cohortMetric,
            totalSiblingPairs = pairSum,
            maxCohortSize = maxMetric,
            maxCohortParents = maxParents,
            cohorts = listedCohorts,
            relatedEdgesWithinCohorts = relatedWithin,
            cohortSizeDistribution = dist,
        )
    }

    private fun cohortSizeDistribution(sizes: List<Int>): CohortSizeDistribution {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        for (s in sizes) {
            when {
                s in 2..5 -> a++
                s in 6..10 -> b++
                s in 11..20 -> c++
                s > 20 -> d++
            }
        }
        return CohortSizeDistribution(twoToFive = a, sixToTen = b, elevenToTwenty = c, overTwenty = d)
    }

    private fun countRelatedAmong(siblings: Set<String>, graph: RdfGraph): Long {
        if (siblings.size < 2) return 0L
        var n = 0L
        for (t in graph.getTriplesSequence()) {
            if (t.predicate != SKOS.related) continue
            val s = (t.subject as? Iri)?.value ?: continue
            val o = (t.obj as? Iri)?.value ?: continue
            if (s in siblings && o in siblings) n++
        }
        return n
    }
}
