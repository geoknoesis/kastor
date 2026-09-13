package com.geoknoesis.kastor.ontoquality.embed

/**
 * Thrown when an exact similarity search exhausts a [SimilaritySearchLimits] budget (deadline, distance
 * evaluations or result pairs). The search never returns a silently truncated result.
 *
 * Extends [IllegalStateException] for compatibility with callers that caught the previous exception type.
 */
class SimilaritySearchBudgetExceededException(message: String) : IllegalStateException(message)
