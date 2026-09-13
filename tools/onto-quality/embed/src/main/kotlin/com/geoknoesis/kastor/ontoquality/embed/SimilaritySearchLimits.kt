package com.geoknoesis.kastor.ontoquality.embed

import java.time.Duration

/** Cooperative limits for tree construction, cache wait and exact pair enumeration. */
data class SimilaritySearchLimits(
    val maxDistanceEvaluations: Long = 50_000_000,
    val maxPairs: Int = 1_000_000,
    val timeout: Duration = Duration.ofSeconds(30),
) {
    init {
        require(maxDistanceEvaluations > 0 && maxPairs > 0)
        require(!timeout.isNegative && !timeout.isZero)
    }
}
