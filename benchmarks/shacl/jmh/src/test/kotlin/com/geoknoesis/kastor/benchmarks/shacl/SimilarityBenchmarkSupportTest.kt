package com.geoknoesis.kastor.benchmarks.shacl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** The clustered workload looks like label embeddings, not like the uniform or identical vectors of the scaling cases. */
class SimilarityBenchmarkSupportTest {
    private val vectors = SimilarityBenchmarkSupport.clusteredVectors(400)

    private fun cosine(a: FloatArray, b: FloatArray) = a.indices.sumOf { a[it].toDouble() * b[it] }

    @Test
    fun `vectors are unit length and deterministic`() {
        assertEquals(400, vectors.size)
        assertTrue(vectors.values.all { abs(cosine(it, it) - 1.0) < 1e-4 })
        val again = SimilarityBenchmarkSupport.clusteredVectors(400)
        assertTrue(vectors.all { (iri, v) -> again.getValue(iri).contentEquals(v) })
    }

    @Test
    fun `vectors are anisotropic and clustered, with some pairs above the threshold`() {
        val list = vectors.values.toList()
        val sample = (0 until 200).map { cosine(list[it], list[(it * 7 + 3) % list.size]) }
        assertTrue(sample.average() > 0.2, "embeddings share a direction: mean cosine ${sample.average()}")
        val index = SimilarityBenchmarkSupport.newIndex(vectors)
        val exact = SimilarityBenchmarkSupport.countScaled(index, vectors.size, approximate = false)
        val pairs = vectors.size.toLong() * (vectors.size - 1) / 2
        assertTrue(exact in 1 until pairs / 100, "a few near-synonym pairs, not all: $exact")
        val approximate = SimilarityBenchmarkSupport.countScaled(index, vectors.size, approximate = true)
        assertTrue(approximate in 1..exact, "LSH finds a subset of the exact pairs: $approximate of $exact")
    }
}
