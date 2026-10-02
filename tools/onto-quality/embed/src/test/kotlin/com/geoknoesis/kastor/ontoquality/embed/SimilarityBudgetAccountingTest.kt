package com.geoknoesis.kastor.ontoquality.embed

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfResource
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SimilarityBudgetAccountingTest {
    private fun index() = SimilarityIndex((0 until 8).associate { Iri("urn:$it") to floatArrayOf(1f, 0f) })

    private fun index(clock: () -> Long) = SimilarityIndex((0 until 8).associate { Iri("urn:$it") to floatArrayOf(1f, 0f) }, clock)

    @Test
    fun `time the consumer spends between results does not count against the deadline`() {
        // A fake clock that only the consumer advances: the search itself takes no time at all.
        var now = 0L
        val limits = SimilaritySearchLimits(timeout = Duration.ofMillis(300))
        var consumed = 0
        for (pair in index { now }.pairsAboveThreshold(0.9, limits)) {
            now += Duration.ofMillis(25).toNanos() // 28 pairs x 25 ms = 700 ms of consumer time
            consumed++
        }
        assertEquals(28, consumed)
        assertEquals(Duration.ofMillis(700).toNanos(), now)
    }

    @Test
    fun `time the search itself spends counts against the deadline`() {
        // Every reading of the clock by the search takes one millisecond; the consumer takes none.
        var now = 0L
        val limits = SimilaritySearchLimits(timeout = Duration.ofMillis(20))
        val failure =
            assertFailsWith<SimilaritySearchBudgetExceededException> {
                index { now += 1_000_000; now }.pairsAboveThreshold(0.9, limits).toList()
            }
        assertEquals(SimilaritySearchBudgetExceededException.Limit.DEADLINE, failure.limit)
    }

    @Test
    fun `exhausted budgets raise the dedicated exception type`() {
        val evaluations =
            assertFailsWith<SimilaritySearchBudgetExceededException> {
                index().pairsAboveThreshold(0.9, SimilaritySearchLimits(maxDistanceEvaluations = 1)).toList()
            }
        assertTrue(evaluations.message!!.contains("evaluation limit"))
        assertEquals(SimilaritySearchBudgetExceededException.Limit.DISTANCE_EVALUATIONS, evaluations.limit)
        val pairs =
            assertFailsWith<SimilaritySearchBudgetExceededException> {
                index().pairsAboveThreshold(0.9, SimilaritySearchLimits(maxPairs = 3)).toList()
            }
        assertEquals("Similarity result limit exceeded (3 pairs)", pairs.message)
        assertEquals(SimilaritySearchBudgetExceededException.Limit.RESULT_PAIRS, pairs.limit)
        val approximatePairs =
            assertFailsWith<SimilaritySearchBudgetExceededException> {
                index().pairsAboveThreshold(0.9, SimilaritySearchLimits(maxPairs = 3), SimilaritySearchMode.ApproximateLsh()).toList()
            }
        assertEquals(SimilaritySearchBudgetExceededException.Limit.RESULT_PAIRS, approximatePairs.limit)
        val deadline =
            assertFailsWith<SimilaritySearchBudgetExceededException> {
                index().pairsAboveThreshold(0.9, SimilaritySearchLimits(timeout = Duration.ofNanos(1))).toList()
            }
        assertEquals("Similarity search deadline exceeded", deadline.message)
        assertEquals(SimilaritySearchBudgetExceededException.Limit.DEADLINE, deadline.limit)
        // The message-only constructor stays available and carries no limit.
        assertEquals(null, SimilaritySearchBudgetExceededException("custom").limit)
    }

    @Test
    fun `blank-node keys are rejected with a clear error instead of being dropped`() {
        val embeddings: Map<RdfResource, FloatArray> =
            mapOf(Iri("urn:a") to floatArrayOf(1f, 0f), BlankNode("b0") to floatArrayOf(0f, 1f))
        val error = assertFailsWith<IllegalArgumentException> { SimilarityIndex(embeddings) }
        assertTrue(error.message!!.contains("blank node"), error.message)
    }
}
