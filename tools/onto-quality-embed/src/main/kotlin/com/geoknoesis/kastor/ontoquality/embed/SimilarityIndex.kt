package com.geoknoesis.kastor.ontoquality.embed

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfResource

/**
 * Naive O(n²) cosine similarity over L2-normalized embeddings.
 *
 * v0.2.x may replace this with an HNSW-backed index without changing [SemanticEnricher].
 */
class SimilarityIndex(
    private val embeddings: Map<RdfResource, FloatArray>,
) {
    private val entries: List<Pair<Iri, FloatArray>> =
        embeddings.entries.mapNotNull { (res, vec) ->
            if (res is Iri) res to vec else null
        }

    fun pairsAboveThreshold(threshold: Double): Sequence<Pair<Iri, Iri>> =
        sequence {
            val n = entries.size
            for (i in 0 until n) {
                val (iriA, a) = entries[i]
                for (j in i + 1 until n) {
                    val (iriB, b) = entries[j]
                    var dot = 0.0
                    for (k in a.indices) dot += a[k] * b[k]
                    if (dot >= threshold) {
                        val first: Iri
                        val second: Iri
                        if (iriA.value < iriB.value) {
                            first = iriA
                            second = iriB
                        } else {
                            first = iriB
                            second = iriA
                        }
                        yield(first to second)
                    }
                }
            }
        }
}
