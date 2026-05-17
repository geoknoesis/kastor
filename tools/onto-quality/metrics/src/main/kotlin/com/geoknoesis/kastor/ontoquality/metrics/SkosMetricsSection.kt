package com.geoknoesis.kastor.ontoquality.metrics

data class SkosMetricsSection(
    val conceptCount: MetricValue,
    val conceptSchemeCount: Long,
    val collectionCount: Long,
    val orderedCollectionCount: Long,
    val prefLabelCoverage: MetricValue,
    val definitionCoverage: MetricValue,
    val orphanConceptCount: MetricValue,
    val structuralEdgeCounts: SkosStructuralEdges,
    val mappingEdgeCounts: SkosMappingEdges,
    val schemesByConceptCount: Map<String, Long>,
    val prefLabelsPerLanguage: Map<String, Long>,
    val siblingCohorts: SkosSiblingCohorts,
)

data class SkosStructuralEdges(
    val broaderEdges: Long,
    val narrowerEdges: Long,
    val relatedEdges: Long,
    val broaderTransitiveEdges: Long,
    val narrowerTransitiveEdges: Long,
)

data class SkosMappingEdges(
    val exactMatch: Long,
    val closeMatch: Long,
    val broadMatch: Long,
    val narrowMatch: Long,
    val relatedMatch: Long,
    val totalMappings: Long,
)

data class SkosSiblingCohorts(
    val cohortCount: MetricValue,
    val totalSiblingPairs: Long,
    val maxCohortSize: MetricValue,
    val maxCohortParents: List<String>,
    val cohorts: List<SkosSiblingCohort>,
    val relatedEdgesWithinCohorts: Long,
    val cohortSizeDistribution: CohortSizeDistribution,
)

data class SkosSiblingCohort(
    val parent: String,
    val siblings: List<String>,
    val relatedEdgesAmongSiblings: Long,
)

data class CohortSizeDistribution(
    val twoToFive: Long,
    val sixToTen: Long,
    val elevenToTwenty: Long,
    val overTwenty: Long,
)
