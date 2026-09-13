package com.geoknoesis.kastor.ontoquality.embed

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfResource
import org.junit.jupiter.api.Test
import kotlin.test.*
import kotlin.random.Random
import kotlin.math.sqrt

class SimilarityIndexRegressionTest {
    @Test fun `interleaved iterators retain independent traversal state`() {
        val index = SimilarityIndex((0 until 8).associate { Iri("urn:$it") to floatArrayOf(1f, 0f) })
        val query = index.pairsAboveThreshold(0.9)
        val firstIterator = query.iterator()
        val firstPair = firstIterator.next()
        val secondResult = query.toList()
        assertEquals(28, secondResult.size)
        assertEquals(secondResult, listOf(firstPair) + firstIterator.asSequence().toList())
        assertEquals(secondResult, query.toList())
    }

    @Test fun `metric search equals exhaustive results at several thresholds`() {
        val random = Random(47)
        val vectors = (0 until 400).associate { i ->
            val v = FloatArray(24) { random.nextDouble(-1.0, 1.0).toFloat() }
            val norm = sqrt(v.sumOf { it.toDouble() * it })
            (Iri("urn:vector:$i") as RdfResource) to FloatArray(v.size) { (v[it] / norm).toFloat() }
        }
        val index = SimilarityIndex(vectors)
        for (threshold in listOf(-1.0, 0.0, 0.5, 0.95, 1.0)) {
            val entries = vectors.entries.toList()
            val expected = buildSet {
                for (i in entries.indices) for (j in i + 1 until entries.size) {
                    val a = entries[i]; val b = entries[j]
                    if (a.value.indices.sumOf { a.value[it].toDouble() * b.value[it] } >= threshold) {
                        val x = a.key as Iri; val y = b.key as Iri
                        add(if (x.value < y.value) x to y else y to x)
                    }
                }
            }
            assertEquals(expected, index.pairsAboveThreshold(threshold).toSet())
        }
    }
    @Test fun `invalid vectors are rejected`() {
        assertFailsWith<IllegalArgumentException> { SimilarityIndex(mapOf(Iri("urn:a") to floatArrayOf(Float.NaN))) }
        assertFailsWith<IllegalArgumentException> { SimilarityIndex(mapOf(Iri("urn:a") to floatArrayOf(0f))) }
        assertFailsWith<IllegalArgumentException> { SimilarityIndex(mapOf(Iri("urn:a") to floatArrayOf(1f), Iri("urn:b") to floatArrayOf(1f, 0f))) }
    }
}
