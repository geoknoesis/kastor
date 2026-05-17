package com.geoknoesis.kastor.ontoquality.metrics

data class MetricValue(
    val metricIri: String,
    val oquareName: String?,
    val rawValue: Double,
    val score: Int?,
    val computable: Boolean,
    val notes: String?,
)
