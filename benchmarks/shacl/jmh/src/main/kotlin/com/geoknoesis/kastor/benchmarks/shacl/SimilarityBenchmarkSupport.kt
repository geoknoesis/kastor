package com.geoknoesis.kastor.benchmarks.shacl

import com.geoknoesis.kastor.ontoquality.embed.SimilarityIndex
import com.geoknoesis.kastor.ontoquality.embed.SimilarityLimitsPolicy
import com.geoknoesis.kastor.ontoquality.embed.SimilaritySearchMode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfResource
import kotlin.math.sqrt
import kotlin.random.Random

object SimilarityBenchmarkSupport {
    /** Embedding width of the bundled MiniLM model. */
    const val DIMENSIONS = 384

    /** Threshold of the benchmarks: the onto-qa default is 0.85; 0.9 keeps the pair counts of the scaling cases. */
    const val THRESHOLD = 0.9

    @JvmStatic fun index(size: Int, dense: Boolean): SimilarityIndex {
        val random = Random(42)
        return SimilarityIndex((0 until size).associate { i ->
            val vector = if (dense) FloatArray(DIMENSIONS).also { it[0] = 1f }
                else FloatArray(DIMENSIONS) { random.nextFloat() - 0.5f }.let(::normalise)
            Iri("urn:vector:$i") to vector
        })
    }

    @JvmStatic fun count(index: SimilarityIndex): Int = index.pairsAboveThreshold(THRESHOLD).count()

    /**
     * [size] unit vectors shaped like sentence embeddings of ontology labels rather than like uniform noise: they share
     * a common direction (MiniLM embeddings are anisotropic, every cosine is well above 0) and fall into clusters of
     * related labels (about 20 per cluster), each vector its centroid plus noise. A few pairs per cluster pass
     * [THRESHOLD], as in a real vocabulary with near-synonyms. Deterministic for a [seed].
     */
    @JvmStatic fun clusteredVectors(size: Int, seed: Int = 42): Map<RdfResource, FloatArray> {
        require(size > 0)
        val random = Random(seed)
        fun gaussian() = FloatArray(DIMENSIONS) { (random.nextDouble() * 2 - 1 + random.nextDouble() * 2 - 1).toFloat() }
        val common = normalise(gaussian())
        val clusters = (size / 20).coerceAtLeast(1)
        val centroids = List(clusters) { normalise(gaussian()) }
        return (0 until size).associate { i ->
            val centroid = centroids[i % clusters]
            val noise = normalise(gaussian())
            val spread = if (i % 7 == 0) 0.15f else 0.45f
            Iri("urn:label:$i") as RdfResource to normalise(FloatArray(DIMENSIONS) { 0.55f * common[it] + 0.75f * centroid[it] + spread * noise[it] })
        }
    }

    /** A new index over [vectors]: its metric tree is built by the first search, so a benchmark of build plus search starts here. */
    @JvmStatic fun newIndex(vectors: Map<RdfResource, FloatArray>): SimilarityIndex = SimilarityIndex(vectors)

    /** Pairs above [THRESHOLD] under the scaled limits onto-qa uses for [index]'s size, exact or with random-projection LSH. */
    @JvmStatic fun countScaled(index: SimilarityIndex, size: Int, approximate: Boolean): Int {
        val mode = if (approximate) SimilaritySearchMode.ApproximateLsh() else SimilaritySearchMode.Exact
        return index.pairsAboveThreshold(THRESHOLD, SimilarityLimitsPolicy.scaled().limitsFor(size), mode).count()
    }

    private fun normalise(values: FloatArray): FloatArray {
        val norm = sqrt(values.sumOf { it.toDouble() * it })
        return FloatArray(values.size) { (values[it] / norm).toFloat() }
    }
}
