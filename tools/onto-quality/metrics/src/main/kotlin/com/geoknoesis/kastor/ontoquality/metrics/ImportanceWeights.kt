package com.geoknoesis.kastor.ontoquality.metrics

/**
 * Weights for the importance score formula used by [com.geoknoesis.kastor.ontoquality.metrics.integration.KastorMetricsProvider].
 * Higher fan-out, more incoming references, and shallower depth all increase importance.
 */
data class ImportanceWeights(
    /** Weight on log(1 + transitive descendant count). */
    val fanOutWeight: Double,
    /** Weight on log(1 + properties having this class as domain or range). */
    val incomingPropertiesWeight: Double,
    /** Weight on (1 / (depth + 1)) — shallower is more central after normalization. */
    val shallowDepthWeight: Double,
    /** Small bonus for having an `rdfs:label`. */
    val labelPresenceWeight: Double,
) {
    companion object {
        fun default(): ImportanceWeights =
            ImportanceWeights(
                fanOutWeight = 0.4,
                incomingPropertiesWeight = 0.3,
                shallowDepthWeight = 0.2,
                labelPresenceWeight = 0.1,
            )
    }
}
