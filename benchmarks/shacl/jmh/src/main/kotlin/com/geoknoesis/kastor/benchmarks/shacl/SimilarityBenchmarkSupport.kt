package com.geoknoesis.kastor.benchmarks.shacl

import com.geoknoesis.kastor.ontoquality.embed.SimilarityIndex
import com.geoknoesis.kastor.rdf.Iri
import kotlin.math.sqrt
import kotlin.random.Random

object SimilarityBenchmarkSupport {
    @JvmStatic fun index(size: Int, dense: Boolean): SimilarityIndex {
        val random = Random(42)
        return SimilarityIndex((0 until size).associate { i ->
            val vector = if (dense) FloatArray(384).also { it[0] = 1f }
                else FloatArray(384) { random.nextFloat() - 0.5f }.let { values ->
                    val norm = sqrt(values.sumOf { it.toDouble() * it })
                    FloatArray(values.size) { (values[it] / norm).toFloat() }
                }
            Iri("urn:vector:$i") to vector
        })
    }
    @JvmStatic fun count(index: SimilarityIndex): Int = index.pairsAboveThreshold(0.9).count()
}
