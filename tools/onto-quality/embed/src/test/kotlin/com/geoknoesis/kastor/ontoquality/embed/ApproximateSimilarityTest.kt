package com.geoknoesis.kastor.ontoquality.embed

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfResource
import java.util.Random
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApproximateSimilarityTest {
    private fun normalize(v: DoubleArray): FloatArray {
        val norm = sqrt(v.sumOf { it * it })
        return FloatArray(v.size) { (v[it] / norm).toFloat() }
    }

    /** 200 random 64-d unit vectors plus 3 planted near-duplicates (cosine about 0.99 with their base vector). */
    private fun index(): SimilarityIndex {
        val random = Random(7)
        val embeddings = LinkedHashMap<RdfResource, FloatArray>()
        val bases = (0 until 200).map { DoubleArray(64) { random.nextGaussian() } }
        bases.forEachIndexed { i, v -> embeddings[Iri("http://example.org/lsh#e$i")] = normalize(v) }
        for (k in 0 until 3) {
            val near = DoubleArray(64) { bases[k][it] + 0.02 * random.nextGaussian() * sqrt(64.0) / 8.0 }
            embeddings[Iri("http://example.org/lsh#near$k")] = normalize(near)
        }
        return SimilarityIndex(embeddings)
    }

    @Test
    fun `approximate LSH search finds the same pairs as exact search on well separated data`() {
        val index = index()
        val exact = index.pairsAboveThreshold(0.9).toSet()
        assertEquals(
            (0 until 3).map { Iri("http://example.org/lsh#e$it") to Iri("http://example.org/lsh#near$it") }.toSet(),
            exact,
        )
        val approximate =
            index.pairsAboveThreshold(0.9, SimilaritySearchLimits(), SimilaritySearchMode.ApproximateLsh()).toSet()
        assertEquals(exact, approximate)
        // Exact mode through the mode overload is the exact search.
        assertEquals(exact, index.pairsAboveThreshold(0.9, SimilaritySearchLimits(), SimilaritySearchMode.Exact).toSet())
    }

    @Test
    fun `approximate search only reports verified pairs and respects the work budget`() {
        val index = index()
        val loose = index.pairsAboveThreshold(0.2, SimilaritySearchLimits(), SimilaritySearchMode.ApproximateLsh()).toSet()
        val exactLoose = index.pairsAboveThreshold(0.2).toSet()
        assertTrue(exactLoose.containsAll(loose), "approximate results must be a subset of the exact results")
        assertFailsWith<SimilaritySearchBudgetExceededException> {
            index.pairsAboveThreshold(0.9, SimilaritySearchLimits(maxDistanceEvaluations = 1), SimilaritySearchMode.ApproximateLsh()).toList()
        }
    }

    @Test
    fun `LSH parameters are validated`() {
        assertFailsWith<IllegalArgumentException> { SimilaritySearchMode.ApproximateLsh(tables = 0) }
        assertFailsWith<IllegalArgumentException> { SimilaritySearchMode.ApproximateLsh(bitsPerTable = 63) }
        assertEquals("approximate-lsh(tables=20,bitsPerTable=10,seed=42)", SimilaritySearchMode.ApproximateLsh().label)
        assertEquals("exact", SimilaritySearchMode.Exact.label)
    }
}
