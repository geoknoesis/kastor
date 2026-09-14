package com.geoknoesis.kastor.ontoquality.embed

/**
 * Thrown when a similarity search exhausts a [SimilaritySearchLimits] budget (deadline, distance
 * evaluations or result pairs). The search never returns a silently truncated result.
 *
 * Extends [IllegalStateException] for compatibility with callers that caught the previous exception type.
 *
 * @property limit which budget was exhausted, so callers can give targeted advice (for example a higher similarity
 *   threshold when there are too many result pairs); `null` when constructed without one.
 */
class SimilaritySearchBudgetExceededException @JvmOverloads constructor(
    message: String,
    val limit: Limit? = null,
) : IllegalStateException(message) {
    /** The exhausted budget of [SimilaritySearchLimits]. */
    enum class Limit {
        /** [SimilaritySearchLimits.timeout]. */
        DEADLINE,

        /** [SimilaritySearchLimits.maxDistanceEvaluations]. */
        DISTANCE_EVALUATIONS,

        /** [SimilaritySearchLimits.maxPairs]: more pairs are above the threshold than allowed. */
        RESULT_PAIRS,
    }
}
