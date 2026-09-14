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
    fun `approximate search is deterministic across runs and index instances`() {
        val limits = SimilaritySearchLimits()
        val mode = SimilaritySearchMode.ApproximateLsh()
        val first = index().pairsAboveThreshold(0.2, limits, mode).toList()
        val second = index().pairsAboveThreshold(0.2, limits, mode).toList()
        val sameIndex = index().let { idx -> List(2) { idx.pairsAboveThreshold(0.2, limits, mode).toList() } }
        assertTrue(first.isNotEmpty())
        // Same seed: identical pairs in identical order, whether the index is rebuilt or reused.
        assertEquals(first, second)
        assertEquals(sameIndex[0], sameIndex[1])
        assertEquals(first, sameIndex[0])
    }

    @Test
    fun `approximate search can miss a pair that exact search finds`() {
        // Two unit vectors with cosine 0.3: random hyperplanes separate them with probability acos(0.3) / pi = 0.40
        // per bit, so with one table of 16 bits they share a bucket with probability 0.597^16 = 0.03 %.
        val a = FloatArray(64).also { it[0] = 1f }
        val b = FloatArray(64).also { it[0] = 0.3f; it[1] = sqrt(1.0 - 0.09).toFloat() }
        val pairIndex = SimilarityIndex(mapOf(Iri("http://example.org/lsh#a") to a, Iri("http://example.org/lsh#b") to b))
        val expected = Iri("http://example.org/lsh#a") to Iri("http://example.org/lsh#b")

        assertEquals(listOf(expected), pairIndex.pairsAboveThreshold(0.25).toList())
        val narrow = SimilaritySearchMode.ApproximateLsh(tables = 1, bitsPerTable = 16, seed = 42)
        assertEquals(emptyList(), pairIndex.pairsAboveThreshold(0.25, SimilaritySearchLimits(), narrow).toList())
    }

    @Test
    fun `LSH parameters are validated`() {
        assertFailsWith<IllegalArgumentException> { SimilaritySearchMode.ApproximateLsh(tables = 0) }
        assertFailsWith<IllegalArgumentException> { SimilaritySearchMode.ApproximateLsh(bitsPerTable = 63) }
        assertEquals("approximate-lsh(tables=20,bitsPerTable=10,seed=42)", SimilaritySearchMode.ApproximateLsh().label)
        assertEquals("exact", SimilaritySearchMode.Exact.label)
    }
}
