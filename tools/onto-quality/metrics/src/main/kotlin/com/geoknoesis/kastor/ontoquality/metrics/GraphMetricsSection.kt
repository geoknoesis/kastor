package com.geoknoesis.kastor.ontoquality.metrics

data class GraphMetricsSection(
    val tripleCount: Long,
    val distinctSubjectCount: Long,
    val distinctPredicateCount: Long,
    val distinctObjectCount: Long,
    val blankNodeSubjectCount: Long,
    val literalObjectCount: Long,
    val iriObjectCount: Long,
    val distinctClassesUsed: Long,
)
