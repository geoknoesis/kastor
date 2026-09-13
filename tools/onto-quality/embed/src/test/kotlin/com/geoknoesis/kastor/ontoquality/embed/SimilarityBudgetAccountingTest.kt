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

    @Test
    fun `time the consumer spends between results does not count against the deadline`() {
        val limits = SimilaritySearchLimits(timeout = Duration.ofMillis(300))
        var consumed = 0
        for (pair in index().pairsAboveThreshold(0.9, limits)) {
            Thread.sleep(25) // 28 pairs x 25 ms = 700 ms of consumer time
            consumed++
        }
        assertEquals(28, consumed)
    }

    @Test
    fun `exhausted budgets raise the dedicated exception type`() {
        val evaluations =
            assertFailsWith<SimilaritySearchBudgetExceededException> {
                index().pairsAboveThreshold(0.9, SimilaritySearchLimits(maxDistanceEvaluations = 1)).toList()
            }
        assertTrue(evaluations.message!!.contains("evaluation limit"))
        val pairs =
            assertFailsWith<SimilaritySearchBudgetExceededException> {
                index().pairsAboveThreshold(0.9, SimilaritySearchLimits(maxPairs = 3)).toList()
            }
        assertTrue(pairs.message!!.contains("result limit"))
    }

    @Test
    fun `blank-node keys are rejected with a clear error instead of being dropped`() {
        val embeddings: Map<RdfResource, FloatArray> =
            mapOf(Iri("urn:a") to floatArrayOf(1f, 0f), BlankNode("b0") to floatArrayOf(0f, 1f))
        val error = assertFailsWith<IllegalArgumentException> { SimilarityIndex(embeddings) }
        assertTrue(error.message!!.contains("blank node"), error.message)
    }
}
