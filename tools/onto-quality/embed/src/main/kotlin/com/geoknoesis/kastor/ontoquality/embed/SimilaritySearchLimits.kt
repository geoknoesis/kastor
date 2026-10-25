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

    companion object {
        /** The smallest work budget [forEntityCount] chooses. */
        const val DEFAULT_MAX_DISTANCE_EVALUATIONS: Long = 50_000_000L

        /** The shortest deadline [forEntityCount] chooses. */
        @JvmField
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(30)

        /** Conservative throughput assumption for the deadline: 5,000 distance evaluations per millisecond. */
        private const val EVALUATIONS_PER_MILLI = 5_000L

        /** The longest deadline [forEntityCount] chooses on its own; a longer search needs an explicit limit. */
        @JvmField
        val MAX_SCALED_TIMEOUT: Duration = Duration.ofMinutes(10)

        /** The largest work budget [forEntityCount] chooses: what [MAX_SCALED_TIMEOUT] allows at 5,000 evaluations per ms. */
        const val MAX_SCALED_DISTANCE_EVALUATIONS: Long = 3_000_000_000L

        /**
         * Limits sized for [entities] embedded entities, never below the fixed defaults.
         *
         * The work budget covers the worst case of the exact metric-tree search — every query visiting every later
         * entry, n(n−1)/2 distance evaluations — plus n × bitLength(n) evaluations to build the tree; the deadline
         * allows [EVALUATIONS_PER_MILLI] evaluations per millisecond. High-dimensional embeddings prune poorly, so the
         * worst case is the realistic bound for large vocabularies.
         *
         * Both are capped at [MAX_SCALED_TIMEOUT] (10 minutes) and the work it allows (3 × 10⁹ evaluations): beyond
         * about 77,000 entities an exact search that prunes poorly stops with
         * [SimilaritySearchBudgetExceededException] instead of running for an hour. Use
         * [SimilaritySearchMode.ApproximateLsh], or explicit limits, for larger vocabularies.
         */
        fun forEntityCount(entities: Int): SimilaritySearchLimits {
            val n = entities.coerceAtLeast(0).toLong()
            val bitLength = 64 - java.lang.Long.numberOfLeadingZeros(n)
            val worstCase = n * (n - 1) / 2 + n * bitLength
            val evaluations = worstCase.coerceIn(DEFAULT_MAX_DISTANCE_EVALUATIONS, MAX_SCALED_DISTANCE_EVALUATIONS)
            val timeout = maxOf(DEFAULT_TIMEOUT, Duration.ofMillis(evaluations / EVALUATIONS_PER_MILLI))
            return SimilaritySearchLimits(maxDistanceEvaluations = evaluations, timeout = timeout)
        }
    }
}

/** Chooses [SimilaritySearchLimits] once the number of embedded entities is known. */
fun interface SimilarityLimitsPolicy {
    fun limitsFor(entityCount: Int): SimilaritySearchLimits

    companion object {
        /**
         * [SimilaritySearchLimits.forEntityCount], with optional fixed overrides for the work budget, the deadline and
         * the maximum number of result pairs.
         */
        @JvmStatic
        @JvmOverloads
        fun scaled(maxDistanceEvaluations: Long? = null, timeout: Duration? = null, maxPairs: Int? = null): SimilarityLimitsPolicy =
            SimilarityLimitsPolicy { entityCount ->
                val base = SimilaritySearchLimits.forEntityCount(entityCount)
                base.copy(
                    maxDistanceEvaluations = maxDistanceEvaluations ?: base.maxDistanceEvaluations,
                    maxPairs = maxPairs ?: base.maxPairs,
                    timeout = timeout ?: base.timeout,
                )
            }

        /** The same [limits] regardless of entity count. */
        @JvmStatic
        fun fixed(limits: SimilaritySearchLimits): SimilarityLimitsPolicy = SimilarityLimitsPolicy { limits }
    }
}
