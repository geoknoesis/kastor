package com.geoknoesis.kastor.ontoquality.metrics.serialize

import com.geoknoesis.kastor.ontoquality.metrics.MetricValue
import com.geoknoesis.kastor.ontoquality.metrics.VocabularyMetricsReport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal object JsonSerializer {
    private val json =
        kotlinx.serialization.json.Json {
            prettyPrint = true
            encodeDefaults = true
        }

    fun toJson(report: VocabularyMetricsReport): String {
        val root: JsonObject =
            jsonObjectSorted(
                mapOf(
                    "computedAt" to JsonPrimitive(report.computedAt.toString()),
                    "graph" to graphSection(report),
                    "moduleVersion" to JsonPrimitive(report.moduleVersion),
                    "owl" to owlSection(report),
                    "oquareVersion" to JsonPrimitive(report.oquareVersion),
                    "skos" to skosSection(report),
                ),
            )
        return json.encodeToString(JsonElement.serializer(), root)
    }

    private fun graphSection(r: VocabularyMetricsReport): JsonObject {
        val g = r.graph
        return jsonObjectSorted(
            mapOf(
                "blankNodeSubjectCount" to JsonPrimitive(g.blankNodeSubjectCount),
                "distinctClassesUsed" to JsonPrimitive(g.distinctClassesUsed),
                "distinctObjectCount" to JsonPrimitive(g.distinctObjectCount),
                "distinctPredicateCount" to JsonPrimitive(g.distinctPredicateCount),
                "distinctSubjectCount" to JsonPrimitive(g.distinctSubjectCount),
                "iriObjectCount" to JsonPrimitive(g.iriObjectCount),
                "literalObjectCount" to JsonPrimitive(g.literalObjectCount),
                "tripleCount" to JsonPrimitive(g.tripleCount),
            ),
        )
    }

    private fun owlSection(r: VocabularyMetricsReport): JsonObject {
        val e = r.owl.entityCounts
        val entity =
            jsonObjectSorted(
                mapOf(
                    "owlAnnotationProperties" to JsonPrimitive(e.owlAnnotationProperties),
                    "owlClasses" to JsonPrimitive(e.owlClasses),
                    "owlDatatypeProperties" to JsonPrimitive(e.owlDatatypeProperties),
                    "owlNamedIndividuals" to JsonPrimitive(e.owlNamedIndividuals),
                    "owlObjectProperties" to JsonPrimitive(e.owlObjectProperties),
                    "owlOntologies" to JsonPrimitive(e.owlOntologies),
                    "owlRestrictions" to JsonPrimitive(e.owlRestrictions),
                    "rdfsClasses" to JsonPrimitive(e.rdfsClasses),
                    "totalNamedClasses" to JsonPrimitive(e.totalNamedClasses),
                    "totalProperties" to JsonPrimitive(e.totalProperties),
                ),
            )
        return jsonObjectSorted(
            mapOf(
                "entityCounts" to entity,
                "extensions" to extensionSection(r.owl.extensions),
                "oquare" to oquareMetrics(r.owl.oquare.toList()),
            ),
        )
    }

    private fun extensionSection(ext: com.geoknoesis.kastor.ontoquality.metrics.OwlExtensions): JsonObject {
        val fan = ext.subClassFanOut
        val depth = ext.classHierarchyDepth
        val imports = ext.imports
        return jsonObjectSorted(
            mapOf(
                "classHierarchyDepth" to
                    jsonObjectSorted(
                        buildMap {
                            put("classesAtMaxDepth", JsonPrimitive(depth.classesAtMaxDepth))
                            put("cycleParticipants", JsonArray(depth.cycleParticipants.map { JsonPrimitive(it) }))
                            put("cyclesDetected", JsonPrimitive(depth.cyclesDetected))
                            put(
                                "deepestChainExample",
                                if (depth.deepestChainExample == null) {
                                    JsonNull
                                } else {
                                    JsonArray(depth.deepestChainExample.map { JsonPrimitive(it) })
                                },
                            )
                            put("depthCapHit", JsonPrimitive(depth.depthCapHit))
                            put("maxDepthFound", JsonPrimitive(depth.maxDepthFound))
                            if (depth.depthByClass.isNotEmpty()) {
                                put(
                                    "depthByClass",
                                    jsonObjectSorted(
                                        depth.depthByClass.keys.sorted().associateWith { cls ->
                                            JsonPrimitive(depth.depthByClass.getValue(cls))
                                        },
                                    ),
                                )
                            }
                        },
                    ),
                "imports" to
                    jsonObjectSorted(
                        mapOf(
                            "importStatements" to JsonPrimitive(imports.importStatements),
                            "importedIris" to JsonArray(imports.importedIris.map { JsonPrimitive(it) }),
                            "unversionedImports" to JsonPrimitive(imports.unversionedImports),
                            "versionedImports" to JsonPrimitive(imports.versionedImports),
                        ),
                    ),
                "ontologyHeaders" to
                    buildJsonArray {
                        for (h in ext.ontologyHeaders.sortedBy { it.ontologyIri }) {
                            add(
                                jsonObjectSorted(
                                    mapOf(
                                        "hasComment" to JsonPrimitive(h.hasComment),
                                        "hasCreator" to JsonPrimitive(h.hasCreator),
                                        "hasLabel" to JsonPrimitive(h.hasLabel),
                                        "hasLicense" to JsonPrimitive(h.hasLicense),
                                        "hasVersionIri" to JsonPrimitive(h.hasVersionIri),
                                        "ontologyIri" to JsonPrimitive(h.ontologyIri),
                                    ),
                                ),
                            )
                        }
                    },
                "subClassFanOut" to
                    jsonObjectSorted(
                        buildMap {
                            put(
                                "fanOutDistribution",
                                jsonObjectSorted(
                                    mapOf(
                                        "overFifty" to JsonPrimitive(fan.fanOutDistribution.overFifty),
                                        "singleChild" to JsonPrimitive(fan.fanOutDistribution.singleChild),
                                        "sixToTwenty" to JsonPrimitive(fan.fanOutDistribution.sixToTwenty),
                                        "twentyOneToFifty" to JsonPrimitive(fan.fanOutDistribution.twentyOneToFifty),
                                        "twoToFive" to JsonPrimitive(fan.fanOutDistribution.twoToFive),
                                    ),
                                ),
                            )
                            put("maxFanOut", JsonPrimitive(fan.maxFanOut))
                            put("maxFanOutParents", JsonArray(fan.maxFanOutParents.map { JsonPrimitive(it) }))
                            put("meanFanOut", JsonPrimitive(round4(fan.meanFanOut)))
                            put("medianFanOut", JsonPrimitive(round4(fan.medianFanOut)))
                            put("parentsWithChildren", JsonPrimitive(fan.parentsWithChildren))
                            put(
                                "topNByFanOut",
                                buildJsonArray {
                                    for (e in fan.topNByFanOut.sortedWith(compareBy({ it.parent }, { it.directChildCount }))) {
                                        add(
                                            jsonObjectSorted(
                                                mapOf(
                                                    "directChildCount" to JsonPrimitive(e.directChildCount),
                                                    "parent" to JsonPrimitive(e.parent),
                                                ),
                                            ),
                                        )
                                    }
                                },
                            )
                            put("totalDirectEdges", JsonPrimitive(fan.totalDirectEdges))
                            if (fan.fullFanOutMap.isNotEmpty()) {
                                put(
                                    "fullFanOutMap",
                                    jsonObjectSorted(
                                        fan.fullFanOutMap.keys.sorted().associateWith { parent ->
                                            JsonPrimitive(fan.fullFanOutMap.getValue(parent))
                                        },
                                    ),
                                )
                            }
                        },
                    ),
            ),
        )
    }

    private fun skosSection(r: VocabularyMetricsReport): JsonObject {
        val s = r.skos
        val cohorts = s.siblingCohorts
        val prefLang =
            jsonObjectSorted(
                s.prefLabelsPerLanguage.keys.sorted().associateWith { k ->
                    JsonPrimitive(s.prefLabelsPerLanguage[k] ?: 0L)
                },
            )
        val schemes =
            jsonObjectSorted(
                s.schemesByConceptCount.keys.sorted().associateWith { k ->
                    JsonPrimitive(s.schemesByConceptCount[k] ?: 0L)
                },
            )
        return jsonObjectSorted(
            mapOf(
                "collectionCount" to JsonPrimitive(s.collectionCount),
                "conceptCount" to metric(s.conceptCount),
                "conceptSchemeCount" to JsonPrimitive(s.conceptSchemeCount),
                "definitionCoverage" to metric(s.definitionCoverage),
                "mappingEdgeCounts" to
                    jsonObjectSorted(
                        mapOf(
                            "broadMatch" to JsonPrimitive(s.mappingEdgeCounts.broadMatch),
                            "closeMatch" to JsonPrimitive(s.mappingEdgeCounts.closeMatch),
                            "exactMatch" to JsonPrimitive(s.mappingEdgeCounts.exactMatch),
                            "narrowMatch" to JsonPrimitive(s.mappingEdgeCounts.narrowMatch),
                            "relatedMatch" to JsonPrimitive(s.mappingEdgeCounts.relatedMatch),
                            "totalMappings" to JsonPrimitive(s.mappingEdgeCounts.totalMappings),
                        ),
                    ),
                "orderedCollectionCount" to JsonPrimitive(s.orderedCollectionCount),
                "orphanConceptCount" to metric(s.orphanConceptCount),
                "prefLabelCoverage" to metric(s.prefLabelCoverage),
                "prefLabelsPerLanguage" to prefLang,
                "schemesByConceptCount" to schemes,
                "siblingCohorts" to
                    jsonObjectSorted(
                        mapOf(
                            "cohortSizeDistribution" to
                                jsonObjectSorted(
                                    mapOf(
                                        "elevenToTwenty" to JsonPrimitive(cohorts.cohortSizeDistribution.elevenToTwenty),
                                        "overTwenty" to JsonPrimitive(cohorts.cohortSizeDistribution.overTwenty),
                                        "sixToTen" to JsonPrimitive(cohorts.cohortSizeDistribution.sixToTen),
                                        "twoToFive" to JsonPrimitive(cohorts.cohortSizeDistribution.twoToFive),
                                    ),
                                ),
                            "cohortCount" to metric(cohorts.cohortCount),
                            "cohorts" to
                                buildJsonArray {
                                    for (c in cohorts.cohorts.sortedWith(compareBy({ it.parent }, { it.siblings.joinToString() }))) {
                                        add(
                                            jsonObjectSorted(
                                                mapOf(
                                                    "parent" to JsonPrimitive(c.parent),
                                                    "relatedEdgesAmongSiblings" to JsonPrimitive(c.relatedEdgesAmongSiblings),
                                                    "siblings" to JsonArray(c.siblings.map { JsonPrimitive(it) }),
                                                ),
                                            ),
                                        )
                                    }
                                },
                            "maxCohortParents" to JsonArray(cohorts.maxCohortParents.map { JsonPrimitive(it) }),
                            "maxCohortSize" to metric(cohorts.maxCohortSize),
                            "relatedEdgesWithinCohorts" to JsonPrimitive(cohorts.relatedEdgesWithinCohorts),
                            "totalSiblingPairs" to JsonPrimitive(cohorts.totalSiblingPairs),
                        ),
                    ),
                "structuralEdgeCounts" to
                    jsonObjectSorted(
                        mapOf(
                            "broaderEdges" to JsonPrimitive(s.structuralEdgeCounts.broaderEdges),
                            "broaderTransitiveEdges" to JsonPrimitive(s.structuralEdgeCounts.broaderTransitiveEdges),
                            "narrowerEdges" to JsonPrimitive(s.structuralEdgeCounts.narrowerEdges),
                            "narrowerTransitiveEdges" to JsonPrimitive(s.structuralEdgeCounts.narrowerTransitiveEdges),
                            "relatedEdges" to JsonPrimitive(s.structuralEdgeCounts.relatedEdges),
                        ),
                    ),
            ),
        )
    }

    private fun oquareMetrics(metrics: List<MetricValue>): JsonObject =
        jsonObjectSorted(
            metrics.sortedBy { it.metricIri }.associate { m ->
                localName(m.metricIri) to metric(m)
            },
        )

    private fun metric(m: MetricValue): JsonObject =
        jsonObjectSorted(
            mapOf(
                "computable" to JsonPrimitive(m.computable),
                "metricIri" to JsonPrimitive(m.metricIri),
                "notes" to if (m.notes == null) JsonNull else JsonPrimitive(m.notes),
                "oquareName" to if (m.oquareName == null) JsonNull else JsonPrimitive(m.oquareName),
                "rawValue" to JsonPrimitive(round4(m.rawValue)),
                "score" to if (m.score == null) JsonNull else JsonPrimitive(m.score),
            ),
        )

    private fun localName(iri: String): String = iri.substringAfterLast('#').substringAfterLast('/')

    private fun round4(d: Double): Double =
        String.format(java.util.Locale.US, "%.4f", d).toDouble()

    private fun jsonObjectSorted(entries: Map<String, JsonElement>): JsonObject =
        buildJsonObject {
            for (k in entries.keys.sorted()) {
                put(k, entries[k]!!)
            }
        }
}
