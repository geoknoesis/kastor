package com.geoknoesis.kastor.ontoquality.embed

import com.geoknoesis.kastor.rdf.Iri
import java.time.Duration
import org.junit.jupiter.api.Test
import kotlin.test.*

class SimilarityBudgetTest {
    private fun index() = SimilarityIndex((0 until 8).associate { Iri("urn:$it") to floatArrayOf(1f, 0f) })

    @Test fun `dense search skips subtrees that cannot contain a later pair endpoint`() {
        val index = SimilarityIndex((0 until 128).associate { Iri("urn:$it") to floatArrayOf(1f, 0f) })
        // Warm the tree separately so this budget measures query traversal, not construction.
        assertEquals(8128, index.pairsAboveThreshold(0.9).count())
        val pairs = index.pairsAboveThreshold(0.9, SimilaritySearchLimits(maxDistanceEvaluations = 10_000)).toList()
        assertEquals(8128, pairs.size)
        assertEquals(pairs.size, pairs.toSet().size)
        // Every unordered pair is still present, despite a budget below 128 * 128 visits.
        assertTrue(pairs.all { (a, b) -> a.value < b.value })
    }

    @Test fun `failed tree construction can be retried with a sufficient budget`() {
        val index = index()
        val error = assertFailsWith<IllegalStateException> {
            index.pairsAboveThreshold(0.9, SimilaritySearchLimits(maxDistanceEvaluations = 1)).toList()
        }
        assertTrue(error.message!!.contains("evaluation limit"))
        assertEquals(28, index.pairsAboveThreshold(0.9).count())
    }

    @Test fun `dense output fails explicitly and callers may consume a prefix`() {
        val index = index()
        val limits = SimilaritySearchLimits(maxPairs = 1)
        assertEquals(1, index.pairsAboveThreshold(0.9, limits).take(1).count())
        assertTrue(assertFailsWith<IllegalStateException> {
            index.pairsAboveThreshold(0.9, limits).toList()
        }.message!!.contains("result limit"))
    }

    @Test fun `interruption is preserved and does not poison the index`() {
        val index = index()
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<IllegalStateException> { index.pairsAboveThreshold(0.9).count() }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        assertEquals(28, index.pairsAboveThreshold(0.9).count())
        assertFailsWith<IllegalArgumentException> { SimilaritySearchLimits(timeout = Duration.ZERO) }
    }
}
